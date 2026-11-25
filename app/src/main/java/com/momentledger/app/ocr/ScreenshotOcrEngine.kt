package com.momentledger.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.momentledger.app.domain.parser.OcrImageInput
import com.momentledger.app.domain.parser.OcrLine
import com.momentledger.app.domain.parser.ScreenshotBillParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * Pure-Kotlin tiling & sampling planner for standard and tall scrolling bill screenshots.
 */
object ScreenshotTilingPlanner {

    data class RawRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    const val DEFAULT_TARGET_MAX_WIDTH = 1440
    const val DEFAULT_STRIP_HEIGHT = 2000
    const val DEFAULT_OVERLAP = 180
    const val DEFAULT_SINGLE_PASS_THRESHOLD = 2600

    /**
     * Computes power-of-two BitmapFactory inSampleSize capped by width only,
     * so tall scrolling screenshots (e.g. 1080x12000) never lose horizontal legibility.
     */
    fun computeSampleSizeForWidth(
        width: Int,
        targetMaxWidth: Int = DEFAULT_TARGET_MAX_WIDTH
    ): Int {
        if (width <= 0 || targetMaxWidth <= 0 || width <= targetMaxWidth) return 1
        var sampleSize = 1
        while ((width / (sampleSize * 2)) >= targetMaxWidth) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    /**
     * Plans overlapping vertical Y ranges `[startY, endYExclusive)` for a screenshot of [height].
     */
    fun planVerticalStrips(
        height: Int,
        stripHeight: Int = DEFAULT_STRIP_HEIGHT,
        overlap: Int = DEFAULT_OVERLAP,
        singlePassThreshold: Int = DEFAULT_SINGLE_PASS_THRESHOLD
    ): List<IntRange> {
        if (height <= 0) return emptyList()
        val safeStripHeight = stripHeight.coerceAtLeast(256)
        val safeOverlap = overlap.coerceIn(0, safeStripHeight / 2)
        if (height <= singlePassThreshold || height <= safeStripHeight) {
            return listOf(0 until height)
        }
        val step = (safeStripHeight - safeOverlap).coerceAtLeast(1)
        val strips = mutableListOf<IntRange>()
        var top = 0
        while (top < height) {
            val bottomExclusive = (top + safeStripHeight).coerceAtMost(height)
            strips += (top until bottomExclusive)
            if (bottomExclusive >= height) break
            top += step
        }
        return strips
    }

    /**
     * Maps a vertical strip `[stripStart, stripEndExclusive)` in oriented image coordinates
     * back to the corresponding region in raw (unrotated) pixel coordinates for BitmapRegionDecoder.
     */
    fun mapOrientedStripToRawRect(
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int,
        stripStart: Int,
        stripEndExclusive: Int
    ): RawRect = when (((rotationDegrees % 360) + 360) % 360) {
        90 -> RawRect(
            left = stripStart.coerceIn(0, rawWidth),
            top = 0,
            right = stripEndExclusive.coerceIn(0, rawWidth),
            bottom = rawHeight
        )
        180 -> RawRect(
            left = 0,
            top = (rawHeight - stripEndExclusive).coerceIn(0, rawHeight),
            right = rawWidth,
            bottom = (rawHeight - stripStart).coerceIn(0, rawHeight)
        )
        270 -> RawRect(
            left = (rawWidth - stripEndExclusive).coerceIn(0, rawWidth),
            top = 0,
            right = (rawWidth - stripStart).coerceIn(0, rawWidth),
            bottom = rawHeight
        )
        else -> RawRect(
            left = 0,
            top = stripStart.coerceIn(0, rawHeight),
            right = rawWidth,
            bottom = stripEndExclusive.coerceIn(0, rawHeight)
        )
    }
}

/**
 * Pure-Kotlin tone analysis and adaptive ColorMatrix coefficients for OCR preprocessing.
 * Uses percentile luma statistics to convert dark-mode receipts (light/gray text on dark background)
 * into crisp dark-text-on-white bitmaps with a gentle slope that preserves thin/handwritten anti-aliased strokes.
 */
internal object OcrImagePreprocessor {

