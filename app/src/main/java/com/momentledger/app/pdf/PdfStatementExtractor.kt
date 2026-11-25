package com.momentledger.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.momentledger.app.domain.parser.PdfPageContent
import com.momentledger.app.domain.parser.PdfTextToken
import com.momentledger.app.ocr.ScreenshotOcrEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

class PdfPasswordRequiredException(message: String) : Exception(message)

data class PdfExtractionResult(
    val pages: List<PdfPageContent>,
    val totalPageCount: Int,
    val truncated: Boolean
)

/**
 * Offline bank-statement PDF extractor.
 *
 * Primary path: PDFBox-Android text + coordinate extraction (supports encrypted PDFs such as
 * ICBC PDF 1.5 with empty user password or user-entered password, and strips diagonal rotated
 * watermarks/stamps via text-matrix shear filtering).
 * Fallback path: Android [PdfRenderer] -> offline ML Kit Chinese OCR for image-only scanned PDFs.
 */
class PdfStatementExtractor(
    context: Context,
    private val ocrEngine: ScreenshotOcrEngine
) {
    private val appContext = context.applicationContext

    @Volatile
    private var pdfBoxInitialized = false

    private fun ensurePdfBoxInitialized() {
        if (pdfBoxInitialized) return
        synchronized(this) {
            if (!pdfBoxInitialized) {
                PDFBoxResourceLoader.init(appContext)
                pdfBoxInitialized = true
            }
        }
    }

    suspend fun extractPages(
        uri: Uri,
        password: String = ""
    ): PdfExtractionResult = withContext(Dispatchers.IO) {
        cleanupStaleCacheFiles()
        val (boxResult, tempFile) = extractTextOrSpillToCache(uri, password)
        if (boxResult != null) {
            return@withContext boxResult
        }
        val seekableFile = tempFile
            ?: throw IllegalArgumentException("无法读取所选 PDF 文件")
        try {
            extractWithPdfRendererOcr(seekableFile, hadUserPassword = password.isNotEmpty())
        } finally {
            runCatching { seekableFile.delete() }
        }
    }

    private suspend fun extractTextOrSpillToCache(
        uri: Uri,
        password: String
    ): Pair<PdfExtractionResult?, File?> {
        val bytes = readUriBytesBounded(uri)
        if (bytes.isEmpty()) {
            throw IllegalArgumentException("无法读取所选 PDF 文件")
        }
        if (!hasPdfMagicHeader(bytes)) {
            throw IllegalArgumentException("所选文件不是有效的 PDF 文档")
        }

        ensurePdfBoxInitialized()
        val ctx = kotlin.coroutines.coroutineContext
        val boxResult = PdfBoxPageExtractor.extractPages(bytes, password) {
            ctx.ensureActive()
        }
        val hasAnyText = boxResult.pages.any { it.tokens.isNotEmpty() || it.rawText.isNotBlank() }
        if (hasAnyText) {
            return boxResult to null
        }

        // Spill to a seekable cache file so PdfRenderer works even for non-seekable ACTION_SEND pipes,
        // and let `bytes` go out of scope before allocating ARGB_8888 OCR bitmaps.
        return null to spillToCacheFile(bytes)
    }

    suspend fun extractPagesFromBytes(
        rawBytes: ByteArray,
        password: String = ""
    ): PdfExtractionResult = withContext(Dispatchers.IO) {
        cleanupStaleCacheFiles()
        if (rawBytes.isEmpty()) {
            throw IllegalArgumentException("无法读取所选 PDF 文件")
        }
        if (!hasPdfMagicHeader(rawBytes)) {
            throw IllegalArgumentException("所选文件不是有效的 PDF 文档")
        }

        ensurePdfBoxInitialized()
        val boxResult = PdfBoxPageExtractor.extractPages(rawBytes, password) {
            coroutineContext.ensureActive()
        }
        val hasAnyText = boxResult.pages.any { it.tokens.isNotEmpty() || it.rawText.isNotBlank() }
        if (hasAnyText) {
            return@withContext boxResult
        }

        val tempFile = spillToCacheFile(rawBytes)
        try {
            extractWithPdfRendererOcr(tempFile, hadUserPassword = password.isNotEmpty())
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    private fun cleanupStaleCacheFiles() {
        runCatching {
            val cutoff = System.currentTimeMillis() - 30L * 60L * 1000L
            appContext.cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith("pdf_ocr_") && file.name.endsWith(".pdf") &&
                    file.lastModified() < cutoff
            }?.forEach { stale -> runCatching { stale.delete() } }
        }
    }

    private fun spillToCacheFile(bytes: ByteArray): File {
        val tempFile = File.createTempFile("pdf_ocr_", ".pdf", appContext.cacheDir)
        try {
            tempFile.outputStream().use { it.write(bytes) }
        } catch (t: Throwable) {
            runCatching { tempFile.delete() }
            throw t
        }
        return tempFile
    }

    private suspend fun extractWithPdfRendererOcr(
        seekablePdfFile: File,
        hadUserPassword: Boolean
    ): PdfExtractionResult {
        val pfd = ParcelFileDescriptor.open(seekablePdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        return pfd.use { descriptor ->
            val renderer = try {
                PdfRenderer(descriptor)
            } catch (_: SecurityException) {
                if (hadUserPassword) {
                    throw IllegalArgumentException(
                        "该加密 PDF 为扫描图片版，请截图后通过「截图识账」导入"
                    )
                }
                throw PdfPasswordRequiredException("该银行账单 PDF 已加密，请输入打开密码")
            }
            renderer.use { pdf ->
                val totalCount = pdf.pageCount
                val pageCount = totalCount.coerceAtMost(MAX_OCR_FALLBACK_PAGES)
                val pages = ArrayList<PdfPageContent>(pageCount)
                for (i in 0 until pageCount) {
                    coroutineContext.ensureActive()
                    val page = pdf.openPage(i)
                    val (bitmap, bmpW, bmpH) = try {
                        val pw = page.width.coerceAtLeast(1)
                        val ph = page.height.coerceAtLeast(1)
                        val scale = minOf(
                            OCR_RENDER_MAX_WIDTH.toFloat() / pw,
                            OCR_RENDER_MAX_HEIGHT.toFloat() / ph
                        ).coerceIn(0.05f, 2.5f)
                        val w = (pw * scale).toInt().coerceIn(1, OCR_RENDER_MAX_WIDTH)
                        val h = (ph * scale).toInt().coerceIn(1, OCR_RENDER_MAX_HEIGHT)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        try {
                            val canvas = Canvas(bmp)
                            canvas.drawColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        } catch (t: Throwable) {
                            if (!bmp.isRecycled) bmp.recycle()
                            throw t
                        }
                        Triple(bmp, w, h)
                    } finally {
                        page.close()
                    }
                    // Check cancellation BEFORE transferring bitmap ownership to ML Kit.
                    // Once passed to recognizeBitmapOwned(), ScreenshotOcrEngine's Task
                    // onCompleteListener owns recycling so cancellation never causes native use-after-recycle.
                    try {
                        coroutineContext.ensureActive()
                    } catch (t: Throwable) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        throw t
                    }
                    val ocrLines = ocrEngine.recognizeBitmapOwned(bitmap)
                    val tokens = ocrLines.map { line ->
                        PdfTextToken(
                            text = line.text,
                            x = line.left.toFloat(),
                            y = line.top.toFloat(),
                            width = line.width.toFloat(),
                            height = line.height.toFloat()
                        )
                    }
                    pages += PdfPageContent(
                        pageNumber = i + 1,
                        width = bmpW.toFloat(),
                        height = bmpH.toFloat(),
                        rawText = ocrLines.joinToString("\n") { it.text },
                        tokens = tokens
                    )
                }
                PdfExtractionResult(
                    pages = pages,
                    totalPageCount = totalCount,
                    truncated = totalCount > pageCount
                )
            }
        }
    }

    fun readUriBytesBounded(uri: Uri): ByteArray {
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
                if (total.toLong() > MAX_PDF_BYTES) {
                    throw IllegalArgumentException("PDF 账单文件过大（上限 30MB）")
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

    companion object {
        private const val MAX_PDF_BYTES = 30L * 1024L * 1024L
        private const val MAX_OCR_FALLBACK_PAGES = 20
        private const val OCR_RENDER_MAX_WIDTH = 1440
        private const val OCR_RENDER_MAX_HEIGHT = 2400

        fun hasPdfMagicHeader(bytes: ByteArray): Boolean {
            if (bytes.size < 5) return false
            // Allow leading whitespace/BOM before %PDF-
            val limit = minOf(bytes.size - 5, 16)
            for (i in 0..limit) {
                if (bytes[i] == '%'.code.toByte() &&
                    bytes[i + 1] == 'P'.code.toByte() &&
                    bytes[i + 2] == 'D'.code.toByte() &&
                    bytes[i + 3] == 'F'.code.toByte() &&
                    bytes[i + 4] == '-'.code.toByte()
                ) {
                    return true
                }
            }
            return false
        }
    }
}

internal object PdfBoxPageExtractor {

    private const val MAX_PDF_PAGES = 100
    private const val DIAGONAL_AXIS_RATIO_THRESHOLD = 0.15f

    /**
     * Returns true for diagonally rotated watermarks/stamps (~8.5°..81.5° in any quadrant),
     * while preserving axis-aligned 0°/90°/180°/270° table text (including rotated landscape
     * pages and italic/oblique fonts whose baseline shearY remains 0).
     */
    internal fun isDiagonalTextMatrix(scaleX: Float, shearY: Float): Boolean {
        val ax = abs(scaleX)
        val ay = abs(shearY)
        if (ax <= 0.001f || ay <= 0.001f) return false
        return (minOf(ax, ay) / maxOf(ax, ay)) > DIAGONAL_AXIS_RATIO_THRESHOLD
    }

    fun extractPages(
        rawBytes: ByteArray,
        password: String = "",
        checkCancellation: () -> Unit = {}
    ): PdfExtractionResult {
        val document = try {
            PDDocument.load(rawBytes, password)
        } catch (_: InvalidPasswordException) {
            throw PdfPasswordRequiredException(
                if (password.isEmpty()) {
                    "该银行账单 PDF 已加密，请输入打开密码"
                } else {
                    "PDF 密码不正确，请重新输入"
                }
            )
        }

        return try {
            val actualTotalPages = document.numberOfPages
            val totalPages = actualTotalPages.coerceAtMost(MAX_PDF_PAGES)
            val result = ArrayList<PdfPageContent>(totalPages)
            for (pageIndex in 0 until totalPages) {
                checkCancellation()
                val pdPage = document.getPage(pageIndex)
                val mediaBox = pdPage.mediaBox
                val pageWidth = mediaBox?.width ?: 842f
                val pageHeight = mediaBox?.height ?: 595f

                val stripper = UprightTokenStripper().apply {
                    sortByPosition = true
                    startPage = pageIndex + 1
                    endPage = pageIndex + 1
                }
                val text = stripper.getText(document)
                result += PdfPageContent(
                    pageNumber = pageIndex + 1,
                    width = pageWidth,
                    height = pageHeight,
                    rawText = text,
                    tokens = stripper.collectedTokens
                )
            }
            PdfExtractionResult(
                pages = result,
                totalPageCount = actualTotalPages,
                truncated = actualTotalPages > totalPages
            )
        } finally {
            runCatching { document.close() }
        }
    }

    private class UprightTokenStripper : PDFTextStripper() {

        val collectedTokens = mutableListOf<PdfTextToken>()

        /**
         * Drop diagonally rotated watermark/stamp glyphs BEFORE PDFTextStripper sorts and
         * groups characters into lines/words; otherwise a watermark crossing a table header
         * (e.g. "收入/支出金额") or numeric cell (e.g. "-55.60") can split a single token in half.
         */
        override fun processTextPosition(text: TextPosition?) {
            if (text == null || text.unicode == null) return
            val tm = text.textMatrix
            if (tm != null && isDiagonalTextMatrix(tm.scaleX, tm.shearY)) {
                return
            }
            super.processTextPosition(text)
        }

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            if (textPositions.isNullOrEmpty()) return
            val upright = textPositions.filter { pos ->
                val tm = pos.textMatrix
                val isRotatedWatermark = tm != null && isDiagonalTextMatrix(tm.scaleX, tm.shearY)
                !isRotatedWatermark && pos.unicode != null
            }
            if (upright.isEmpty()) return

            val uprightText = upright.joinToString("") { it.unicode.orEmpty() }
            if (uprightText.isNotBlank()) {
                super.writeString(uprightText, upright)
            }

            val currentText = StringBuilder()
            var startX = 0f
            var endX = 0f
            var baselineY = 0f
            var maxH = 0f

            fun flushToken() {
                val trimmed = currentText.toString().trim()
                if (trimmed.isNotEmpty()) {
                    collectedTokens += PdfTextToken(
                        text = trimmed,
                        x = startX,
                        y = baselineY,
                        width = (endX - startX).coerceAtLeast(1f),
                        height = maxH.coerceAtLeast(6f)
                    )
                }
                currentText.clear()
            }

            for (pos in upright) {
                val ch = pos.unicode.orEmpty()
                val x = pos.xDirAdj
                val w = pos.widthDirAdj.coerceAtLeast(0.5f)
                val y = pos.yDirAdj
                val h = pos.heightDir.coerceAtLeast(6f)
                val fontSize = pos.fontSizeInPt.coerceAtLeast(6f)

                if (currentText.isNotEmpty()) {
                    val gap = x - endX
                    val yJump = abs(y - baselineY)
                    val colGapThreshold = maxOf(5.5f, fontSize * 0.65f)
                    if (gap > colGapThreshold || gap < -fontSize || yJump > maxOf(4f, maxH * 0.6f)) {
                        flushToken()
                    }
                }

                if (currentText.isEmpty()) {
                    startX = x
                    endX = x + w
                    baselineY = y
                    maxH = h
                } else {
                    endX = maxOf(endX, x + w)
                    maxH = maxOf(maxH, h)
                }
                currentText.append(ch)
            }
            flushToken()
        }
    }
}
