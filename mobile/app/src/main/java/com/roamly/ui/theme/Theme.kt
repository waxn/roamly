package com.roamly.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Brand accents (shared across themes for a consistent, playful pop) ──────────
// Warm coral → pink, cool sky → teal, and a violet tertiary. Travel-poster energy.
val Coral       = Color(0xFFF9734F)
val CoralDeep   = Color(0xFFF2557A)
val Sky         = Color(0xFF38BDF8)
val Teal        = Color(0xFF2DD4BF)
val Violet      = Color(0xFFA78BFA)
val Sunshine    = Color(0xFFFBBF24)
private val ErrorRed = Color(0xFFF4604D)

// ── Dark palette — deep indigo-navy clay ───────────────────────────────────────
private val DarkBackground     = Color(0xFF0B0E16)
private val DarkBackgroundTop  = Color(0xFF121A2B)
private val DarkSurface        = Color(0xFF171E2E)
private val DarkSurfaceHi      = Color(0xFF1E2638)
private val DarkSurfaceVariant = Color(0xFF222C42)
private val DarkOnBackground   = Color(0xFFEDF0F7)
private val DarkMuted          = Color(0xFF8B93AC)
private val DarkOutline        = Color(0xFF2A3450)

// ── Light palette — warm lavender-grey clay ────────────────────────────────────
private val LightBackground     = Color(0xFFEFF1F9)
private val LightBackgroundTop  = Color(0xFFF6F4FC)
private val LightSurface        = Color(0xFFFFFFFF)
private val LightSurfaceHi      = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFE9ECF7)
private val LightOnBackground   = Color(0xFF1A2030)
private val LightMuted          = Color(0xFF6B7288)
private val LightOutline        = Color(0xFFD6DAEA)

private val DarkColors = darkColorScheme(
    primary              = Coral,
    onPrimary            = Color.White,
    secondary            = Teal,
    onSecondary          = Color(0xFF052B26),
    tertiary             = Violet,
    onTertiary           = Color(0xFF21104A),
    error                = ErrorRed,
    onError              = Color.White,
    background           = DarkBackground,
    onBackground         = DarkOnBackground,
    surface              = DarkSurface,
    onSurface            = DarkOnBackground,
    surfaceVariant       = DarkSurfaceVariant,
    onSurfaceVariant     = DarkMuted,
    outline              = DarkOutline,
    primaryContainer     = Color(0xFF3A1E18),
    onPrimaryContainer   = Color(0xFFFFC7B6),
    secondaryContainer   = Color(0xFF0E2E2A),
    onSecondaryContainer = Color(0xFF9CF0E0),
)

private val LightColors = lightColorScheme(
    primary              = Coral,
    onPrimary            = Color.White,
    secondary            = Teal,
    onSecondary          = Color.White,
    tertiary             = Violet,
    onTertiary           = Color.White,
    error                = ErrorRed,
    onError              = Color.White,
    background           = LightBackground,
    onBackground         = LightOnBackground,
    surface              = LightSurface,
    onSurface            = LightOnBackground,
    surfaceVariant       = LightSurfaceVariant,
    onSurfaceVariant     = LightMuted,
    outline              = LightOutline,
    primaryContainer     = Color(0xFFFFE3DA),
    onPrimaryContainer   = Color(0xFF7A2A18),
    secondaryContainer   = Color(0xFFD3F6EF),
    onSecondaryContainer = Color(0xFF0C3B35),
)

// Chunky, friendly corners — claymorphism lives on big radii.
private val RoamlyShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small      = RoundedCornerShape(16.dp),
    medium     = RoundedCornerShape(22.dp),
    large      = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val RoamlyTypography = Typography().run {
    copy(
        displaySmall   = displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall  = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge     = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium    = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall     = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge     = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/**
 * Extra palette that Material's ColorScheme can't express — the soft top/bottom
 * shadow colours that give clay its depth, plus the screen background gradient
 * and the brand accent gradients used by buttons, badges and headers.
 */
data class ClayColors(
    val isDark: Boolean,
    val surfaceTop: Color,
    val surfaceBottom: Color,
    val shadowDark: Color,
    val shadowLight: Color,
    val backgroundBrush: Brush,
    val primaryGradient: List<Color>,
    val secondaryGradient: List<Color>,
    val tertiaryGradient: List<Color>,
)

val LocalClayColors: ProvidableCompositionLocal<ClayColors> = staticCompositionLocalOf {
    error("ClayColors not provided")
}

object Clay {
    val colors: ClayColors
        @Composable get() = LocalClayColors.current
}

private val DarkClay = ClayColors(
    isDark = true,
    surfaceTop = DarkSurfaceHi,
    surfaceBottom = DarkSurface,
    shadowDark = Color(0xCC04070E),
    shadowLight = Color(0x14FFFFFF),
    backgroundBrush = Brush.linearGradient(listOf(DarkBackgroundTop, DarkBackground)),
    primaryGradient = listOf(Coral, CoralDeep),
    secondaryGradient = listOf(Sky, Teal),
    tertiaryGradient = listOf(Violet, Color(0xFF7C6BF0)),
)

private val LightClay = ClayColors(
    isDark = false,
    surfaceTop = LightSurfaceHi,
    surfaceBottom = Color(0xFFF3F4FB),
    shadowDark = Color(0x33A6ADCB),
    shadowLight = Color(0xFFFFFFFF),
    backgroundBrush = Brush.linearGradient(listOf(LightBackgroundTop, LightBackground)),
    primaryGradient = listOf(Coral, CoralDeep),
    secondaryGradient = listOf(Sky, Teal),
    tertiaryGradient = listOf(Violet, Color(0xFF8B79F2)),
)

@Composable
fun RoamlyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalClayColors provides if (darkTheme) DarkClay else LightClay) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            shapes = RoamlyShapes,
            typography = RoamlyTypography,
            content = content
        )
    }
}
