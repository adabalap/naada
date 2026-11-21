package com.adabala.naada.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

/** The look-and-feel options offered in Settings → Appearance. */
enum class ThemeChoice(val id: String, val label: String, val blurb: String, val swatch: List<Color>) {
    System("system", "Classic", "Follows your phone's light / dark setting", listOf(Brand.Purple, Brand.Rose, Brand.Gold)),
    Night("dark", "Night", "Deep plum, easy on the eyes", listOf(Brand.Night, Brand.Purple, Color(0xFFD7A6FF))),
    Day("light", "Daylight", "Bright and airy", listOf(Brand.Paper, Color(0xFFF3EDF8), Brand.Purple)),
    Midnight("amoled", "Midnight", "Pure black — saves battery on OLED", listOf(Color.Black, Color(0xFF1A1A1A), Color(0xFFB98CFF))),
    Wallpaper("wallpaper", "Material You", "Colours from your wallpaper", listOf(Color(0xFF6750A4), Color(0xFF625B71), Color(0xFF7D5260))),
    Sunset("sunset", "Sunset", "Warm saffron dusk", listOf(Color(0xFF1C0F0A), Color(0xFFFF7A45), Color(0xFFFFC857))),
    Ocean("ocean", "Ocean", "Cool teal depths", listOf(Color(0xFF04161C), Color(0xFF13A8A8), Color(0xFF7DF9E4))),
    Neon("neon", "Neon", "Synthwave glow", listOf(Color(0xFF0D0221), Color(0xFFFF2ED1), Color(0xFF00F0FF))),
    Toon("cartoon", "Toon", "Playful, bold and bubbly", listOf(Color(0xFFFFF4D6), Color(0xFFFF5A5F), Color(0xFF00A6ED)));

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: System
    }
}

/** Extras Material doesn't model: the hero gradient and how round things are. */
data class NaadaStyle(
    val dark: Boolean,
    val hero: List<Color>,
    val corner: Dp,
    val playful: Boolean = false,
) {
    val heroBrush: Brush get() = Brush.linearGradient(hero)
}

val LocalNaadaStyle = compositionLocalOf { NaadaStyle(true, listOf(Brand.Purple, Brand.Rose, Brand.Gold), 14.dp) }

private val NightScheme = darkColorScheme(
    primary = Color(0xFFD7A6FF), onPrimary = Color(0xFF2C0A45),
    primaryContainer = Brand.Purple, onPrimaryContainer = Color(0xFFF1DBFF),
    secondary = Brand.Rose, tertiary = Brand.Gold,
    background = Brand.Night, onBackground = Color(0xFFEDE6F3),
    surface = Brand.Night, onSurface = Color(0xFFEDE6F3),
    surfaceVariant = Brand.NightSurfaceHigh, onSurfaceVariant = Color(0xFFB9AEC4),
    surfaceContainer = Brand.NightSurface, surfaceContainerHigh = Brand.NightSurfaceHigh,
    surfaceContainerLow = Color(0xFF17101F), surfaceContainerHighest = Color(0xFF30253D),
    outline = Color(0xFF5B4E68), outlineVariant = Color(0xFF3A2F45),
)

private val DayScheme = lightColorScheme(
    primary = Brand.Purple, onPrimary = Color.White,
    primaryContainer = Color(0xFFF3EDF8), onPrimaryContainer = Brand.Purple,
    secondary = Brand.Rose, tertiary = Brand.Gold,
    background = Brand.Paper, onBackground = Color(0xFF1A1917),
    surface = Brand.Paper, onSurface = Color(0xFF1A1917),
    surfaceVariant = Color(0xFFECEAE3), onSurfaceVariant = Color(0xFF636059),
    surfaceContainer = Color(0xFFF5F4F0), surfaceContainerHigh = Color(0xFFECEAE3),
    surfaceContainerLow = Color(0xFFF8F7F3), surfaceContainerHighest = Color(0xFFE3E0D7),
    outline = Color(0xFF9B9891), outlineVariant = Color(0xFFD9D6CC),
)

private val MidnightScheme = NightScheme.copy(
    primary = Color(0xFFB98CFF),
    background = Color.Black, surface = Color.Black,
    surfaceContainerLow = Color(0xFF080808), surfaceContainer = Color(0xFF101010),
    surfaceContainerHigh = Color(0xFF181818), surfaceContainerHighest = Color(0xFF222222),
    surfaceVariant = Color(0xFF1A1A1A), outlineVariant = Color(0xFF2A2A2A),
)

private fun darkTinted(bg: Color, s1: Color, s2: Color, s3: Color, primary: Color, secondary: Color, tertiary: Color, text: Color, sub: Color) =
    darkColorScheme(
        primary = primary, onPrimary = bg, primaryContainer = s3, onPrimaryContainer = text,
        secondary = secondary, tertiary = tertiary,
        background = bg, onBackground = text, surface = bg, onSurface = text,
        surfaceVariant = s2, onSurfaceVariant = sub,
        surfaceContainerLow = s1, surfaceContainer = s1, surfaceContainerHigh = s2, surfaceContainerHighest = s3,
        outline = sub.copy(alpha = .5f), outlineVariant = s3,
    )

