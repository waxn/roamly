package com.roamly.ui.flights

import com.roamly.ui.kit.Units
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasChip
import com.roamly.ui.kit.AtlasDialog
import com.roamly.ui.kit.AtlasField
import com.roamly.ui.kit.ButtonKind
import com.roamly.ui.kit.ChipRow
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.Foldout
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.MapViewHost
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PushedScreen
import com.roamly.ui.kit.SectionTitle
import com.roamly.ui.kit.addGlowLine
import com.roamly.ui.kit.fitTo
import com.roamly.ui.kit.rememberAtlasMapView
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape
import org.osmdroid.util.GeoPoint

/**
 * Confirm-and-correct after a scan, and the editor for any saved flight. Every
 * field stays editable: a boarding pass is authoritative for the seat and the
 * booking, AeroDataBox for times and aircraft, and the user for everything.
 */
@Composable
fun FlightEditScreen(viewModel: FlightsViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val d = state.draft
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val startSaved = remember { state.savedCount }
    val kmlPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "flight.kml"
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes != null) viewModel.previewFile(name, bytes)
    }
    LaunchedEffect(state.savedCount) { if (state.savedCount != startSaved) { haptics.success(); onDone() } }

    val c = Atlas.colors
    PushedScreen(
        title = if (d.id == null) "Add flight" else "Edit flight",
        onBack = onDone,
        subtitle = listOf(d.dep, d.arr).filter { it.isNotBlank() }.joinToString(" → ").ifBlank { null },
        actions = { if (d.id != null) IconAction(Icons.Rounded.DeleteOutline, "Delete", { confirmDelete = true }, tint = c.danger) },
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Flight", d.flightNumber, Modifier.weight(1f), caps = true, hint = "BA 117") { v -> viewModel.edit { it.copy(flightNumber = v) } }
                    Field("Date", d.date, Modifier.weight(1f), hint = "yyyy-mm-dd") { v -> viewModel.edit { it.copy(date = v) } }
                }
                if (state.lookupEnabled != false) {
                    AtlasButton("Look up flight details", viewModel::lookup, Modifier.fillMaxWidth(),
                        kind = ButtonKind.Secondary, icon = Icons.Rounded.TravelExplore, loading = state.lookingUp)
                }
                state.status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (state.statusIsError) c.danger else c.textMuted)
                }
                state.legChoices.forEach { leg ->
                    Panel(Modifier.fillMaxWidth(), tone = com.roamly.ui.kit.PanelTone.Sunken, padding = PaddingValues(12.dp),
                        onClick = { viewModel.applyLeg(leg) }) {
                        Text("${leg.depIata} → ${leg.arrIata}", style = MaterialTheme.typography.titleSmall, color = c.text)
                        Text("${leg.depCity} → ${leg.arrCity}", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                    }
                }
            }

            Card("Route") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("From", d.dep, Modifier.weight(1f), caps = true, hint = "JFK") { v -> viewModel.edit { it.copy(dep = v) } }
                    Field("To", d.arr, Modifier.weight(1f), caps = true, hint = "LAX") { v -> viewModel.edit { it.copy(arr = v) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Departs", d.schedDep, Modifier.weight(1f), hint = "2026-05-01 08:15") { v -> viewModel.edit { it.copy(schedDep = v) } }
                    Field("Arrives", d.schedArr, Modifier.weight(1f), hint = "2026-05-01 11:40") { v -> viewModel.edit { it.copy(schedArr = v) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Actual departure", d.actualDep, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(actualDep = v) } }
                    Field("Actual arrival", d.actualArr, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(actualArr = v) } }
                }
                Text("Times are the clock at each airport, as printed on the pass.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }

            Card("Aircraft & seat") {
                Field("Airline", d.airlineName) { v -> viewModel.edit { it.copy(airlineName = v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Aircraft", d.aircraftModel, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(aircraftModel = v) } }
                    Field("Registration", d.registration, Modifier.weight(1f), caps = true) { v -> viewModel.edit { it.copy(registration = v) } }
                }
                Field("Seat", d.seat, caps = true, hint = "14A") { v -> viewModel.edit { it.copy(seat = v) } }
                Choice("Class", d.seatClass, SEAT_CLASSES) { v -> viewModel.edit { it.copy(seatClass = v) } }
                Choice("Seat type", d.seatType, SEAT_TYPES) { v -> viewModel.edit { it.copy(seatType = v) } }
                Choice("Reason", d.reason, REASONS) { v -> viewModel.edit { it.copy(reason = v) } }
                Field("Notes", d.notes, singleLine = false) { v -> viewModel.edit { it.copy(notes = v) } }
            }

            TrackCard(
                state = state,
                onFr24 = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                            "https://www.flightradar24.com/data/flights/" + d.flightNumber.replace(" ", "").lowercase())))
                    }
                },
                onKml = {
                    kmlPicker.launch(arrayOf("application/vnd.google-earth.kml+xml", "application/vnd.google-earth.kmz",
                        "application/xml", "text/xml", "text/csv", "text/comma-separated-values", "application/octet-stream"))
                },
                onOpenSky = { viewModel.previewTrack("opensky") },
                onRematch = { viewModel.previewTrack("match") },
                onUnlink = viewModel::removeTrack,
            )

            Panel(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                Foldout("Booking & advanced", showAdvanced, { showAdvanced = !showAdvanced }) {
                    Text("From the boarding pass. The booking reference can manage the booking — keep it to yourself.",
                        style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                    Field("Booking reference", d.bookingRef, caps = true) { v -> viewModel.edit { it.copy(bookingRef = v) } }
                    Field("Passenger", d.passengerName) { v -> viewModel.edit { it.copy(passengerName = v) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field("Sequence", d.checkinSeq, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(checkinSeq = v) } }
                        Field("Ticket", d.ticketNumber, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(ticketNumber = v) } }
                    }
                    Field("Frequent flyer", d.frequentFlyer, caps = true) { v -> viewModel.edit { it.copy(frequentFlyer = v) } }
                    Field("Mode S address", d.icao24, hint = "a1b2c3") { v -> viewModel.edit { it.copy(icao24 = v.lowercase()) } }
                    when {
                        state.passImage != null -> "A photo of the pass will be saved with this flight."
                        d.hasPassImage -> "A photo of the pass is saved (view it on the website)."
                        else -> null
                    }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textMuted) }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        Row(
            Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AtlasButton("Cancel", onDone, Modifier.weight(1f), kind = ButtonKind.Secondary)
            AtlasButton("Save flight", { viewModel.save() }, Modifier.weight(1.4f), loading = state.saving, icon = Icons.Rounded.Flight, feel = Feel.None)
        }
    }

    state.duplicateOf?.let { existing ->
        AtlasDialog(
            title = "Already in your log",
            onDismiss = viewModel::dismissDuplicate,
            confirmText = "Update it",
            onConfirm = { viewModel.save(overwriteId = existing) },
            body = { Text("${d.flightNumber} on ${d.date} is already saved. Update that flight with these details instead?") },
        )
    }
    if (confirmDelete) {
        AtlasDialog(
            title = "Delete this flight?",
            onDismiss = { confirmDelete = false },
            confirmText = "Delete",
            onConfirm = { confirmDelete = false; d.id?.let { viewModel.delete(it, onDone) } },
            destructive = true,
            icon = Icons.Rounded.DeleteOutline,
            body = { Text("It's removed from your flight log, along with any boarding-pass photo.") },
        )
    }
}