    data class ToneProfile(
        val isDarkBackground: Boolean,
        val averageLuma: Float,
        val darkRatio: Float,
        val p15: Float,
        val p85: Float,
        val p96: Float,
        val p04: Float
    )

    fun analyzeLumaSamples(lumaSamples: FloatArray): ToneProfile {
        if (lumaSamples.isEmpty()) {
            return ToneProfile(
                isDarkBackground = false,
                averageLuma = 255f,
                darkRatio = 0f,
                p15 = 255f,
                p85 = 255f,
                p96 = 255f,
                p04 = 255f
            )
        }
        var sum = 0f
        var darkCount = 0
        for (luma in lumaSamples) {
            sum += luma
            if (luma < 96f) darkCount++
        }
        val sorted = lumaSamples.copyOf().apply { sort() }
        fun percentile(p: Float): Float {
            val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
            return sorted[idx]
        }
        val avg = sum / lumaSamples.size
        val darkRatio = darkCount.toFloat() / lumaSamples.size
        return ToneProfile(
            isDarkBackground = darkRatio >= 0.52f && avg < 125f,
            averageLuma = avg,
            darkRatio = darkRatio,
            p15 = percentile(0.15f),
            p85 = percentile(0.85f),
            p96 = percentile(0.96f),
            p04 = percentile(0.04f)
        )
    }

    /**
     * Returns a 4x5 RGBA ColorMatrix array that maps input pixels to grayscale and
     * applies adaptive inversion/contrast stretching calibrated to the strip's tone profile.
     * Kept deliberately gentle so thin/handwritten 1px anti-aliased strokes are never eroded.
     */
    fun buildColorMatrixValues(profile: ToneProfile): FloatArray {
        val (scale, offset) = if (profile.isDarkBackground) {
            val bg = profile.p15.coerceIn(0f, 60f)
            val fg = profile.p96.coerceIn(bg + 90f, 255f)
            val dynamicScale = (-255f / (fg - bg).coerceAtLeast(155f)).coerceIn(-1.55f, -1.18f)
            // Map bg + 4 -> 255 (clean white background) while preserving mid-gray stroke edges.
            val dynamicOffset = 255f - dynamicScale * (bg + 4f)
            dynamicScale to dynamicOffset
        } else {
            val bg = profile.p85.coerceIn(195f, 255f)
            val fg = profile.p04.coerceIn(0f, bg - 80f)
            val dynamicScale = (255f / (bg - fg).coerceAtLeast(180f)).coerceIn(1.15f, 1.40f)
            // Keep clean white backgrounds at 255 while gently darkening light-gray labels.
            val dynamicOffset = 255f - dynamicScale * (bg - 3f)
            dynamicScale to dynamicOffset
        }
        val r = 0.299f * scale
        val g = 0.587f * scale
        val b = 0.114f * scale
        return floatArrayOf(
            r, g, b, 0f, offset,
            r, g, b, 0f, offset,
            r, g, b, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )
    }
}

/**
 * Fully offline, zero-network, zero-GMS Chinese OCR engine backed by PaddleOCR PP-OCRv5 Mobile
 * running on ONNX Runtime. Single-screen screenshots run in one unsplit pass with full-resolution
 * line crops, while tall scrolling screenshots tile cleanly via vertical overlapping strips.
 */
class ScreenshotOcrEngine(context: Context) {

    private val appContext = context.applicationContext
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineLock = Any()
    private val inferenceMutex = Mutex()
    private var activeEngine: PpOcrV5Engine? = null
    private var inFlightTasks: Int = 0
    private var idleCloseJob: Job? = null

    private fun acquireEngine(): PpOcrV5Engine = synchronized(engineLock) {
        idleCloseJob?.cancel()
        idleCloseJob = null
        inFlightTasks += 1
        activeEngine ?: PpOcrV5Engine(appContext).also { activeEngine = it }
    }

    private fun releaseEngine() {
        synchronized(engineLock) {
            inFlightTasks = (inFlightTasks - 1).coerceAtLeast(0)
            if (inFlightTasks == 0 && activeEngine != null) {
                idleCloseJob?.cancel()
                idleCloseJob = lifecycleScope.launch {
                    delay(IDLE_CLOSE_DELAY_MS)
                    val toClose = synchronized(engineLock) {
                        if (inFlightTasks == 0) {
                            activeEngine.also { activeEngine = null }
                        } else {
                            null
                        }
                    }
                    runCatching { toClose?.close() }
                }
            }
        }
    }

