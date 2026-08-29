package com.roamly.ui.health

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.health.HealthConnectManager
import com.roamly.ui.theme.ClayButton
import com.roamly.ui.theme.ClayCard
import com.roamly.ui.theme.ClayOutlinedButton
import com.roamly.ui.theme.ClaySectionHeader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME_FMT = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

@Composable
fun HealthScreen(
    onBack: () -> Unit,
    viewModel: HealthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var showWorkouts by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Health Connect's own permission contract. Declared here in the composable
    // body, matching how SettingsScreen declares its permission launchers.
    val permissionLauncher = rememberLauncherForActivityResult(
        viewModel.permissionContract()
    ) { granted -> viewModel.onPermissionsResult(granted) }

    val optionalLauncher = rememberLauncherForActivityResult(
        viewModel.permissionContract()
    ) { _ -> viewModel.onOptionalPermissionsResult() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    if (showWorkouts) {
        HealthWorkoutsScreen(viewModel = viewModel, onBack = { showWorkouts = false })
        return
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete health data?") },
            text = {
                Text("This removes every health record and imported workout from your Roamly " +
                    "server. Nothing in Health Connect on this phone is touched, and syncing " +
                    "will start over from scratch.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteAllServerData()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
                Text("Health", style = MaterialTheme.typography.titleLarge,
                     color = MaterialTheme.colorScheme.onBackground)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state.availability) {
                    HealthConnectManager.Availability.UNAVAILABLE -> UnavailableCard()
                    HealthConnectManager.Availability.UPDATE_REQUIRED -> UpdateRequiredCard {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                                "market://details?id=com.google.android.apps.healthdata")))
                        }
                    }
                    HealthConnectManager.Availability.AVAILABLE -> {
                        ConnectCard(
                            state = state,
                            onGrant = { permissionLauncher.launch(viewModel.requiredPermissions()) },
                            onOpenSettings = {
                                runCatching { context.startActivity(viewModel.settingsIntent()) }
                            },
                            onToggle = viewModel::setSyncEnabled,
                        )

                        if (state.hasPermissions) {
                            OptionalPermissionsCard(
                                state = state,
                                onRequestHistory = {
                                    optionalLauncher.launch(setOf(viewModel.historyPermission()))
                                },
                                onRequestBackground = {
                                    optionalLauncher.launch(setOf(viewModel.backgroundPermission()))
                                },
                            )

                            StatusCard(state = state, onSyncNow = viewModel::syncNow)

                            ClayCard {
                                ClaySectionHeader(title = "Workouts", icon = Icons.Rounded.DirectionsRun)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Workouts are never uploaded automatically. Browse the exercise " +
                                        "sessions on this phone and import the ones worth keeping.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                ClayButton(
                                    onClick = {
                                        showWorkouts = true
                                        viewModel.loadWorkouts()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Browse workouts") }
                            }

                            ClayCard {
                                ClaySectionHeader(title = "Danger zone", icon = Icons.Rounded.Settings)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Remove every health record and imported workout from the server.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                ClayOutlinedButton(
                                    onClick = { confirmDelete = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    contentColor = MaterialTheme.colorScheme.error,
                                ) { Text("Delete health data") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun UnavailableCard() {
    ClayCard {
        ClaySectionHeader(title = "Health Connect", icon = Icons.Rounded.MonitorHeart)
        Spacer(Modifier.height(10.dp))
        Text(
            "Health Connect isn't available on this device, so Roamly can't read step data " +
                "here. It ships with Android 14 and later, and installs from the Play Store on " +
                "Android 13 and earlier.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun UpdateRequiredCard(onUpdate: () -> Unit) {
    ClayCard {
        ClaySectionHeader(title = "Update Health Connect", icon = Icons.Rounded.MonitorHeart)
        Spacer(Modifier.height(10.dp))
        Text(
            "The version of Health Connect on this phone is too old for Roamly to read from.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        ClayButton(onClick = onUpdate, modifier = Modifier.fillMaxWidth()) { Text("Update") }
    }
}

@Composable
private fun ConnectCard(
    state: HealthUiState,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    ClayCard {
        ClaySectionHeader(title = "Health Connect", icon = Icons.Rounded.MonitorHeart)
        Spacer(Modifier.height(10.dp))

        if (!state.hasPermissions) {
            Text(
                "Roamly can read your steps, distance and calories from Health Connect and show " +
                    "them on the Health page of your server. It never writes anything back.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            if (state.permissionPermanentlyDenied) {
                // Health Connect only shows its dialog twice per install; after
                // that the request returns instantly with nothing granted, so
                // the only way through is Health Connect's own settings.
                Text(
                    "Health Connect won't show the permission prompt again. Grant access from " +
                        "its own settings instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(10.dp))
                ClayButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Health Connect settings")
                }
            } else {
                ClayButton(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant access")
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sync health data", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Steps, distance and calories, every few hours.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.syncEnabled,
                    onCheckedChange = onToggle,
                    enabled = !state.busy,
                )
            }
        }
    }
}

@Composable
private fun OptionalPermissionsCard(
    state: HealthUiState,
    onRequestHistory: () -> Unit,
    onRequestBackground: () -> Unit,
) {
    // Both are genuinely optional upgrades, each with its own consequence when
    // declined — so they get their own rows and their own explanations rather
    // than being bundled into the initial grant.
    if (state.hasHistoryPermission && state.hasBackgroundPermission) return

    ClayCard {
        ClaySectionHeader(title = "Optional access", icon = Icons.Rounded.Settings)

        if (!state.hasHistoryPermission) {
            Spacer(Modifier.height(12.dp))
            Text("Read more than 30 days", fontWeight = FontWeight.SemiBold)
            Text(
                "Without this, Health Connect only hands over the last 30 days — anything older " +
                    "simply won't appear on your Health page.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ClayOutlinedButton(onClick = onRequestHistory, modifier = Modifier.fillMaxWidth()) {
                Text("Allow older data")
            }
        }

        if (!state.hasBackgroundPermission) {
            Spacer(Modifier.height(16.dp))
            Text("Sync in the background", fontWeight = FontWeight.SemiBold)
            Text(
                "Without this, Roamly can only read health data while the app is open, so your " +
                    "Health page updates when you next launch it rather than on its own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ClayOutlinedButton(onClick = onRequestBackground, modifier = Modifier.fillMaxWidth()) {
                Text("Allow background sync")
            }
        }
    }
}

@Composable
private fun StatusCard(state: HealthUiState, onSyncNow: () -> Unit) {
    ClayCard {
        ClaySectionHeader(title = "Sync status", icon = Icons.Rounded.MonitorHeart)
        Spacer(Modifier.height(10.dp))

        val status = state.status
        InfoRow("Records on server", status?.sampleCount?.toString() ?: "—")
        InfoRow("Workouts imported", status?.workoutCount?.toString() ?: "—")
        InfoRow(
            "Last sync",
            if (state.lastSyncTime > 0) TIME_FMT.format(Date(state.lastSyncTime)) else "never",
        )
        if (state.lastSyncTime > 0 && state.lastSyncSuccess) {
            InfoRow("Last run", "${state.lastSyncCount} records")
        }
        if (state.lastSyncError.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                state.lastSyncError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(12.dp))
        ClayOutlinedButton(
            onClick = onSyncNow,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.syncEnabled,
        ) { Text("Sync now") }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
