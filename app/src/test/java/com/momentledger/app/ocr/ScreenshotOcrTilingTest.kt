package com.momentledger.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotOcrTilingTest {

    @Test
    fun computeSampleSizeForWidth_preservesFullResolutionForScrolling1080x12000Screenshot() {
        assertEquals(1, ScreenshotTilingPlanner.computeSampleSizeForWidth(1080, 1440))
        assertEquals(1, ScreenshotTilingPlanner.computeSampleSizeForWidth(1440, 1440))
    }

    @Test
    fun computeSampleSizeForWidth_downsamplesUltraWideImagesByPowerOfTwo() {
        assertEquals(2, ScreenshotTilingPlanner.computeSampleSizeForWidth(3000, 1440))
        assertEquals(4, ScreenshotTilingPlanner.computeSampleSizeForWidth(6000, 1440))
    }

    @Test
    fun planVerticalStrips_singleStripForStandardScreenHeight() {
        assertEquals(
            listOf(0 until 2400),
            ScreenshotTilingPlanner.planVerticalStrips(
                height = 2400,
                stripHeight = 2000,
                overlap = 180,
                singlePassThreshold = 2600
            )
        )
    }

    @Test
    fun planVerticalStrips_tilesTallScrollingScreenshotWithOverlapAndFullCoverage() {
        val strips = ScreenshotTilingPlanner.planVerticalStrips(
            height = 5000,
            stripHeight = 2000,
            overlap = 180,
            singlePassThreshold = 2600
        )

        assertTrue(
            "Expected at least 3 strips for a 5000px tall screenshot, got ${strips.size}",
            strips.size >= 3
        )
        assertEquals("First strip must start at the top of the image", 0, strips.first().first)
        assertEquals("Last strip must reach the final pixel", 4999, strips.last().last)

        strips.forEach { strip ->
            val stripHeightPx = strip.last - strip.first + 1
            assertTrue(
                "Each strip must fit within stripHeight=2000, was $stripHeightPx",
                stripHeightPx <= 2000
            )
        }

        for (i in 0 until strips.size - 1) {
            val adjacentOverlap = strips[i].last - strips[i + 1].first + 1
            assertTrue(
                "Adjacent strips must overlap by at least 180px, was $adjacentOverlap",
                adjacentOverlap >= 180
            )
        }
    }

    @Test
    fun mapOrientedStripToRawRect_handles0_90_180_270RotationsAccurately() {
        assertEquals(
            ScreenshotTilingPlanner.RawRect(0, 0, 1080, 2000),
            ScreenshotTilingPlanner.mapOrientedStripToRawRect(
                rawWidth = 1080,
                rawHeight = 5000,
                rotationDegrees = 0,
                stripStart = 0,
                stripEndExclusive = 2000
            )
        )
        assertEquals(
            ScreenshotTilingPlanner.RawRect(0, 3000, 1080, 5000),
            ScreenshotTilingPlanner.mapOrientedStripToRawRect(
                rawWidth = 1080,
                rawHeight = 5000,
                rotationDegrees = 180,
                stripStart = 0,
                stripEndExclusive = 2000
            )
        )
        assertEquals(
            ScreenshotTilingPlanner.RawRect(0, 0, 2000, 1080),
            ScreenshotTilingPlanner.mapOrientedStripToRawRect(
                rawWidth = 5000,
                rawHeight = 1080,
                rotationDegrees = 90,
                stripStart = 0,
                stripEndExclusive = 2000
            )
        )
        assertEquals(
            ScreenshotTilingPlanner.RawRect(1820, 0, 3820, 1080),
            ScreenshotTilingPlanner.mapOrientedStripToRawRect(
                rawWidth = 5000,
                rawHeight = 1080,
                rotationDegrees = 90,
                stripStart = 1820,
                stripEndExclusive = 3820
            )
        )
        assertEquals(
            ScreenshotTilingPlanner.RawRect(3000, 0, 5000, 1080),
            ScreenshotTilingPlanner.mapOrientedStripToRawRect(
                rawWidth = 5000,
                rawHeight = 1080,
                rotationDegrees = 270,
                stripStart = 0,
                stripEndExclusive = 2000
            )
        )
        assertEquals(
            ScreenshotTilingPlanner.RawRect(1180, 0, 3180, 1080),
            ScreenshotTilingPlanner.mapOrientedStripToRawRect(
                rawWidth = 5000,
                rawHeight = 1080,
                rotationDegrees = 270,
                stripStart = 1820,
                stripEndExclusive = 3820
            )
        )
    }

    @Test
    fun ocrImagePreprocessorDetectsDarkAndLightBackgroundsAndEnhancesContrast() {
        val darkSamples = FloatArray(100) { if (it < 85) 18f else 210f }
        val darkProfile = OcrImagePreprocessor.analyzeLumaSamples(darkSamples)
        assertTrue(darkProfile.isDarkBackground)

        val darkMatrix = OcrImagePreprocessor.buildColorMatrixValues(darkProfile)
        fun applyLuma(matrix: FloatArray, luma: Float): Float =
            (matrix[0] * luma + matrix[1] * luma + matrix[2] * luma + matrix[4]).coerceIn(0f, 255f)
        // Dark background (#121212) maps to clean white; dark-gray labels (#707070) stay crisp dark gray; white text maps to black.
        assertEquals(255f, applyLuma(darkMatrix, 18f), 1f)
        assertTrue(applyLuma(darkMatrix, 112f) in 80f..150f)
        assertTrue(applyLuma(darkMatrix, 210f) <= 20f)

        val lightSamples = FloatArray(100) { if (it < 85) 248f else 40f }
        val lightProfile = OcrImagePreprocessor.analyzeLumaSamples(lightSamples)
        assertTrue(!lightProfile.isDarkBackground)

        val lightMatrix = OcrImagePreprocessor.buildColorMatrixValues(lightProfile)
        assertEquals(255f, applyLuma(lightMatrix, 248f), 1f)
        assertTrue(applyLuma(lightMatrix, 160f) < 160f)
        assertTrue(applyLuma(lightMatrix, 40f) <= 15f)
    }
}
