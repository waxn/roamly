package com.roamly.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// Matched to the darker website palette.
private val Primary        = Color(0xFF3B82F6)
private val Secondary      = Color(0xFF22C55E)
private val ErrorRed       = Color(0xFFEF4444)

private val DarkBackground     = Color(0xFF05070B)
private val DarkSurface        = Color(0xFF0D1017)
private val DarkSurfaceVariant = Color(0xFF141A24)
private val DarkOnBackground   = Color(0xFFE7EAF0)
private val DarkMuted          = Color(0xFF8A90A6)
private val DarkOutline        = Color(0xFF202736)

private val LightBackground     = Color(0xFFF3F4F8)
private val LightSurface        = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFE7EAF0)
private val LightOnBackground   = Color(0xFF171A22)
private val LightMuted          = Color(0xFF626774)
private val LightOutline        = Color(0xFFCFD4E1)

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
    secondaryContainer   = Color(0xFF122417),
    onSecondaryContainer = Color(0xFF8CF0B0),
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
    secondaryContainer   = Color(0xFFDFFAE8),
    onSecondaryContainer = Color(0xFF14532D),
)

private val RoamlyShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun RoamlyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = RoamlyShapes,
        content = content
    )
}
