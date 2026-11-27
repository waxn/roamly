package com.roamly.ui.health

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncLock
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.health.HealthConnectManager
import com.roamly.ui.kit.RefreshBox
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasDialog
import com.roamly.ui.kit.ButtonKind
import com.roamly.ui.kit.EmptyState
import com.roamly.ui.kit.IconTile
import com.roamly.ui.kit.ListRow
import com.roamly.ui.kit.Notice
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PushedScreen
import com.roamly.ui.kit.RowDivider
import com.roamly.ui.kit.RowGroup
import com.roamly.ui.kit.SectionTitle
import com.roamly.ui.kit.StatBlock
import com.roamly.ui.kit.ToggleRow
import com.roamly.ui.kit.dockAwareBottom
import com.roamly.ui.theme.Atlas
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Clock face follows the km / mi switch, like the web Health page: miles = 12-hour.
private fun timeFormat(miles: Boolean) = SimpleDateFormat(if (miles) "d MMM, h:mm a" else "d MMM, HH:mm", Locale.getDefault())

/** Health Connect: connect, choose what to sync, and import workouts by hand. */
@Composable
fun HealthScreen(
    onBack: (() -> Unit)? = null,
    viewModel: HealthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val c = Atlas.colors
    val miles by com.roamly.ui.kit.Units.miles.collectAsState()
    var showWorkouts by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(viewModel.permissionContract()) { granted -> viewModel.onPermissionsResult(granted) }
    val optionalLauncher = rememberLauncherForActivityResult(viewModel.permissionContract()) { _ -> viewModel.onOptionalPermissionsResult() }

    if (showWorkouts) {
        HealthWorkoutsScreen(viewModel = viewModel, onBack = { showWorkouts = false })
        return
    }

    Box(Modifier.fillMaxSize()) {
        PushedScreen(title = "Health", onBack = onBack, subtitle = "via Health Connect") {
            RefreshBox(onRefresh = {viewModel.refresh()}, modifier = Modifier.fillMaxSize()) {
Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = dockAwareBottom()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HealthDataSection()
                SectionTitle("Sync from this phone", Modifier.padding(top = 10.dp))
                when (state.availability) {
                    HealthConnectManager.Availability.UNAVAILABLE -> EmptyState(
                        Icons.Rounded.MonitorHeart, "Health Connect isn't here",
                        body = "It ships with Android 14 and later, and installs from the Play Store on Android 13 and earlier.",
                        tint = c.rose,
                    )
                    HealthConnectManager.Availability.UPDATE_REQUIRED -> EmptyState(
                        Icons.Rounded.MonitorHeart, "Update Health Connect",
                        body = "The version on this phone is too old for Roamly to read from.",
                        tint = c.rose, action = "Update",
                        onAction = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))) }
                        },
                    )
                    HealthConnectManager.Availability.AVAILABLE -> {
                        if (!state.hasPermissions) {
                            Panel(Modifier.fillMaxWidth()) {
                                IconTile(Icons.Rounded.MonitorHeart, tint = c.rose, size = 48.dp, iconSize = 24.dp)
                                Spacer(Modifier.height(14.dp))
                                Text("Bring in your steps", style = MaterialTheme.typography.titleLarge, color = c.text)
                                Text("Roamly reads steps, distance and calories from Health Connect for your server's Health page. It never writes anything back.",
                                    style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                                Spacer(Modifier.height(16.dp))
                                if (state.permissionPermanentlyDenied) {
                                    // Health Connect shows its dialog twice per install; after that only its own settings work.
                                    Text("Health Connect won't show the prompt again — grant access from its settings.",
                                        style = MaterialTheme.typography.bodySmall, color = c.danger)
                                    Spacer(Modifier.height(10.dp))
                                    AtlasButton("Open Health Connect settings", { runCatching { context.startActivity(viewModel.settingsIntent()) } }, Modifier.fillMaxWidth())
                                } else {
                                    AtlasButton("Grant access", { permissionLauncher.launch(viewModel.requiredPermissions()) }, Modifier.fillMaxWidth())
                                }
                            }
                        } else {
                            RowGroup {
                                ToggleRow("Sync health data", state.syncEnabled, viewModel::setSyncEnabled,
                                    subtitle = "Steps, distance and calories, every few hours", icon = Icons.Rounded.Sync, iconTint = c.rose, enabled = !state.busy)
                            }
                            if (!state.hasHistoryPermission || !state.hasBackgroundPermission) {
                                SectionTitle("Optional access", Modifier.padding(top = 10.dp))
                                RowGroup {
                                    if (!state.hasHistoryPermission) {
                                        ListRow("Read more than 30 days", subtitle = "Without it, older data never reaches your Health page",
                                            icon = Icons.Rounded.History, iconTint = c.amber,
                                            trailing = { AtlasButton("Allow", { optionalLauncher.launch(setOf(viewModel.historyPermission())) }, compact = true, kind = ButtonKind.Secondary) })
                                    }
                                    if (!state.hasHistoryPermission && !state.hasBackgroundPermission) RowDivider()
                                    if (!state.hasBackgroundPermission) {
                                        ListRow("Sync in the background", subtitle = "Without it, health data updates only when the app is open",
                                            icon = Icons.Rounded.SyncLock, iconTint = c.amber,
                                            trailing = { AtlasButton("Allow", { optionalLauncher.launch(setOf(viewModel.backgroundPermission())) }, compact = true, kind = ButtonKind.Secondary) })
                                    }
                                }
                            }
                            SectionTitle("Status", Modifier.padding(top = 10.dp))
                            Panel(Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    StatBlock(state.status?.sampleCount?.let { "%,d".format(it) } ?: "—", "records", small = true)
                                    StatBlock(state.status?.workoutCount?.toString() ?: "—", "workouts", small = true)
                                    StatBlock(if (state.lastSyncTime > 0 && state.lastSyncSuccess) "${state.lastSyncCount}" else "—", "last run", small = true)
                                }
                                Spacer(Modifier.height(12.dp))
                                Text("Last sync " + if (state.lastSyncTime > 0) timeFormat(miles).format(Date(state.lastSyncTime)) else "never",
                                    style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                                if (state.lastSyncError.isNotBlank()) Text(state.lastSyncError, style = MaterialTheme.typography.bodySmall, color = c.danger)
                                Spacer(Modifier.height(12.dp))
                                AtlasButton("Sync now", viewModel::syncNow, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, enabled = state.syncEnabled, loading = state.busy)
                            }
                            Panel(Modifier.fillMaxWidth(), onClick = { showWorkouts = true; viewModel.loadWorkouts() }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconTile(Icons.Rounded.DirectionsRun, tint = c.lime)
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Browse workouts", style = MaterialTheme.typography.titleMedium, color = c.text)
                                        Text("Never uploaded automatically — import the ones worth keeping.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            AtlasButton("Delete health data from server", { confirmDelete = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Danger, icon = Icons.Rounded.DeleteOutline)
                        }
                    }
                }
            }
}
        }
        Notice(state.message, viewModel::clearMessage, Modifier.align(Alignment.BottomCenter))
    }

    if (confirmDelete) {
        AtlasDialog(
            title = "Delete health data?",
            onDismiss = { confirmDelete = false },
            confirmText = "Delete",
            onConfirm = { confirmDelete = false; viewModel.deleteAllServerData() },
            destructive = true,
            icon = Icons.Rounded.DeleteOutline,
            body = { Text("This removes every health record and imported workout from your Roamly server. Nothing in Health Connect on this phone is touched, and syncing starts over from scratch.") },
        )
    }
}