private val SunsetScheme = darkTinted(
    Color(0xFF1C0F0A), Color(0xFF26160F), Color(0xFF331E14), Color(0xFF4A2A1B),
    Color(0xFFFF8A4C), Color(0xFFFF4D6D), Color(0xFFFFC857), Color(0xFFFFEDE2), Color(0xFFD9B8A6),
)
private val OceanScheme = darkTinted(
    Color(0xFF04161C), Color(0xFF0A2129), Color(0xFF102C36), Color(0xFF173B47),
    Color(0xFF4FE3D0), Color(0xFF3FA9F5), Color(0xFF9BF6E0), Color(0xFFE2FBF7), Color(0xFF9DC3C7),
)
private val NeonScheme = darkTinted(
    Color(0xFF0D0221), Color(0xFF160733), Color(0xFF1F0B45), Color(0xFF2E1263),
    Color(0xFFFF4FD8), Color(0xFF00F0FF), Color(0xFFFFE600), Color(0xFFF7EEFF), Color(0xFFB8A6D9),
)
private val ToonScheme = lightColorScheme(
    primary = Color(0xFFFF5A5F), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD6D7), onPrimaryContainer = Color(0xFF5C0A0D),
    secondary = Color(0xFF00A6ED), tertiary = Color(0xFFFFB400),
    background = Color(0xFFFFF4D6), onBackground = Color(0xFF2B2118),
    surface = Color(0xFFFFF4D6), onSurface = Color(0xFF2B2118),
    surfaceVariant = Color(0xFFFFE7A8), onSurfaceVariant = Color(0xFF6B5A44),
    surfaceContainerLow = Color(0xFFFFF8E6), surfaceContainer = Color(0xFFFFEFC2),
    surfaceContainerHigh = Color(0xFFFFE7A8), surfaceContainerHighest = Color(0xFFFFDD8A),
    outline = Color(0xFF2B2118), outlineVariant = Color(0xFFE9CF8D),
)

private val BaseType = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val ToonType = BaseType.let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 0.sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Black, letterSpacing = 0.sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
        bodyLarge = t.bodyLarge.copy(fontWeight = FontWeight.Medium),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
    )
}

private val ToonShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(36.dp),
)

val SectionTitle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)

/** Resolves a choice to colours + style. Exposed so the picker can preview. */
@Composable
fun resolveTheme(choice: ThemeChoice): Pair<ColorScheme, NaadaStyle> {
    val sysDark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val brand = listOf(Brand.Purple, Brand.Rose, Brand.Gold)
    return when (choice) {
        ThemeChoice.System -> (if (sysDark) NightScheme else DayScheme) to NaadaStyle(sysDark, brand, 14.dp)
        ThemeChoice.Night -> NightScheme to NaadaStyle(true, brand, 14.dp)
        ThemeChoice.Day -> DayScheme to NaadaStyle(false, brand, 14.dp)
        ThemeChoice.Midnight -> MidnightScheme to NaadaStyle(true, listOf(Color(0xFF2B1747), Color(0xFF6B2FA3), Color(0xFFB98CFF)), 14.dp)
        ThemeChoice.Wallpaper -> {
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (sysDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else if (sysDark) NightScheme else DayScheme
            scheme to NaadaStyle(sysDark, listOf(scheme.primaryContainer, scheme.primary, scheme.tertiary), 16.dp)
        }
        ThemeChoice.Sunset -> SunsetScheme to NaadaStyle(true, listOf(Color(0xFF8E2A0C), Color(0xFFFF6B35), Color(0xFFFFC857)), 16.dp)
        ThemeChoice.Ocean -> OceanScheme to NaadaStyle(true, listOf(Color(0xFF0B3C5D), Color(0xFF13A8A8), Color(0xFF7DF9E4)), 16.dp)
        ThemeChoice.Neon -> NeonScheme to NaadaStyle(true, listOf(Color(0xFF7B00FF), Color(0xFFFF2ED1), Color(0xFF00F0FF)), 12.dp)
        ThemeChoice.Toon -> ToonScheme to NaadaStyle(false, listOf(Color(0xFFFF5A5F), Color(0xFFFFB400), Color(0xFF00A6ED)), 24.dp, playful = true)
    }
}

/**
 * App theme. [accent] is the colour pulled from the current song's artwork;
 * when present it tints the primary colour so the whole app follows the music.
 */
@Composable
fun NaadaTheme(
    choice: ThemeChoice = ThemeChoice.System,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val (base, style) = resolveTheme(choice)
    val target = accent?.let { if (style.dark) lighten(it) else darken(it) } ?: base.primary
    val primary by animateColorAsState(target, tween(700), label = "accent")
    CompositionLocalProvider(LocalNaadaStyle provides style) {
        MaterialTheme(
            colorScheme = base.copy(primary = primary),
            typography = if (style.playful) ToonType else BaseType,
            shapes = if (style.playful) ToonShapes else Shapes(),
            content = content,
        )
    }
}

private fun lighten(c: Color): Color = Color(
    red = c.red + (1 - c.red) * 0.45f,
    green = c.green + (1 - c.green) * 0.45f,
    blue = c.blue + (1 - c.blue) * 0.45f,
)

private fun darken(c: Color): Color = Color(red = c.red * 0.7f, green = c.green * 0.7f, blue = c.blue * 0.7f)