    /**
     * Consumes a screen bitmap through the exact same resolution and tiling pipeline
     * used by [recognizeUri], ensuring identical accuracy between live capture and image OCR.
     */
    internal suspend fun recognizeScreen(bitmap: Bitmap, capturedAtMillis: Long): OcrImageInput =
        recognizeUnifiedBitmap(bitmap, capturedAtMillis, recycleSource = true)

    /**
     * Recognizes a screen bitmap without recycling [bitmap] so callers can run an in-memory
     * high-resolution payment-time retry pass if needed, then recycle [bitmap] in their own finally block.
     */
    internal suspend fun recognizeScreenRetained(bitmap: Bitmap, capturedAtMillis: Long): OcrImageInput =
        recognizeUnifiedBitmap(bitmap, capturedAtMillis, recycleSource = false)

    internal suspend fun retryPaymentTimeFromBitmap(
        source: Bitmap,
        input: OcrImageInput
    ): List<OcrLine> = withContext(Dispatchers.IO) {
        if (source.isRecycled || input.imageHeight <= 0) return@withContext emptyList()
        val field = ScreenshotBillParser.paymentTimeRegion(input) ?: return@withContext emptyList()
        val padding = (field.height * 2).coerceAtMost(120)
        val top = ((field.top - padding).toLong() * source.height / input.imageHeight).toInt()
            .coerceIn(0, source.height)
        val bottom = ((field.bottom + padding).toLong() * source.height / input.imageHeight).toInt()
            .coerceIn(0, source.height)
        val cropHeight = bottom - top
        if (cropHeight <= 0 || cropHeight > source.height / 5) return@withContext emptyList()

        val crop = try {
            Bitmap.createBitmap(source, 0, top, source.width, cropHeight)
        } catch (_: OutOfMemoryError) {
            return@withContext emptyList()
        }
        val upscaled = try {
            if (crop.width < 1200 && (crop.width.toLong() * 2 * crop.height * 2) <= MAX_TIME_RETRY_PIXELS * 2) {
                Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true).also {
                    if (it !== crop && !crop.isRecycled) crop.recycle()
                }
            } else {
                crop
            }
        } catch (_: OutOfMemoryError) {
            crop
        }
        recognizeBitmapOwned(upscaled)
    }

    private suspend fun recognizeUnifiedBitmap(
        source: Bitmap,
        fallbackMillis: Long,
        recycleSource: Boolean
    ): OcrImageInput {
        val working = normalizeBitmapWidth(source, recycleSource = recycleSource)
        val ownsWorking = working !== source || recycleSource
        val finalWidth = working.width
        val finalHeight = working.height
        val strips = ScreenshotTilingPlanner.planVerticalStrips(
            height = finalHeight,
            stripHeight = OCR_STRIP_HEIGHT,
            overlap = OCR_STRIP_OVERLAP,
            singlePassThreshold = OCR_SINGLE_PASS_THRESHOLD
        )
        if (strips.size == 1) {
            val lines = runOcrOnBitmap(working, yOffset = 0, recycleAfter = ownsWorking)
            return OcrImageInput(
                lines = ScreenshotBillParser.deduplicateTiledOcrLines(lines),
                imageWidth = finalWidth,
                imageHeight = finalHeight,
                fallbackTimestampMillis = fallbackMillis
            )
        }

        return try {
            val allLines = mutableListOf<OcrLine>()
            for (strip in strips) {
                val stripHeight = strip.last - strip.first + 1
                if (stripHeight <= 0) continue
                val crop = try {
                    Bitmap.createBitmap(working, 0, strip.first, finalWidth, stripHeight)
                } catch (_: OutOfMemoryError) {
                    throw IllegalStateException("截图分辨率过高，请裁剪后重试")
                }
                allLines += runOcrOnBitmap(crop, yOffset = strip.first, recycleAfter = true)
            }
            OcrImageInput(
                lines = ScreenshotBillParser.deduplicateTiledOcrLines(allLines),
                imageWidth = finalWidth,
                imageHeight = finalHeight,
                fallbackTimestampMillis = fallbackMillis
            )
        } finally {
            if (ownsWorking && !working.isRecycled) {
                working.recycle()
            }
        }
    }

