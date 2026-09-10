package com.roamly.ui.family

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.ui.theme.Clay
import com.roamly.ui.theme.ClayButton
import com.roamly.ui.theme.ClayCard
import com.roamly.ui.theme.ClayOutlinedButton
import com.roamly.ui.theme.ClayTextButton

private enum class FamilyTab(val label: String) { MAP("Map"), PLACES("Places"), CIRCLE("Circle") }

/**
 * Entry point for Family Circle, reached from Settings in both Simple and
 * Advanced Mode (no dedicated bottom-nav slot — the bar is full at six, same
 * reasoning as the Record screen). A segmented tab hosts Map / Places /
 * Circle internally, the same pattern SearchTabScreen already uses for
 * Ask/Search, rather than adding more top-level nav routes.
 */
@Composable
fun FamilyScreen(onBack: () -> Unit, viewModel: FamilyViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    var tab by remember { mutableStateOf(FamilyTab.MAP) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showCirclePicker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(state.selectedCircle?.name ?: "Family Circle") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            },
            actions = {
                if (state.circles.size > 1) {
                    IconButton(onClick = { showCirclePicker = true }) {
                        Icon(Icons.Rounded.SwapHoriz, "Switch circle")
                    }
                }
            },
        )

        when {
            state.loading && state.circles.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.circles.isEmpty() -> {
                EmptyCircleState(onCreate = { showCreateDialog = true })
            }
            state.selectedCircle?.let { !isMemberAccepted(it) } == true -> {
                PendingInviteState(circleName = state.selectedCircle?.name ?: "")
            }
            else -> {
                SecondaryTabRow(selectedTabIndex = tab.ordinal) {
                    FamilyTab.entries.forEach { t ->
                        Tab(
                            selected = tab == t,
                            onClick = { tab = t },
                            text = { Text(t.label) },
                        )
                    }
                }
                when (tab) {
                    FamilyTab.MAP -> FamilyMapContent(state = state, onRefresh = viewModel::refreshLocations)
                    FamilyTab.PLACES -> FamilyPlacesContent(viewModel = viewModel, state = state)
                    FamilyTab.CIRCLE -> FamilyCircleContent(viewModel = viewModel, state = state, onCreateAnother = { showCreateDialog = true })
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateCircleDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                viewModel.createCircle(name) { _, _ -> showCreateDialog = false }
            },
        )
    }

    if (showCirclePicker) {
        CirclePickerDialog(
            circles = state.circles,
            onDismiss = { showCirclePicker = false },
            onPick = { id -> viewModel.selectCircle(id); showCirclePicker = false },
            onCreateNew = { showCirclePicker = false; showCreateDialog = true },
        )
    }
}

private fun isMemberAccepted(circle: com.roamly.data.api.FamilyCircleDetailResponse): Boolean = circle.accepted

@Composable
private fun EmptyCircleState(onCreate: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.People, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("No Family Circle yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Create a circle to see where family members are and get notified when someone arrives at or leaves a place.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        ClayButton(onClick = onCreate, gradient = Clay.colors.secondaryGradient) {
            Text("Create a circle")
        }
    }
}

@Composable
private fun PendingInviteState(circleName: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.MailOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text("Invite pending", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Open the invite link you received to join \"$circleName\" — it explains what sharing your location means before you accept.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun CreateCircleDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Family Circle") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("e.g. \"Our Family\"") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CirclePickerDialog(
    circles: List<com.roamly.data.api.FamilyCircleDetailResponse>,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onCreateNew: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch circle") },
        text = {
            LazyColumn {
                items(circles) { c ->
                    ListItem(
                        headlineContent = { Text(c.name) },
                        supportingContent = if (!c.accepted) { { Text("Invite pending") } } else null,
                        modifier = Modifier.clickable { onPick(c.id) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onCreateNew) { Text("New circle") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
