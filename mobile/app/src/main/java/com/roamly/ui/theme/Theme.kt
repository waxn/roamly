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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Field Journal palette — ported from tracker/templates/tracker/base.html.
// Same hexes as the web app's :root (dark, default) and [data-theme="light"].
private object FieldJournalDark {
    val bg = Color(0xFF14160F)
    val bgCard = Color(0xFF1B1E15)
    val bgElevated = Color(0xFF23271C)
    val border = Color(0xFF30352A)
    val borderStrong = Color(0xFF474D3B)
    val text = Color(0xFFE9E5D8)
    val textMuted = Color(0xFFA9A794)
    val primary = Color(0xFFE8763D)
    val rust = Color(0xFFD0694A)
    val moss = Color(0xFF7F9B6D)
    val ochre = Color(0xFFCF9A44)
    val mauve = Color(0xFFA888A0)
    val mapBlue = Color(0xFF7BA3B8)
    val danger = Color(0xFFD4553F)
    val success = Color(0xFF7BA05B)
    val warning = Color(0xFFCF9A44)
}

private object FieldJournalLight {
    val bg = Color(0xFFF2EFE4)
    val bgCard = Color(0xFFFAF8F0)
    val bgElevated = Color(0xFFF5F2E7)
    val border = Color(0xFFD9D3C2)
    val borderStrong = Color(0xFFC2BBA6)
    val text = Color(0xFF262319)
    val textMuted = Color(0xFF5C5847)
    val primary = Color(0xFFC85F2A)
    val rust = Color(0xFFB8532F)
    val moss = Color(0xFF5F7A4F)
    val ochre = Color(0xFFA97B1F)
    val mauve = Color(0xFF86657E)
    val mapBlue = Color(0xFF4F7D94)
    val danger = Color(0xFFBD4634)
    val success = Color(0xFF5F8A3F)
    val warning = Color(0xFFA97B1F)
}

private val DarkColors = darkColorScheme(
    primary              = FieldJournalDark.primary,
    onPrimary            = Color.White,
    secondary            = FieldJournalDark.mapBlue,
    onSecondary          = Color(0xFF0B2530),
    tertiary             = FieldJournalDark.mauve,
    onTertiary           = Color(0xFF2A1A26),
    error                = FieldJournalDark.danger,
    onError              = Color.White,
    errorContainer       = Color(0xFF3A1712),
    onErrorContainer     = Color(0xFFFFB4A8),
    background           = FieldJournalDark.bg,
    onBackground         = FieldJournalDark.text,
    surface              = FieldJournalDark.bgCard,
    onSurface            = FieldJournalDark.text,
    surfaceVariant       = FieldJournalDark.bgElevated,
    onSurfaceVariant     = FieldJournalDark.textMuted,
    outline              = FieldJournalDark.border,
    outlineVariant       = FieldJournalDark.borderStrong,
    primaryContainer     = Color(0xFF3A2A1A),
    onPrimaryContainer   = Color(0xFFFFCDA3),
    secondaryContainer   = Color(0xFF16323B),
    onSecondaryContainer = Color(0xFFBFE3EE),
    tertiaryContainer    = Color(0xFF2E222C),
    onTertiaryContainer  = Color(0xFFE8D3E2),
)

private val LightColors = lightColorScheme(
    primary              = FieldJournalLight.primary,
    onPrimary            = Color.White,
    secondary            = FieldJournalLight.mapBlue,
    onSecondary          = Color.White,
    tertiary             = FieldJournalLight.mauve,
    onTertiary           = Color.White,
    error                = FieldJournalLight.danger,
    onError              = Color.White,
    errorContainer       = Color(0xFFFFDAD4),
    onErrorContainer     = Color(0xFF73342A),
    background           = FieldJournalLight.bg,
    onBackground         = FieldJournalLight.text,
    surface              = FieldJournalLight.bgCard,
    onSurface            = FieldJournalLight.text,
    surfaceVariant       = FieldJournalLight.bgElevated,
    onSurfaceVariant     = FieldJournalLight.textMuted,
    outline              = FieldJournalLight.border,
    outlineVariant       = FieldJournalLight.borderStrong,
    primaryContainer     = Color(0xFFFFE0C8),
    onPrimaryContainer   = Color(0xFF6B3212),
    secondaryContainer   = Color(0xFFD7ECF2),
    onSecondaryContainer = Color(0xFF143842),
    tertiaryContainer    = Color(0xFFF2E3EE),
    onTertiaryContainer  = Color(0xFF4A2F46),
)

// Radii — crisp plates, not pillowy M3 defaults. Mirrors the web's --radius-* tightening.
private val RoamlyShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small      = RoundedCornerShape(8.dp),
    medium     = RoundedCornerShape(10.dp),
    large      = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

