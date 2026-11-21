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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.momentledger.app.domain.parser.OcrImageInput
import com.momentledger.app.domain.parser.OcrLine
import com.momentledger.app.domain.parser.ScreenshotBillParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
 * Fully offline, zero-network, zero-GMS Chinese OCR engine backed by bundled ML Kit.
 * Supports single-screen and tall scrolling screenshots via vertical overlapping strips.
 */
class ScreenshotOcrEngine(context: Context) {

    private val appContext = context.applicationContext

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
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

        // Fast, memory-bounded path for tall scrolling screenshots (0/90/180/270 EXIF rotations)
        if (sampledHeight > ScreenshotTilingPlanner.DEFAULT_SINGLE_PASS_THRESHOLD) {
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

        // Standard single-pass decode (or unsupported-region-format fallback with total-pixel RAM cap)
        val maxFallbackPixels = if (rotationDegrees != 0) MAX_ROTATED_FALLBACK_PIXELS else MAX_FALLBACK_PIXELS
        var fallbackSampleSize = sampleSize
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

        val finalWidth = orientedBitmap.width
        val finalHeight = orientedBitmap.height
        val strips = ScreenshotTilingPlanner.planVerticalStrips(finalHeight)
        if (strips.size == 1) {
            // Single strip: ownership transfers to recognizeBitmapAndRecycle so bitmap is never
            // recycled synchronously on coroutine cancellation while ML Kit still reads it.
            val visionText = recognizeBitmapAndRecycle(orientedBitmap)
            return@withContext OcrImageInput(
                lines = ScreenshotBillParser.deduplicateTiledOcrLines(extractLines(visionText, yOffset = 0)),
                imageWidth = finalWidth,
                imageHeight = finalHeight,
                fallbackTimestampMillis = fallbackMillis
            )
        }

        try {
            val allLines = mutableListOf<OcrLine>()
            for (strip in strips) {
                val stripHeight = strip.last - strip.first + 1
                if (stripHeight <= 0) continue
                val crop = try {
                    Bitmap.createBitmap(orientedBitmap, 0, strip.first, finalWidth, stripHeight)
                } catch (_: OutOfMemoryError) {
                    throw IllegalStateException("截图分辨率过高，请裁剪后重试")
                }
                val visionText = recognizeBitmapAndRecycle(crop)
                allLines += extractLines(visionText, yOffset = strip.first)
            }
            OcrImageInput(
                lines = ScreenshotBillParser.deduplicateTiledOcrLines(allLines),
                imageWidth = finalWidth,
                imageHeight = finalHeight,
                fallbackTimestampMillis = fallbackMillis
            )
        } finally {
            if (!orientedBitmap.isRecycled) {
                orientedBitmap.recycle()
            }
        }
    }

    private suspend fun recognizeViaRegionDecoder(
        rawBytes: ByteArray,
        rawWidth: Int,
        rawHeight: Int,
        orientedHeight: Int,
        rotationDegrees: Int,
        sampleSize: Int
    ): List<OcrLine>? {
        val decoder = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(rawBytes, 0, rawBytes.size)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(rawBytes, 0, rawBytes.size, false)
            }
        }.getOrNull() ?: return null

        return try {
            val rawStripHeight = ScreenshotTilingPlanner.DEFAULT_STRIP_HEIGHT * sampleSize
            val rawOverlap = ScreenshotTilingPlanner.DEFAULT_OVERLAP * sampleSize
            val rawThreshold = ScreenshotTilingPlanner.DEFAULT_SINGLE_PASS_THRESHOLD * sampleSize
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
                val rawStripBitmap = runCatching { decoder.decodeRegion(rect, opts) }.getOrNull() ?: continue
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
                val visionText = recognizeBitmapAndRecycle(orientedStripBitmap)
                collected += extractLines(visionText, yOffset = strip.first / sampleSize)
            }
            collected
        } finally {
            runCatching { decoder.recycle() }
        }
    }

    /**
     * Public helper allowing [com.momentledger.app.pdf.PdfStatementExtractor] to OCR rendered
     * scanned-PDF pages while sharing the same safe Task-bound bitmap recycling contract.
     */
    suspend fun recognizeBitmapOwned(bitmap: Bitmap): List<OcrLine> = withContext(Dispatchers.IO) {
        val visionText = recognizeBitmapAndRecycle(bitmap)
        extractLines(visionText, yOffset = 0)
    }

    /**
     * Runs ML Kit OCR on [bitmap] and defers recycling until ML Kit's Task actually completes,
     * preventing native use-after-recycle crashes if the calling coroutine is cancelled mid-OCR.
     */
    private suspend fun recognizeBitmapAndRecycle(bitmap: Bitmap): Text =
        suspendCancellableCoroutine { cont ->
            val task = try {
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image)
            } catch (t: Throwable) {
                if (!bitmap.isRecycled) bitmap.recycle()
                if (cont.isActive) cont.resumeWithException(t)
                return@suspendCancellableCoroutine
            }

            task
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result)
                }
                .addOnFailureListener { error ->
                    if (cont.isActive) cont.resumeWithException(error)
                }
                .addOnCompleteListener {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
        }

    private fun extractLines(visionText: Text, yOffset: Int): List<OcrLine> {
        val result = mutableListOf<OcrLine>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val text = line.text.trim()
                if (text.isEmpty()) continue
                val box = line.boundingBox
                if (box != null) {
                    result += OcrLine(
                        text = text,
                        left = box.left,
                        top = box.top + yOffset,
                        right = box.right,
                        bottom = box.bottom + yOffset
                    )
                } else {
                    result += OcrLine(text = text, left = 0, top = yOffset, right = 100, bottom = yOffset + 40)
                }
            }
        }
        return result
    }

    private fun readUriBytesBounded(uri: Uri): ByteArray {
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_IMAGE_BYTES) {
                    throw IllegalArgumentException("单张截图过大（上限 30MB）")
                }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
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
    }
}
