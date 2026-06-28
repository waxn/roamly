package com.roamly.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Brand accents (kept for hero banners / adventure covers, not general UI) ──
val Coral       = Color(0xFFF9734F)
val CoralDeep   = Color(0xFFF2557A)
val Sky         = Color(0xFF38BDF8)
val Teal        = Color(0xFF2DD4BF)
val Violet      = Color(0xFFA78BFA)
val Sunshine    = Color(0xFFFBBF24)
private val ErrorRed = Color(0xFFF4604D)

// ── Fallback static palettes (Android < 12 / no dynamic color) ────────────────
private val DarkColors = darkColorScheme(
    primary              = Coral,
    onPrimary            = Color.White,
    secondary            = Teal,
    onSecondary          = Color(0xFF052B26),
    tertiary             = Violet,
    onTertiary           = Color(0xFF21104A),
    error                = ErrorRed,
    onError              = Color.White,
    background           = Color(0xFF0F1117),
    onBackground         = Color(0xFFEDF0F7),
    surface              = Color(0xFF1A1D27),
    onSurface            = Color(0xFFEDF0F7),
    surfaceVariant       = Color(0xFF252836),
    onSurfaceVariant     = Color(0xFF8B93AC),
    outline              = Color(0xFF3A3F52),
    primaryContainer     = Color(0xFF3A1E18),
    onPrimaryContainer   = Color(0xFFFFC7B6),
    secondaryContainer   = Color(0xFF0E2E2A),
    onSecondaryContainer = Color(0xFF9CF0E0),
    tertiaryContainer    = Color(0xFF2A1F4A),
    onTertiaryContainer  = Color(0xFFD3BBFF),
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
    background           = Color(0xFFF3F4F6),
    onBackground         = Color(0xFF1A2030),
    surface              = Color.White,
    onSurface            = Color(0xFF1A2030),
    surfaceVariant       = Color(0xFFE9ECF7),
    onSurfaceVariant     = Color(0xFF6B7288),
    outline              = Color(0xFFD0D5E8),
    primaryContainer     = Color(0xFFFFE3DA),
    onPrimaryContainer   = Color(0xFF7A2A18),
    secondaryContainer   = Color(0xFFD3F6EF),
    onSecondaryContainer = Color(0xFF0C3B35),
    tertiaryContainer    = Color(0xFFEFE4FF),
    onTertiaryContainer  = Color(0xFF4A2F8A),
)

private val RoamlyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
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
 * Minimal extra-theme values — only what can't be expressed by Material's ColorScheme.
 * No longer holds shadow/gradient values; those are gone. Only brand accent gradient
 * lists (for hero banners and adventure covers) and a dark-mode flag.
 */
data class ClayColors(
    val isDark: Boolean,
    // Kept for backward compat and hero / cover gradients — not used for cards/buttons.
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
    // Flat — surfaceTop == surfaceBottom so any verticalGradient call renders solid
    surfaceTop = Color(0xFF1A1D27),
    surfaceBottom = Color(0xFF1A1D27),
    shadowDark = Color.Transparent,
    shadowLight = Color.Transparent,
    backgroundBrush = Brush.linearGradient(listOf(Color(0xFF0F1117), Color(0xFF0F1117))),
    primaryGradient = listOf(Coral, CoralDeep),
    secondaryGradient = listOf(Sky, Teal),
    tertiaryGradient = listOf(Violet, Color(0xFF7C6BF0)),
)

private val LightClay = ClayColors(
    isDark = false,
    surfaceTop = Color.White,
    surfaceBottom = Color.White,
    shadowDark = Color.Transparent,
    shadowLight = Color.Transparent,
    backgroundBrush = Brush.linearGradient(listOf(Color(0xFFF3F4F6), Color(0xFFF3F4F6))),
    primaryGradient = listOf(Coral, CoralDeep),
    secondaryGradient = listOf(Sky, Teal),
    tertiaryGradient = listOf(Violet, Color(0xFF8B79F2)),
)

@Composable
fun RoamlyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val materialColors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context).copy(
                primary = Coral, onPrimary = Color.White,
                secondary = Teal, onSecondary = Color(0xFF052B26),
                tertiary = Violet,
                error = ErrorRed,
            )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !darkTheme ->
            dynamicLightColorScheme(context).copy(
                primary = Coral, onPrimary = Color.White,
                secondary = Teal, onSecondary = Color.White,
                tertiary = Violet,
                error = ErrorRed,
            )
        darkTheme -> DarkColors
        else -> LightColors
    }

    CompositionLocalProvider(LocalClayColors provides if (darkTheme) DarkClay else LightClay) {
        MaterialTheme(
            colorScheme = materialColors,
            shapes = RoamlyShapes,
            typography = RoamlyTypography,
            content = content
        )
    }
}
