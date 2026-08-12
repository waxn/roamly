package com.roamly.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Field Journal design kit — flat plates with hairline borders, no Material
 * elevation/shadow. Mirrors the web app's card/button treatment so the mobile
 * and web UI read as the same product.
 */

/** No-op — shadows are gone in the flat design. Kept for call-site compatibility. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.claySoftShadow(
    cornerRadius: Dp,
    shadowDark: Color,
    shadowLight: Color,
    depth: Dp = 14.dp,
    drawLight: Boolean = true,
): Modifier = this  // intentionally a no-op

/**
 * A flat, hairline-bordered container surface — no tonal elevation, no shadow.
 */
@Composable
fun ClaySurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 10.dp,
    depth: Dp = 0.dp,
    gradient: List<Color>? = null,
    highlight: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(content = content)
    }
}

/**
 * A card with standard padding — flat plate, 1dp hairline border. Clickable
 * variant shows a ripple.
 */
@Composable
fun ClayCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 10.dp,
    depth: Dp = 0.dp,
    contentPadding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = border,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = border,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}

/**
 * A filled button mapped by the gradient hint:
 * - red/destructive gradient → error-colored
 * - anything else → tonal (secondary container)
 * Always flat — zero elevation, matching the web's borderless filled buttons.
 */
@Composable
fun ClayButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradient: List<Color>? = null,
    enabled: Boolean = true,
    cornerRadius: Dp = 10.dp,
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
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (contentColor != Color.Unspecified) contentColor else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(cornerRadius),
        colors = colors,
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            disabledElevation = 0.dp,
        ),
    ) {
        content()
    }
}

/** A hairline-bordered, transparent-fill button — the flat outline variant. */
@Composable
fun ClayOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = 10.dp,
    contentColor: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(cornerRadius),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (contentColor != Color.Unspecified) contentColor else MaterialTheme.colorScheme.onSurface,
        ),
        content = content,
    )
}

/** A borderless text button, tinted with the primary trail-blaze orange. */
@Composable
fun ClayTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (contentColor != Color.Unspecified) contentColor else MaterialTheme.colorScheme.primary,
        ),
        content = content,
    )
}

/** A flat icon button — no ripple halo color surprises, just the theme's on-surface tint. */
@Composable
fun ClayIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (contentColor != Color.Unspecified) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        content = content,
    )
}

/**
 * A small icon container — flat container-color fill (no gradient).
 * The gradient hint picks the container color: primary-ish → primaryContainer,
 * secondary-ish → secondaryContainer, tertiary-ish → tertiaryContainer.
 */
@Composable
fun ClayIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    gradient: List<Color>? = null,
    size: Dp = 44.dp,
    cornerRadius: Dp = 10.dp,
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

/** Section header: icon badge + title, optional trailing slot. Title uses the Fraunces display type. */
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
        ClayIconBadge(icon = icon, gradient = gradient, size = 32.dp, cornerRadius = 8.dp)
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
