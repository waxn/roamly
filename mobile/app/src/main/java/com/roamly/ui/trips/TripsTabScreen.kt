package com.roamly.ui.trips

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roamly.ui.kit.Dot
import com.roamly.ui.kit.Eyebrow
import com.roamly.ui.kit.IconTile
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PanelTone
import com.roamly.ui.kit.ScreenHeader
import com.roamly.ui.kit.SegmentedSwitch
import com.roamly.ui.theme.Atlas

/**
 * The Trips tab: shared Adventures and the private Journal behind one gliding
 * switch, with the featured trip blog (when there is one) as a live card on
 * top. Slots, not screens: RoamlyNavHost owns the ViewModel scoping.
 */
@Composable
fun TripsTabScreen(
    featuredBlogName: String?,
    onOpenBlog: () -> Unit,
    trips: @Composable () -> Unit,
    journal: @Composable () -> Unit,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Atlas.colors.bg)) {
        ScreenHeader(title = if (page == 0) "Adventures" else "Journal", eyebrow = "Trips")
        SegmentedSwitch(
            options = listOf("Adventures", "Journal"),
            selected = page,
            onSelect = { page = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        )
        if (featuredBlogName != null) {
            FeaturedBlogCard(featuredBlogName, onOpenBlog, Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
        }
        Box(Modifier.weight(1f).padding(top = 8.dp)) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { it / 6 * dir } + fadeIn()) togetherWith (slideOutHorizontally { -it / 6 * dir } + fadeOut())
                },
                label = "tripsPage",
            ) { p -> if (p == 0) trips() else journal() }
        }
    }
}

/** The trip being blogged right now: one tap into its feed. */
@Composable
fun FeaturedBlogCard(name: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = Atlas.colors
    Panel(modifier.fillMaxWidth(), tone = PanelTone.Accent, padding = PaddingValues(14.dp), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Public, tint = c.accent, filled = true, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(c.accent, size = 6.dp, pulse = true)
                    Spacer(Modifier.width(6.dp))
                    Eyebrow("Live trip blog")
                }
                Text(name.ifBlank { "Trip blog" }, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = c.accent)
        }
    }
}
