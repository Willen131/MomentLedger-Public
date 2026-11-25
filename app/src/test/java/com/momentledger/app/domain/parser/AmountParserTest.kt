package com.momentledger.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountParserTest {

    @Test
    fun parseToCents_parsesStandardAndFormattedAmounts() {
        assertEquals(1690L, AmountParser.parseToCents("¥16.90"))
        assertEquals(123456L, AmountParser.parseToCents("￥1,234.56元"))
        assertEquals(4860L, AmountParser.parseToCents("-48.6"))
        assertEquals(9990L, AmountParser.parseToCents("RMB 99.9"))
        assertEquals(1234L, AmountParser.parseToCents(" +12.34 "))
        assertEquals(2000L, AmountParser.parseToCents("19.995"))
        assertEquals(5L, AmountParser.parseToCents("0.05"))
        assertEquals(100L, AmountParser.parseToCents("\uE605 1.00"))
        assertEquals(1250L, AmountParser.parseToCents("￥\u00A012.50\u200B"))
    }

    @Test
    fun parseToCents_returnsNullForZeroOrInvalidInputs() {
        assertNull(AmountParser.parseToCents("0.00"))
        assertNull(AmountParser.parseToCents("-0.00"))
        assertNull(AmountParser.parseToCents("0"))
        assertNull(AmountParser.parseToCents("invalid"))
        assertNull(AmountParser.parseToCents(""))
        assertNull(AmountParser.parseToCents("   "))
        assertNull(AmountParser.parseToCents("2026-11-19"))
    }

    @Test
    fun formatYuan_formatsCentsWithOptionalPrefix() {
        assertEquals("-¥16.90", AmountParser.formatYuan(1690L, "-"))
        assertEquals("¥16.90", AmountParser.formatYuan(1690L))
        assertEquals("+¥1234.56", AmountParser.formatYuan(123456L, "+"))
        assertEquals("¥0.05", AmountParser.formatYuan(5L))
        assertEquals("-¥16.90", AmountParser.formatYuan(-1690L))
        assertEquals("¥0.00", AmountParser.formatYuan(0L))
    }

    @Test
    fun formatRoundedYuan_roundsHalfUpToNearestYuan() {
        assertEquals("¥147", AmountParser.formatRoundedYuan(14650L))
        assertEquals("¥146", AmountParser.formatRoundedYuan(14649L))
        assertEquals("¥0", AmountParser.formatRoundedYuan(0L))
        assertEquals("-¥147", AmountParser.formatRoundedYuan(-14650L))
        assertEquals("¥0", AmountParser.formatRoundedYuan(-10L))
    }
}
