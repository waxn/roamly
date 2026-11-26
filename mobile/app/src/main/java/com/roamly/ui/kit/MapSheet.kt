package com.roamly.ui.kit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleShape
import kotlinx.coroutines.launch

/** The sheet's collapsed content area (handle and figures), above the nav bar. */
val MapSheetPeek = 116.dp

/**
 * The map's one persistent bottom sheet: a collapsed peek that is always on
 * screen, and an expanded body that holds everything else.
 *
 * Two detents (peek / expanded) rather than three: it is a plain drag on the
 * header with a settling spring, so it needs no experimental anchored-drag API.
 * Only the header (handle + [peek]) is draggable; the body scrolls by itself,
 * so the two gestures never fight. Back collapses an open sheet first.
 *
 * Place it last in a `Box`, aligned to the bottom. [peekHeight] is the visible
 * height when collapsed, including the navigation bar.
 */
@Composable
fun MapSheet(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    peekHeight: Dp,
    modifier: Modifier = Modifier,
    expandedFraction: Float = 0.62f,
    peek: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val c = Atlas.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val expandedHeight = maxOf(peekHeight + 120.dp, screenH * expandedFraction)

    val progress = remember { Animatable(if (expanded) 1f else 0f) }
    LaunchedEffect(expanded) { progress.animateTo(if (expanded) 1f else 0f, AtlasMotion.glide()) }

    BackHandler(enabled = expanded) { onExpandedChange(false) }

    val rangePx = with(density) { (expandedHeight - peekHeight).toPx() }.coerceAtLeast(1f)
    val drag = rememberDraggableState { delta ->
        scope.launch { progress.snapTo((progress.value - delta / rangePx).coerceIn(0f, 1f)) }
    }
    val height = peekHeight + (expandedHeight - peekHeight) * progress.value
    val shape = SquircleShape(topStart = 28.dp, topEnd = 28.dp)

    Column(
        modifier
            .fillMaxWidth()
            .height(height)
            .shadow(8.dp, shape, ambientColor = c.shadow, spotColor = c.shadow)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape),
    ) {
        Column(
            Modifier.fillMaxWidth().draggable(
                state = drag,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    val target = when {
                        velocity < -600f -> true
                        velocity > 600f -> false
                        else -> progress.value > 0.5f
                    }
                    if (target != expanded) haptics.select()
                    // Settle here too: if the detent did not change, nothing else will.
                    scope.launch { progress.animateTo(if (target) 1f else 0f, AtlasMotion.glide()) }
                    onExpandedChange(target)
                },
            ),
        ) {
            Box(
                Modifier.fillMaxWidth().height(22.dp)
                    .tappable(feel = Feel.None, pressedScale = 1f) {
                        haptics.select(); onExpandedChange(!expanded)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(SquircleShape(50)).background(c.lineStrong))
            }
            peek()
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = peekHeight - MapSheetPeek + 16.dp),
            content = body,
        )
    }
}
