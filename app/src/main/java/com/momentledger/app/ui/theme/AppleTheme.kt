package com.momentledger.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit

@Immutable
data class AppleColors(
    val bg: Color,
    val card: Color,
    val text: Color,
    val subText: Color,
    val separator: Color,
    val segBg: Color,
    val segActive: Color,
    val blue: Color,
    val orange: Color,
    val green: Color,
    val red: Color,
    val indigo: Color,
    val barTrack: Color,
    val tabBg: Color
)

val LightAppleColors = AppleColors(
    bg = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    text = Color(0xFF000000),
    subText = Color(0xFF8E8E93),
    separator = Color(0x1F3C3C43),
    segBg = Color(0x1F767680),
    segActive = Color(0xFFFFFFFF),
    blue = Color(0xFF007AFF),
    orange = Color(0xFFFF9500),
    green = Color(0xFF34C759),
    red = Color(0xFFFF3B30),
    indigo = Color(0xFF5856D6),
    barTrack = Color(0x24787880),
    tabBg = Color(0xEBF9F9F9)
)

val DarkAppleColors = AppleColors(
    bg = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    text = Color(0xFFFFFFFF),
    subText = Color(0xFF8E8E93),
    separator = Color(0x73545458),
    segBg = Color(0x3D767680),
    segActive = Color(0xFF636366),
    blue = Color(0xFF0A84FF),
    orange = Color(0xFFFF9F0A),
    green = Color(0xFF30D158),
    red = Color(0xFFFF453A),
    indigo = Color(0xFF5E5CE6),
    barTrack = Color(0x42787880),
    tabBg = Color(0xEB1C1C1E)
)

val LocalAppleColors = staticCompositionLocalOf { LightAppleColors }
val LocalIsDarkTheme = staticCompositionLocalOf { false }

val appleColors: AppleColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAppleColors.current

@Composable
fun AppleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkAppleColors else LightAppleColors
    val materialScheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.blue,
            secondary = colors.indigo,
            background = colors.bg,
            surface = colors.card,
            surfaceVariant = colors.segBg,
            onPrimary = Color.White,
            onBackground = colors.text,
            onSurface = colors.text,
            onSurfaceVariant = colors.subText,
            error = colors.red,
            outline = colors.separator
        )
    } else {
        lightColorScheme(
            primary = colors.blue,
            secondary = colors.indigo,
            background = colors.bg,
            surface = colors.card,
            surfaceVariant = colors.segBg,
            onPrimary = Color.White,
            onBackground = colors.text,
            onSurface = colors.text,
            onSurfaceVariant = colors.subText,
            error = colors.red,
            outline = colors.separator
        )
    }

    CompositionLocalProvider(
        LocalAppleColors provides colors,
        LocalIsDarkTheme provides darkTheme
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            content = content
        )
    }
}

fun TnumTextStyle(
    base: TextStyle = TextStyle.Default,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified
): TextStyle = base.copy(
    fontSize = if (fontSize != TextUnit.Unspecified) fontSize else base.fontSize,
    fontWeight = fontWeight ?: base.fontWeight,
    color = if (color != Color.Unspecified) color else base.color,
    fontFeatureSettings = "tnum"
)

fun TextStyle.withTnum(): TextStyle = copy(fontFeatureSettings = "tnum")

fun parseCategoryColor(colorHex: String, fallback: Color): Color {
    val cleaned = colorHex.trim().removePrefix("#")
    val parsed = cleaned.toLongOrNull(16) ?: return fallback
    return when (cleaned.length) {
        6 -> Color((0xFF000000L or parsed))
        8 -> Color(parsed)
        else -> fallback
    }
}