private val SEAT_CLASSES = listOf("" to "Not set", "economy" to "Economy", "premium" to "Premium", "business" to "Business", "first" to "First", "private" to "Private")
private val SEAT_TYPES = listOf("" to "Not set", "window" to "Window", "middle" to "Middle", "aisle" to "Aisle")
private val REASONS = listOf("" to "Not set", "leisure" to "Leisure", "business" to "Business", "crew" to "Crew", "other" to "Other")

@Composable
private fun Card(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        if (title != null) SectionTitle(title)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    caps: Boolean = false,
    singleLine: Boolean = true,
    hint: String? = null,
    onChange: (String) -> Unit,
) {
    AtlasField(
        value = value,
        onValueChange = { onChange(if (caps) it.uppercase() else it) },
        label = label,
        placeholder = hint,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        keyboardOptions = KeyboardOptions(capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.Sentences),
        modifier = modifier,
    )
}

@Composable
private fun Choice(label: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Atlas.colors.textMuted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        ChipRow(contentPadding = PaddingValues(0.dp)) {
            options.drop(1).forEach { (key, text) -> AtlasChip(text, value == key, { onChange(if (value == key) "" else key) }) }
        }
    }
}

/** The flight's track — recorded, else OpenSky's, else the great-circle route — and ways to get a better one. */
@Composable
private fun TrackCard(
    state: FlightsUiState,
    onFr24: () -> Unit,
    onKml: () -> Unit,
    onOpenSky: () -> Unit,
    onRematch: () -> Unit,
    onUnlink: () -> Unit,
) {
    val c = Atlas.colors
    val d = state.draft
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
        Box(Modifier.fillMaxWidth().height(200.dp).clip(SquircleShape(topStart = SquircleRadius.panel, topEnd = SquircleRadius.panel))) {
            TrackMiniMap(state)
        }
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Flight track")
            val text = when {
                state.pendingRemove -> "The track will be removed when you save."
                state.pendingTrack != null -> (if (state.pendingTrack.source == "opensky") "OpenSky track" else "Recorded track") +
                    " · ${state.pendingTrack.message} Not saved yet — press Save to keep it."
                d.id == null -> "Find a track with the buttons below. Nothing is kept until you press Save."
                else -> when (d.trackSource) {
                    "recorded" -> "Recorded track, from your own points or an uploaded KML" + (d.trackDistanceKm?.let { " · %,d %s flown".format(Units.dist(it).toLong(), Units.distUnit) } ?: "")
                    "opensky" -> "OpenSky track · ${d.openskyMessage} Upload a KML to replace it."
                    else -> "No track yet — showing the great-circle route. " + when (d.openskyStatus) {
                        "pending" -> "OpenSky: waiting for the flight to land."
                        "none" -> "OpenSky has no track for this flight."
                        "too_old" -> "Older than OpenSky's 30 days. Upload a KML from Flightradar24."
                        "no_aircraft", "error" -> d.openskyMessage
                        else -> ""
                    }
                }
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AtlasButton("Find track", onOpenSky, Modifier.weight(1f), kind = ButtonKind.Secondary, compact = true, icon = Icons.Rounded.Radar, loading = state.trackBusy)
                AtlasButton("Upload file", onKml, Modifier.weight(1f), kind = ButtonKind.Secondary, compact = true, icon = Icons.Rounded.FileUpload, enabled = !state.trackBusy)
            }
            AtlasButton("Use my recorded locations", onRematch, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, compact = true, icon = Icons.Rounded.Route, enabled = !state.trackBusy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AtlasButton("Flightradar24", onFr24, Modifier.weight(1f), kind = ButtonKind.Ghost, compact = true, icon = Icons.Rounded.OpenInNew, enabled = d.flightNumber.isNotBlank())
                val shown = !state.pendingRemove && (state.pendingTrack != null || d.trackSource.isNotBlank())
                if (shown) AtlasButton("Remove track", onUnlink, Modifier.weight(1f), kind = ButtonKind.Danger, compact = true, enabled = !state.trackBusy)
            }
        }
    }
}

