package com.roamly.ui.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.roamly.data.api.DayNote
import com.roamly.data.api.PlannedStop

/** A left-accented section heading used by the Day Log / Itinerary sections. */
@Composable
fun SectionHeader(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Box(Modifier.width(4.dp).height(20.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
fun DayLogEntryCard(note: DayNote, serverUrl: String, onEdit: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DayAuthorAvatar(note.author, mediaUrl(serverUrl, note.avatar))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (note.title.isNotBlank()) {
                    Text(note.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                }
                Text(note.author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (note.isMine && onEdit != null) {
                IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.Edit, "Edit", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (note.body.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(note.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
        }
        note.place?.let { p ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val emoji = p.category?.let { PLACE_CAT_EMOJI[it] }
                if (emoji != null) Text(emoji) else Icon(Icons.Rounded.Place, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text(p.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (note.photos.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            MediaThumbnailRow(media = note.photos, serverUrl = serverUrl, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DayAuthorAvatar(name: String, avatarUrl: String?) {
    if (avatarUrl != null) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = null,
            modifier = Modifier.size(34.dp).clip(CircleShape),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
fun ItineraryStopCard(
    index: Int,
    stop: PlannedStop,
    onEdit: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
    ) {
        // Numbered badge
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF9B8EF7)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$index", style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stop.name.ifBlank { "Stop $index" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            stop.locationName?.takeIf { it.isNotBlank() }?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Place, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(3.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                stop.arrivalDate?.takeIf { it.isNotBlank() }?.let { StopMeta(Icons.Rounded.CalendarMonth, it) }
                stop.nights?.takeIf { it > 0 }?.let { StopMeta(Icons.Rounded.NightsStay, "$it night${if (it != 1) "s" else ""}") }
                stop.transport?.takeIf { it.isNotBlank() }?.let { StopMeta(Icons.Rounded.DirectionsCar, it) }
            }
            stop.accommodation?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                StopMeta(Icons.Rounded.Hotel, it)
            }
            stop.notes?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onEdit != null) {
            Column {
                IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.Edit, "Edit", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onMoveUp != null) IconButton(onClick = onMoveUp, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.KeyboardArrowUp, "Move up", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onMoveDown != null) IconButton(onClick = onMoveDown, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Move down", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private val TRANSPORT_OPTIONS = listOf(
    "" to "—", "plane" to "✈️ Plane", "car" to "🚗 Car", "train" to "🚆 Train",
    "bus" to "🚌 Bus", "boat" to "⛴️ Boat", "cycle" to "🚲 Cycle", "walk" to "🚶 Walk", "other" to "• Other",
)

/** Create/edit dialog for one planned itinerary stop. */
@Composable
fun StopEditorDialog(
    stop: PlannedStop,
    onSave: (PlannedStop) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(stop.name) }
    var locationName by remember { mutableStateOf(stop.locationName ?: "") }
    var arrivalDate by remember { mutableStateOf(stop.arrivalDate ?: "") }
    var nights by remember { mutableStateOf((stop.nights ?: 0).toString()) }
    var transport by remember { mutableStateOf(stop.transport ?: "") }
    var accommodation by remember { mutableStateOf(stop.accommodation ?: "") }
    var notes by remember { mutableStateOf(stop.notes ?: "") }
    var transportOpen by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth().padding(16.dp)
                .clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.background).padding(18.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (stop.id == 0) "New stop" else "Edit stop", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            androidx.compose.material3.OutlinedTextField(locationName, { locationName = it }, label = { Text("Location") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(arrivalDate, { arrivalDate = it }, label = { Text("Arrival (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("2025-06-01") })
                androidx.compose.material3.OutlinedTextField(nights, { nights = it.filter { c -> c.isDigit() } }, label = { Text("Nights") }, singleLine = true, modifier = Modifier.width(90.dp))
            }
            // Transport dropdown
            Box {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .clickable { transportOpen = true }.padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Getting there:  ${TRANSPORT_OPTIONS.firstOrNull { it.first == transport }?.second ?: "—"}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                androidx.compose.material3.DropdownMenu(expanded = transportOpen, onDismissRequest = { transportOpen = false }) {
                    TRANSPORT_OPTIONS.forEach { (v, label) ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { transport = v; transportOpen = false })
                    }
                }
            }
            androidx.compose.material3.OutlinedTextField(accommodation, { accommodation = it }, label = { Text("Accommodation") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            androidx.compose.material3.OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    androidx.compose.material3.TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onClose) { Text("Cancel") }
                androidx.compose.material3.Button(onClick = {
                    onSave(stop.copy(
                        name = name, locationName = locationName, arrivalDate = arrivalDate,
                        nights = nights.toIntOrNull() ?: 0, transport = transport,
                        accommodation = accommodation, notes = notes,
                    ))
                }) { Text("Save") }
            }
        }
    }
}

@Composable
private fun StopMeta(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(3.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Color-swatch legend for the map's per-member tracks. Owner is color index 0
 *  (matching MEMBER_TRACK_COLORS / adventure_public.html); other members follow
 *  in the same order drawMemberTracks assigns them. */
@Composable
fun MemberTrackLegend(trip: com.roamly.data.api.TripResponse) {
    val members = trip.memberLocations.keys.filter { trip.memberLocations[it]?.isNotEmpty() == true }
    if (members.isEmpty()) return
    val ownerName = trip.members?.firstOrNull { it.role == "creator" }?.username ?: "Owner"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LegendSwatch(MEMBER_TRACK_COLORS[0], ownerName)
        members.forEachIndexed { i, name ->
            LegendSwatch(MEMBER_TRACK_COLORS[(i + 1) % MEMBER_TRACK_COLORS.size], name)
        }
    }
}

@Composable
private fun LegendSwatch(color: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(Color(color)))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Format an ISO date (yyyy-MM-dd) as a friendly day header. */
fun formatDayHeader(iso: String): String = try {
    val d = java.time.LocalDate.parse(iso.take(10))
    d.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy"))
} catch (e: Exception) { iso }

/** Full-screen editor for one of the member's own Day Log entries. */
@Composable
fun DayNoteEditorDialog(
    editor: DayNoteEditorState,
    places: List<com.roamly.data.api.PlacePayload>,
    serverUrl: String,
    onTitle: (String) -> Unit,
    onBody: (String) -> Unit,
    onPlace: (Int?) -> Unit,
    onAddPhotos: (List<android.net.Uri>) -> Unit,
    onDeletePhoto: (Int) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris -> if (uris.isNotEmpty()) onAddPhotos(uris) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.background)
                .padding(18.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDayHeader(editor.note.date), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete entry", tint = MaterialTheme.colorScheme.error) }
            }
            androidx.compose.material3.OutlinedTextField(
                value = editor.title, onValueChange = onTitle,
                label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.material3.OutlinedTextField(
                value = editor.body, onValueChange = onBody,
                label = { Text("What happened?") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
            )

            // Linked place dropdown (optional).
            if (places.isNotEmpty()) {
                PlacePickerRow(places = places, selectedId = editor.placeId, onSelect = onPlace)
            }

            // Photos
            if (editor.photos.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    editor.photos.forEach { m ->
                        Box {
                            AsyncImage(
                                model = mediaUrl(serverUrl, m.thumb ?: m.url),
                                contentDescription = null,
                                modifier = Modifier.size(80.dp).clip(RoundedCornerShape(10.dp)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                            IconButton(onClick = { onDeletePhoto(m.id) }, modifier = Modifier.align(Alignment.TopEnd).size(24.dp)) {
                                Icon(Icons.Rounded.Delete, "Remove", Modifier.size(14.dp), tint = Color.White)
                            }
                        }
                    }
                }
            }
            androidx.compose.material3.OutlinedButton(
                onClick = { picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                enabled = !editor.uploading && editor.photos.size < 10,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (editor.uploading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Add photos / videos")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.TextButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Close") }
                androidx.compose.material3.Button(onClick = onSave, enabled = !editor.saving, modifier = Modifier.weight(1f)) {
                    Text(if (editor.saving) "Saving…" else "Save")
                }
            }
        }
    }
}

@Composable
private fun PlacePickerRow(
    places: List<com.roamly.data.api.PlacePayload>,
    selectedId: Int?,
    onSelect: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = places.firstOrNull { it.id == selectedId }?.name ?: "No linked place"
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Place, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(selectedName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { Text("No linked place") }, onClick = { expanded = false; onSelect(null) })
            places.forEach { p ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(p.name) }, onClick = { expanded = false; onSelect(p.id) })
            }
        }
    }
}
