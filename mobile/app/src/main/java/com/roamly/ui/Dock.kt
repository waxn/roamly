package com.roamly.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.RadioButtonChecked
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

private val SLOT = 66.dp
private val RECORD_EXTRA = 6.dp

/**
 * The floating dock: a frosted pill hovering over the content, with the tabs
 * either side of a raised Record button. A soft indicator glides to the
 * selected tab; switching ticks the haptics; the Record button pulses while an
 * activity is being recorded.
 */
@Composable
internal fun Dock(
    visible: Boolean,
    tabs: List<Tab>,
    selected: Tab?,
    showRecord: Boolean,
    recording: Boolean,
    onSelect: (Tab) -> Unit,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Atlas.colors
    val haptics = rememberHaptics()
    // Slots left to right: tabs, with the record button in the middle.
    val recordAt = if (showRecord) tabs.size / 2 else -1
    val slots: List<Tab?> = buildList {
        tabs.forEachIndexed { i, t ->
            if (i == recordAt) add(null)
            add(t)
        }
    }
    val selectedSlot = slots.indexOf(selected).coerceAtLeast(0)

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(AtlasMotion.glide()) { it * 2 } + fadeIn(),
        exit = slideOutVertically(tween(180)) { it * 2 } + fadeOut(tween(120)),
    ) {
        val shape = SquircleShape(50)
        Box(
            Modifier
                .navigationBarsPadding()
                .padding(bottom = DOCK_MARGIN)
                .shadow(6.dp, shape, ambientColor = c.shadow.copy(alpha = 0.3f), spotColor = c.shadow.copy(alpha = 0.3f))
                .clip(shape)
                .background(c.glass)
                .border(1.dp, c.lineStrong, shape)
                .height(DOCK_HEIGHT)
                .padding(horizontal = 6.dp),
        ) {
            // The record slot is 6dp wider than a tab slot; tabs after it shift by that.
            val target = SLOT * selectedSlot + if (recordAt in 0 until selectedSlot) RECORD_EXTRA else 0.dp
            val x by animateDpAsState(target, AtlasMotion.glide(), label = "dockIndicator")
            if (selected != null) {
                Box(
                    Modifier
                        .offset(x = x + 5.dp)
                        .align(Alignment.CenterStart)
                        .size(width = SLOT - 10.dp, height = DOCK_HEIGHT - 14.dp)
                        .clip(SquircleShape(50))
                        .background(c.accentSoft)
                )
            }
            Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                slots.forEach { tab ->
                    if (tab == null) {
                        RecordButton(recording) { haptics.thud(); onRecord() }
                    } else {
                        DockItem(tab, tab == selected) {
                            if (tab != selected) haptics.select()
                            onSelect(tab)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DockItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val c = Atlas.colors
    val tint by animateColorAsState(if (selected) c.accent else c.textMuted, label = "dockTint")
    val lift by animateFloatAsState(if (selected) 1.1f else 1f, AtlasMotion.snappy(), label = "dockLift")
    Column(
        Modifier
            .width(SLOT)
            .fillMaxHeight()
            .clip(SquircleShape(50))
            .tappable(feel = Feel.None, pressedScale = 0.86f, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected; contentDescription = tab.label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(
            tab.icon, null, tint = tint,
            modifier = Modifier.size(24.dp).graphicsLayer { scaleX = lift; scaleY = lift },
        )
        Spacer(Modifier.height(3.dp))
        Text(tab.label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    val c = Atlas.colors
    val pulse = rememberInfiniteTransition(label = "rec")
    val ring by pulse.animateFloat(
        initialValue = 1f, targetValue = if (recording) 1.28f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ring",
    )
    Box(Modifier.width(SLOT + RECORD_EXTRA).fillMaxHeight(), contentAlignment = Alignment.Center) {
        if (recording) {
            Box(
                Modifier.size(50.dp).graphicsLayer { scaleX = ring; scaleY = ring; alpha = 2f - ring * 1.3f }
                    .clip(SquircleShape(50)).background(c.danger.copy(alpha = 0.35f))
            )
        }
        Box(
            Modifier
                .size(52.dp)
                .shadow(2.dp, SquircleShape(50))
                .clip(SquircleShape(50))
                .background(
                    if (recording) Brush.linearGradient(listOf(c.danger, c.danger))
                    else Brush.linearGradient(listOf(c.record, c.record))
                )
                .tappable(feel = Feel.None, pressedScale = 0.85f, onClick = onClick)
                .semantics { contentDescription = if (recording) "Recording — open" else "Record an activity" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (recording) Icons.Rounded.RadioButtonChecked else Icons.Rounded.FiberManualRecord,
                null, tint = if (recording) androidx.compose.ui.graphics.Color.White else c.onRecord, modifier = Modifier.size(24.dp),
            )
        }
    }
}