    private fun normalizeBitmapWidth(source: Bitmap, recycleSource: Boolean): Bitmap {
        val maxWidth = ScreenshotTilingPlanner.DEFAULT_TARGET_MAX_WIDTH
        if (source.width <= maxWidth) return source
        val targetHeight = (source.height.toLong() * maxWidth / source.width).toInt().coerceAtLeast(1)
        val scaled = try {
            Bitmap.createScaledBitmap(source, maxWidth, targetHeight, true)
        } catch (_: OutOfMemoryError) {
            return source
        }
        if (recycleSource && scaled !== source && !source.isRecycled) {
            source.recycle()
        }
        return scaled
    }

    suspend fun recognizeUri(uri: Uri): OcrImageInput = withContext(Dispatchers.IO) {
        val rawBytes = readUriBytesBounded(uri)
        if (rawBytes.isEmpty()) {
            return@withContext OcrImageInput(emptyList(), fallbackTimestampMillis = System.currentTimeMillis())
        }

        val rotationDegrees = readExifRotationDegrees(rawBytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        val rawWidth = bounds.outWidth
        val rawHeight = bounds.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) {
            return@withContext OcrImageInput(emptyList(), fallbackTimestampMillis = System.currentTimeMillis())
        }

        val rotatedSwap = rotationDegrees == 90 || rotationDegrees == 270
        val orientedWidth = if (rotatedSwap) rawHeight else rawWidth
        val orientedHeight = if (rotatedSwap) rawWidth else rawHeight
        val sampleSize = ScreenshotTilingPlanner.computeSampleSizeForWidth(orientedWidth)
        val sampledWidth = (orientedWidth / sampleSize).coerceAtLeast(1)
        val sampledHeight = (orientedHeight / sampleSize).coerceAtLeast(1)

        val fallbackMillis = System.currentTimeMillis()

        // Fast, memory-bounded path for true tall scrolling screenshots (> 1.5 screens).
        // Standard single-screen screenshots go through recognizeUnifiedBitmap so gallery OCR
        // and live screen capture share the exact same resize, strip tiling, and tone pipeline.
        if (sampledHeight > LONG_SCROLL_REGION_THRESHOLD) {
            val tiledLines = recognizeViaRegionDecoder(
                rawBytes = rawBytes,
                rawWidth = rawWidth,
                rawHeight = rawHeight,
                orientedHeight = orientedHeight,
                rotationDegrees = rotationDegrees,
                sampleSize = sampleSize
            )
            if (tiledLines != null) {
                return@withContext OcrImageInput(
                    lines = ScreenshotBillParser.deduplicateTiledOcrLines(tiledLines),
                    imageWidth = sampledWidth,
                    imageHeight = sampledHeight,
                    fallbackTimestampMillis = fallbackMillis
                )
            }
        }

        // Standard decode path: preserve full crispness up to DEFAULT_TARGET_MAX_WIDTH via exact
        // bilinear scaling inside recognizeUnifiedBitmap (instead of halving 1441px -> 720px).
        val maxFallbackPixels = if (rotationDegrees != 0) MAX_ROTATED_FALLBACK_PIXELS else MAX_FALLBACK_PIXELS
        var fallbackSampleSize = if (orientedWidth > ScreenshotTilingPlanner.DEFAULT_TARGET_MAX_WIDTH * 2) {
            sampleSize
        } else {
            1
        }
        while (
            ((orientedWidth.toLong() / fallbackSampleSize).coerceAtLeast(1L) *
                (orientedHeight.toLong() / fallbackSampleSize).coerceAtLeast(1L)) > maxFallbackPixels
        ) {
            fallbackSampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = fallbackSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
        } catch (_: OutOfMemoryError) {
            throw IllegalStateException("截图分辨率过高，请裁剪后重试")
        } ?: return@withContext OcrImageInput(emptyList(), fallbackTimestampMillis = fallbackMillis)

        val orientedBitmap = try {
            rotateBitmapIfNeeded(decoded, rotationDegrees)
        } catch (_: OutOfMemoryError) {
            if (!decoded.isRecycled) decoded.recycle()
            throw IllegalStateException("截图分辨率过高，请裁剪后重试")
        } finally {
            if (rotationDegrees != 0 && !decoded.isRecycled) {
                decoded.recycle()
            }
        }

        recognizeUnifiedBitmap(orientedBitmap, fallbackMillis, recycleSource = true)
    }

