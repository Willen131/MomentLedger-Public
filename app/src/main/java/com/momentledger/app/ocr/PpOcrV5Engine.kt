package com.momentledger.app.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.momentledger.app.domain.parser.OcrLine
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.ensureActive

internal data class Point2D(val x: Float, val y: Float)

internal data class QuadBox(val points: List<Point2D>) {
    fun minX(): Float = points.minOfOrNull { it.x } ?: 0f
    fun maxX(): Float = points.maxOfOrNull { it.x } ?: 0f
    fun minY(): Float = points.minOfOrNull { it.y } ?: 0f
    fun maxY(): Float = points.maxOfOrNull { it.y } ?: 0f
}

internal data class CtcDecodeResult(
    val text: String,
    val confidence: Float
)

/**
 * Pure-Kotlin geometry and CTC decoding math for PaddleOCR PP-OCRv5 DB detection and SVTR_LCNet
 * recognition (MIT/Apache-2.0 compatible reference implementation without OpenCV).
 * Kept free of Android framework classes so every algorithm is directly unit-testable on the JVM.
 */
internal object PpOcrV5Math {

    const val DET_LIMIT_SIDE_LEN = 1280
    const val DET_THRESH = 0.30f
    const val DET_BOX_THRESH = 0.55f
    const val DET_UNCLIP_RATIO = 1.50f
    const val DET_MIN_SIZE = 3.0f
    const val DET_MAX_CANDIDATES = 1000
    private const val EPSILON = 1e-6f

