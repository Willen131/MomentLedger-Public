package com.momentledger.app.quickentry

import kotlin.math.cos
import kotlin.math.sin

internal const val PET_BLINK_DURATION_MILLIS = 820L

internal fun petBlinkClosure(fraction: Float): Float = when {
    fraction <= 0f || fraction >= 1f -> 0f
    fraction < 0.26f -> ease(fraction / 0.26f)
    fraction <= 0.62f -> 1f
    else -> ease((1f - fraction) / 0.38f)
}

internal fun petBreathLift(elapsedMillis: Long): Float {
    val phase = elapsedMillis * (2.0 * Math.PI / 3_850.0)
    return (0.7 * (1.0 - cos(phase))).toFloat()
}

internal fun petHeadSway(elapsedMillis: Long): Float =
    (1.25 * sin(elapsedMillis * (2.0 * Math.PI / 4_600.0)) +
        0.35 * sin(elapsedMillis * (2.0 * Math.PI / 7_100.0))).toFloat()

internal fun petHairTipOffset(elapsedMillis: Long, lagMillis: Long): Float {
    val elapsed = (elapsedMillis - lagMillis).coerceAtLeast(0L)
    val broadSway = 3.8 * sin(elapsed * (2.0 * Math.PI / 3_550.0))
    val smallerSway = 1.2 * sin(elapsed * (2.0 * Math.PI / 5_200.0))
    return (broadSway + smallerSway).toFloat()
}

internal fun petHairInfluence(along: Float): Float =
    ease(((along - 0.12f) / 0.82f).coerceIn(0f, 1f))

internal fun petHairCoverage(along: Float): Float =
    ease(((along - 0.22f) / 0.36f).coerceIn(0f, 1f))

private fun ease(progress: Float): Float = progress * progress * (3f - 2f * progress)