    internal suspend fun retryPaymentTime(uri: Uri, input: OcrImageInput): List<OcrLine> = withContext(Dispatchers.IO) {
        val field = ScreenshotBillParser.paymentTimeRegion(input) ?: return@withContext emptyList()
        val padding = (field.height * 2).coerceAtMost(120)
        val top = (field.top - padding).coerceIn(0, input.imageHeight)
        val bottom = (field.bottom + padding).coerceIn(0, input.imageHeight)
        if (bottom <= top || bottom - top > input.imageHeight / 6) return@withContext emptyList()

        val bytes = readUriBytesBounded(uri)
        if (bytes.isEmpty()) return@withContext emptyList()
        val rotation = readExifRotationDegrees(bytes)
        val decoder = openRegionDecoder(bytes) ?: return@withContext emptyList()
        val rawStrip = try {
            val rotated = rotation == 90 || rotation == 270
            val orientedWidth = if (rotated) decoder.height else decoder.width
            val orientedHeight = if (rotated) decoder.width else decoder.height
            val rawRect = ScreenshotTilingPlanner.mapOrientedStripToRawRect(
                decoder.width, decoder.height, rotation,
                (top.toLong() * orientedHeight / input.imageHeight).toInt(),
                ((bottom.toLong() * orientedHeight + input.imageHeight - 1) / input.imageHeight).toInt()
            )
            val width = rawRect.right - rawRect.left
            val height = rawRect.bottom - rawRect.top
            if (width <= 0 || height <= 0) return@withContext emptyList()
            var sampleSize = ScreenshotTilingPlanner.computeSampleSizeForWidth(orientedWidth)
            while ((width / sampleSize).toLong() * (height / sampleSize) > MAX_TIME_RETRY_PIXELS) {
                sampleSize *= 2
            }
            decoder.decodeRegion(
                Rect(rawRect.left, rawRect.top, rawRect.right, rawRect.bottom),
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        } catch (_: OutOfMemoryError) {
            return@withContext emptyList()
        } finally {
            decoder.recycle()
        } ?: return@withContext emptyList()

        val retryBitmap = try {
            rotateBitmapIfNeeded(rawStrip, rotation)
        } catch (_: OutOfMemoryError) {
            return@withContext emptyList()
        } finally {
            if (rotation != 0) rawStrip.recycle()
        }
        recognizeBitmapOwned(retryBitmap)
    }

    private fun openRegionDecoder(bytes: ByteArray): BitmapRegionDecoder? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        }
    }.getOrNull()

    private suspend fun recognizeViaRegionDecoder(
        rawBytes: ByteArray,
        rawWidth: Int,
        rawHeight: Int,
        orientedHeight: Int,
        rotationDegrees: Int,
        sampleSize: Int
    ): List<OcrLine>? {
        val decoder = openRegionDecoder(rawBytes) ?: return null

        return try {
            val rawStripHeight = OCR_STRIP_HEIGHT * sampleSize
            val rawOverlap = OCR_STRIP_OVERLAP * sampleSize
            val rawThreshold = OCR_SINGLE_PASS_THRESHOLD * sampleSize
            val rawStrips = ScreenshotTilingPlanner.planVerticalStrips(
                height = orientedHeight,
                stripHeight = rawStripHeight,
                overlap = rawOverlap,
                singlePassThreshold = rawThreshold
            )
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val collected = mutableListOf<OcrLine>()
            for (strip in rawStrips) {
                val mapped = ScreenshotTilingPlanner.mapOrientedStripToRawRect(
                    rawWidth = rawWidth,
                    rawHeight = rawHeight,
                    rotationDegrees = rotationDegrees,
                    stripStart = strip.first,
                    stripEndExclusive = (strip.last + 1).coerceAtMost(orientedHeight)
                )
                if (mapped.right <= mapped.left || mapped.bottom <= mapped.top) continue
                val rect = Rect(mapped.left, mapped.top, mapped.right, mapped.bottom)
                val rawStripBitmap = try {
                    decoder.decodeRegion(rect, opts)
                } catch (_: OutOfMemoryError) {
                    throw IllegalStateException("截图分辨率过高，请裁剪后重试")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: continue
                val orientedStripBitmap = if (rotationDegrees != 0) {
                    try {
                        rotateBitmapIfNeeded(rawStripBitmap, rotationDegrees)
                    } catch (_: OutOfMemoryError) {
                        if (!rawStripBitmap.isRecycled) rawStripBitmap.recycle()
                        throw IllegalStateException("截图分辨率过高，请裁剪后重试")
                    } finally {
                        if (!rawStripBitmap.isRecycled) {
                            rawStripBitmap.recycle()
                        }
                    }
                } else {
                    rawStripBitmap
                }
                val normalizedStrip = normalizeBitmapWidth(orientedStripBitmap, recycleSource = true)
                collected += runOcrOnBitmap(
                    bitmap = normalizedStrip,
                    yOffset = strip.first / sampleSize,
                    recycleAfter = true
                )
            }
            collected
        } finally {
            runCatching { decoder.recycle() }
        }
    }

    /**
     * Public helper allowing [com.momentledger.app.pdf.PdfStatementExtractor] to OCR rendered
     * scanned-PDF pages while sharing the same safe bitmap recycling contract.
     */
    suspend fun recognizeBitmapOwned(bitmap: Bitmap): List<OcrLine> =
        runOcrOnBitmap(bitmap, yOffset = 0, recycleAfter = true)

    private suspend fun runOcrOnBitmap(
        bitmap: Bitmap,
        yOffset: Int,
        recycleAfter: Boolean
    ): List<OcrLine> = withContext(Dispatchers.Default) {
        try {
            inferenceMutex.withLock {
                val engine = acquireEngine()
                try {
                    engine.recognize(bitmap, yOffset)
                } finally {
                    releaseEngine()
                }
            }
        } finally {
            if (recycleAfter && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun readUriBytesBounded(uri: Uri): ByteArray {
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            val chunks = ArrayList<ByteArray>()
            var total = 0
            val chunkSize = 256 * 1024
            var current = ByteArray(chunkSize)
            var offset = 0
            while (true) {
                val read = input.read(current, offset, current.size - offset)
                if (read < 0) break
                total += read
                if (total.toLong() > MAX_IMAGE_BYTES) {
                    throw IllegalArgumentException("单张截图过大（上限 30MB）")
                }
                offset += read
                if (offset == current.size) {
                    chunks.add(current)
                    current = ByteArray(chunkSize)
                    offset = 0
                }
            }
            if (chunks.isEmpty()) {
                return if (offset == 0) ByteArray(0) else current.copyOf(offset)
            }
            val result = ByteArray(total)
            var pos = 0
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, result, pos, chunk.size)
                pos += chunk.size
            }
            if (offset > 0) {
                System.arraycopy(current, 0, result, pos, offset)
            }
            return result
        }
        return ByteArray(0)
    }

    private fun readExifRotationDegrees(bytes: ByteArray): Int = runCatching {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun rotateBitmapIfNeeded(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return source
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 30L * 1024L * 1024L
        const val MAX_FALLBACK_PIXELS = 16_000_000L
        const val MAX_ROTATED_FALLBACK_PIXELS = 10_000_000L
        const val MAX_TIME_RETRY_PIXELS = 1_000_000L
        const val IDLE_CLOSE_DELAY_MS = 30_000L
        const val OCR_STRIP_HEIGHT = 2000
        const val OCR_STRIP_OVERLAP = 200
        const val OCR_SINGLE_PASS_THRESHOLD = 2800
        const val LONG_SCROLL_REGION_THRESHOLD = 4200
    }
}