    fun calculateDetResizeDimensions(
        width: Int,
        height: Int,
        limitSideLen: Int = DET_LIMIT_SIDE_LEN
    ): Pair<Int, Int> {
        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)
        val maxSide = max(safeW, safeH)
        val ratio = if (maxSide > limitSideLen) limitSideLen.toFloat() / maxSide else 1.0f
        var resizedW = max(1, (safeW * ratio).roundToInt())
        var resizedH = max(1, (safeH * ratio).roundToInt())
        resizedW = max(((resizedW + 31) / 32) * 32, 32)
        resizedH = max(((resizedH + 31) / 32) * 32, 32)
        return resizedW to resizedH
    }

    fun postprocessDetection(
        probFlat: FloatArray,
        resizedWidth: Int,
        resizedHeight: Int,
        originalWidth: Int,
        originalHeight: Int,
        thresh: Float = DET_THRESH,
        boxThresh: Float = DET_BOX_THRESH,
        unclipRatio: Float = DET_UNCLIP_RATIO
    ): List<QuadBox> {
        val total = resizedWidth * resizedHeight
        if (resizedWidth <= 0 || resizedHeight <= 0 || probFlat.size < total) return emptyList()

        val binaryMap = BooleanArray(total) { probFlat[it] > thresh }
        val components = extractConnectedComponents(binaryMap, resizedWidth, resizedHeight)
            .sortedByDescending { it.size }
            .take(DET_MAX_CANDIDATES)

        val scaleX = originalWidth.toFloat() / resizedWidth
        val scaleY = originalHeight.toFloat() / resizedHeight
        val boxes = ArrayList<QuadBox>(components.size)

        for (component in components) {
            if (component.size < 4) continue
            val hull = convexHull(component)
            if (hull.size < 3) continue
            val rect = minimumAreaRectangle(hull, pointsAreConvex = true)
            if (rect.size != 4) continue

            val score = calculateBoxScore(probFlat, resizedWidth, resizedHeight, rect)
            if (score < boxThresh) continue

            val unclipped = unclipPolygon(rect, unclipRatio)
            if (unclipped.size < 3) continue

            val expanded = minimumAreaRectangle(unclipped, pointsAreConvex = false)
            if (expanded.size != 4) continue
            if (minEdgeLength(expanded) < DET_MIN_SIZE) continue

            val scaled = expanded.map { pt ->
                Point2D(
                    x = (pt.x.coerceIn(0f, resizedWidth - 1f) * scaleX).coerceIn(0f, originalWidth.toFloat()),
                    y = (pt.y.coerceIn(0f, resizedHeight - 1f) * scaleY).coerceIn(0f, originalHeight.toFloat())
                )
            }
            val ordered = orderClockwise(scaled)
            val width = max(distance(ordered[0], ordered[1]), distance(ordered[2], ordered[3]))
            val height = max(distance(ordered[0], ordered[3]), distance(ordered[1], ordered[2]))
            if (width >= DET_MIN_SIZE && height >= DET_MIN_SIZE) {
                boxes += QuadBox(ordered)
            }
        }
        return sortBoxes(boxes)
    }

    /**
     * 8-connected flood fill using a primitive IntArray stack (zero Pair boxing / low GC).
     */
    internal fun extractConnectedComponents(
        binaryMap: BooleanArray,
        width: Int,
        height: Int
    ): List<List<Point2D>> {
        val total = width * height
        val visited = BooleanArray(total)
        val components = ArrayList<List<Point2D>>()
        var stack = IntArray(256)

        for (y in 0 until height) {
            val rowOff = y * width
            for (x in 0 until width) {
                val startIdx = rowOff + x
                if (!binaryMap[startIdx] || visited[startIdx]) continue

                var sp = 0
                stack[sp++] = startIdx
                visited[startIdx] = true
                val points = ArrayList<Point2D>()

                while (sp > 0) {
                    val idx = stack[--sp]
                    val cy = idx / width
                    val cx = idx - cy * width
                    points.add(Point2D(cx.toFloat(), cy.toFloat()))

                    val yMin = max(0, cy - 1)
                    val yMax = min(height - 1, cy + 1)
                    val xMin = max(0, cx - 1)
                    val xMax = min(width - 1, cx + 1)
                    for (ny in yMin..yMax) {
                        val nRow = ny * width
                        for (nx in xMin..xMax) {
                            val nIdx = nRow + nx
                            if (binaryMap[nIdx] && !visited[nIdx]) {
                                visited[nIdx] = true
                                if (sp == stack.size) {
                                    stack = stack.copyOf(stack.size * 2)
                                }
                                stack[sp++] = nIdx
                            }
                        }
                    }
                }
                components.add(points)
            }
        }
        return components
    }

    internal fun convexHull(points: List<Point2D>): List<Point2D> {
        if (points.size < 3) return points
        val sorted = points.sortedWith(compareBy<Point2D> { it.x }.thenBy { it.y })
        val lower = ArrayList<Point2D>(sorted.size)
        val upper = ArrayList<Point2D>(sorted.size)

        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) {
                lower.removeAt(lower.lastIndex)
            }
            lower.add(p)
        }
        for (i in sorted.lastIndex downTo 0) {
            val p = sorted[i]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) {
                upper.removeAt(upper.lastIndex)
            }
            upper.add(p)
        }
        lower.removeAt(lower.lastIndex)
        upper.removeAt(upper.lastIndex)
        val result = ArrayList<Point2D>(lower.size + upper.size)
        result.addAll(lower)
        result.addAll(upper)
        return result
    }

    internal fun minimumAreaRectangle(
        points: List<Point2D>,
        pointsAreConvex: Boolean = false
    ): List<Point2D> {
        val hull = if (pointsAreConvex) points else convexHull(points)
        if (hull.size < 3) return emptyList()

        var bestRect: List<Point2D> = emptyList()
        var minArea = Float.MAX_VALUE

        for (i in hull.indices) {
            val p1 = hull[i]
            val p2 = hull[(i + 1) % hull.size]
            val edge = normalizeVector(p2.x - p1.x, p2.y - p1.y) ?: continue
            val normal = Point2D(-edge.y, edge.x)

            var minProj = Float.MAX_VALUE
            var maxProj = -Float.MAX_VALUE
            var minOrth = Float.MAX_VALUE
            var maxOrth = -Float.MAX_VALUE

            for (pt in hull) {
                val rx = pt.x - p1.x
                val ry = pt.y - p1.y
                val proj = rx * edge.x + ry * edge.y
                val orth = rx * normal.x + ry * normal.y
                if (proj < minProj) minProj = proj
                if (proj > maxProj) maxProj = proj
                if (orth < minOrth) minOrth = orth
                if (orth > maxOrth) maxOrth = orth
            }

            val width = maxProj - minProj
            val height = maxOrth - minOrth
            val area = width * height
            if (area < minArea && width > 1e-3f && height > 1e-3f) {
                minArea = area
                bestRect = listOf(
                    Point2D(p1.x + edge.x * minProj + normal.x * minOrth, p1.y + edge.y * minProj + normal.y * minOrth),
                    Point2D(p1.x + edge.x * maxProj + normal.x * minOrth, p1.y + edge.y * maxProj + normal.y * minOrth),
                    Point2D(p1.x + edge.x * maxProj + normal.x * maxOrth, p1.y + edge.y * maxProj + normal.y * maxOrth),
                    Point2D(p1.x + edge.x * minProj + normal.x * maxOrth, p1.y + edge.y * minProj + normal.y * maxOrth)
                )
            }
        }
        return bestRect.ifEmpty { axisAlignedBoundingBox(hull) }
    }

    internal fun unclipPolygon(box: List<Point2D>, unclipRatio: Float): List<Point2D> {
        if (box.size < 3) return emptyList()
        val area = polygonSignedArea(box)
        val perimeter = polygonPerimeter(box)
        if (perimeter <= EPSILON) return emptyList()
        val offset = abs(area) * unclipRatio / perimeter
        if (offset <= EPSILON) return box

        val count = box.size
        val isCcw = area > 0f
        val result = ArrayList<Point2D>(count)
        for (i in 0 until count) {
            val prev = box[(i - 1 + count) % count]
            val curr = box[i]
            val next = box[(i + 1) % count]
            val dir1 = normalizeVector(curr.x - prev.x, curr.y - prev.y) ?: continue
            val dir2 = normalizeVector(next.x - curr.x, next.y - curr.y) ?: continue
            val n1 = if (isCcw) Point2D(dir1.y, -dir1.x) else Point2D(-dir1.y, dir1.x)
            val n2 = if (isCcw) Point2D(dir2.y, -dir2.x) else Point2D(-dir2.y, dir2.x)
            val p1 = Point2D(curr.x + n1.x * offset, curr.y + n1.y * offset)
            val p2 = Point2D(curr.x + n2.x * offset, curr.y + n2.y * offset)
            result.add(intersectLines(p1, dir1, p2, dir2) ?: curr)
        }
        return if (result.size >= 3) result else emptyList()
    }

    internal fun orderClockwise(points: List<Point2D>): List<Point2D> {
        if (points.size != 4) return points
        var cx = 0f
        var cy = 0f
        for (p in points) {
            cx += p.x
            cy += p.y
        }
        cx /= 4f
        cy /= 4f
        val sorted = points.sortedBy { atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()) }
        var topLeftIndex = 0
        var minSum = sorted[0].x + sorted[0].y
        for (i in 1 until 4) {
            val sum = sorted[i].x + sorted[i].y
            if (sum < minSum) {
                minSum = sum
                topLeftIndex = i
            }
        }
        return List(4) { i -> sorted[(topLeftIndex + i) % 4] }
    }

    internal fun sortBoxes(boxes: List<QuadBox>): List<QuadBox> {
        if (boxes.size <= 1) return boxes
        val sortedByTop = boxes.sortedBy { it.minY() }
        val ordered = ArrayList<QuadBox>(sortedByTop.size)
        var i = 0
        while (i < sortedByTop.size) {
            val refY = sortedByTop[i].minY()
            val group = ArrayList<QuadBox>()
            var j = i
            while (j < sortedByTop.size && abs(sortedByTop[j].minY() - refY) <= 10f) {
                group.add(sortedByTop[j])
                j++
            }
            group.sortBy { it.minX() }
            ordered.addAll(group)
            i = j
        }
        return ordered
    }

    internal fun ctcDecode(
        charIndices: IntArray,
        probs: FloatArray,
        dictionary: List<String>
    ): CtcDecodeResult {
        if (charIndices.isEmpty()) return CtcDecodeResult("", 0f)
        val sb = StringBuilder()
        var probSum = 0.0
        var charCount = 0
        var t = 0
        while (t < charIndices.size) {
            val idx = charIndices[t]
            if (idx == 0) {
                t++
                continue
            }
            var end = t + 1
            var runProbSum = probs[t].toDouble()
            while (end < charIndices.size && charIndices[end] == idx) {
                runProbSum += probs[end].toDouble()
                end++
            }
            if (idx in 1 until dictionary.size) {
                sb.append(dictionary[idx])
                probSum += runProbSum / (end - t)
                charCount++
            }
            t = end
        }
        val confidence = if (charCount > 0) (probSum / charCount).toFloat() else 0f
        return CtcDecodeResult(sb.toString(), confidence)
    }

    internal fun distance(a: Point2D, b: Point2D): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun calculateBoxScore(
        probFlat: FloatArray,
        width: Int,
        height: Int,
        polygon: List<Point2D>
    ): Float {
        var minX = floor(polygon.minOf { it.x.toDouble() }).toInt().coerceIn(0, width - 1)
        var maxX = ceil(polygon.maxOf { it.x.toDouble() }).toInt().coerceIn(0, width - 1)
        var minY = floor(polygon.minOf { it.y.toDouble() }).toInt().coerceIn(0, height - 1)
        var maxY = ceil(polygon.maxOf { it.y.toDouble() }).toInt().coerceIn(0, height - 1)
        if (maxX < minX || maxY < minY) return 0f

        var sum = 0f
        var count = 0
        for (y in minY..maxY) {
            val rowOff = y * width
            for (x in minX..maxX) {
                if (isPointInsideQuad(x + 0.5f, y + 0.5f, polygon)) {
                    sum += probFlat[rowOff + x]
                    count++
                }
            }
        }
        return if (count > 0) sum / count else 0f
    }

    private fun isPointInsideQuad(x: Float, y: Float, quad: List<Point2D>): Boolean {
        var hasPos = false
        var hasNeg = false
        for (i in quad.indices) {
            val p1 = quad[i]
            val p2 = quad[(i + 1) % quad.size]
            val c = (p2.x - p1.x) * (y - p1.y) - (p2.y - p1.y) * (x - p1.x)
            if (c > 0f) hasPos = true else if (c < 0f) hasNeg = true
            if (hasPos && hasNeg) return false
        }
        return true
    }

    private fun axisAlignedBoundingBox(points: List<Point2D>): List<Point2D> {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        return listOf(
            Point2D(minX, minY),
            Point2D(maxX, minY),
            Point2D(maxX, maxY),
            Point2D(minX, maxY)
        )
    }

    private fun cross(o: Point2D, a: Point2D, b: Point2D): Float =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    private fun normalizeVector(dx: Float, dy: Float): Point2D? {
        val len = sqrt(dx * dx + dy * dy)
        return if (len < EPSILON) null else Point2D(dx / len, dy / len)
    }

    private fun polygonSignedArea(points: List<Point2D>): Float {
        var area = 0f
        for (i in points.indices) {
            val j = (i + 1) % points.size
            area += points[i].x * points[j].y - points[j].x * points[i].y
        }
        return area / 2f
    }

    private fun polygonPerimeter(points: List<Point2D>): Float {
        var p = 0f
        for (i in points.indices) {
            p += distance(points[i], points[(i + 1) % points.size])
        }
        return p
    }

    private fun minEdgeLength(box: List<Point2D>): Float {
        var minLen = Float.MAX_VALUE
        for (i in box.indices) {
            val d = distance(box[i], box[(i + 1) % box.size])
            if (d < minLen) minLen = d
        }
        return if (minLen == Float.MAX_VALUE) 0f else minLen
    }

    private fun intersectLines(
        p1: Point2D,
        d1: Point2D,
        p2: Point2D,
        d2: Point2D
    ): Point2D? {
        val c = d1.x * d2.y - d1.y * d2.x
        if (abs(c) < EPSILON) return null
        val t = ((p2.x - p1.x) * d2.y - (p2.y - p1.y) * d2.x) / c
        return Point2D(p1.x + d1.x * t, p1.y + d1.y * t)
    }
}

