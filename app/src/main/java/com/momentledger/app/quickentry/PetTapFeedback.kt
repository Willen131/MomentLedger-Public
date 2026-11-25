package com.momentledger.app.quickentry

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.CombinedVibration
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import com.momentledger.app.data.prefs.OverlayAppearance
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class PetHapticPattern(
    val timingsMs: LongArray,
    val amplitudes: IntArray
)

/**
 * Tactile vibration controller for desktop pet taps with stepless strength scaling.
 *
 * Uses explicit background-exempt attributes (`VibrationAttributes.USAGE_ALARM` on API 31+
 * and `AudioAttributes.USAGE_ALARM` on API 26-30) so Android 12-15 `VibratorManagerService`
 * never silently drops taps coming from a `TYPE_APPLICATION_OVERLAY` foreground service
 * when the host app is not the top Activity.
 */
internal class PetTapFeedback(context: Context) {
    private val appContext = context.applicationContext
    private var lastTapElapsedMs = 0L

    fun onPetTapped(
        anchorView: View,
        pet: OverlayPet,
        hapticEnabled: Boolean,
        strength: Float
    ) {
        if (!hapticEnabled || strength <= ZERO_STRENGTH_EPSILON) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTapElapsedMs < MIN_TAP_INTERVAL_MS) return
        lastTapElapsedMs = now
        trigger(anchorView, pet, strength)
    }

    fun preview(anchorView: View, pet: OverlayPet, strength: Float) {
        if (strength <= ZERO_STRENGTH_EPSILON) return
        trigger(anchorView, pet, strength)
    }

    private fun trigger(anchorView: View, pet: OverlayPet, strength: Float) {
        if (strength <= ZERO_STRENGTH_EPSILON) return
        if (!vibrateHardware(pet, strength)) {
            runCatching {
                anchorView.performHapticFeedback(
                    HapticFeedbackConstants.LONG_PRESS,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrateHardware(pet: OverlayPet, strength: Float): Boolean = runCatching {
        val pattern = patternFor(pet, strength) ?: return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appContext.getSystemService(VibratorManager::class.java) ?: return false
            val vibrator = manager.defaultVibrator
            if (!vibrator.hasVibrator()) return false
            val effect = buildEffect(pattern, vibrator.hasAmplitudeControl())
            val attrs = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_ALARM)
                .build()
            manager.vibrate(CombinedVibration.createParallel(effect), attrs)
            true
        } else {
            val vibrator = appContext.getSystemService(Vibrator::class.java) ?: return false
            if (!vibrator.hasVibrator()) return false
            val effect = buildEffect(pattern, vibrator.hasAmplitudeControl())
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            vibrator.vibrate(effect, audioAttrs)
            true
        }
    }.getOrDefault(false)

    private fun buildEffect(pattern: PetHapticPattern, hasAmplitudeControl: Boolean): VibrationEffect {
        return if (hasAmplitudeControl) {
            if (pattern.timingsMs.size == 2 && pattern.timingsMs[0] == 0L) {
                VibrationEffect.createOneShot(pattern.timingsMs[1], pattern.amplitudes[1])
            } else {
                VibrationEffect.createWaveform(pattern.timingsMs, pattern.amplitudes, -1)
            }
        } else {
            if (pattern.timingsMs.size == 2 && pattern.timingsMs[0] == 0L) {
                VibrationEffect.createOneShot(pattern.timingsMs[1], VibrationEffect.DEFAULT_AMPLITUDE)
            } else {
                VibrationEffect.createWaveform(pattern.timingsMs, -1)
            }
        }
    }

    companion object {
        private const val MIN_TAP_INTERVAL_MS = 65L
        private const val ZERO_STRENGTH_EPSILON = 0.005f
        private const val MIN_PULSE_MS = 6L
        private const val MIN_AMPLITUDE = 6
        private const val MAX_AMPLITUDE = 255

        internal fun patternFor(pet: OverlayPet, strength: Float = 1f): PetHapticPattern? {
            val s = strength.coerceIn(
                OverlayAppearance.MIN_TAP_HAPTIC_STRENGTH,
                OverlayAppearance.MAX_TAP_HAPTIC_STRENGTH
            )
            if (s <= ZERO_STRENGTH_EPSILON) return null
            val base = basePatternFor(pet)
            // Perceptual gamma curve so 10% feels genuinely whisper-light, 50% feels medium, 100% is full.
            val durationScale = 0.22f + 0.78f * s
            val amplitudeScale = s.toDouble().pow(1.65).toFloat()
            val scaledTimings = LongArray(base.timingsMs.size) { index ->
                val raw = base.timingsMs[index]
                if (index % 2 == 1) {
                    (raw * durationScale).roundToLong().coerceAtLeast(MIN_PULSE_MS)
                } else {
                    raw
                }
            }
            val scaledAmplitudes = IntArray(base.amplitudes.size) { index ->
                val raw = base.amplitudes[index]
                if (index % 2 == 1 && raw > 0) {
                    (raw * amplitudeScale).roundToInt().coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE)
                } else {
                    0
                }
            }
            return PetHapticPattern(scaledTimings, scaledAmplitudes)
        }

        private fun basePatternFor(pet: OverlayPet): PetHapticPattern = when (pet) {
            // Hitori: crisp guitar-pick double-tick
            OverlayPet.HITORI -> PetHapticPattern(
                timingsMs = longArrayOf(0L, 28L, 36L, 36L),
                amplitudes = intArrayOf(0, 210, 0, 255)
            )
            // Nijika: snappy drumstick count-in double-tap
            OverlayPet.NIJIKA -> PetHapticPattern(
                timingsMs = longArrayOf(0L, 32L, 42L, 40L),
                amplitudes = intArrayOf(0, 225, 0, 255)
            )
            // Ryo: solid single bass thump
            OverlayPet.RYO -> PetHapticPattern(
                timingsMs = longArrayOf(0L, 46L),
                amplitudes = intArrayOf(0, 255)
            )
            // Kita: bright bouncy sparkle double-pulse
            OverlayPet.KITA -> PetHapticPattern(
                timingsMs = longArrayOf(0L, 26L, 30L, 34L),
                amplitudes = intArrayOf(0, 215, 0, 245)
            )
        }
    }
}
