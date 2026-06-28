package com.roamly.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material 3 design kit wrappers.
 *
 * The clay shadow/gradient look has been replaced by flat M3 surfaces that
 * inherit their color from the system dynamic palette. The external API of each
 * composable is kept the same so call sites need minimal updates.
 */

/** No-op — shadows are gone in the flat M3 design. Kept for call-site compatibility. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.claySoftShadow(
    cornerRadius: Dp,
    shadowDark: Color,
    shadowLight: Color,
    depth: Dp = 14.dp,
    drawLight: Boolean = true,
): Modifier = this  // intentionally a no-op

/**
 * A container surface — now just an M3 [ElevatedCard] so it picks up
 * tonal elevation and system color automatically.
 */
@Composable
fun ClaySurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    depth: Dp = 2.dp,
    gradient: List<Color>? = null,
    highlight: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = depth.coerceAtMost(4.dp)),
    ) {
        Box(content = content)
    }
}

/**
 * A card with standard padding. Clickable variant shows a ripple.
 */
@Composable
fun ClayCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    depth: Dp = 2.dp,
    contentPadding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(cornerRadius),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    } else {
        ElevatedCard(
            modifier = modifier,
            shape = RoundedCornerShape(cornerRadius),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}

/**
 * A button mapped to the appropriate M3 style based on the gradient hint:
 * - red/destructive gradient → error-colored Button
 * - any gradient → FilledTonalButton (inherits secondary container color)
 * - null → FilledTonalButton
 */
@Composable
fun ClayButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradient: List<Color>? = null,
    enabled: Boolean = true,
    cornerRadius: Dp = 16.dp,
    contentColor: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit,
) {
    val isDestructive = gradient?.any { c -> c.red > 0.75f && c.green < 0.4f && c.blue < 0.4f } == true
    val colors = if (isDestructive) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        )
    } else {
        ButtonDefaults.filledTonalButtonColors()
    }
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(cornerRadius),
        colors = colors,
    ) {
        content()
    }
}

/**
 * A small icon container — now uses M3 secondary/tertiary container colors
 * instead of a gradient. The gradient hint picks the container color:
 * primary-ish → primaryContainer, secondary-ish → secondaryContainer,
 * tertiary-ish → tertiaryContainer.
 */
@Composable
fun ClayIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    gradient: List<Color>? = null,
    size: Dp = 44.dp,
    cornerRadius: Dp = 12.dp,
    iconTint: Color = Color.Unspecified,
    contentDescription: String? = null,
) {
    val clay = Clay.colors
    val containerColor = when {
        gradient === clay.secondaryGradient -> MaterialTheme.colorScheme.secondaryContainer
        gradient === clay.tertiaryGradient  -> MaterialTheme.colorScheme.tertiaryContainer
        else                               -> MaterialTheme.colorScheme.primaryContainer
    }
    val resolvedTint = if (iconTint != Color.Unspecified) iconTint
    else when {
        gradient === clay.secondaryGradient -> MaterialTheme.colorScheme.onSecondaryContainer
        gradient === clay.tertiaryGradient  -> MaterialTheme.colorScheme.onTertiaryContainer
        else                               -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = resolvedTint,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** Section header: icon badge + title, optional trailing slot. */
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
        ClayIconBadge(icon = icon, gradient = gradient, size = 32.dp, cornerRadius = 10.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
    }
}
