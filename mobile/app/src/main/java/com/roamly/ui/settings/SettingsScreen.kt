package com.roamly.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onLoggedOut: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Settings, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Settings")
                }
            })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {

            // ── Connection ─────────────────────────────────────────────────
            SettingsCard {
                SectionHeader("Connection", Icons.Filled.Link)
                LabeledValue(Icons.Filled.Link,    "Server",    state.serverUrl.ifBlank { "Not set" })
                LabeledValue(Icons.Filled.Person,  "Username",  state.username.ifBlank { "Not set" })
                LabeledValue(Icons.Filled.Devices, "Device ID", state.deviceId.take(8).ifBlank { "Not set" })
            }

            Spacer(Modifier.height(16.dp))

            // ── Tracking ───────────────────────────────────────────────────
            SettingsCard {
                SectionHeader("Tracking", Icons.Filled.LocationOn)

                // Start / Stop button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            if (state.isTracking) "Tracking active" else "Tracking stopped",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            if (state.isTracking) "Collecting location in background"
                            else "Tap to start sending location",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { viewModel.toggleTracking() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isTracking)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (state.isTracking) "Stop" else "Start")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Sync status
                val syncText = when {
                    state.lastSyncTime == 0L -> "Never synced"
                    state.lastSyncSuccess    -> {
                        val pts = when (state.lastSyncCount) {
                            0    -> "nothing to send"
                            1    -> "1 point sent"
                            else -> "${state.lastSyncCount} points sent"
                        }
                        "Last sync: ${relativeTime(state.lastSyncTime)} ($pts)"
                    }
                    else -> "Last sync failed: ${relativeTime(state.lastSyncTime)}" +
                            if (state.lastSyncError.isNotBlank()) " — ${state.lastSyncError}" else ""
                }
                val syncColor = when {
                    state.lastSyncTime == 0L    -> MaterialTheme.colorScheme.onSurfaceVariant
                    state.lastSyncSuccess       -> Color(0xFF16A34A)
                    else                        -> MaterialTheme.colorScheme.error
                }
                Text(
                    syncText,
                    style = MaterialTheme.typography.bodySmall,
                    color = syncColor,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Sync Now button + spinner
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { viewModel.syncNow() },
                        enabled = !state.isSyncing
                    ) {
                        Text(if (state.isSyncing) "Syncing…" else "Sync Now")
                    }
                    if (state.isSyncing) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Tracking mode
                TrackingModePicker(state.trackingMode, viewModel::setTrackingMode)

                // Accuracy threshold
                AccuracyPicker(state.maxAccuracyM, viewModel::setMaxAccuracyM)

                // Minimum displacement
                DisplacementPicker(state.minDisplacementM, viewModel::setMinDisplacementM)
            }

            Spacer(Modifier.height(16.dp))

            // ── Appearance ─────────────────────────────────────────────────
            SettingsCard {
                SectionHeader("Appearance", Icons.Filled.DarkMode)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Dark Mode", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = state.darkMode, onCheckedChange = viewModel::setDarkMode)
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── About ──────────────────────────────────────────────────────
            SettingsCard {
                SectionHeader("About", Icons.Filled.Info)
                LabeledValue(Icons.Filled.Info,    "App",     "Roamly for Android")
                LabeledValue(Icons.Filled.Devices, "Version", "1.0.0")
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { viewModel.logout(onLoggedOut) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Disconnect")
            }
        }
    }
}

// ── Sub-composables ────────────────────────────────────────────────────────

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun LabeledValue(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = if (label == "Device ID") FontFamily.Monospace else FontFamily.Default)
    }
}

@Composable
private fun TrackingModePicker(current: String, onSelect: (String) -> Unit) {
    val modes = listOf(
        "precision" to "Precision (5s, high battery)",
        "balanced"  to "Balanced (30s, moderate battery)",
        "low_power" to "Low Power (5min, minimal battery)",
    )
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Mode", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(modes.first { it.first == current }.second, style = MaterialTheme.typography.bodySmall)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                modes.forEach { (key, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(key); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun AccuracyPicker(current: Int, onSelect: (Int) -> Unit) {
    val options = listOf(25 to "25 m (strict)", 50 to "50 m", 100 to "100 m (default)", 200 to "200 m", 500 to "500 m")
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Max accuracy", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(options.firstOrNull { it.first == current }?.second ?: "${current}m",
                    style = MaterialTheme.typography.bodySmall)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (v, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(v); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun DisplacementPicker(current: Int, onSelect: (Int) -> Unit) {
    val options = listOf(0 to "0 m (every fix)", 5 to "5 m (default)", 10 to "10 m", 25 to "25 m", 50 to "50 m")
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Min movement", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(options.firstOrNull { it.first == current }?.second ?: "${current}m",
                    style = MaterialTheme.typography.bodySmall)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (v, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(v); expanded = false })
                }
            }
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────

private fun relativeTime(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < 60_000L        -> "just now"
        diff < 3_600_000L     -> "${diff / 60_000}m ago"
        diff < 86_400_000L    -> "${diff / 3_600_000}h ago"
        else                  -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMs))
    }
}
