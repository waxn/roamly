package com.roamly.ui.trips

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.roamly.data.api.DayNote
import com.roamly.data.api.PlannedStop
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasChip
import com.roamly.ui.kit.AtlasField
import com.roamly.ui.kit.AtlasSheet
import com.roamly.ui.kit.ButtonKind
import com.roamly.ui.kit.ChipRow
import com.roamly.ui.kit.Dot
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.MEMBER_TRACK_COLORS
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.SectionTitle
import com.roamly.ui.kit.Tag
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.kit.tappable
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.AtlasType
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape

/** A section heading with an optional action on the right. */
@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    SectionTitle(title, Modifier.padding(top = 12.dp), action = action, onAction = onAction)
}

@Composable
fun DayLogEntryCard(note: DayNote, serverUrl: String, onEdit: (() -> Unit)? = null) {
    val c = Atlas.colors
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp), onClick = if (note.isMine) onEdit else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(note.author, mediaUrl(serverUrl, note.avatar))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (note.title.isNotBlank()) Text(note.title, style = MaterialTheme.typography.titleSmall, color = c.text)
                Text(note.author, style = MaterialTheme.typography.labelMedium, color = c.accent)
            }
            if (note.isMine && onEdit != null) IconAction(Icons.Rounded.Edit, "Edit", onEdit, container = null, tint = c.textMuted, size = 34.dp, iconSize = 17.dp)
        }
        if (note.body.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(note.body, style = MaterialTheme.typography.bodyMedium, color = c.text)
        }
        note.place?.let { p ->
            Spacer(Modifier.height(8.dp))
            Tag(listOfNotNull(p.category?.let { PLACE_CAT_EMOJI[it] }, p.name).joinToString(" "), color = c.accent)
        }
        if (note.photos.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            MediaThumbnailRow(media = note.photos, serverUrl = serverUrl, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A person's round avatar: their picture, else their initial. */
@Composable
fun Avatar(name: String?, url: String? = null, size: Int = 34) {
    val c = Atlas.colors
    if (url != null) {
        AsyncImage(url, null, Modifier.size(size.dp).clip(SquircleShape(50)), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(size.dp).clip(SquircleShape(50)).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Text(name?.firstOrNull()?.uppercaseChar()?.toString() ?: "?", style = MaterialTheme.typography.labelLarge, color = c.accent)
        }
    }
}

/** One stop on the itinerary, on a dotted rail joining it to the next. */
@Composable
fun ItineraryStopCard(
    index: Int,
    stop: PlannedStop,
    last: Boolean = false,
    onEdit: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val c = Atlas.colors
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(30.dp).clip(SquircleShape(50)).background(c.violet), contentAlignment = Alignment.Center) {
                Text("$index", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
            if (!last) Box(Modifier.width(2.dp).height(70.dp).background(c.violet.copy(alpha = 0.3f)))
        }
        Spacer(Modifier.width(10.dp))
        Panel(Modifier.weight(1f).padding(bottom = 10.dp), padding = PaddingValues(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), onClick = onEdit) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text(stop.name.ifBlank { "Stop $index" }, style = MaterialTheme.typography.titleSmall, color = c.text)
                    stop.locationName?.takeIf { it.isNotBlank() }?.let { Meta(Icons.Rounded.Place, it) }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        stop.arrivalDate?.takeIf { it.isNotBlank() }?.let { Meta(Icons.Rounded.CalendarMonth, it) }
                        stop.nights?.takeIf { it > 0 }?.let { Meta(Icons.Rounded.NightsStay, "$it night${if (it != 1) "s" else ""}") }
                        stop.transport?.takeIf { it.isNotBlank() }?.let { Meta(Icons.Rounded.DirectionsCar, TRANSPORT_OPTIONS.firstOrNull { o -> o.first == it }?.second ?: it) }
                    }
                    stop.accommodation?.takeIf { it.isNotBlank() }?.let { Meta(Icons.Rounded.Hotel, it) }
                    stop.notes?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                    }
                }
                if (onEdit != null) {
                    Column {
                        if (onMoveUp != null) IconAction(Icons.Rounded.KeyboardArrowUp, "Move up", onMoveUp, container = null, tint = c.textMuted, size = 32.dp, feel = Feel.Tick)
                        if (onMoveDown != null) IconAction(Icons.Rounded.KeyboardArrowDown, "Move down", onMoveDown, container = null, tint = c.textMuted, size = 32.dp, feel = Feel.Tick)
                    }
                }
            }
        }
    }
}

@Composable
private fun Meta(icon: ImageVector, text: String) {
    val c = Atlas.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Icon(icon, null, Modifier.size(13.dp), tint = c.textFaint)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = c.textMuted)
    }
}

private val TRANSPORT_OPTIONS = listOf(
    "plane" to "✈️ Plane", "car" to "🚗 Car", "train" to "🚆 Train", "bus" to "🚌 Bus",
    "boat" to "⛴️ Boat", "cycle" to "🚲 Cycle", "walk" to "🚶 Walk", "other" to "• Other",
)

