package com.roamly.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Matched to web CSS variables
private val Primary        = Color(0xFF3B82F6)   // --primary
private val Secondary      = Color(0xFF22C55E)   // --success
private val ErrorRed       = Color(0xFFEF4444)   // --danger

private val DarkBackground     = Color(0xFF0F1117)   // --bg
private val DarkSurface        = Color(0xFF1A1D27)   // --bg-card
private val DarkSurfaceVariant = Color(0xFF252830)   // --bg-input
private val DarkOnBackground   = Color(0xFFE4E6EB)   // --text
private val DarkMuted          = Color(0xFF8B8FA3)   // --text-muted
private val DarkOutline        = Color(0xFF2D3040)   // --border

private val LightBackground     = Color(0xFFF5F5F7)
private val LightSurface        = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFE8E8ED)
private val LightOnBackground   = Color(0xFF1D1D1F)
private val LightMuted          = Color(0xFF6E6E73)
private val LightOutline        = Color(0xFFD1D1D6)

private val DarkColors = darkColorScheme(
    primary              = Primary,
    onPrimary            = Color.White,
    secondary            = Secondary,
    onSecondary          = Color.White,
    error                = ErrorRed,
    onError              = Color.White,
    background           = DarkBackground,
    onBackground         = DarkOnBackground,
    surface              = DarkSurface,
    onSurface            = DarkOnBackground,
    surfaceVariant       = DarkSurfaceVariant,
    onSurfaceVariant     = DarkMuted,
    outline              = DarkOutline,
    secondaryContainer   = Color(0xFF1A3A2A),
    onSecondaryContainer = Color(0xFF6EE7A0),
)

private val LightColors = lightColorScheme(
    primary              = Primary,
    onPrimary            = Color.White,
    secondary            = Secondary,
    onSecondary          = Color.White,
    error                = ErrorRed,
    onError              = Color.White,
    background           = LightBackground,
    onBackground         = LightOnBackground,
    surface              = LightSurface,
    onSurface            = LightOnBackground,
    surfaceVariant       = LightSurfaceVariant,
    onSurfaceVariant     = LightMuted,
    outline              = LightOutline,
    secondaryContainer   = Color(0xFFDCFCE7),
    onSecondaryContainer = Color(0xFF166534),
)

@Composable
fun RoamlyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
