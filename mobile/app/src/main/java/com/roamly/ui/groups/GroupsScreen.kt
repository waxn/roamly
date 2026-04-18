package com.roamly.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.PalResponse
import com.roamly.data.api.TripResponse
import com.roamly.ui.pals.PalsViewModel
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

    Scaffold(
        topBar = { TopAppBar(title = { Text("Groups") }) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (selectedTab == 0) tripsViewModel.showCreateDialog()
                    else palsViewModel.showCreateDialog()
                }
            ) {
                Icon(Icons.Filled.Add, contentDescription = if (selectedTab == 0) "New Trip" else "New Pal")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Trips") },
                    icon = { Icon(Icons.Filled.Route, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Pals") },
                    icon = { Icon(Icons.Filled.People, contentDescription = null) }
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> {
                        when {
                            tripsState.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            tripsState.trips.isEmpty() -> {
                                Column(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Filled.Route, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(8.dp))
                                    Text("No trips yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            else -> LazyColumn {
                                items(tripsState.trips) { trip ->
                                    TripCard(trip, onClick = { onTripClick(trip.id) }, onDelete = { tripsViewModel.deleteTrip(trip.id) })
                                }
                            }
                        }
                        tripsState.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
                        }
                    }
                    1 -> {
                        when {
                            palsState.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            palsState.pals.isEmpty() -> {
                                Column(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Filled.People, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(8.dp))
                                    Text("No pals yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Tap + to create one", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            else -> LazyColumn {
                                items(palsState.pals) { pal ->
                                    PalCard(pal, onClick = { onPalClick(pal.id) })
                                }
                            }
                        }
                        palsState.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
                        }
                    }
                }
            }
        }
    }

    if (tripsState.showCreateDialog) {
        var name by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = tripsViewModel::hideCreateDialog,
            title = { Text("New Trip") },
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
            title = { Text("New Group Trip") },
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
private fun TripCard(trip: TripResponse, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onClick)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(trip.name, style = MaterialTheme.typography.titleMedium)
                trip.description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                val dateRange = listOfNotNull(trip.startTime?.take(10), trip.endTime?.take(10)).joinToString(" → ")
                if (dateRange.isNotEmpty()) Text(dateRange, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${trip.locationCount} pts", style = MaterialTheme.typography.labelSmall)
                    Text("${trip.memberCount} member${if (trip.memberCount != 1) "s" else ""}", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PalCard(pal: PalResponse, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(pal.name, style = MaterialTheme.typography.titleMedium)
            pal.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            val dateRange = listOfNotNull(pal.startDate, pal.endDate).joinToString(" → ")
            if (dateRange.isNotEmpty()) Text(dateRange, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${pal.memberCount} members${pal.creator?.let { " · by $it" } ?: ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
