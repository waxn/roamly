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
 * Night Atlas — the app's own design system (see docs/claude/mobile-app.md).
 *
 * Map-first and dark-first: a deep ink ground, frosted panels that float over
 * the map, one vivid Roamly-orange accent, and big tabular numerals for the
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
    bg = Color(0xFF07090D),
    surface = Color(0xFF10131A),
    surfaceHigh = Color(0xFF181C25),
    surfaceHighest = Color(0xFF222733),
    glass = Color(0xF2121620),
    line = Color(0x14FFFFFF),
    lineStrong = Color(0x29FFFFFF),
    text = Color(0xFFF3F4F7),
    textMuted = Color(0xFF9199A8),
    textFaint = Color(0xFF5C6370),
    accent = Color(0xFFFF7A3D),
    onAccent = Color(0xFF1A0A02),
    accentSoft = Color(0x29FF7A3D),
    cyan = Color(0xFF4CD3F0),
    lime = Color(0xFFB4E05A),
    violet = Color(0xFFA990FF),
    rose = Color(0xFFFF6F8E),
    amber = Color(0xFFFFC14D),
    sky = Color(0xFF6EA8FF),
    success = Color(0xFF3DDC97),
    warning = Color(0xFFFFC14D),
    danger = Color(0xFFFF5D5D),
    dangerSoft = Color(0x26FF5D5D),
    shadow = Color(0xFF000000),
)

private val DayAtlas = AtlasColors(
    isDark = false,
    bg = Color(0xFFF3F4F6),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEDEFF2),
    surfaceHighest = Color(0xFFE2E5EA),
    glass = Color(0xF5FFFFFF),
    line = Color(0x140B1220),
    lineStrong = Color(0x290B1220),
    text = Color(0xFF0D1117),
    textMuted = Color(0xFF5A6372),
    textFaint = Color(0xFF959DAA),
    accent = Color(0xFFF2621E),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0x1FF2621E),
    cyan = Color(0xFF0A9EC2),
    lime = Color(0xFF5E9A12),
    violet = Color(0xFF6E52E0),
    rose = Color(0xFFE0365F),
    amber = Color(0xFFC98A00),
    sky = Color(0xFF2F6FE0),
    success = Color(0xFF14A06A),
    warning = Color(0xFFC98A00),
    danger = Color(0xFFE03A3A),
    dangerSoft = Color(0x1FE03A3A),
    shadow = Color(0xFF0B1220),
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
    headlineLarge = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.9).sp),
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
    labelSmall = TextStyle(fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp),
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
