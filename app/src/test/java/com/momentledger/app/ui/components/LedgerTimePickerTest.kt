package com.momentledger.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class LedgerTimePickerTest {
    @Test
    fun acceptsEdgesAndRejectsOutOfRangeOrMalformedInputs() {
        assertEquals(LocalTime.MIDNIGHT, parseLedgerTime("0", "00"))
        assertEquals(LocalTime.of(23, 59), parseLedgerTime("23", "59"))
        for ((hour, minute) in listOf("24" to "00", "12" to "60", "-1" to "00", "123" to "04", "" to "0", "08" to "ab")) {
            assertNull(parseLedgerTime(hour, minute))
        }
    }
}
