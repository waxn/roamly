package com.roamly.ui.trips

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.Comment
import com.roamly.data.api.TimelineEvent
import com.roamly.data.api.TripLatLng
import com.roamly.data.api.TripResponse
import com.roamly.ui.groups.tripDates
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasDialog
import com.roamly.ui.kit.AtlasField
import com.roamly.ui.kit.AtlasSheet
import com.roamly.ui.kit.ButtonKind
import com.roamly.ui.kit.EmptyState
import com.roamly.ui.kit.ErrorNote
import com.roamly.ui.kit.Eyebrow
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.IconTile
import com.roamly.ui.kit.ListRow
import com.roamly.ui.kit.MEMBER_TRACK_COLORS
import com.roamly.ui.kit.MapViewHost
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PanelTone
import com.roamly.ui.kit.StatBlock
import com.roamly.ui.kit.TrackSketch
import com.roamly.ui.kit.avatarMarker
import com.roamly.ui.kit.fitTo
import com.roamly.ui.kit.rememberAtlasMapView
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.kit.tappable
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/**
 * An Adventure: a hero with the route sketched from the real points, then
 * the story, itinerary, day log and updates — with writing for all but the
 * story (block editing stays on the web).
 */
@Composable
fun TripDetailScreen(tripId: Int, onBack: () -> Unit, viewModel: TripDetailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val c = Atlas.colors
    val haptics = rememberHaptics()
    var showFullMap by remember { mutableStateOf(false) }
    var showAddDay by remember { mutableStateOf(false) }
    var deleteBlurb by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(tripId) { viewModel.load(tripId) }

    val trip = state.trip
    if (showFullMap && trip != null) {
        BackHandler { showFullMap = false }
        TripFullMapScreen(trip, onBack = { showFullMap = false })
        return
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        when {
            state.isLoading && trip == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (trip != null) {
                    item { Hero(trip, onOpenMap = { showFullMap = true }) }
                    if (trip.memberLocations.values.any { it.isNotEmpty() }) {
                        item { Box(Modifier.padding(horizontal = 20.dp)) { MemberTrackLegend(trip) } }
                    }
                    state.error?.let { item { ErrorNote(it, Modifier.padding(horizontal = 20.dp)) } }

                    if (trip.body.isNotEmpty()) {
                        item { Pad { SectionHeader("Story") } }
                        items(trip.body) { block ->
                            Pad { StoryBlock(block = block, places = trip.places, photos = trip.photos, serverUrl = state.serverUrl, onMapEmbedTap = { showFullMap = true }) }
                        }
                    }

                    item { Pad { SectionHeader("Itinerary", action = "Add stop", onAction = viewModel::newStop) } }
                    if (trip.plannedStops.isEmpty()) {
                        item { Pad { Text("No stops planned. Add where you're headed and when.", style = MaterialTheme.typography.bodySmall, color = c.textMuted) } }
                    }
                    itemsIndexed(trip.plannedStops, key = { _, s -> "stop-${s.id}" }) { i, stop ->
                        Pad {
                            ItineraryStopCard(
                                index = i + 1, stop = stop, last = i == trip.plannedStops.lastIndex,
                                onEdit = { viewModel.editStop(stop) },
                                onMoveUp = if (i > 0) ({ viewModel.moveStop(stop.id, up = true) }) else null,
                                onMoveDown = if (i < trip.plannedStops.lastIndex) ({ viewModel.moveStop(stop.id, up = false) }) else null,
                            )
                        }
                    }

                    item { Pad { SectionHeader("Day log", action = "Add entry", onAction = { showAddDay = true }) } }
                    if (trip.dayNotes.isEmpty()) {
                        item { Pad { Text("Nothing logged yet. Each person writes their own entry per day.", style = MaterialTheme.typography.bodySmall, color = c.textMuted) } }
                    }
                    trip.dayNotes.groupBy { it.date }.forEach { (date, notes) ->
                        item(key = "daylog-$date") {
                            Pad { Text(formatDayHeader(date), style = MaterialTheme.typography.labelSmall, color = c.textMuted, modifier = Modifier.padding(top = 4.dp)) }
                        }
                        items(notes, key = { "daynote-${it.id}" }) { note ->
                            Pad { DayLogEntryCard(note, state.serverUrl, onEdit = if (note.isMine) ({ viewModel.openDayNote(note) }) else null) }
                        }
                    }

                    item { Pad { SectionHeader("Updates") } }
                }
                if (state.events.isEmpty()) {
                    item { EmptyState(Icons.Rounded.ChatBubbleOutline, "No updates yet", body = "Post an update or mark a milestone with the + button.") }
                } else {
                    items(state.events, key = { "ev-${it.type}-${it.id}" }) { event ->
                        Pad {
                            if (event.type == "milestone") MilestoneCard(event)
                            else BlurbCard(
                                event = event,
                                serverUrl = state.serverUrl,
                                comments = state.comments[event.id],
                                isExpanded = state.expandedBlurbId == event.id,
                                onDelete = { deleteBlurb = event.id },
                                onToggleComments = { viewModel.toggleComments(event.id) },
                                onPostComment = { text -> viewModel.createComment(event.id, text) },
                                onDeleteComment = { cid -> viewModel.deleteComment(event.id, cid) },
                            )
                        }
                    }
                }
            }
        }

        // Floating top bar over the hero.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.Rounded.ArrowBack, "Back", onBack, container = c.glass, bordered = true)
            Spacer(Modifier.weight(1f))
            if (trip?.isCreator == true) {
                IconAction(Icons.Rounded.PersonAdd, "Invite", viewModel::openInvite, container = c.glass, bordered = true)
                Spacer(Modifier.width(8.dp))
            }
            IconAction(
                Icons.Rounded.Public, if (trip?.isPublic == true) "Make private" else "Make public",
                { haptics.toggle(trip?.isPublic != true); viewModel.togglePublic() },
                container = if (trip?.isPublic == true) c.accent else c.glass,
                tint = if (trip?.isPublic == true) c.onAccent else c.text, bordered = trip?.isPublic != true, feel = Feel.None,
            )
        }

        AtlasButton("Add", viewModel::showAddTypeDialog,
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 20.dp), icon = Icons.Rounded.Add)
    }

    // ── Sheets & dialogs ────────────────────────────────────────────────
    if (state.showAddTypeDialog) {
        AtlasSheet(onDismiss = viewModel::hideAddTypeDialog, title = "Add to the trip") {
            ListRow("Update", subtitle = "Text, a rating, photos or videos", icon = Icons.Rounded.ChatBubbleOutline, iconTint = c.accent,
                onClick = viewModel::showBlurbDialog, modifier = Modifier.padding(horizontal = 8.dp))
            ListRow("Milestone", subtitle = "Mark a moment on the timeline", icon = Icons.Rounded.EmojiEvents, iconTint = c.amber,
                onClick = viewModel::showMilestoneDialog, modifier = Modifier.padding(horizontal = 8.dp))
            ListRow("Day log entry", subtitle = "What you did on a day of the trip", icon = Icons.Rounded.Route, iconTint = c.cyan,
                onClick = { viewModel.hideAddTypeDialog(); showAddDay = true }, modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
    if (state.showBlurbDialog) {
        BlurbCreateDialog(onDismiss = viewModel::hideBlurbDialog, onPost = { text, title, rating, category, uris -> viewModel.createBlurb(text, title, rating, category, uris) })
    }
    if (state.showMilestoneDialog) {
        MilestoneCreateDialog(onDismiss = viewModel::hideMilestoneDialog, onConfirm = { emoji, title, desc, date -> viewModel.createMilestone(emoji, title, desc, date) })
    }
    if (showAddDay) {
        var date by remember { mutableStateOf(java.time.LocalDate.now().toString()) }
        AtlasDialog(
            title = "New day-log entry",
            onDismiss = { showAddDay = false },
            confirmText = "Create",
            onConfirm = { viewModel.startNewDayNote(date); showAddDay = false },
            body = { AtlasField(date, { date = it }, label = "Date", placeholder = "YYYY-MM-DD") },
        )
    }
    state.dayEditor?.let { editor ->
        DayNoteEditorDialog(
            editor = editor, places = state.trip?.places ?: emptyList(), serverUrl = state.serverUrl,
            onTitle = viewModel::editDayTitle, onBody = viewModel::editDayBody, onPlace = viewModel::editDayPlace,
            onAddPhotos = viewModel::addDayNotePhotos, onDeletePhoto = viewModel::deleteDayNotePhoto,
            onSave = viewModel::saveDayNote, onDelete = viewModel::deleteDayNote, onClose = viewModel::closeDayEditor,
        )
    }
    state.stopEditor?.let { stop ->
        StopEditorDialog(stop = stop, onSave = viewModel::saveStop, onDelete = if (stop.id != 0) ({ viewModel.deleteStop(stop.id) }) else null, onClose = viewModel::closeStopEditor)
    }
    if (state.showInviteSheet) {
        InviteSheet(
            inviteUrl = state.inviteUrl, members = state.trip?.members ?: emptyList(),
            onRotate = { viewModel.generateInvite(rotate = true) }, onRemoveMember = viewModel::removeMember, onClose = viewModel::closeInvite,
        )
    }
    deleteBlurb?.let { id ->
        AtlasDialog(
            title = "Delete this update?",
            onDismiss = { deleteBlurb = null },
            confirmText = "Delete",
            onConfirm = { viewModel.deleteBlurb(id); deleteBlurb = null },
            destructive = true, icon = Icons.Rounded.DeleteOutline,
            body = { Text("It's removed from the trip for everyone, with its comments.") },
        )
    }
}

@Composable
private fun Pad(content: @Composable () -> Unit) = Box(Modifier.padding(horizontal = 20.dp)) { content() }

@Composable
private fun Hero(trip: TripResponse, onOpenMap: () -> Unit) {
    val c = Atlas.colors
    val lines = remember(trip) {
        buildList {
            if (trip.locations.isNotEmpty()) add(trip.locations.map { it.lat to it.lng } to Color(MEMBER_TRACK_COLORS[0]))
            var idx = 1
            trip.memberLocations.values.forEach { pts ->
                if (pts.isNotEmpty()) { add(pts.map { it.lat to it.lng } to Color(MEMBER_TRACK_COLORS[idx % MEMBER_TRACK_COLORS.size])); idx++ }
            }
            val stops = trip.plannedStops.filter { it.latitude != null && it.longitude != null }.sortedBy { it.order }
            if (stops.size >= 2) add(stops.map { it.latitude!! to it.longitude!! } to Color(0xFFA990FF))
        }
    }
    val hasMap = lines.isNotEmpty()
    Column {
        Box(Modifier.fillMaxWidth().height(300.dp)) {
            if (hasMap) TrackSketch(lines, Modifier.fillMaxSize(), background = c.surface)
            else com.roamly.ui.kit.RouteArt(trip.id, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.bg.copy(alpha = 0.6f), Color.Transparent, Color.Transparent, c.bg))))
            if (hasMap) {
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 16.dp).clip(SquircleShape(50)).background(c.glass)
                        .tappable(pressedScale = 0.94f, onClick = onOpenMap).padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.OpenInFull, null, tint = c.accent, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open map", style = MaterialTheme.typography.labelLarge, color = c.text)
                }
            }
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Eyebrow(tripDates(trip.startTime, trip.endTime))
            Spacer(Modifier.height(6.dp))
            Text(trip.name, style = MaterialTheme.typography.headlineLarge, color = c.text)
            trip.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge, color = c.textMuted)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                StatBlock("%,d".format(trip.pointCount), "points", small = true)
                StatBlock("${trip.memberCountResolved}", if (trip.memberCountResolved == 1) "person" else "people", small = true)
                if (trip.plannedStops.isNotEmpty()) StatBlock("${trip.plannedStops.size}", "stops", small = true)
                if (trip.dayNotes.isNotEmpty()) StatBlock("${trip.dayNotes.size}", "entries", small = true)
            }
        }
    }
}

