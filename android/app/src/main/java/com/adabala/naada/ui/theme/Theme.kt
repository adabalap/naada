package com.adabala.naada.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Brand {
    val Purple = Color(0xFF4A1A6B)
    val PurpleLight = Color(0xFF7B3FA0)
    val Rose = Color(0xFFE11D75)
    val Gold = Color(0xFFF59E0B)
    val Teal = Color(0xFF0891B2)
    val Night = Color(0xFF120C1A)
    val NightSurface = Color(0xFF1C1426)
    val NightSurfaceHigh = Color(0xFF271D33)
    val Paper = Color(0xFFFAFAF8)

    val gradient = Brush.linearGradient(listOf(Purple, Rose, Gold))
}

private val Dark = darkColorScheme(
    primary = Color(0xFFD7A6FF),
    onPrimary = Color(0xFF2C0A45),
    primaryContainer = Brand.Purple,
    onPrimaryContainer = Color(0xFFF1DBFF),
    secondary = Brand.Rose,
    tertiary = Brand.Gold,
    background = Brand.Night,
    onBackground = Color(0xFFEDE6F3),
    surface = Brand.Night,
    onSurface = Color(0xFFEDE6F3),
    surfaceVariant = Brand.NightSurfaceHigh,
    onSurfaceVariant = Color(0xFFB9AEC4),
    surfaceContainer = Brand.NightSurface,
    surfaceContainerHigh = Brand.NightSurfaceHigh,
    surfaceContainerLow = Color(0xFF17101F),
    surfaceContainerHighest = Color(0xFF30253D),
    outline = Color(0xFF5B4E68),
    outlineVariant = Color(0xFF3A2F45),
)

private val Light = lightColorScheme(
    primary = Brand.Purple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3EDF8),
    onPrimaryContainer = Brand.Purple,
    secondary = Brand.Rose,
    tertiary = Brand.Gold,
    background = Brand.Paper,
    onBackground = Color(0xFF1A1917),
    surface = Brand.Paper,
    onSurface = Color(0xFF1A1917),
    surfaceVariant = Color(0xFFECEAE3),
    onSurfaceVariant = Color(0xFF636059),
    surfaceContainer = Color(0xFFF5F4F0),
    surfaceContainerHigh = Color(0xFFECEAE3),
    surfaceContainerLow = Color(0xFFF8F7F3),
    surfaceContainerHighest = Color(0xFFE3E0D7),
    outline = Color(0xFF9B9891),
    outlineVariant = Color(0xFFD9D6CC),
)

private val NaadaType = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val SectionTitle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)

/**
 * App theme. [accent] is the colour pulled from the current song's artwork;
 * when present it tints the primary colour so the whole app follows the music.
 */
@Composable
fun NaadaTheme(
    accent: Color? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) Dark else Light
    val target = accent?.let { if (darkTheme) lighten(it) else darken(it) } ?: base.primary
    val primary by animateColorAsState(target, tween(700), label = "accent")
    MaterialTheme(
        colorScheme = base.copy(primary = primary),
        typography = NaadaType,
        content = content,
    )
}

private fun lighten(c: Color): Color = Color(
    red = c.red + (1 - c.red) * 0.45f,
    green = c.green + (1 - c.green) * 0.45f,
    blue = c.blue + (1 - c.blue) * 0.45f,
)

private fun darken(c: Color): Color = Color(red = c.red * 0.7f, green = c.green * 0.7f, blue = c.blue * 0.7f)
