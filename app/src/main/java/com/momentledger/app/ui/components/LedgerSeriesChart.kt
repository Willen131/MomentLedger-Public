package com.momentledger.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentledger.app.domain.model.HourlyBucket
import com.momentledger.app.domain.parser.AmountParser
import com.momentledger.app.ui.theme.LocalAppleColors
import com.momentledger.app.ui.theme.TnumTextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class ChartType(val label: String) {
    BAR("条状图"), PIE("扇形图"), LINE("折线图");

    fun next(): ChartType = entries[(ordinal + 1) % entries.size]
}

internal data class ChartPoint(val label: String, val amountCents: Long, val axisLabel: String = label)

private val pieColors = listOf(
    Color(0xFFBF4E81), Color(0xFFD69D33), Color(0xFF4E66AF), Color(0xFFDA634B),
    Color(0xFF278872), Color(0xFF8860B8), Color(0xFFAC6C40), Color(0xFF408CA8),
    Color(0xFFAE728E), Color(0xFF708430), Color(0xFF775861), Color(0xFF98656C)
)

private fun pieColor(index: Int): Color = pieColors.getOrNull(index) ?: Color.hsl(
    hue = (index * 137.508f) % 360f,
    saturation = 0.60f,
    lightness = if (index % 2 == 0) 0.35f else 0.58f
)

