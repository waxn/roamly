package com.roamly.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Claymorphism design kit.
 *
 * The look is built from three ingredients applied consistently everywhere:
 *  1. a soft *dark* drop shadow offset down-right (the object casts onto the page)
 *  2. a soft *light* shadow offset up-left (the page bounces light back)
 *  3. a vertical gradient fill (lighter at the top) + a 1px top highlight
 * Together they read as a puffy, moulded surface rather than a flat rectangle.
 */

/**
 * The core clay shadow modifier — a soft, rounded drop shadow behind whatever it's
 * applied to. Uses Compose's [shadow] (GPU-rendered, always soft and perfectly
 * rounded) tinted with the clay shadow colour. Use a smaller [depth] for inline
 * rows, larger for hero cards. The top "light" highlight is provided separately by
 * the 1px gradient border on ClaySurface, so [shadowLight]/[drawLight] are kept
 * only for call-site compatibility.
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.claySoftShadow(
    cornerRadius: Dp,
    shadowDark: Color,
    shadowLight: Color,
    depth: Dp = 14.dp,
    drawLight: Boolean = true,
): Modifier = this.shadow(
    elevation = depth * 0.7f,
    shape = RoundedCornerShape(cornerRadius),
    clip = false,
    ambientColor = shadowDark.copy(alpha = (shadowDark.alpha * 1.4f).coerceAtMost(1f)),
    spotColor = shadowDark.copy(alpha = (shadowDark.alpha * 1.4f).coerceAtMost(1f)),
)

/**
 * A moulded clay surface — the most-used building block (cards, panels, chips).
 * Exposes a [BoxScope] so callers can align content freely.
 */
@Composable
fun ClaySurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    depth: Dp = 14.dp,
    gradient: List<Color>? = null,
    highlight: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val clay = Clay.colors
    val shape = RoundedCornerShape(cornerRadius)
    val fill = gradient ?: listOf(clay.surfaceTop, clay.surfaceBottom)
    Box(
        modifier = modifier
            .claySoftShadow(cornerRadius, clay.shadowDark, clay.shadowLight, depth)
            .clip(shape)
            .background(Brush.verticalGradient(fill))
            .then(
                if (highlight) Modifier.border(
                    BorderStroke(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (clay.isDark) 0.06f else 0.7f),
                                Color.Transparent,
                            )
                        )
                    ),
                    shape,
                ) else Modifier
            ),
        content = content,
    )
}

/**
 * A clay card: a [ClaySurface] with standard padding and an optional press
 * animation when [onClick] is supplied (squishes like real clay).
 */
@Composable
fun ClayCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    depth: Dp = 14.dp,
    contentPadding: Dp = 18.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(), label = "clayPress")

    val clickable = if (onClick != null) {
        Modifier
            .scale(scale)
            .clickable(interactionSource = interaction, indication = ripple(), onClick = onClick)
    } else Modifier

    ClaySurface(
        modifier = modifier.then(clickable),
        cornerRadius = cornerRadius,
        depth = depth,
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * A puffy gradient button. Squishes on press. Defaults to the coral brand gradient.
 */
@Composable
fun ClayButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradient: List<Color>? = null,
    enabled: Boolean = true,
    cornerRadius: Dp = 20.dp,
    contentColor: Color = Color.White,
    content: @Composable RowScope.() -> Unit,
) {
    val clay = Clay.colors
    val colors = gradient ?: clay.primaryGradient
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.96f else 1f, spring(), label = "btnPress")
    val shape = RoundedCornerShape(cornerRadius)
    val alpha = if (enabled) 1f else 0.5f

    Box(
        modifier = modifier
            .scale(scale)
            .graphicsLayer { this.alpha = alpha }
            .claySoftShadow(
                cornerRadius,
                colors.last().copy(alpha = if (clay.isDark) 0.45f else 0.40f),
                Color.Transparent,
                depth = 12.dp,
                drawLight = false,
            )
            .clip(shape)
            .background(Brush.verticalGradient(colors))
            .border(
                BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent))),
                shape,
            )
            .clickable(interactionSource = interaction, indication = ripple(color = Color.White), enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                ProvideTextStyle(MaterialTheme.typography.titleSmall) { content() }
            }
        }
    }
}

/**
 * A rounded gradient "badge" holding an icon — the little 3D chip of colour used
 * as the visual anchor of section headers, list rows and the app logo.
 */
@Composable
fun ClayIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    gradient: List<Color>? = null,
    size: Dp = 44.dp,
    cornerRadius: Dp = 14.dp,
    iconTint: Color = Color.White,
    contentDescription: String? = null,
) {
    val clay = Clay.colors
    val colors = gradient ?: clay.primaryGradient
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .claySoftShadow(cornerRadius, colors.last().copy(alpha = 0.40f), Color.Transparent, depth = 9.dp, drawLight = false)
            .clip(shape)
            .background(Brush.verticalGradient(colors))
            .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.4f), Color.Transparent))), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = iconTint, modifier = Modifier.size(size * 0.5f))
    }
}

/** A section header: gradient icon badge + title, optional trailing slot. */
@Composable
fun ClaySectionHeader(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    gradient: List<Color>? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClayIconBadge(icon = icon, gradient = gradient, size = 34.dp, cornerRadius = 11.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
    }
}