@Composable
private fun BlurbCard(
    event: TimelineEvent,
    serverUrl: String,
    comments: List<Comment>?,
    isExpanded: Boolean,
    onDelete: () -> Unit,
    onToggleComments: () -> Unit,
    onPostComment: (String) -> Unit,
    onDeleteComment: (Int) -> Unit,
) {
    val c = Atlas.colors
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(event.author)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(event.author ?: "", style = MaterialTheme.typography.labelLarge, color = c.text)
                Text(event.createdAt?.take(10) ?: "", style = MaterialTheme.typography.labelSmall, color = c.textFaint)
            }
            if (event.canDelete) IconAction(Icons.Rounded.DeleteOutline, "Delete", onDelete, container = null, tint = c.textMuted, size = 34.dp, iconSize = 18.dp)
        }
        event.title?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.titleMedium, color = c.text)
        }
        if (event.rating != null || !event.category.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            PlaceMeta(event.rating, event.category)
        }
        if (!event.text.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(event.text, style = MaterialTheme.typography.bodyLarge, color = c.text)
        }
        event.locationName?.let {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Place, null, Modifier.size(14.dp), tint = c.accent)
                Spacer(Modifier.width(4.dp))
                Text(it, style = MaterialTheme.typography.labelMedium, color = c.textMuted)
            }
        }
        if (event.photos.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            MediaThumbnailRow(media = event.photos, serverUrl = serverUrl, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(10.dp))
        val count = comments?.size ?: event.commentCount
        Row(
            Modifier.clip(SquircleShape(50)).background(c.surfaceHigh).tappable(feel = Feel.Tick, pressedScale = 0.95f, onClick = onToggleComments)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ChatBubbleOutline, null, Modifier.size(14.dp), tint = c.textMuted)
            Spacer(Modifier.width(6.dp))
            Text(if (count > 0) "$count comment${if (count != 1) "s" else ""}" else "Comment", style = MaterialTheme.typography.labelMedium, color = c.text)
        }
        AnimatedVisibility(isExpanded) {
            Column(Modifier.padding(top = 10.dp)) {
                HorizontalDivider(color = c.line)
                if (comments == null) {
                    CircularProgressIndicator(Modifier.padding(10.dp).size(20.dp), strokeWidth = 2.dp, color = c.accent)
                } else {
                    comments.forEach { cm ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                            Avatar(cm.author, size = 28)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(cm.author, style = MaterialTheme.typography.labelLarge, color = c.text)
                                Text(cm.text, style = MaterialTheme.typography.bodyMedium, color = c.text)
                            }
                            if (cm.canDelete) IconAction(Icons.Rounded.DeleteOutline, "Delete comment", { onDeleteComment(cm.id) }, container = null, tint = c.textFaint, size = 30.dp, iconSize = 15.dp)
                        }
                    }
                }
                var text by remember { mutableStateOf("") }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AtlasField(text, { text = it }, Modifier.weight(1f), placeholder = "Add a comment…")
                    Spacer(Modifier.width(8.dp))
                    IconAction(Icons.Rounded.ArrowUpward, "Post", { if (text.isNotBlank()) { onPostComment(text); text = "" } },
                        container = if (text.isNotBlank()) c.accent else c.surfaceHighest, tint = if (text.isNotBlank()) c.onAccent else c.textFaint)
                }
            }
        }
    }
}