/**
 * Fully offline, zero-GMS PaddleOCR PP-OCRv5 Mobile engine powered by ONNX Runtime.
 * Models are bundled in APK assets/ppocrv5/ and materialized once into noBackupFilesDir
 * so ONNX Runtime can mmap weights natively without Java heap spikes.
 */
internal class PpOcrV5Engine(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessionOptions: OrtSession.SessionOptions
    private val detSession: OrtSession
    private val recSession: OrtSession
    private val detInputName: String
    private val recInputName: String
    private val dictionary: List<String>

    init {
        val modelDir = ensureBundledModels(appContext)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        sessionOptions = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            setIntraOpNumThreads(threads)
            // Prevent ORT worker threads from busy-spinning mobile big.LITTLE cores between/after inferences.
            addConfigEntry("session.intra_op.allow_spinning", "0")
            addConfigEntry("session.inter_op.allow_spinning", "0")
        }
        detSession = ortEnv.createSession(File(modelDir, DET_ASSET).absolutePath, sessionOptions)
        recSession = ortEnv.createSession(File(modelDir, REC_ASSET).absolutePath, sessionOptions)
        detInputName = detSession.inputNames.first()
        recInputName = recSession.inputNames.first()
        dictionary = loadDictionary(File(modelDir, DICT_ASSET))
    }

    suspend fun recognize(bitmap: Bitmap, yOffset: Int = 0): List<OcrLine> {
        coroutineContext.ensureActive()
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return emptyList()

        val boxes = detectBoxes(bitmap)
        if (boxes.isEmpty()) return emptyList()
        coroutineContext.ensureActive()

        val crops = ArrayList<Bitmap>(boxes.size)
        val validBoxes = ArrayList<QuadBox>(boxes.size)
        try {
            for (box in boxes) {
                coroutineContext.ensureActive()
                val crop = cropTextRegion(bitmap, box) ?: continue
                crops += crop
                validBoxes += box
            }
            if (crops.isEmpty()) return emptyList()

            val results = recognizeBatched(crops)
            val lines = ArrayList<OcrLine>(results.size)
            for (i in results.indices) {
                val res = results[i]
                val trimmed = res.text.trim()
                if (trimmed.isEmpty() || res.confidence < MIN_RECOGNITION_SCORE) continue
                if (trimmed in LONE_BAR_NOISE) continue
                val box = validBoxes[i]
                val left = floor(box.minX().toDouble()).toInt().coerceIn(0, bitmap.width)
                val top = floor(box.minY().toDouble()).toInt().coerceIn(0, bitmap.height)
                val right = ceil(box.maxX().toDouble()).toInt().coerceIn(left + 1, bitmap.width.coerceAtLeast(left + 1))
                val bottom = ceil(box.maxY().toDouble()).toInt().coerceIn(top + 1, bitmap.height.coerceAtLeast(top + 1))
                lines += OcrLine(
                    text = trimmed,
                    left = left,
                    top = top + yOffset,
                    right = right,
                    bottom = bottom + yOffset
                )
            }
            return lines
        } finally {
            for (crop in crops) {
                if (!crop.isRecycled) crop.recycle()
            }
        }
    }

    private fun detectBoxes(bitmap: Bitmap): List<QuadBox> {
        val origW = bitmap.width
        val origH = bitmap.height
        val (resizedW, resizedH) = PpOcrV5Math.calculateDetResizeDimensions(origW, origH)
        val resized = if (resizedW == origW && resizedH == origH) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, resizedW, resizedH, true)
        }
        val pixels = IntArray(resizedW * resizedH)
        try {
            resized.getPixels(pixels, 0, resizedW, 0, 0, resizedW, resizedH)
        } finally {
            if (resized !== bitmap && !resized.isRecycled) resized.recycle()
        }

        val planeSize = resizedW * resizedH
        val input = FloatArray(3 * planeSize)
        val inv255 = 1.0f / 255.0f
        for (i in 0 until planeSize) {
            val px = pixels[i]
            val b = (px and 0xFF) * inv255
            val g = ((px ushr 8) and 0xFF) * inv255
            val r = ((px ushr 16) and 0xFF) * inv255
            // CHW BGR order matching PaddleOCR training normalization
            input[i] = (b - DET_MEAN_0) * DET_INV_STD_0
            input[planeSize + i] = (g - DET_MEAN_1) * DET_INV_STD_1
            input[2 * planeSize + i] = (r - DET_MEAN_2) * DET_INV_STD_2
        }

        val shape = longArrayOf(1L, 3L, resizedH.toLong(), resizedW.toLong())
        val tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(input), shape)
        return try {
            detSession.run(mapOf(detInputName to tensor)).use { outputs ->
                val outTensor = outputs[0] as OnnxTensor
                val buf = outTensor.floatBuffer.duplicate()
                buf.rewind()
                val probFlat = FloatArray(planeSize)
                buf.get(probFlat, 0, min(probFlat.size, buf.remaining()))
                PpOcrV5Math.postprocessDetection(
                    probFlat = probFlat,
                    resizedWidth = resizedW,
                    resizedHeight = resizedH,
                    originalWidth = origW,
                    originalHeight = origH
                )
            }
        } finally {
            tensor.close()
        }
    }

    private fun cropTextRegion(source: Bitmap, box: QuadBox): Bitmap? {
        val pts = box.points
        if (pts.size != 4 || source.isRecycled) return null
        val width = max(
            PpOcrV5Math.distance(pts[0], pts[1]),
            PpOcrV5Math.distance(pts[2], pts[3])
        ).roundToInt().coerceIn(1, source.width.coerceAtLeast(1))
        val height = max(
            PpOcrV5Math.distance(pts[0], pts[3]),
            PpOcrV5Math.distance(pts[1], pts[2])
        ).roundToInt().coerceIn(1, source.height.coerceAtLeast(1))

        val srcPoints = floatArrayOf(
            pts[0].x, pts[0].y,
            pts[1].x, pts[1].y,
            pts[2].x, pts[2].y,
            pts[3].x, pts[3].y
        )
        val dstPoints = floatArrayOf(
            0f, 0f,
            width.toFloat(), 0f,
            width.toFloat(), height.toFloat(),
            0f, height.toFloat()
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)) return null

        val cropped = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return null
        }
        val canvas = Canvas(cropped)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        canvas.drawBitmap(source, matrix, paint)

        // Only rotate genuinely tall multi-character vertical columns, never single narrow glyphs like "1" or "¥".
        if (height.toFloat() / width >= 2.2f && height >= 80) {
            val rotMatrix = Matrix().apply { postRotate(90f) }
            val rotated = try {
                Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, rotMatrix, true)
            } catch (_: OutOfMemoryError) {
                return cropped
            }
            if (rotated !== cropped && !cropped.isRecycled) cropped.recycle()
            return rotated
        }
        return cropped
    }

    private suspend fun recognizeBatched(crops: List<Bitmap>): List<CtcDecodeResult> {
        val sortedIndices = crops.indices.sortedBy { crops[it].width.toFloat() / crops[it].height.coerceAtLeast(1) }
        val ordered = MutableList(crops.size) { CtcDecodeResult("", 0f) }
        var rowBuf: FloatArray? = null

        var start = 0
        while (start < sortedIndices.size) {
            coroutineContext.ensureActive()
            val end = min(start + REC_BATCH_SIZE, sortedIndices.size)
            val batchIdx = sortedIndices.subList(start, end)
            val batchCrops = batchIdx.map { crops[it] }

            var maxWhRatio = REC_BASE_WIDTH.toFloat() / REC_IMG_HEIGHT
            for (img in batchCrops) {
                val r = img.width.toFloat() / img.height.coerceAtLeast(1)
                if (r > maxWhRatio) maxWhRatio = r
            }
            val targetWidth = ceil(REC_IMG_HEIGHT * maxWhRatio).toInt().coerceIn(REC_BASE_WIDTH, REC_MAX_WIDTH)
            val batchSize = batchCrops.size
            val channelStride = REC_IMG_HEIGHT * targetWidth
            val sampleStride = 3 * channelStride
            val input = FloatArray(batchSize * sampleStride)

            for (b in 0 until batchSize) {
                fillRecSample(batchCrops[b], input, b * sampleStride, targetWidth, channelStride)
            }

            val shape = longArrayOf(batchSize.toLong(), 3L, REC_IMG_HEIGHT.toLong(), targetWidth.toLong())
            val tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(input), shape)
            try {
                recSession.run(mapOf(recInputName to tensor)).use { outputs ->
                    val outTensor = outputs[0] as OnnxTensor
                    val outShape = outTensor.info.shape
                    val seqLen = outShape[1].toInt()
                    val vocabSize = outShape[2].toInt()
                    val buf = outTensor.floatBuffer.duplicate()
                    buf.rewind()
                    val stepRow = rowBuf?.takeIf { it.size == vocabSize } ?: FloatArray(vocabSize).also { rowBuf = it }
                    val charIndices = IntArray(seqLen)
                    val probs = FloatArray(seqLen)

                    for (b in 0 until batchSize) {
                        for (t in 0 until seqLen) {
                            buf.get(stepRow, 0, vocabSize)
                            var maxProb = stepRow[0]
                            var maxIdx = 0
                            for (c in 1 until vocabSize) {
                                val p = stepRow[c]
                                if (p > maxProb) {
                                    maxProb = p
                                    maxIdx = c
                                }
                            }
                            charIndices[t] = maxIdx
                            probs[t] = maxProb
                        }
                        ordered[batchIdx[b]] = PpOcrV5Math.ctcDecode(charIndices, probs, dictionary)
                    }
                }
            } finally {
                tensor.close()
            }
            start = end
        }
        return ordered
    }

    private fun fillRecSample(
        crop: Bitmap,
        dst: FloatArray,
        baseOffset: Int,
        targetWidth: Int,
        channelStride: Int
    ) {
        val ratio = crop.width.toFloat() / crop.height.coerceAtLeast(1)
        val resizedW = ceil(REC_IMG_HEIGHT * ratio).toInt().coerceIn(1, targetWidth)
        val resized = if (crop.width == resizedW && crop.height == REC_IMG_HEIGHT) {
            crop
        } else {
            Bitmap.createScaledBitmap(crop, resizedW, REC_IMG_HEIGHT, true)
        }
        val pixels = IntArray(resizedW * REC_IMG_HEIGHT)
        try {
            resized.getPixels(pixels, 0, resizedW, 0, 0, resizedW, REC_IMG_HEIGHT)
        } finally {
            if (resized !== crop && !resized.isRecycled) resized.recycle()
        }

        val scale = 2.0f / 255.0f
        for (y in 0 until REC_IMG_HEIGHT) {
            val dstRow = baseOffset + y * targetWidth
            val srcRow = y * resizedW
            for (x in 0 until resizedW) {
                val px = pixels[srcRow + x]
                val idx = dstRow + x
                // BGR order, normalized to [-1, 1] via (v / 255 - 0.5) / 0.5 == v * (2 / 255) - 1
                dst[idx] = (px and 0xFF) * scale - 1.0f
                dst[idx + channelStride] = ((px ushr 8) and 0xFF) * scale - 1.0f
                dst[idx + 2 * channelStride] = ((px ushr 16) and 0xFF) * scale - 1.0f
            }
        }
    }

    override fun close() {
        runCatching { detSession.close() }
        runCatching { recSession.close() }
        runCatching { sessionOptions.close() }
    }

    companion object {
        private const val ASSET_DIR = "ppocrv5"
        private const val DET_ASSET = "det.onnx"
        private const val REC_ASSET = "rec.onnx"
        private const val DICT_ASSET = "ppocrv5_dict.txt"
        private const val READY_MARKER = ".ready_v5_1"

        private const val DET_MEAN_0 = 0.485f
        private const val DET_MEAN_1 = 0.456f
        private const val DET_MEAN_2 = 0.406f
        private const val DET_INV_STD_0 = 1.0f / 0.229f
        private const val DET_INV_STD_1 = 1.0f / 0.224f
        private const val DET_INV_STD_2 = 1.0f / 0.225f

        private const val REC_IMG_HEIGHT = 48
        private const val REC_BASE_WIDTH = 320
        private const val REC_MAX_WIDTH = 2048
        private const val REC_BATCH_SIZE = 4
        private const val MIN_RECOGNITION_SCORE = 0.45f

        private val LONE_BAR_NOISE = setOf("|", "｜", "丨", "│")
        private val extractLock = Any()

        private val REQUIRED_FILES = listOf(
            DET_ASSET to 4_748_769L,
            REC_ASSET to 16_517_247L,
            DICT_ASSET to 74_012L
        )

        private fun ensureBundledModels(context: Context): File = synchronized(extractLock) {
            val baseDir = File(context.noBackupFilesDir, ASSET_DIR)
            if (!baseDir.exists()) baseDir.mkdirs()
            val marker = File(baseDir, READY_MARKER)
            val allValid = marker.exists() && REQUIRED_FILES.all { (name, expectedSize) ->
                val f = File(baseDir, name)
                f.exists() && f.length() == expectedSize
            }
            if (allValid) return baseDir

            val assetManager = context.assets
            for ((name, expectedSize) in REQUIRED_FILES) {
                val target = File(baseDir, name)
                if (target.exists() && target.length() == expectedSize) continue
                val tmp = File(baseDir, "$name.tmp")
                assetManager.open("$ASSET_DIR/$name").use { input ->
                    FileOutputStream(tmp).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
            marker.writeText("ok")
            baseDir
        }

        private fun loadDictionary(dictFile: File): List<String> {
            val lines = dictFile.bufferedReader(Charsets.UTF_8).use { it.readLines() }
            val dict = ArrayList<String>(lines.size + 2)
            dict.add("blank")
            dict.addAll(lines)
            dict.add(" ")
            return dict
        }
    }
}
