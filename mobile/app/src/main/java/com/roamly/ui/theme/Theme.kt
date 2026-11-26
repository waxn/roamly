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
 * Map-first and dark-first: a warm graphite ground, calm panels, a soft sand accent
 * (coral is Record only), and big tabular numerals for the
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
    bg = Color(0xFF121416),
    surface = Color(0xFF1A1D1F),
    surfaceHigh = Color(0xFF232729),
    surfaceHighest = Color(0xFF2D3235),
    glass = Color(0xF21A1D1F),
    line = Color(0x1AE9E7E2),
    lineStrong = Color(0x33E9E7E2),
    text = Color(0xFFE9E7E2),
    textMuted = Color(0xFFA39F97),
    textFaint = Color(0xFF6E6B65),
    accent = Color(0xFFD4B48C),
    onAccent = Color(0xFF241A0E),
    accentSoft = Color(0x26D4B48C),
    record = Color(0xFFE27D6A),
    onRecord = Color(0xFF2A0D07),
    cyan = Color(0xFF86AFA9),
    lime = Color(0xFFA5B58A),
    violet = Color(0xFFA89CC4),
    rose = Color(0xFFCF9AA0),
    amber = Color(0xFFD3B075),
    sky = Color(0xFF8DA9C4),
    success = Color(0xFF7DB596),
    warning = Color(0xFFD3B075),
    danger = Color(0xFFE27D6A),
    dangerSoft = Color(0x26E27D6A),
    shadow = Color(0xFF000000),
)

private val DayAtlas = AtlasColors(
    isDark = false,
    bg = Color(0xFFF2F0EC),
    surface = Color(0xFFFBFAF8),
    surfaceHigh = Color(0xFFE8E5DF),
    surfaceHighest = Color(0xFFDAD6CE),
    glass = Color(0xF2FBFAF8),
    line = Color(0x1A23211E),
    lineStrong = Color(0x3323211E),
    text = Color(0xFF23211E),
    textMuted = Color(0xFF65615A),
    textFaint = Color(0xFF9A958C),
    accent = Color(0xFF8F6A40),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0x1F8F6A40),
    record = Color(0xFFC65A45),
    onRecord = Color(0xFFFFFFFF),
    cyan = Color(0xFF4F7F7A),
    lime = Color(0xFF6F7F4C),
    violet = Color(0xFF6E6190),
    rose = Color(0xFFA8636F),
    amber = Color(0xFF9A7330),
    sky = Color(0xFF506F8F),
    success = Color(0xFF3F7F5E),
    warning = Color(0xFF9A7330),
    danger = Color(0xFFB3473B),
    dangerSoft = Color(0x1FB3473B),
    shadow = Color(0xFF23211E),
)

private fun AtlasColors.toScheme() = if (isDark) darkColorScheme(
    primary = accent, onPrimary = onAccent,
    primaryContainer = accentSoft, onPrimaryContainer = accent,
    secondary = cyan, onSecondary = Color(0xFF10201E),
    secondaryContainer = surfaceHighest, onSecondaryContainer = text,
    tertiary = violet, onTertiary = Color(0xFF1A1630),
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
    outline = lineStrong, outlineVariant = line, scrim = Color(0x99121416),
)

private const val TNUM = "tnum"

private val AtlasTypography = Typography(
    displayLarge = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 58.sp, letterSpacing = (-2).sp, fontFeatureSettings = TNUM),
    displayMedium = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-1.5).sp, fontFeatureSettings = TNUM),
    displaySmall = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-1.1).sp, fontFeatureSettings = TNUM),
    headlineLarge = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.7).sp),
    headlineSmall = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
    titleSmall = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp),
    labelSmall = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
)

/** Extra type roles M3 has no slot for. */
object AtlasType {
    /** A large figure: distance, a count. Tabular so it doesn't jitter as it counts. */
    val figure = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 32.sp, letterSpacing = (-1).sp, fontFeatureSettings = TNUM)
    val figureSmall = TextStyle(fontFamily = FigtreeFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 22.sp, letterSpacing = (-0.5).sp, fontFeatureSettings = TNUM)
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
