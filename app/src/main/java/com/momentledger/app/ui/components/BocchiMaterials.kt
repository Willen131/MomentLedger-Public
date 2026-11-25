package com.momentledger.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.momentledger.app.ui.theme.LocalAppleColors
import com.momentledger.app.ui.theme.LocalIsDarkTheme

@Composable
internal fun Modifier.bocchiPageBackground(stage: BocchiStage): Modifier {
    val colors = LocalAppleColors.current
    val dark = LocalIsDarkTheme.current
    val accent = stage.motif.accent(colors)
    return drawWithCache {
        val glow = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = if (dark) 0.17f else 0.105f), Color.Transparent),
            center = Offset(size.width * 0.81f, size.width * 0.29f),
            radius = (size.width * 1.04f).coerceAtLeast(1f)
        )
        onDrawBehind {
            drawRect(colors.bg)
            drawRect(glow)
        }
    }
}

/** A restrained film of colored light above a legible base surface. */
@Composable
internal fun Modifier.bocchiGlassFill(base: Color, accent: Color): Modifier {
    val dark = LocalIsDarkTheme.current
    val light = lerp(base, Color.White, if (dark) 0.085f else 0.60f).copy(alpha = base.alpha)
    val tint = lerp(base, accent, if (dark) 0.19f else 0.11f).copy(alpha = base.alpha)
    return background(
        Brush.linearGradient(0f to light, 0.48f to base, 1f to tint)
    )
}

@Composable
internal fun bocchiGlassRim(): Brush {
    val dark = LocalIsDarkTheme.current
    return Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) 0.27f else 0.96f),
        0.57f to Color.White.copy(alpha = if (dark) 0.06f else 0.28f),
        1f to LocalAppleColors.current.text.copy(alpha = if (dark) 0.13f else 0.12f)
    )
}

internal val BocchiTicketShape: Shape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    val corner = minOf(h * 0.16f, w * 0.09f)
    val notch = minOf(h * 0.088f, w * 0.026f)
    val edge = h * 0.64f

    moveTo(corner, 0f)
    lineTo(w - corner, 0f)
    quadraticTo(w, 0f, w, corner)
    lineTo(w, edge - notch)
    cubicTo(w - 1.32f * notch, edge - notch, w - 1.32f * notch, edge + notch, w, edge + notch)
    lineTo(w, h - corner)
    quadraticTo(w, h, w - corner, h)
    lineTo(corner, h)
    quadraticTo(0f, h, 0f, h - corner)
    lineTo(0f, edge + notch)
    cubicTo(1.32f * notch, edge + notch, 1.32f * notch, edge - notch, 0f, edge - notch)
    lineTo(0f, corner)
    quadraticTo(0f, 0f, corner, 0f)
    close()
}

@Composable
internal fun BocchiTicketPerforation(color: Color, modifier: Modifier) {
    Box(
        modifier.drawWithCache {
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
            val x = size.width * 0.82f
            val inset8 = 8.dp.toPx()
            val inset10 = 10.dp.toPx()
            val stroke1 = 1.dp.toPx()
            val stroke07 = 0.7.dp.toPx()
            val highlight = Color.White.copy(alpha = 0.17f)
            onDrawBehind {
                drawLine(
                    color,
                    start = Offset(x, inset8),
                    end = Offset(x, size.height - inset8),
                    strokeWidth = stroke1,
                    pathEffect = dashEffect
                )
                drawLine(
                    highlight,
                    start = Offset(x + stroke1, inset10),
                    end = Offset(x + stroke1, size.height - inset10),
                    strokeWidth = stroke07
                )
            }
        }
    )
}
