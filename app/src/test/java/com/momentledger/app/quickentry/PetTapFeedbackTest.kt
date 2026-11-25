package com.momentledger.app.quickentry

import com.momentledger.app.data.prefs.OverlayAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PetTapFeedbackTest {

    @Test
    fun zeroStrengthProducesNoVibrationAndLowPercentageIsGenuinelyGentle() {
        assertEquals(0.0f, OverlayAppearance.MIN_TAP_HAPTIC_STRENGTH, 0.0001f)
        assertEquals(1.0f, OverlayAppearance.MAX_TAP_HAPTIC_STRENGTH, 0.0001f)

        OverlayPet.entries.forEach { pet ->
            assertNull("${pet.id} at 0% should produce no vibration", PetTapFeedback.patternFor(pet, 0f))

            val p10 = requireNotNull(PetTapFeedback.patternFor(pet, 0.10f))
            val p50 = requireNotNull(PetTapFeedback.patternFor(pet, 0.50f))
            val p100 = requireNotNull(PetTapFeedback.patternFor(pet, 1.00f))

            listOf(p10, p50, p100).forEach { pattern ->
                assertEquals(pattern.timingsMs.size, pattern.amplitudes.size)
                assertTrue(pattern.timingsMs.size >= 2)
                assertEquals(0L, pattern.timingsMs[0])
                pattern.timingsMs.indices
                    .filter { it % 2 == 1 }
                    .forEach { idx ->
                        assertTrue(pattern.timingsMs[idx] >= 6L)
                        assertTrue(pattern.amplitudes[idx] in 6..255)
                    }
            }

            // 10% must feel genuinely whisper-light (amplitude <= 20, not 70+)
            val amp10 = p10.amplitudes.indices.filter { it % 2 == 1 }.maxOf { p10.amplitudes[it] }
            val amp50 = p50.amplitudes.indices.filter { it % 2 == 1 }.maxOf { p50.amplitudes[it] }
            val amp100 = p100.amplitudes.indices.filter { it % 2 == 1 }.maxOf { p100.amplitudes[it] }
            assertTrue("${pet.id} 10% amplitude ($amp10) must be genuinely gentle (<= 20)", amp10 <= 20)
            assertTrue("${pet.id} amplitude should grow monotonically: $amp10 < $amp50 < $amp100",
                amp10 < amp50 && amp50 < amp100)

            val dur10 = p10.timingsMs.indices.filter { it % 2 == 1 }.sumOf { p10.timingsMs[it] }
            val dur50 = p50.timingsMs.indices.filter { it % 2 == 1 }.sumOf { p50.timingsMs[it] }
            val dur100 = p100.timingsMs.indices.filter { it % 2 == 1 }.sumOf { p100.timingsMs[it] }
            assertTrue("${pet.id} duration should grow monotonically: $dur10 < $dur50 < $dur100",
                dur10 < dur50 && dur50 < dur100)
        }
    }

    @Test
    fun rawTapSoundAssetsAreRemoved() {
        val rawDir = File("src/main/res/raw")
        val remainingSfx = rawDir.listFiles { f -> f.isFile && f.name.startsWith("sfx_") }?.toList().orEmpty()
        assertFalse("No sfx_* raw audio files should remain", remainingSfx.isNotEmpty())
    }
}