@Composable
internal fun ChartTypeButton(type: ChartType, includeLine: Boolean = true, onChange: (ChartType) -> Unit) {
    val colors = LocalAppleColors.current
    val next = if (!includeLine && type == ChartType.PIE) ChartType.BAR else type.next()
    val glyph = when (type) {
        ChartType.BAR -> Icons.Rounded.BarChart
        ChartType.PIE -> Icons.Rounded.PieChart
        ChartType.LINE -> Icons.AutoMirrored.Rounded.ShowChart
    }
    IconButton(onClick = { onChange(next) }) {
        Icon(
            imageVector = glyph,
            contentDescription = "当前${type.label}，切换到${next.label}",
            tint = colors.blue,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
internal fun SwitchableHourlyChart(
    buckets: List<HourlyBucket>,
    type: ChartType,
    selectedHour: Int?,
    onSelectHour: (Int?) -> Unit,
    height: Dp,
    calendarStyle: Boolean = false
) {
    if (type == ChartType.BAR) {
        HourlyBarChart(buckets, selectedHour, onSelectHour, height, allowDragScrub = true, calendarStyle = calendarStyle)
    } else {
        val points = remember(buckets) {
            val byHour = buckets.associateBy { it.hour }
            (0..23).map { hour ->
                val label = "%02d".format(Locale.ROOT, hour)
                ChartPoint("$label:00–$label:59", byHour[hour]?.displayAmountCents ?: 0L, "${label}时")
            }
        }
        val signedAmounts = remember(buckets) { buckets.any { it.netAmountCents < 0L } }
        LedgerSeriesChart(
            points, type, height, selectedHour, onSelectHour, allowDrag = true,
            pieNote = when {
                calendarStyle && signedAmounts -> "按各小时金额的绝对值计算占比。"
                signedAmounts -> "按正值小时金额计算占比，负值小时不参与。"
                else -> "每个扇区是该小时金额占图中总额的比例。"
            },
            tickIndices = listOf(0, 6, 12, 18, 23)
        )
    }
}

@Composable
internal fun LedgerSeriesChart(
    points: List<ChartPoint>,
    type: ChartType,
    height: Dp = 108.dp,
    selectedIndex: Int? = null,
    onSelect: ((Int) -> Unit)? = null,
    allowDrag: Boolean = false,
    pieNote: String = "每个扇区的百分比按图中正值金额合计计算。",
    tickIndices: List<Int> = chartTickIndices(points.size),
    onSelectLegend: ((Int) -> Unit)? = onSelect
) {
    if (points.isEmpty()) return
    val colors = LocalAppleColors.current
    if (type == ChartType.PIE) {
        SeriesPie(points, height, selectedIndex, onSelect, onSelectLegend, pieNote)
        return
    }

    val low = minOf(0L, points.minOf { it.amountCents }).toDouble()
    val high = maxOf(0L, points.maxOf { it.amountCents }).toDouble()
    val range = (high - low).coerceAtLeast(1.0)
    val select by rememberUpdatedState(onSelect)
    val selected = points.getOrNull(selectedIndex ?: -1)
    val chartDescription = selected?.let { "${type.label}，${it.label} ${AmountParser.formatYuan(it.amountCents)}" }
        ?: type.label
    val interaction = if (onSelect == null) Modifier else {
        Modifier.pointerInput(points.size) {
            detectTapGestures { tap ->
                select?.invoke(pointForX(tap.x, size.width, points.size))
            }
        }.then(
            if (!allowDrag) Modifier else Modifier.pointerInput(points.size) {
                var lastIndex = -1
                detectHorizontalDragGestures(
                    onDragStart = { start ->
                        lastIndex = pointForX(start.x, size.width, points.size)
                        select?.invoke(lastIndex)
                    }
                ) { change, _ ->
                    change.consume()
                    val index = pointForX(change.position.x, size.width, points.size)
                    if (index != lastIndex) {
                        lastIndex = index
                        select?.invoke(index)
                    }
                }
            }
        )
    }

    Canvas(
        Modifier.fillMaxWidth().height(height)
            .semantics { contentDescription = chartDescription }
            .then(interaction)
    ) {
        val slot = size.width / points.size
        val padding = 6.dp.toPx()
        val plotHeight = (size.height - padding * 2).coerceAtLeast(0f)
        fun y(value: Long): Float = padding + ((high - value.toDouble()) / range * plotHeight).toFloat()
        val zero = y(0)
        drawLine(colors.barTrack, Offset(0f, zero), Offset(size.width, zero), strokeWidth = 1.dp.toPx())
        val barWidth = min(slot * 0.67f, 28.dp.toPx())
        var previous: Offset? = null
        points.forEachIndexed { index, point ->
            val position = Offset((index + 0.5f) * slot, y(point.amountCents))
            val color = when {
                selectedIndex == index -> colors.orange
                point.amountCents < 0L -> colors.red
                else -> colors.blue
            }
            if (type == ChartType.LINE) {
                previous?.let {
                    drawLine(colors.blue, it, position, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
                previous = position
            } else {
                val left = position.x - barWidth / 2
                val top = min(zero, position.y)
                val barHeight = abs(position.y - zero).coerceAtLeast(2.dp.toPx())
                drawRoundRect(
                    color = if (point.amountCents == 0L && index != selectedIndex) colors.barTrack else color,
                    topLeft = Offset(left, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }
        }
        if (type == ChartType.LINE) {
            points.forEachIndexed { index, point ->
                val position = Offset((index + 0.5f) * slot, y(point.amountCents))
                val color = if (point.amountCents < 0L) colors.red else colors.blue
                drawCircle(color, 2.dp.toPx(), position)
            }
            if (selectedIndex != null && selected != null) {
                val markerCenter = Offset((selectedIndex + 0.5f) * slot, y(selected.amountCents))
                drawCircle(colors.orange, 5.5.dp.toPx(), markerCenter)
                drawCircle(colors.card, 2.8.dp.toPx(), markerCenter)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(18.dp)) {
        val labelWidth = minOf(46.dp, maxWidth / tickIndices.size.coerceAtLeast(1))
        tickIndices.forEach { index ->
            val center = maxWidth * ((index + 0.5f) / points.size)
            Text(
                points[index].axisLabel,
                color = colors.subText,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(x = (center - labelWidth / 2).coerceIn(0.dp, maxWidth - labelWidth))
                    .width(labelWidth)
            )
        }
    }
}

private fun chartTickIndices(count: Int): List<Int> =
    if (count <= 7) (0 until count).toList()
    else (0..4).map { (it * (count - 1) / 4f).roundToInt() }

private fun pointForX(x: Float, width: Int, count: Int): Int =
    if (width <= 0) 0 else (x / width * count).toInt().coerceIn(0, count - 1)

private data class PieSection(val index: Int, val point: ChartPoint, val color: Color, val fraction: Double)

@Composable
private fun SeriesPie(
    points: List<ChartPoint>,
    height: Dp,
    selectedIndex: Int?,
    onSelect: ((Int) -> Unit)?,
    onSelectLegend: ((Int) -> Unit)?,
    note: String
) {
    val colors = LocalAppleColors.current
    val sections = remember(points) {
        val positive = points.withIndex().filter { it.value.amountCents > 0L }
        val total = positive.sumOf { it.value.amountCents.toDouble() }
        positive.mapIndexed { position, (index, point) ->
            PieSection(index, point, pieColor(position), point.amountCents / total)
        }
    }
    val select by rememberUpdatedState(onSelect)
    val interaction = if (onSelect == null || sections.isEmpty()) Modifier else Modifier.pointerInput(sections) {
        detectTapGestures { tap ->
            val x = tap.x - size.width / 2f
            val y = tap.y - size.height / 2f
            val radius = min(size.width, size.height) / 2f
            if (x * x + y * y <= radius * radius) {
                val fraction = ((Math.toDegrees(atan2(y.toDouble(), x.toDouble())) + 450.0) % 360.0) / 360.0
                var end = 0.0
                val section = sections.firstOrNull {
                    end += it.fraction
                    fraction < end
                } ?: sections.last()
                select?.invoke(section.index)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (sections.isEmpty()) {
            Text("暂无可计算占比的金额", color = colors.subText, fontSize = 12.sp)
            Text(note, color = colors.subText, fontSize = 11.sp)
            return@Column
        }
        Canvas(
            Modifier.align(Alignment.CenterHorizontally).size(height)
                .semantics { contentDescription = "金额占比扇形图" }
                .then(interaction)
        ) {
            var start = -90f
            val hasSelection = sections.any { it.index == selectedIndex }
            for (section in sections) {
                val sweep = (section.fraction * 360.0).toFloat()
                val opacity = if (!hasSelection || selectedIndex == section.index) 1f else 0.55f
                drawArc(section.color.copy(alpha = opacity), start, sweep, useCenter = true)
                start += sweep
            }
        }
        Text(note, color = colors.subText, fontSize = 11.sp)
        sections.forEach { section ->
            val isSelected = section.index == selectedIndex
            val selection = if (onSelectLegend != null) Modifier.clickable { onSelectLegend(section.index) } else Modifier
            Row(
                Modifier.fillMaxWidth().then(selection).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(9.dp).background(section.color, CircleShape))
                Text(
                    section.point.label,
                    color = colors.text,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    AmountParser.formatYuan(section.point.amountCents),
                    style = TnumTextStyle(fontSize = 12.sp, color = colors.text)
                )
                Text(
                    piePercent(section.fraction),
                    style = TnumTextStyle(fontSize = 12.sp, color = colors.subText),
                    modifier = Modifier.width(48.dp),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

private fun piePercent(fraction: Double): String =
    if (fraction < 0.001) "<0.1%" else String.format(Locale.ROOT, "%.1f%%", fraction * 100)
