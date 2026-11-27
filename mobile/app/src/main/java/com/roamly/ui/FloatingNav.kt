package com.roamly.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roamly.ui.kit.AtlasMotion
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.kit.tappable
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleShape

/**
 * The floating bar: a frosted pill hovering at the bottom with one slot per
 * page. A soft indicator glides to the current page and switching ticks the
 * haptics. Shown only on the root pages.
 */
@Composable
internal fun FloatingNav(
    visible: Boolean,
    tabs: List<Tab>,
    selected: Tab?,
    recording: Boolean,
    onSelect: (Tab) -> Unit,
    onRecord: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = Atlas.colors
    val haptics = rememberHaptics()
    // The record button sits in the middle slot between the two halves of the tabs.
    val centre = if (onRecord != null) tabs.size / 2 else -1
    val slots = tabs.size + if (centre >= 0) 1 else 0
    val tabIndex = tabs.indexOf(selected).coerceAtLeast(0)
    val selectedSlot = if (centre in 0..tabIndex) tabIndex + 1 else tabIndex

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(AtlasMotion.glide()) { it * 2 } + fadeIn(),
        exit = slideOutVertically(tween(160)) { it * 2 } + fadeOut(tween(100)),
    ) {
        val shape = SquircleShape(50)
        Box(
            Modifier
                .navigationBarsPadding()
                .padding(start = DOCK_SIDE, end = DOCK_SIDE, bottom = DOCK_MARGIN)
                .fillMaxWidth()
                .shadow(8.dp, shape, ambientColor = c.shadow.copy(alpha = 0.35f), spotColor = c.shadow.copy(alpha = 0.35f))
                .clip(shape)
                .background(c.glass)
                .border(1.dp, c.lineStrong, shape)
                .height(DOCK_HEIGHT)
                .padding(horizontal = 6.dp),
        ) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val slot = maxWidth / slots.coerceAtLeast(1)
                val x by animateDpAsState(slot * selectedSlot, AtlasMotion.glide(), label = "navIndicator")
                if (selected != null) {
                    Box(
                        Modifier
                            .offset(x = x + 5.dp)
                            .align(Alignment.CenterStart)
                            .size(width = slot - 10.dp, height = DOCK_HEIGHT - 14.dp)
                            .clip(SquircleShape(50))
                            .background(c.accentSoft)
                    )
                }
            }
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { i, tab ->
                    if (i == centre && onRecord != null) {
                        RecordSlot(recording, Modifier.weight(1f)) { haptics.thud(); onRecord() }
                    }
                    NavItem(tab, tab == selected, live = recording && tab == Tab.Record, Modifier.weight(1f)) {
                        if (tab != selected) haptics.select()
                        onSelect(tab)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavItem(tab: Tab, selected: Boolean, live: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Atlas.colors
    val tint by animateColorAsState(if (selected) c.accent else c.textMuted, label = "navTint")
    val lift by animateFloatAsState(if (selected) 1.08f else 1f, AtlasMotion.snappy(), label = "navLift")
    Column(
        modifier
            .fillMaxHeight()
            .clip(SquircleShape(50))
            .tappable(feel = Feel.None, pressedScale = 0.88f, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected; contentDescription = tab.label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(tab.icon, null, tint = tint, modifier = Modifier.size(24.dp).graphicsLayer { scaleX = lift; scaleY = lift })
            // A small coral dot on the Activity page while something is being recorded.
            if (live) Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(SquircleShape(50)).background(c.record))
        }
        Spacer(Modifier.height(3.dp))
        Text(tab.label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

/** The centre of the dock: a round coral Record button, red and pulsing while a session is live. */
@Composable
private fun RecordSlot(recording: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Atlas.colors
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "rec")
    val ring by pulse.animateFloat(
        initialValue = 1f, targetValue = if (recording) 1.28f else 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            tween(900), androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "ring",
    )
    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        if (recording) {
            Box(
                Modifier.size(48.dp).graphicsLayer { scaleX = ring; scaleY = ring; alpha = 2f - ring * 1.3f }
                    .clip(SquircleShape(50)).background(c.danger.copy(alpha = 0.35f))
            )
        }
        Box(
            Modifier.size(48.dp)
                .clip(SquircleShape(50))
                .background(if (recording) c.danger else c.record)
                .tappable(feel = Feel.None, pressedScale = 0.88f, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = if (recording) "Recording — open" else "Record an activity" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (recording) Icons.Rounded.RadioButtonChecked
                else Icons.Rounded.FiberManualRecord,
                null, tint = if (recording) androidx.compose.ui.graphics.Color.White else c.onRecord,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
