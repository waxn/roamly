package com.roamly.ui.groups

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.TripResponse
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasDialog
import com.roamly.ui.kit.AtlasField
import com.roamly.ui.kit.AtlasSheet
import com.roamly.ui.kit.EmptyState
import com.roamly.ui.kit.ErrorNote
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.RouteArt
import com.roamly.ui.kit.TrackSketch
import com.roamly.ui.kit.Skeleton
import com.roamly.ui.kit.Tag
import com.roamly.ui.kit.dockAwareBottom
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape
import com.roamly.ui.trips.TripsViewModel
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** The Adventures list: one card per trip, each with its own generated route art. */
@Composable
fun GroupsScreen(
    tripsViewModel: TripsViewModel = hiltViewModel(),
    onTripClick: (Int) -> Unit,
) {
    val state by tripsViewModel.uiState.collectAsState()
    val haptics = rememberHaptics()
    var pendingDelete by remember { mutableStateOf<TripResponse?>(null) }

    Box(Modifier.fillMaxSize()) {
        when {
            state.isLoading && state.trips.isEmpty() -> Column(
                Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { repeat(3) { Skeleton(Modifier.fillMaxWidth().height(200.dp), SquircleShape(SquircleRadius.panel)) } }
            state.trips.isEmpty() -> EmptyState(
                Icons.Rounded.Explore, "No adventures yet",
                body = "Start a trip to gather your route, photos and stories in one place — and share it with whoever came along.",
                action = "New trip", onAction = tripsViewModel::showCreateDialog,
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = dockAwareBottom(96.dp)),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(state.trips.distinctBy { it.id }, key = { it.id }) { trip ->
                    TripCard(trip, onClick = { onTripClick(trip.id) }, onLongClick = { pendingDelete = trip })
                }
            }
        }
        state.error?.let {
            ErrorNote(it, Modifier.align(Alignment.TopCenter).padding(horizontal = 20.dp), onRetry = tripsViewModel::loadTrips)
        }
        AnimatedVisibility(
            visible = state.trips.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = dockAwareBottom(16.dp)),
            enter = scaleIn() + fadeIn(),
        ) {
            AtlasButton("New trip", tripsViewModel::showCreateDialog, icon = Icons.Rounded.Add)
        }
    }

    if (state.showCreateDialog) {
        var name by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        AtlasSheet(onDismiss = tripsViewModel::hideCreateDialog, title = "New trip") {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                AtlasField(name, { name = it }, label = "Name", placeholder = "Lisbon long weekend")
                AtlasField(description, { description = it }, label = "Description", placeholder = "Optional", singleLine = false, minLines = 2)
                AtlasButton(
                    "Create trip",
                    { tripsViewModel.createTrip(name, description) },
                    Modifier.fillMaxWidth(),
                    enabled = name.isNotBlank(),
                    feel = Feel.Confirm,
                )
            }
        }
    }

    pendingDelete?.let { trip ->
        AtlasDialog(
            title = "Delete “${trip.name}”?",
            onDismiss = { pendingDelete = null },
            confirmText = "Delete",
            onConfirm = { haptics.confirm(); tripsViewModel.deleteTrip(trip.id); pendingDelete = null },
            destructive = true,
            icon = Icons.Rounded.DeleteOutline,
            body = { Text("The trip, its story and its day log go for everyone in it. Your location history stays.") },
        )
    }
}

@Composable
private fun TripCard(trip: TripResponse, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Atlas.colors
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp), onClick = onClick, onLongClick = onLongClick) {
        Box(Modifier.fillMaxWidth().height(116.dp).clip(SquircleShape(topStart = SquircleRadius.panel, topEnd = SquircleRadius.panel))) {
            val line = remember(trip.preview) { trip.preview.orEmpty().filter { it.size >= 2 }.map { it[0] to it[1] } }
            if (line.size >= 2) TrackSketch(listOf(line to c.accent), Modifier.fillMaxSize(), background = c.surfaceHigh)
            else RouteArt(trip.id, Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (trip.isPublic) Tag("Public", color = c.cyan, icon = Icons.Rounded.Public)
            }
        }
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(tripDates(trip.startTime, trip.endTime), style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            Spacer(Modifier.height(4.dp))
            Text(trip.name, style = MaterialTheme.typography.titleLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            trip.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Meta(Icons.Rounded.Place, "%,d points".format(trip.pointCount))
                Meta(Icons.Rounded.People, if (trip.memberCountResolved == 1) "Just you" else "${trip.memberCountResolved} people")
            }
        }
    }
}

@Composable
private fun Meta(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = Atlas.colors.textFaint)
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Atlas.colors.textMuted)
    }
}

private fun parseDay(iso: String?): LocalDate? {
    if (iso.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate() }
        .getOrElse { runCatching { LocalDate.parse(iso.take(10)) }.getOrNull() }
}

/** "12 – 18 MAR 2025 · 7 DAYS" */
internal fun tripDates(startIso: String?, endIso: String?): String {
    val s = parseDay(startIso) ?: return ""
    val e = parseDay(endIso) ?: s
    val days = ChronoUnit.DAYS.between(s, e) + 1
    val full = DateTimeFormatter.ofPattern("d MMM yyyy")
    val range = when {
        s == e -> s.format(full)
        s.year == e.year && s.month == e.month -> "${s.dayOfMonth} – ${e.format(full)}"
        s.year == e.year -> "${s.format(DateTimeFormatter.ofPattern("d MMM"))} – ${e.format(full)}"
        else -> "${s.format(full)} – ${e.format(full)}"
    }
    return if (days > 1) "$range · $days days" else range
}