private val RoamlyTypography = Typography().run {
    copy(
        displayLarge   = displayLarge.copy(fontFamily = FrauncesFamily, fontWeight = FontWeight.Bold),
        displayMedium  = displayMedium.copy(fontFamily = FrauncesFamily, fontWeight = FontWeight.Bold),
        displaySmall   = displaySmall.copy(fontFamily = FrauncesFamily, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineLarge  = headlineLarge.copy(fontFamily = FrauncesFamily, fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontFamily = FrauncesFamily, fontWeight = FontWeight.Bold),
        headlineSmall  = headlineSmall.copy(fontFamily = FrauncesFamily, fontWeight = FontWeight.Bold),
        titleLarge     = titleLarge.copy(fontFamily = FrauncesFamily, fontWeight = FontWeight.SemiBold),
        // titleMedium/titleSmall back nearly every section header in the app (ClaySectionHeader,
        // TripSections.SectionHeader, StatsScreen.SectionHeader, MapScreen.ResultHeader) — Fraunces
        // here is what makes those read as editorial headings rather than body copy.
        titleMedium    = titleMedium.copy(fontFamily = FrauncesFamily, fontWeight = FontWeight.SemiBold),
        titleSmall     = titleSmall.copy(fontFamily = FrauncesFamily, fontWeight = FontWeight.SemiBold),
        bodyLarge      = bodyLarge.copy(fontFamily = PlexSansFamily),
        bodyMedium     = bodyMedium.copy(fontFamily = PlexSansFamily),
        bodySmall      = bodySmall.copy(fontFamily = PlexSansFamily),
        labelLarge     = labelLarge.copy(fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold),
        labelMedium    = labelMedium.copy(fontFamily = PlexSansFamily, fontWeight = FontWeight.Medium),
        labelSmall     = labelSmall.copy(fontFamily = PlexSansFamily, fontWeight = FontWeight.Medium),
    )
}

/**
 * Extra Field Journal values M3's ColorScheme has no role for: the earthy
 * categorical accents (moss/rust/ochre/mauve/mapBlue, matching the web's
 * clay-mint/coral/amber/lavender/sky tokens) plus success/warning status
 * colors. Read via [Clay.colors] instead of importing raw Color constants,
 * so every consumer stays theme- (and light/dark-) aware.
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
    val moss: Color,
    val rust: Color,
    val ochre: Color,
    val mauve: Color,
    val mapBlue: Color,
    val success: Color,
    val warning: Color,
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
    surfaceTop = FieldJournalDark.bgCard,
    surfaceBottom = FieldJournalDark.bgCard,
    shadowDark = Color.Transparent,
    shadowLight = Color.Transparent,
    backgroundBrush = Brush.linearGradient(listOf(FieldJournalDark.bg, FieldJournalDark.bg)),
    primaryGradient = listOf(FieldJournalDark.primary, FieldJournalDark.rust),
    secondaryGradient = listOf(FieldJournalDark.mapBlue, FieldJournalDark.moss),
    tertiaryGradient = listOf(FieldJournalDark.mauve, FieldJournalDark.ochre),
    moss = FieldJournalDark.moss,
    rust = FieldJournalDark.rust,
    ochre = FieldJournalDark.ochre,
    mauve = FieldJournalDark.mauve,
    mapBlue = FieldJournalDark.mapBlue,
    success = FieldJournalDark.success,
    warning = FieldJournalDark.warning,
)

private val LightClay = ClayColors(
    isDark = false,
    surfaceTop = FieldJournalLight.bgCard,
    surfaceBottom = FieldJournalLight.bgCard,
    shadowDark = Color.Transparent,
    shadowLight = Color.Transparent,
    backgroundBrush = Brush.linearGradient(listOf(FieldJournalLight.bg, FieldJournalLight.bg)),
    primaryGradient = listOf(FieldJournalLight.primary, FieldJournalLight.rust),
    secondaryGradient = listOf(FieldJournalLight.mapBlue, FieldJournalLight.moss),
    tertiaryGradient = listOf(FieldJournalLight.mauve, FieldJournalLight.ochre),
    moss = FieldJournalLight.moss,
    rust = FieldJournalLight.rust,
    ochre = FieldJournalLight.ochre,
    mauve = FieldJournalLight.mauve,
    mapBlue = FieldJournalLight.mapBlue,
    success = FieldJournalLight.success,
    warning = FieldJournalLight.warning,
)

/**
 * Field Journal is a fixed brand palette (matching the web app exactly),
 * not per-device Material You — dynamic color is deliberately not used.
 */
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