/** Create/edit one planned itinerary stop. */
@Composable
fun StopEditorDialog(stop: PlannedStop, onSave: (PlannedStop) -> Unit, onDelete: (() -> Unit)?, onClose: () -> Unit) {
    var name by remember { mutableStateOf(stop.name) }
    var locationName by remember { mutableStateOf(stop.locationName ?: "") }
    var arrivalDate by remember { mutableStateOf(stop.arrivalDate ?: "") }
    var nights by remember { mutableStateOf((stop.nights ?: 0).toString()) }
    var transport by remember { mutableStateOf(stop.transport ?: "") }
    var accommodation by remember { mutableStateOf(stop.accommodation ?: "") }
    var notes by remember { mutableStateOf(stop.notes ?: "") }
    AtlasSheet(onDismiss = onClose, title = if (stop.id == 0) "New stop" else "Edit stop") {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AtlasField(name, { name = it }, label = "Name", placeholder = "Porto")
            AtlasField(locationName, { locationName = it }, label = "Location")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AtlasField(arrivalDate, { arrivalDate = it }, Modifier.weight(1f), label = "Arrival", placeholder = "2025-06-01")
                AtlasField(nights, { nights = it.filter { ch -> ch.isDigit() } }, Modifier.width(96.dp), label = "Nights",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Text("Getting there", style = MaterialTheme.typography.labelMedium, color = Atlas.colors.textMuted, modifier = Modifier.padding(start = 4.dp))
            ChipRow(contentPadding = PaddingValues(0.dp)) {
                TRANSPORT_OPTIONS.forEach { (v, label) -> AtlasChip(label, transport == v, { transport = if (transport == v) "" else v }) }
            }
            AtlasField(accommodation, { accommodation = it }, label = "Accommodation")
            AtlasField(notes, { notes = it }, label = "Notes", singleLine = false, minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onDelete != null) AtlasButton("Delete", onDelete, Modifier.weight(1f), kind = ButtonKind.Danger, icon = Icons.Rounded.DeleteOutline)
                AtlasButton("Save stop", {
                    onSave(stop.copy(name = name, locationName = locationName, arrivalDate = arrivalDate, nights = nights.toIntOrNull() ?: 0,
                        transport = transport, accommodation = accommodation, notes = notes))
                }, Modifier.weight(1.4f), enabled = name.isNotBlank(), feel = Feel.Confirm)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Colour swatches for the per-member tracks on the trip map (owner = index 0). */
@Composable
fun MemberTrackLegend(trip: com.roamly.data.api.TripResponse) {
    val members = trip.memberLocations.keys.filter { trip.memberLocations[it]?.isNotEmpty() == true }
    if (members.isEmpty()) return
    val ownerName = trip.members?.firstOrNull { it.role == "creator" }?.username ?: "Owner"
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Swatch(MEMBER_TRACK_COLORS[0], ownerName)
        members.forEachIndexed { i, name -> Swatch(MEMBER_TRACK_COLORS[(i + 1) % MEMBER_TRACK_COLORS.size], name) }
    }
}

@Composable
private fun Swatch(color: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(Color(color), 10.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Atlas.colors.textMuted)
    }
}

/** An ISO date (yyyy-MM-dd) as a day header. */
fun formatDayHeader(iso: String): String = try {
    java.time.LocalDate.parse(iso.take(10)).format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy"))
} catch (e: Exception) { iso }

/** A new "update": text, optional title, rating, category and up to 5 photos/videos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlurbCreateDialog(
    onDismiss: () -> Unit,
    onPost: (text: String, title: String, rating: Int?, category: String?, uris: List<android.net.Uri>) -> Unit,
) {
    val c = Atlas.colors
    val haptics = rememberHaptics()
    var text by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var rating by remember { mutableIntStateOf(0) }
    var category by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        if (uris.isNotEmpty()) picked = (picked + uris).distinct().take(5)
    }
    AtlasSheet(onDismiss = onDismiss, title = "Post an update") {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AtlasField(title, { title = it }, label = "Title", placeholder = "Optional")
            AtlasField(text, { text = it }, label = "What's happening?", singleLine = false, minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Rating", style = MaterialTheme.typography.labelMedium, color = c.textMuted, modifier = Modifier.padding(start = 4.dp, end = 10.dp))
                (1..5).forEach { n ->
                    Text(
                        if (n <= rating) "★" else "☆", style = MaterialTheme.typography.headlineSmall,
                        color = if (n <= rating) c.amber else c.textFaint,
                        modifier = Modifier.clip(SquircleShape(8.dp)).tappable(feel = Feel.None, pressedScale = 0.8f) {
                            haptics.tick(); rating = if (rating == n) 0 else n
                        }.padding(horizontal = 3.dp),
                    )
                }
            }
            Text("Category", style = MaterialTheme.typography.labelMedium, color = c.textMuted, modifier = Modifier.padding(start = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PLACE_CAT_LABEL.forEach { (slug, label) ->
                    AtlasChip("${PLACE_CAT_EMOJI[slug] ?: ""} $label", category == slug, { category = if (category == slug) "" else slug })
                }
            }
            AtlasButton(
                if (picked.isEmpty()) "Add photos or videos" else "${picked.size} attached — add more",
                { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = Icons.Rounded.AddPhotoAlternate, enabled = picked.size < 5,
            )
            AtlasButton("Post", { onPost(text, title, rating.takeIf { it > 0 }, category.ifBlank { null }, picked) },
                Modifier.fillMaxWidth(), enabled = text.isNotBlank() || title.isNotBlank() || picked.isNotEmpty(), feel = Feel.Confirm)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Invite by link: the join URL with copy / share / reset, and the member list. */
@Composable
fun InviteSheet(
    inviteUrl: String?,
    members: List<com.roamly.data.api.PalMember>,
    onRotate: () -> Unit,
    onRemoveMember: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val c = Atlas.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptics = rememberHaptics()
    AtlasSheet(onDismiss = onClose, title = "Invite people") {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Anyone with this link can join and share their track.", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            if (inviteUrl.isNullOrBlank()) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = c.accent)
            } else {
                Box(Modifier.fillMaxWidth().clip(SquircleShape(SquircleRadius.control)).background(c.surfaceHigh).border(1.dp, c.line, SquircleShape(SquircleRadius.control)).padding(14.dp)) {
                    Text(inviteUrl, style = AtlasType.mono, color = c.accent)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AtlasButton("Copy", { clipboard.setText(AnnotatedString(inviteUrl)); haptics.confirm() }, Modifier.weight(1f),
                        kind = ButtonKind.Secondary, compact = true, icon = Icons.Rounded.ContentCopy)
                    AtlasButton("Share", {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, inviteUrl) }
                        context.startActivity(android.content.Intent.createChooser(send, "Share invite"))
                    }, Modifier.weight(1f), compact = true, icon = Icons.Rounded.Share)
                }
                AtlasButton("Reset link", onRotate, kind = ButtonKind.Ghost, compact = true)
            }
            if (members.isNotEmpty()) {
                SectionTitle("Members · ${members.size}")
                members.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(m.username)
                        Spacer(Modifier.width(12.dp))
                        Text(m.username, style = MaterialTheme.typography.bodyLarge, color = c.text, modifier = Modifier.weight(1f))
                        if (m.role == "creator") Tag("Owner", color = c.amber)
                        else IconAction(Icons.Rounded.PersonRemove, "Remove ${m.username}", { onRemoveMember(m.userId) }, container = null, tint = c.textMuted, size = 38.dp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Editor for one of your own Day Log entries. */
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
    val c = Atlas.colors
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> if (uris.isNotEmpty()) onAddPhotos(uris) }
    AtlasSheet(onDismiss = onClose) {
        Row(Modifier.padding(start = 24.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatDayHeader(editor.note.date), style = MaterialTheme.typography.headlineSmall, color = c.text, modifier = Modifier.weight(1f))
            IconAction(Icons.Rounded.DeleteOutline, "Delete entry", onDelete, tint = c.danger)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AtlasField(editor.title, onTitle, label = "Title")
            AtlasField(editor.body, onBody, label = "What happened?", singleLine = false, minLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            if (places.isNotEmpty()) {
                Text("Linked place", style = MaterialTheme.typography.labelMedium, color = c.textMuted, modifier = Modifier.padding(start = 4.dp))
                ChipRow(contentPadding = PaddingValues(0.dp)) {
                    AtlasChip("None", editor.placeId == null, { onPlace(null) })
                    places.forEach { p -> AtlasChip(p.name, editor.placeId == p.id, { onPlace(p.id) }, icon = Icons.Rounded.Place) }
                }
            }
            if (editor.photos.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    editor.photos.forEach { m ->
                        Box {
                            AsyncImage(mediaUrl(serverUrl, m.thumb ?: m.url), null, Modifier.size(84.dp).clip(SquircleShape(SquircleRadius.control)), contentScale = ContentScale.Crop)
                            IconAction(Icons.Rounded.Close, "Remove", { onDeletePhoto(m.id) }, Modifier.align(Alignment.TopEnd).padding(4.dp),
                                tint = Color.White, container = Color.Black.copy(alpha = 0.55f), size = 24.dp, iconSize = 14.dp)
                        }
                    }
                }
            }
            AtlasButton(
                "Add photos or videos",
                { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = Icons.Rounded.AddPhotoAlternate,
                loading = editor.uploading, enabled = editor.photos.size < 10,
            )
            AtlasButton("Save entry", onSave, Modifier.fillMaxWidth(), loading = editor.saving, feel = Feel.Confirm)
            Spacer(Modifier.height(8.dp))
        }
    }
}