@Composable
private fun TrackMiniMap(state: FlightsUiState) {
    val c = Atlas.colors
    val d = state.draft
    val mapView = rememberAtlasMapView(interactive = true)
    val coords = when {
        state.pendingRemove -> emptyList()
        state.pendingTrack != null -> state.pendingTrack.coords
        else -> state.editTrack
    }
    val source = state.pendingTrack?.source ?: if (state.pendingRemove) "" else state.editTrackSource
    val color: Color = when (source) { "recorded" -> c.cyan; "opensky" -> c.violet; else -> c.accent }
    LaunchedEffect(coords, d.depDetail, d.arrDetail, color) {
        mapView.overlays.clear()
        mapView.controller.setZoom(2.0)
        val great = coords.size < 2
        val runs = if (!great) wrappedRuns(coords) else {
            val dep = d.depDetail; val arr = d.arrDetail
            if (dep?.depLat != null && dep.depLon != null && arr?.arrLat != null && arr.arrLon != null)
                listOf(listOf(GeoPoint(dep.depLat, dep.depLon), GeoPoint(arr.arrLat, arr.arrLon)))
            else emptyList()
        }
        runs.forEach { mapView.addGlowLine(it, color, width = 6f, geodesic = great) }
        mapView.fitTo(runs.flatten(), 48)
    }
    MapViewHost(mapView, Modifier.fillMaxSize())
}