@Composable
private fun MilestoneCard(event: TimelineEvent) {
    val c = Atlas.colors
    Panel(Modifier.fillMaxWidth(), tone = PanelTone.Accent, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(event.emoji ?: "🏆", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(event.title ?: "", style = MaterialTheme.typography.titleMedium, color = c.text)
                event.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textMuted) }
                event.date?.let { Text(it.take(10), style = MaterialTheme.typography.labelSmall, color = c.accent) }
            }
        }
    }
}

@Composable
private fun MilestoneCreateDialog(onDismiss: () -> Unit, onConfirm: (emoji: String, title: String, description: String, date: String) -> Unit) {
    var emoji by remember { mutableStateOf("🏁") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(java.time.LocalDate.now().toString()) }
    AtlasSheet(onDismiss = onDismiss, title = "Add a milestone") {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AtlasField(emoji, { emoji = it }, Modifier.width(88.dp), label = "Emoji")
                AtlasField(title, { title = it }, Modifier.weight(1f), label = "Title")
            }
            AtlasField(description, { description = it }, label = "Description", singleLine = false, minLines = 2)
            AtlasField(date, { date = it }, label = "Date", placeholder = "YYYY-MM-DD")
            AtlasButton("Add milestone", { onConfirm(emoji, title, description, date) }, Modifier.fillMaxWidth(),
                enabled = title.isNotBlank() && date.isNotBlank(), feel = Feel.Confirm)
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun trackPolyline(mapView: MapView, geo: List<GeoPoint>, color: Int): Polyline =
    Polyline(mapView).apply {
        setPoints(geo)
        outlinePaint.color = color
        outlinePaint.strokeWidth = 8f
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        outlinePaint.isAntiAlias = true
        infoWindow = null
        setOnClickListener { _, _, _ -> false }
    }

/** Each non-owner member's track in its own colour; returns their points for the fit. */
private fun drawMemberTracks(mapView: MapView, memberTracks: Map<String, List<TripLatLng>>): List<GeoPoint> {
    val out = mutableListOf<GeoPoint>()
    var idx = 1
    memberTracks.forEach { (_, pts) ->
        if (pts.isNotEmpty()) {
            val geo = pts.map { GeoPoint(it.lat, it.lng) }
            mapView.overlays.add(trackPolyline(mapView, geo, MEMBER_TRACK_COLORS[idx % MEMBER_TRACK_COLORS.size]))
            out += geo
            idx++
        }
    }
    return out
}

/** Numbered markers joined by a dashed line for the itinerary. Returns their points for the fit. */
private fun addItineraryOverlays(mapView: MapView, stops: List<com.roamly.data.api.PlannedStop>, context: android.content.Context): List<GeoPoint> {
    val located = stops.filter { it.latitude != null && it.longitude != null }.sortedBy { it.order }
    if (located.isEmpty()) return emptyList()
    val geos = located.map { GeoPoint(it.latitude!!, it.longitude!!) }
    val violet = android.graphics.Color.rgb(0xA9, 0x90, 0xFF)
    if (geos.size >= 2) {
        mapView.overlays.add(Polyline(mapView).apply {
            setPoints(geos)
            outlinePaint.color = violet
            outlinePaint.strokeWidth = 5f
            outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 14f), 0f)
            outlinePaint.isAntiAlias = true
            infoWindow = null
            setOnClickListener { _, _, _ -> false }
        })
    }
    located.forEachIndexed { i, stop ->
        mapView.overlays.add(org.osmdroid.views.overlay.Marker(mapView).apply {
            position = geos[i]
            icon = avatarMarker(context, violet, "${i + 1}", 30f)
            setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
            title = stop.name
        })
    }
    return geos
}

