package com.momentledger.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PpOcrV5MathTest {

    @Test
    fun detResizeCapsMaxSideAndAlignsToMultipleOf32() {
        val (w1, h1) = PpOcrV5Math.calculateDetResizeDimensions(1080, 2400)
        assertEquals(0, w1 % 32)
        assertEquals(0, h1 % 32)
        assertEquals(1280, h1)
        assertEquals(576, w1)

        val (w2, h2) = PpOcrV5Math.calculateDetResizeDimensions(465, 1024)
        assertEquals(0, w2 % 32)
        assertEquals(0, h2 % 32)
        assertEquals(480, w2)
        assertEquals(1024, h2)
    }

    @Test
    fun postprocessDetectionFindsUnclipsAndSortsHighConfidenceBoxes() {
        val rw = 64
        val rh = 64
        val prob = FloatArray(rw * rh)
        // High-confidence lower box (y: 40..48, x: 10..40)
        for (y in 40..48) {
            for (x in 10..40) {
                prob[y * rw + x] = 0.92f
            }
        }
        // High-confidence upper box (y: 10..18, x: 12..36)
        for (y in 10..18) {
            for (x in 12..36) {
                prob[y * rw + x] = 0.88f
            }
        }
        // Low-confidence noise blob (below boxThresh 0.55)
        for (y in 26..31) {
            for (x in 10..20) {
                prob[y * rw + x] = 0.35f
            }
        }

        val boxes = PpOcrV5Math.postprocessDetection(
            probFlat = prob,
            resizedWidth = rw,
            resizedHeight = rh,
            originalWidth = 128,
            originalHeight = 128
        )

        assertEquals(2, boxes.size)
        // Sorted top-to-bottom: upper box first, lower box second
        assertTrue(boxes[0].minY() < boxes[1].minY())
        // Scaled to 128x128 and unclipped wider than original tight core (24..72 -> < 24 and > 72)
        assertTrue(boxes[0].minX() < 24f)
        assertTrue(boxes[0].maxX() > 72f)
    }

    @Test
    fun ctcDecodeCollapsesRepeatsAndSeparatesViaBlank() {
        val dict = listOf("blank", "杭", "州", "深", "度", "求", "索", "0", "1", ".")
        // Encodes: "杭州深度求索10.00" with blanks (0) and repeated timesteps
        val indices = intArrayOf(0, 1, 1, 2, 0, 3, 4, 4, 5, 0, 6, 8, 7, 9, 7, 0, 7, 7)
        val probs = FloatArray(indices.size) { 0.95f }

        val decoded = PpOcrV5Math.ctcDecode(indices, probs, dict)
        assertEquals("杭州深度求索10.00", decoded.text)
        assertEquals(0.95f, decoded.confidence, 1e-4f)
    }
}
