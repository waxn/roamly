package com.roamly.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Still Water (code names NightAtlas/DayAtlas) — the app's own design system (see docs/claude/mobile-app.md).
 *
 * Map-first and dark-first: a deep-water ground, calm panels that float over
 * the map, a tide-teal route/active colour (coral is Record only), and big tabular numerals for the
 * figures the app is about (distance, days, places). Light mode ("Day Atlas")
 * keeps the same structure on a cool paper ground.
 *
 * Read colours through [Atlas.colors]; never hardcode a hex in a screen.
 */
@Immutable
data class AtlasColors(
    val isDark: Boolean,
    /** The page ground. */
    val bg: Color,
    /** Cards and sheets. */
    val surface: Color,
    /** Raised controls on a card: fields, segmented tracks, list wells. */
    val surfaceHigh: Color,
    /** The highest step: pressed states, a selected segment's track. */
    val surfaceHighest: Color,
    /** Frosted panels floating over the map (already translucent). */
    val glass: Color,
    /** Hairlines. */
    val line: Color,
    val lineStrong: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,
    val onAccent: Color,
    /** The accent at low alpha, for selected chips and tinted wells. */
    val accentSoft: Color,
    /** Recording only: the Record button and live-tracking marks. Never a general accent. */
    val record: Color,
    val onRecord: Color,
    // Categorical accents for charts, badges and icon tiles.
    val cyan: Color,
    val lime: Color,
    val violet: Color,
    val rose: Color,
    val amber: Color,
    val sky: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val dangerSoft: Color,
    /** Shadow tint for floating panels. */
    val shadow: Color,
) {
    /** The categorical ramp, in the order charts and member tracks use it. */
    val ramp: List<Color> get() = listOf(accent, cyan, lime, violet, rose, amber, sky)
}

private val NightAtlas = AtlasColors(
    isDark = true,
    bg = Color(0xFF0F171A),
    surface = Color(0xFF162226),
    surfaceHigh = Color(0xFF1E2D32),
    surfaceHighest = Color(0xFF2A3C42),
    glass = Color(0xFA162226),
    line = Color(0x1AE6EEEE),
    lineStrong = Color(0x33E6EEEE),
    text = Color(0xFFE6EEEE),
    textMuted = Color(0xFF90A4A9),
    textFaint = Color(0xFF64797E),
    accent = Color(0xFF7FD1C7),
    onAccent = Color(0xFF06201E),
    accentSoft = Color(0x267FD1C7),
    record = Color(0xFFF07A68),
    onRecord = Color(0xFF2A0A05),
    cyan = Color(0xFF6FB9C4),
    lime = Color(0xFF9DB97C),
    violet = Color(0xFFAE9FD0),
    rose = Color(0xFFD99AA4),
    amber = Color(0xFFD9B87A),
    sky = Color(0xFF86A9CC),
    success = Color(0xFF5CC995),
    warning = Color(0xFFD9B87A),
    danger = Color(0xFFEF6B5F),
    dangerSoft = Color(0x26EF6B5F),
    shadow = Color(0xFF000000),
)

private val DayAtlas = AtlasColors(
    isDark = false,
    bg = Color(0xFFEEF2F1),
    surface = Color(0xFFFAFBFA),
    surfaceHigh = Color(0xFFE2E9E8),
    surfaceHighest = Color(0xFFD2DCDB),
    glass = Color(0xFAFAFBFA),
    line = Color(0x1A16303A),
    lineStrong = Color(0x3316303A),
    text = Color(0xFF16303A),
    textMuted = Color(0xFF5B6F75),
    textFaint = Color(0xFF8A9CA1),
    accent = Color(0xFF1F7A84),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0x1F1F7A84),
    record = Color(0xFFE4604D),
    onRecord = Color(0xFFFFFFFF),
    cyan = Color(0xFF2E8A94),
    lime = Color(0xFF627F45),
    violet = Color(0xFF7261A6),
    rose = Color(0xFFB4606E),
    amber = Color(0xFFA67C2E),
    sky = Color(0xFF4A729C),
    success = Color(0xFF1B8A5A),
    warning = Color(0xFFA67C2E),
    danger = Color(0xFFC53A30),
    dangerSoft = Color(0x1FC53A30),
    shadow = Color(0xFF16303A),
)