/** The trip on a full-screen interactive map: every member's track and the itinerary. */
@Composable
private fun TripFullMapScreen(trip: TripResponse, onBack: () -> Unit) {
    val c = Atlas.colors
    val context = LocalContext.current
    val mapView = rememberAtlasMapView(interactive = true)
    LaunchedEffect(trip) {
        mapView.overlays.clear()
        val geo = trip.locations.map { GeoPoint(it.lat, it.lng) }
        val memberGeo = drawMemberTracks(mapView, trip.memberLocations)
        if (geo.isNotEmpty()) mapView.overlays.add(0, trackPolyline(mapView, geo, MEMBER_TRACK_COLORS[0]))
        val stopGeo = addItineraryOverlays(mapView, trip.plannedStops, context)
        mapView.fitTo(geo + memberGeo + stopGeo, 96)
    }
    Box(Modifier.fillMaxSize()) {
        MapViewHost(mapView, Modifier.fillMaxSize())
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.Rounded.ArrowBack, "Back", onBack, container = c.glass, bordered = true)
            Spacer(Modifier.width(10.dp))
            Text(trip.name, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(SquircleShape(50)).background(c.glass).padding(horizontal = 14.dp, vertical = 9.dp))
        }
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconAction(Icons.Rounded.Add, "Zoom in", { mapView.controller.zoomIn() }, container = c.glass, bordered = true, feel = Feel.Tick)
            IconAction(Icons.Rounded.Remove, "Zoom out", { mapView.controller.zoomOut() }, container = c.glass, bordered = true, feel = Feel.Tick)
        }
        if (trip.memberLocations.values.any { it.isNotEmpty() }) {
            Panel(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth(), tone = PanelTone.Glass, padding = PaddingValues(14.dp)) {
                MemberTrackLegend(trip)
            }
        }
    }
}
