package com.momentledger.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentledger.app.domain.model.HourlyBucket
import com.momentledger.app.ui.theme.LocalAppleColors
import com.momentledger.app.ui.theme.TnumTextStyle
import kotlin.math.max

@Composable
fun HourlyBarChart(
    buckets: List<HourlyBucket>,
    selectedHour: Int?,
    onSelectHour: (Int?) -> Unit,
    height: Dp = 120.dp,
    allowDragScrub: Boolean = false,
    calendarStyle: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppleColors.current
    val normalizedBuckets = remember(buckets) {
        val byHour = buckets.associateBy { it.hour }
        (0..23).map { hour ->
            byHour[hour] ?: HourlyBucket(
                hour = hour,
                netAmountCents = 0L,
                displayAmountCents = 0L,
                transactionCount = 0,
                hasRefund = false,
                isPeak = false
            )
        }
    }
    val maxAmountCents = remember(normalizedBuckets) {
        normalizedBuckets.maxOfOrNull { it.displayAmountCents }?.coerceAtLeast(1L) ?: 1L
    }

    val currentSelectedHour by rememberUpdatedState(selectedHour)
    val currentOnSelectHour by rememberUpdatedState(onSelectHour)

    val a11yDescription = remember(selectedHour, normalizedBuckets) {
        if (selectedHour != null && selectedHour in 0..23) {
            val bucket = normalizedBuckets[selectedHour]
            "${"%02d".format(selectedHour)}时共${bucket.transactionCount}笔"
        } else {
            "24小时分布柱状图"
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11yDescription }
    ) {
        val interactionModifier = Modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(allowDragScrub) {
                val gapPx = 3.dp.toPx()
                val minBarPx = 1.dp.toPx()
                detectTapGestures { offset ->
                    val tappedHour = hourForX(offset.x, size.width.toFloat(), gapPx, minBarPx)
                    if ((calendarStyle || !allowDragScrub) && currentSelectedHour == tappedHour) {
                        currentOnSelectHour(null)
                    } else {
                        currentOnSelectHour(tappedHour)
                    }
                }
            }
            .then(
                if (allowDragScrub) {
                    Modifier.pointerInput(Unit) {
                        val gapPx = 3.dp.toPx()
                        val minBarPx = 1.dp.toPx()
                        var lastScrubHour = -1
                        detectHorizontalDragGestures(
                            onDragStart = { startOffset ->
                                val hour = hourForX(
                                    startOffset.x,
                                    size.width.toFloat(),
                                    gapPx,
                                    minBarPx
                                )
                                lastScrubHour = hour
                                currentOnSelectHour(hour)
                            },
                            onDragEnd = { lastScrubHour = -1 },
                            onDragCancel = { lastScrubHour = -1 },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                val hour = hourForX(
                                    change.position.x,
                                    size.width.toFloat(),
                                    gapPx,
                                    minBarPx
                                )
                                if (hour != lastScrubHour) {
                                    lastScrubHour = hour
                                    currentOnSelectHour(hour)
                                }
                            }
                        )
                    }
                } else {
                    Modifier
                }
            )

        val path = remember { Path() }

        Canvas(modifier = interactionModifier) {
            val layout = hourlyBarLayout(
                width = size.width,
                gapPx = 3.dp.toPx(),
                minBarPx = 1.dp.toPx()
            )

            val chartBottom = size.height
            val chartHeight = chartBottom
            val baselineHeight = 4.dp.toPx().coerceAtMost(chartHeight * 0.2f)
            val minActiveHeight = max(baselineHeight + 2.dp.toPx(), chartHeight * 0.10f)
                .coerceAtMost(chartHeight)
            val topCornerRadius = CornerRadius(
                3.dp.toPx().coerceAtMost(layout.barWidth / 2f)
            )

            normalizedBuckets.forEachIndexed { index, bucket ->
                val left = index * layout.slotWidth
                val right = (left + layout.barWidth).coerceAtMost(size.width)
                val ratio = bucket.displayAmountCents.toFloat() / maxAmountCents.toFloat()
                val barHeight = if (bucket.displayAmountCents > 0L) {
                    (ratio * chartHeight).coerceIn(minActiveHeight, chartHeight)
                } else {
                    baselineHeight
                }
                val top = chartBottom - barHeight

                val isSelected = selectedHour == bucket.hour
                val barColor = if (calendarStyle) {
                    when {
                        isSelected ->
                            if (bucket.displayAmountCents > 0L) {
                                colors.orange
                            } else {
                                colors.orange.copy(alpha = 0.45f)
                            }
                        bucket.displayAmountCents > 0L -> colors.blue
                        else -> colors.blue.copy(alpha = 0.16f)
                    }
                } else {
                    when {
                        selectedHour != null -> when {
                            isSelected && bucket.displayAmountCents > 0L -> colors.orange
                            isSelected -> colors.orange.copy(alpha = 0.45f)
                            bucket.displayAmountCents > 0L -> colors.blue.copy(
                                alpha = if (allowDragScrub) 0.72f else 0.35f
                            )
                            else -> colors.barTrack
                        }
                        bucket.isPeak -> colors.orange
                        bucket.displayAmountCents > 0L -> colors.blue
                        else -> colors.barTrack
                    }
                }

                path.reset()
                path.addRoundRect(
                    RoundRect(
                        left = left,
                        top = top,
                        right = right,
                        bottom = chartBottom,
                        topLeftCornerRadius = topCornerRadius,
                        topRightCornerRadius = topCornerRadius,
                        bottomRightCornerRadius = CornerRadius.Zero,
                        bottomLeftCornerRadius = CornerRadius.Zero
                    )
                )
                drawPath(path = path, color = barColor)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("00时", "06时", "12时", "18时", "23时").forEach { tick ->
                Text(
                    text = tick,
                    style = TnumTextStyle(
                        fontSize = 10.sp,
                        color = colors.subText
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

private data class HourlyBarLayout(
    val barWidth: Float,
    val slotWidth: Float
)

private fun hourlyBarLayout(width: Float, gapPx: Float, minBarPx: Float): HourlyBarLayout {
    val slotCount = 24
    if (width <= 0f) return HourlyBarLayout(barWidth = 0f, slotWidth = 0f)

    // Shrink the gaps first so bars keep a visible minimum width on very narrow screens.
    val maxGapTotal = gapPx.coerceAtLeast(0f) * (slotCount - 1)
    val gap = if (width <= slotCount * minBarPx + maxGapTotal) {
        ((width - slotCount * minBarPx) / (slotCount - 1)).coerceAtLeast(0f)
    } else {
        gapPx.coerceAtLeast(0f)
    }
    val barWidth = (width - gap * (slotCount - 1)) / slotCount
    return HourlyBarLayout(barWidth = barWidth, slotWidth = barWidth + gap)
}

/**
 * Maps a horizontal touch offset to an hour using the same slot geometry as drawing:
 * each slot is barWidth + gap wide, and the slot boundary is centered in the gap.
 */
private fun hourForX(x: Float, width: Float, gapPx: Float, minBarPx: Float): Int {
    if (width <= 0f) return 0
    val layout = hourlyBarLayout(width, gapPx, minBarPx)
    if (layout.slotWidth <= 0f) return 0
    val gap = layout.slotWidth - layout.barWidth
    val clamped = x.coerceIn(0f, width)
    val index = ((clamped + gap / 2f) / layout.slotWidth).toInt()
    return index.coerceIn(0, 23)
}
