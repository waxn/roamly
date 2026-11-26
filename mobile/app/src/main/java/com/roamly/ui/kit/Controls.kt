package com.roamly.ui.kit

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape

enum class ButtonKind { Primary, Secondary, Ghost, Danger, Glass }

/** The app's button. Pill-shaped, springy, with a haptic and a loading state. */
@Composable
fun AtlasButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    compact: Boolean = false,
    feel: Feel = Feel.Click,
) {
    val c = Atlas.colors
    val (fill, content) = when (kind) {
        ButtonKind.Primary -> c.text to c.bg
        ButtonKind.Secondary -> c.surfaceHighest to c.text
        ButtonKind.Ghost -> Color.Transparent to c.text
        ButtonKind.Danger -> c.dangerSoft to c.danger
        ButtonKind.Glass -> c.glass to c.text
    }
    val active = enabled && !loading
    val shape = SquircleShape(SquircleRadius.control)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (enabled) fill else fill.copy(alpha = if (fill == Color.Transparent) 0f else 0.4f))
            .then(if (kind == ButtonKind.Glass) Modifier.border(1.dp, c.lineStrong, shape) else Modifier)
            .tappable(enabled = active, feel = feel, onClick = onClick)
            .heightIn(min = if (compact) 38.dp else 52.dp)
            .padding(horizontal = if (compact) 16.dp else 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val tint = if (enabled) content else content.copy(alpha = 0.45f)
        AnimatedContent(loading, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "btn") { isLoading ->
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(18.dp), color = tint, strokeWidth = 2.dp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Icon(icon, null, tint = tint, modifier = Modifier.size(if (compact) 16.dp else 19.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text,
                        style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A round icon button. [container] null = bare icon. */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Atlas.colors.text,
    container: Color? = Atlas.colors.surfaceHigh,
    size: Dp = 42.dp,
    iconSize: Dp = 20.dp,
    enabled: Boolean = true,
    feel: Feel = Feel.Click,
    bordered: Boolean = false,
) {
    val c = Atlas.colors
    val shape = SquircleShape(50)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .then(if (container != null) Modifier.background(container) else Modifier)
            .then(if (bordered) Modifier.border(1.dp, c.lineStrong, shape) else Modifier)
            .tappable(enabled = enabled, feel = feel, pressedScale = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(iconSize))
    }
}

/** A selectable chip. */
@Composable
fun AtlasChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Atlas.colors.accent,
) {
    val c = Atlas.colors
    val bg by animateColorAsState(if (selected) tint.copy(alpha = 0.18f) else c.surfaceHigh, label = "chipBg")
    val fg by animateColorAsState(if (selected) tint else c.textMuted, label = "chipFg")
    val border by animateColorAsState(if (selected) tint.copy(alpha = 0.55f) else Color.Transparent, label = "chipBorder")
    val shape = SquircleShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .tappable(feel = Feel.Select, pressedScale = 0.92f, role = Role.Checkbox, onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/**
 * A segmented control with an indicator that glides between segments.
 * Every segment gets an equal share of the width.
 */
@Composable
fun SegmentedSwitch(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    glass: Boolean = false,
) {
    val c = Atlas.colors
    val haptics = rememberHaptics()
    val shape = SquircleShape(50)
    BoxWithConstraints(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .background(if (glass) c.glass else c.surfaceHigh)
            .then(if (glass) Modifier.border(1.dp, c.lineStrong, shape) else Modifier)
            .padding(4.dp),
    ) {
        val count = options.size.coerceAtLeast(1)
        val segW = maxWidth / count
        val x by animateDpAsState(segW * selected.coerceIn(0, count - 1), AtlasMotion.glide(), label = "seg")
        Box(
            Modifier
                .offset(x = x)
                .width(segW)
                .fillMaxHeight()
                .shadow(4.dp, shape, ambientColor = c.shadow, spotColor = c.shadow)
                .clip(shape)
                .background(if (c.isDark) c.surfaceHighest else c.surface)
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, label ->
                val fg by animateColorAsState(if (i == selected) c.text else c.textMuted, label = "segFg")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .tappable(feel = Feel.None, pressedScale = 0.95f, role = Role.Tab) {
                            if (i != selected) { haptics.select(); onSelect(i) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** A switch drawn in the app's own style, with a springy thumb and a toggle haptic. */
@Composable
fun AtlasSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = Atlas.colors
    val haptics = rememberHaptics()
    val track by animateColorAsState(if (checked) c.accent else c.surfaceHighest, label = "track")
    val x by animateDpAsState(if (checked) 20.dp else 0.dp, AtlasMotion.snappy(), label = "thumb")
    val thumbSize by animateDpAsState(if (checked) 22.dp else 18.dp, AtlasMotion.snappy(), label = "thumbSize")
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 28.dp)
            .clip(SquircleShape(50))
            .background(if (enabled) track else track.copy(alpha = 0.4f))
            .tappable(enabled = enabled, feel = Feel.None, pressedScale = 0.92f, role = Role.Switch) {
                haptics.toggle(!checked); onCheckedChange(!checked)
            }
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = x + (22.dp - thumbSize) / 2)
                .size(thumbSize)
                .shadow(2.dp, SquircleShape(50))
                .clip(SquircleShape(50))
                .background(if (checked) c.onAccent.takeIf { !c.isDark } ?: Color.White else if (c.isDark) c.textMuted else Color.White)
        )
    }
}

/** A text field in the app's style. */
@Composable
fun AtlasField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leading: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val c = Atlas.colors
    Column(modifier) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.textMuted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder?.let { { Text(it, color = c.textFaint) } },
            leadingIcon = leading?.let { { Icon(it, null, tint = c.textMuted, modifier = Modifier.size(20.dp)) } },
            trailingIcon = trailing,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            enabled = enabled,
            isError = isError,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = SquircleShape(SquircleRadius.control),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = c.surfaceHigh,
                unfocusedContainerColor = c.surfaceHigh,
                disabledContainerColor = c.surfaceHigh.copy(alpha = 0.5f),
                errorContainerColor = c.dangerSoft,
                focusedBorderColor = c.accent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                errorBorderColor = c.danger,
                cursorColor = c.accent,
                focusedTextColor = c.text,
                unfocusedTextColor = c.text,
            ),
        )
        if (supportingText != null) {
            Text(
                supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) c.danger else c.textMuted,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }
    }
}

/** A horizontal strip of chips that scrolls. */
@Composable
fun ChipRow(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Label + value used in a small info grid (point details, flight details). */
@Composable
fun InfoPair(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Atlas.colors.textFaint)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            style = if (mono) com.roamly.ui.theme.AtlasType.mono.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize) else MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = Atlas.colors.text,
        )
    }
}
