package com.momentledger.app.pdf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PdfStatementExtractorHelperTest {

    @Test
    fun `hasPdfMagicHeader detects standard and BOM-prefixed PDF headers`() {
        assertTrue(PdfStatementExtractor.hasPdfMagicHeader("%PDF-1.5\n...".toByteArray()))
        assertTrue(
            PdfStatementExtractor.hasPdfMagicHeader(
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "%PDF-1.4".toByteArray()
            )
        )
        assertFalse(PdfStatementExtractor.hasPdfMagicHeader("PK\u0003\u0004xlsx".toByteArray()))
        assertFalse(PdfStatementExtractor.hasPdfMagicHeader("%PDF".toByteArray()))
    }

    @Test
    fun `isDiagonalTextMatrix preserves axis-aligned table text and filters diagonal watermarks`() {
        // 0 deg upright and italic (shearY == 0 even when font size is 12pt)
        assertFalse(PdfBoxPageExtractor.isDiagonalTextMatrix(scaleX = 12f, shearY = 0f))
        // 180 deg inverted axis-aligned
        assertFalse(PdfBoxPageExtractor.isDiagonalTextMatrix(scaleX = -10f, shearY = 0f))
        // 90 deg / 270 deg rotated landscape page table text
        assertFalse(PdfBoxPageExtractor.isDiagonalTextMatrix(scaleX = 0f, shearY = 10f))
        assertFalse(PdfBoxPageExtractor.isDiagonalTextMatrix(scaleX = 0f, shearY = -10f))

        // 30 deg, 45 deg, -45 deg diagonal bank watermarks
        val deg30 = Math.toRadians(30.0)
        val deg45 = Math.toRadians(45.0)
        assertTrue(
            PdfBoxPageExtractor.isDiagonalTextMatrix(
                scaleX = (24.0 * cos(deg30)).toFloat(),
                shearY = (24.0 * sin(deg30)).toFloat()
            )
        )
        assertTrue(
            PdfBoxPageExtractor.isDiagonalTextMatrix(
                scaleX = (36.0 * cos(deg45)).toFloat(),
                shearY = (36.0 * sin(deg45)).toFloat()
            )
        )
        assertTrue(
            PdfBoxPageExtractor.isDiagonalTextMatrix(
                scaleX = (36.0 * cos(deg45)).toFloat(),
                shearY = (-36.0 * sin(deg45)).toFloat()
            )
        )
    }
}
