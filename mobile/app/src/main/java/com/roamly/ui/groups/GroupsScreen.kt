package com.roamly.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.PalResponse
import com.roamly.data.api.TripResponse
import com.roamly.ui.pals.PalsViewModel
import com.roamly.ui.theme.Clay
import com.roamly.ui.theme.ClayCard
import com.roamly.ui.theme.ClayIconBadge
import com.roamly.ui.trips.TripsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    tripsViewModel: TripsViewModel = hiltViewModel(),
    palsViewModel: PalsViewModel = hiltViewModel(),
    onTripClick: (Int) -> Unit,
    onPalClick: (Int) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tripsState by tripsViewModel.uiState.collectAsState()
    val palsState by palsViewModel.uiState.collectAsState()
    val clay = Clay.colors

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ClayIconBadge(Icons.Rounded.Explore, gradient = clay.primaryGradient, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Text("Adventures", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            }

            // Clay segmented tabs
            Segmented(
                options = listOf("Trips" to Icons.Rounded.Route, "Pals" to Icons.Rounded.Groups),
                selected = selectedTab,
                onSelect = { selectedTab = it },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))

            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> when {
                        tripsState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
                        tripsState.trips.isEmpty() -> EmptyState(Icons.Rounded.Route, "No trips yet", "Tap + to start a new journey")
                        else -> LazyColumn(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 0.dp, 16.dp, 100.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(tripsState.trips) { trip ->
                                TripCard(trip, gradient = clay.primaryGradient, onClick = { onTripClick(trip.id) }, onDelete = { tripsViewModel.deleteTrip(trip.id) })
                            }
                        }
                    }
                    1 -> when {
                        palsState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
                        palsState.pals.isEmpty() -> EmptyState(Icons.Rounded.Groups, "No pals yet", "Tap + to create a group trip")
                        else -> LazyColumn(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 0.dp, 16.dp, 100.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(palsState.pals) { pal ->
                                PalCard(pal, gradient = clay.secondaryGradient, onClick = { onPalClick(pal.id) })
                            }
                        }
                    }
                }
                val err = if (selectedTab == 0) tripsState.error else palsState.error
                err?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
                }
            }
        }

        // Clay FAB
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 24.dp)
                .size(60.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable { if (selectedTab == 0) tripsViewModel.showCreateDialog() else palsViewModel.showCreateDialog() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, "New", tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }

    if (tripsState.showCreateDialog) {
        var name by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = tripsViewModel::hideCreateDialog,
            title = { Text("New trip") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { tripsViewModel.createTrip(name, description) }) { Text("Create") } },
            dismissButton = { TextButton(onClick = tripsViewModel::hideCreateDialog) { Text("Cancel") } }
        )
    }

    if (palsState.showCreateDialog) {
        var name by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        var startDate by remember { mutableStateOf("") }
        var endDate by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = palsViewModel::hideCreateDialog,
            title = { Text("New group trip") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = startDate, onValueChange = { startDate = it }, label = { Text("Start *") }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = endDate, onValueChange = { endDate = it }, label = { Text("End *") }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { palsViewModel.createPal(name, description, startDate, endDate) }) { Text("Create") } },
            dismissButton = { TextButton(onClick = palsViewModel::hideCreateDialog) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Segmented(
    options: List<Pair<String, androidx.compose.ui.graphics.vector.ImageVector>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clay = Clay.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        options.forEachIndexed { i, (label, icon) ->
            val isSel = i == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .then(if (isSel) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) }
                    .padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(18.dp), tint = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ClayIconBadge(icon, size = 64.dp, cornerRadius = 22.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TripCard(trip: TripResponse, gradient: List<Color>, onClick: () -> Unit, onDelete: () -> Unit) {
    ClayCard(onClick = onClick, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClayIconBadge(Icons.Rounded.Route, gradient = gradient, size = 46.dp, cornerRadius = 16.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(trip.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                trip.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetaPill(Icons.Rounded.Place, "${trip.locationCount}")
                    MetaPill(Icons.Rounded.People, "${trip.memberCount}")
                    if (trip.isPublic) Text("public", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PalCard(pal: PalResponse, gradient: List<Color>, onClick: () -> Unit) {
    ClayCard(onClick = onClick, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClayIconBadge(Icons.Rounded.Groups, gradient = gradient, size = 46.dp, cornerRadius = 16.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(pal.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                pal.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Spacer(Modifier.height(4.dp))
                val dateRange = listOfNotNull(pal.startDate, pal.endDate).joinToString(" → ")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetaPill(Icons.Rounded.People, "${pal.memberCount}")
                    if (dateRange.isNotEmpty()) MetaPill(Icons.Rounded.CalendarMonth, dateRange)
                }
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MetaPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
