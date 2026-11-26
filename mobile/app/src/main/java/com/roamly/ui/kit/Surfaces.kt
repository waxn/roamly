package com.roamly.ui.kit

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape

enum class PanelTone { Raised, Sunken, Glass, Accent, Outline }

/**
 * The card. [PanelTone.Raised] is a surface step above the page with a
 * hairline; [PanelTone.Glass] floats over the map with a soft shadow;
 * [PanelTone.Sunken] is a well inside another panel; [PanelTone.Accent] a
 * tinted call-out. Tappable when [onClick] is given.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    tone: PanelTone = PanelTone.Raised,
    radius: Dp = SquircleRadius.panel,
    padding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Atlas.colors
    val shape = SquircleShape(radius)
    val (fill, border) = when (tone) {
        PanelTone.Raised -> c.surface to c.line
        PanelTone.Sunken -> c.surfaceHigh to Color.Transparent
        PanelTone.Glass -> c.glass to c.lineStrong
        PanelTone.Accent -> c.accentSoft to c.accent.copy(alpha = 0.28f)
        PanelTone.Outline -> Color.Transparent to c.lineStrong
    }
    var m = modifier
    if (tone == PanelTone.Glass) m = m.shadow(18.dp, shape, ambientColor = c.shadow, spotColor = c.shadow)
    m = m.clip(shape).background(fill)
    if (border != Color.Transparent) m = m.border(1.dp, border, shape)
    if (onClick != null) m = m.tappable(pressedScale = 0.985f, onLongClick = onLongClick, onClick = onClick)
    Column(m.padding(padding), content = content)
}

/** A rounded square holding an icon, tinted with one of the categorical accents. */
@Composable
fun IconTile(
    icon: ImageVector,
    tint: Color = Atlas.colors.accent,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    filled: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(SquircleShape(size * 0.34f))
            .background(if (filled) tint else tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (filled) Atlas.colors.onAccent else tint, modifier = Modifier.size(iconSize))
    }
}

/** A small status/category tag. */
@Composable
fun Tag(
    text: String,
    color: Color = Atlas.colors.textMuted,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    solid: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(SquircleShape(8.dp))
            .background(if (solid) color else color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (solid) Atlas.colors.onAccent else color, modifier = Modifier.size(12.dp).padding(end = 0.dp))
            Box(Modifier.size(4.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (solid) Atlas.colors.onAccent else color,
            maxLines = 1,
        )
    }
}

/** A coloured dot, e.g. a legend swatch or a live indicator. */
@Composable
fun Dot(color: Color, size: Dp = 8.dp, pulse: Boolean = false) {
    if (!pulse) {
        Box(Modifier.size(size).clip(SquircleShape(50)).background(color))
        return
    }
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size).graphicsLayer { alpha = a }.clip(SquircleShape(50)).background(color))
}

/** A skeleton block that shimmers while content loads. */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = SquircleShape(SquircleRadius.control)) {
    val c = Atlas.colors
    val t = rememberInfiniteTransition(label = "skeleton")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300)), label = "x")
    Box(
        modifier
            .clip(shape)
            .background(c.surfaceHigh)
            .background(
                Brush.linearGradient(
                    colors = listOf(Color.Transparent, c.surfaceHighest.copy(alpha = 0.9f), Color.Transparent),
                    start = androidx.compose.ui.geometry.Offset(x * 600f, 0f),
                    end = androidx.compose.ui.geometry.Offset(x * 600f + 400f, 200f),
                )
            )
    )
}
