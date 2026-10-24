package com.roamly.ui.flights

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.roamly.ui.theme.ClayButton
import com.roamly.ui.theme.ClayCard
import com.roamly.ui.theme.ClayOutlinedButton

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
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val startSaved = remember { state.savedCount }

    LaunchedEffect(state.savedCount) { if (state.savedCount != startSaved) onDone() }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Text(if (d.id == null) "Add flight" else "Edit flight",
                 style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (d.id != null) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Rounded.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ClayCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field("Flight number", d.flightNumber, Modifier.weight(1f), caps = true) { v -> viewModel.edit { it.copy(flightNumber = v) } }
                    Field("Date (yyyy-mm-dd)", d.date, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(date = v) } }
                }
                if (state.lookupEnabled != false) {
                    Spacer(Modifier.size(10.dp))
                    ClayOutlinedButton(onClick = viewModel::lookup, enabled = !state.lookingUp,
                                       modifier = Modifier.fillMaxWidth()) {
                        if (state.lookingUp) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.TravelExplore, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Look up flight details")
                    }
                }
                state.status?.let {
                    Spacer(Modifier.size(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall,
                         color = if (state.statusIsError) MaterialTheme.colorScheme.error
                                 else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.legChoices.forEach { leg ->
                    TextButton(onClick = { viewModel.applyLeg(leg) }) {
                        Text("${leg.depIata} → ${leg.arrIata}  ${leg.depCity} → ${leg.arrCity}")
                    }
                }
            }

            ClayCard {
                Section("Route")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("From (JFK)", d.dep, Modifier.weight(1f), caps = true) { v -> viewModel.edit { it.copy(dep = v) } }
                    Field("To (LAX)", d.arr, Modifier.weight(1f), caps = true) { v -> viewModel.edit { it.copy(arr = v) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Departs (local)", d.schedDep, Modifier.weight(1f), hint = "2026-05-01 08:15") { v -> viewModel.edit { it.copy(schedDep = v) } }
                    Field("Arrives (local)", d.schedArr, Modifier.weight(1f), hint = "2026-05-01 11:40") { v -> viewModel.edit { it.copy(schedArr = v) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Actual departure", d.actualDep, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(actualDep = v) } }
                    Field("Actual arrival", d.actualArr, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(actualArr = v) } }
                }
                Text("Times are the clock at each airport, as printed on the pass.",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (d.id != null) {
                    Spacer(Modifier.size(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                d.hasTrack -> "Recorded track linked" +
                                    (d.trackDistanceKm?.let { " · %,d km flown".format(it.toLong()) } ?: "")
                                d.trackAuto -> "No recorded track found."
                                else -> "Track unlinked."
                            },
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.trackAction(unlink = d.hasTrack) }) {
                            Text(if (d.hasTrack) "Unlink" else "Find track")
                        }
                    }
                }
            }

            ClayCard {
                Section("Aircraft & seat")
                Field("Airline", d.airlineName) { v -> viewModel.edit { it.copy(airlineName = v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Aircraft", d.aircraftModel, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(aircraftModel = v) } }
                    Field("Registration", d.registration, Modifier.weight(1f), caps = true) { v -> viewModel.edit { it.copy(registration = v) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Seat", d.seat, Modifier.weight(1f), caps = true) { v -> viewModel.edit { it.copy(seat = v) } }
                    Choice("Class", d.seatClass, SEAT_CLASSES, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(seatClass = v) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Choice("Seat type", d.seatType, SEAT_TYPES, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(seatType = v) } }
                    Choice("Reason", d.reason, REASONS, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(reason = v) } }
                }
                Field("Notes", d.notes, singleLine = false) { v -> viewModel.edit { it.copy(notes = v) } }
            }

            ClayCard(onClick = { showAdvanced = !showAdvanced }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Advanced", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Icon(if (showAdvanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
                if (showAdvanced) {
                    Text("From the boarding pass. The booking reference can manage the booking — keep it to yourself.",
                         style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Field("Booking reference", d.bookingRef, caps = true) { v -> viewModel.edit { it.copy(bookingRef = v) } }
                    Field("Passenger", d.passengerName) { v -> viewModel.edit { it.copy(passengerName = v) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field("Sequence", d.checkinSeq, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(checkinSeq = v) } }
                        Field("Ticket", d.ticketNumber, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(ticketNumber = v) } }
                    }
                    Field("Frequent flyer", d.frequentFlyer, caps = true) { v -> viewModel.edit { it.copy(frequentFlyer = v) } }
                    if (d.id != null) {
                        ClayOutlinedButton(onClick = { viewModel.trackAction(unlink = false) },
                                           modifier = Modifier.fillMaxWidth()) {
                            Text("Re-match recorded track")
                        }
                        Text("Searches your GPS history for this flight again and links the fullest recording.",
                             style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val photo = when {
                        state.passImage != null -> "A photo of the pass will be saved with this flight."
                        d.hasPassImage -> "A photo of the pass is saved (view it on the website)."
                        else -> null
                    }
                    photo?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (d.flightNumber.isNotBlank()) {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                            "https://www.flightradar24.com/data/flights/" + d.flightNumber.replace(" ", "").lowercase())))
                    }
                }) { Text("Open on Flightradar24 ↗") }
            }
            Spacer(Modifier.size(8.dp))
        }

        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ClayOutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Cancel") }
            ClayButton(onClick = { viewModel.save() }, enabled = !state.saving, modifier = Modifier.weight(1f)) {
                if (state.saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Save", fontWeight = FontWeight.Bold)
            }
        }
    }

    state.duplicateOf?.let { existing ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDuplicate,
            title = { Text("Already in your log") },
            text = { Text("${d.flightNumber} on ${d.date} is already saved. Update that flight with these details instead?") },
            confirmButton = { TextButton(onClick = { viewModel.save(overwriteId = existing) }) { Text("Update it") } },
            dismissButton = { TextButton(onClick = viewModel::dismissDuplicate) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this flight?") },
            text = { Text("It is removed from your flight log, including any boarding-pass photo.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    d.id?.let { viewModel.delete(it, onDone) }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private val SEAT_CLASSES = listOf("" to "Not set", "economy" to "Economy", "premium" to "Premium economy",
                                  "business" to "Business", "first" to "First", "private" to "Private")
private val SEAT_TYPES = listOf("" to "Not set", "window" to "Window", "middle" to "Middle", "aisle" to "Aisle")
private val REASONS = listOf("" to "Not set", "leisure" to "Leisure", "business" to "Business",
                             "crew" to "Crew", "other" to "Other")

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
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
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(if (caps) it.uppercase() else it) },
        label = { Text(label) },
        placeholder = hint?.let { { Text(it) } },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(
            capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.Sentences),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Choice(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == value }?.second ?: value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onChange(key); open = false })
            }
        }
    }
}
