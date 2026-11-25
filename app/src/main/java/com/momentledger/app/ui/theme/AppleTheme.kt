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
    bg = Color(0xFFF2EFF6),
    card = Color(0xFFFFFDFF),
    text = Color(0xFF211D2A),
    subText = Color(0xFF635D6E),
    separator = Color(0x245B4D70),
    segBg = Color(0x17655380),
    segActive = Color(0xFFFFFDFE),
    blue = Color(0xFFA72D60),
    orange = Color(0xFFC2410C),
    green = Color(0xFF2E7D5B),
    red = Color(0xFFC0263C),
    indigo = Color(0xFF4B46A8),
    barTrack = Color(0x21685590),
    tabBg = Color(0xFFFCFAFF)
)

val DarkAppleColors = AppleColors(
    bg = Color(0xFF14141D),
    card = Color(0xFF23232E),
    text = Color(0xFFF8F5FF),
    subText = Color(0xFFC4BDD2),
    separator = Color(0x4D8C83A9),
    segBg = Color(0x33736C8C),
    segActive = Color(0xFF3C394A),
    blue = Color(0xFFD36E99),
    orange = Color(0xFFF0A35E),
    green = Color(0xFF5FD39A),
    red = Color(0xFFFF7A7A),
    indigo = Color(0xFFA9A4F0),
    barTrack = Color(0x40807AA3),
    tabBg = Color(0xFF282733)
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
            onPrimary = Color(0xFF3A071B),
            primaryContainer = Color(0xFF5A2540),
            onPrimaryContainer = Color(0xFFFFD9E7),
            secondary = colors.indigo,
            onSecondary = Color(0xFF201C4A),
            secondaryContainer = Color(0xFF37326B),
            onSecondaryContainer = Color(0xFFE4E1FF),
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.card,
            onSurface = colors.text,
            surfaceVariant = colors.segBg,
            onSurfaceVariant = colors.subText,
            surfaceContainerLowest = colors.bg,
            surfaceContainerLow = Color(0xFF1F1F2A),
            surfaceContainer = colors.card,
            surfaceContainerHigh = Color(0xFF2B2937),
            surfaceContainerHighest = Color(0xFF343243),
            error = colors.red,
            onError = Color(0xFF3A0710),
            outline = Color(0xFF9E7F90),
            surfaceTint = colors.blue
        )
    } else {
        lightColorScheme(
            primary = colors.blue,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFFD9E7),
            onPrimaryContainer = Color(0xFF3D0A1D),
            secondary = colors.indigo,
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE4E1FF),
            onSecondaryContainer = Color(0xFF1B1747),
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.card,
            onSurface = colors.text,
            surfaceVariant = colors.segBg,
            onSurfaceVariant = colors.subText,
            surfaceContainerLowest = colors.card,
            surfaceContainerLow = Color(0xFFFAF8FD),
            surfaceContainer = Color(0xFFF6F3FA),
            surfaceContainerHigh = Color(0xFFF5F2F9),
            surfaceContainerHighest = Color(0xFFEDE8F4),
            error = colors.red,
            onError = Color.White,
            outline = Color(0xFF9A6F83),
            surfaceTint = colors.blue
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
