package com.momentledger.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

internal fun DrawScope.drawStaffLines(color: Color) {
    val ink = color.copy(alpha = color.alpha * 0.36f)
    for (step in 1..5) {
        val y = size.height * step / 6f
        drawLine(ink, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
    }
}

internal fun DrawScope.drawDrumBeat(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    color: Color
) {
    if (width < 5.dp.toPx() || height < 10.dp.toPx()) return
    val rim = top + 4.dp.toPx()
    drawLine(
        color = color,
        start = Offset(left + width * 0.27f, rim),
        end = Offset(left + width * 0.73f, rim),
        strokeWidth = 1.dp.toPx(),
        cap = StrokeCap.Round
    )
}

private val reusablePickPath = ThreadLocal.withInitial { Path() }

private fun DrawScope.drawGuitarPick(center: Offset, radius: Float, color: Color) {
    val (x, y) = center
    val pick = reusablePickPath.get()!!.apply {
        reset()
        moveTo(x, y - radius)
        cubicTo(x + radius, y - radius, x + radius, y - radius * 0.35f, x + radius * 0.65f, y + radius * 0.16f)
        cubicTo(x + radius * 0.16f, y + radius, x - radius * 0.16f, y + radius, x - radius * 0.65f, y + radius * 0.16f)
        cubicTo(x - radius, y - radius * 0.35f, x - radius, y - radius, x, y - radius)
        close()
    }
    drawPath(pick, color)
}

internal fun DrawScope.drawPickMarker(position: Offset, surface: Color, accent: Color) {
    drawCircle(surface, 6.dp.toPx(), position)
    drawGuitarPick(position + Offset(0f, 0.7.dp.toPx()), 5.2.dp.toPx(), accent)
}