private fun AtlasColors.toScheme() = if (isDark) darkColorScheme(
    primary = accent, onPrimary = onAccent,
    primaryContainer = accentSoft, onPrimaryContainer = accent,
    secondary = cyan, onSecondary = Color(0xFF00222B),
    secondaryContainer = surfaceHighest, onSecondaryContainer = text,
    tertiary = violet, onTertiary = Color(0xFF1B1140),
    tertiaryContainer = surfaceHigh, onTertiaryContainer = text,
    error = danger, onError = Color.White,
    errorContainer = dangerSoft, onErrorContainer = danger,
    background = bg, onBackground = text,
    surface = surface, onSurface = text,
    surfaceVariant = surfaceHigh, onSurfaceVariant = textMuted,
    surfaceTint = Color.Transparent,
    surfaceDim = bg, surfaceBright = surfaceHighest,
    surfaceContainerLowest = bg, surfaceContainerLow = surface,
    surfaceContainer = surface, surfaceContainerHigh = surfaceHigh,
    surfaceContainerHighest = surfaceHighest,
    inverseSurface = text, inverseOnSurface = bg, inversePrimary = accent,
    outline = lineStrong, outlineVariant = line, scrim = Color(0xCC000000),
) else lightColorScheme(
    primary = accent, onPrimary = onAccent,
    primaryContainer = accentSoft, onPrimaryContainer = accent,
    secondary = cyan, onSecondary = Color.White,
    secondaryContainer = surfaceHighest, onSecondaryContainer = text,
    tertiary = violet, onTertiary = Color.White,
    tertiaryContainer = surfaceHigh, onTertiaryContainer = text,
    error = danger, onError = Color.White,
    errorContainer = dangerSoft, onErrorContainer = danger,
    background = bg, onBackground = text,
    surface = surface, onSurface = text,
    surfaceVariant = surfaceHigh, onSurfaceVariant = textMuted,
    surfaceTint = Color.Transparent,
    surfaceDim = surfaceHighest, surfaceBright = surface,
    surfaceContainerLowest = surface, surfaceContainerLow = surface,
    surfaceContainer = surface, surfaceContainerHigh = surfaceHigh,
    surfaceContainerHighest = surfaceHighest,
    inverseSurface = text, inverseOnSurface = bg, inversePrimary = accent,
    outline = lineStrong, outlineVariant = line, scrim = Color(0x990B1220),
)

private const val TNUM = "tnum"

private val AtlasTypography = Typography(
    displayLarge = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 58.sp, letterSpacing = (-2).sp, fontFeatureSettings = TNUM),
    displayMedium = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-1.5).sp, fontFeatureSettings = TNUM),
    displaySmall = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-1.1).sp, fontFeatureSettings = TNUM),
    headlineLarge = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.7).sp),
    headlineSmall = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
    titleSmall = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp),
    labelSmall = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
)

/** Extra type roles M3 has no slot for. */
object AtlasType {
    /** A large figure: distance, a count. Tabular so it doesn't jitter as it counts. */
    val figure = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 32.sp, letterSpacing = (-1).sp, fontFeatureSettings = TNUM)
    val figureSmall = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 22.sp, letterSpacing = (-0.5).sp, fontFeatureSettings = TNUM)
    /** Coordinates, timestamps, ids. */
    val mono = TextStyle(fontFamily = PlexMonoFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = TNUM)
}

private val AtlasShapes = Shapes(
    extraSmall = SquircleShape(SquircleRadius.tag),
    small = SquircleShape(SquircleRadius.tag),
    medium = SquircleShape(SquircleRadius.control),
    large = SquircleShape(SquircleRadius.panel),
    extraLarge = SquircleShape(SquircleRadius.dialog),
)

val LocalAtlasColors = staticCompositionLocalOf { NightAtlas }

object Atlas {
    val colors: AtlasColors
        @Composable get() = LocalAtlasColors.current
}

/**
 * The app theme. Night Atlas is a fixed brand palette — dynamic (Material You)
 * colour is deliberately not used.
 */
@Composable
fun RoamlyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) NightAtlas else DayAtlas
    CompositionLocalProvider(LocalAtlasColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toScheme(),
            shapes = AtlasShapes,
            typography = AtlasTypography,
            content = content,
        )
    }
}
