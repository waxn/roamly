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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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

private val SLOT = 76.dp

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
    modifier: Modifier = Modifier,
) {
    val c = Atlas.colors
    val haptics = rememberHaptics()
    val selectedSlot = tabs.indexOf(selected).coerceAtLeast(0)

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
                .padding(bottom = DOCK_MARGIN)
                .shadow(8.dp, shape, ambientColor = c.shadow.copy(alpha = 0.35f), spotColor = c.shadow.copy(alpha = 0.35f))
                .clip(shape)
                .background(c.glass)
                .border(1.dp, c.lineStrong, shape)
                .height(DOCK_HEIGHT)
                .padding(horizontal = 6.dp),
        ) {
            val x by animateDpAsState(SLOT * selectedSlot, AtlasMotion.glide(), label = "navIndicator")
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
                tabs.forEach { tab ->
                    NavItem(tab, tab == selected, live = recording && tab == Tab.Record) {
                        if (tab != selected) haptics.select()
                        onSelect(tab)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavItem(tab: Tab, selected: Boolean, live: Boolean, onClick: () -> Unit) {
    val c = Atlas.colors
    val tint by animateColorAsState(if (selected) c.accent else c.textMuted, label = "navTint")
    val lift by animateFloatAsState(if (selected) 1.08f else 1f, AtlasMotion.snappy(), label = "navLift")
    Column(
        Modifier
            .width(SLOT)
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
