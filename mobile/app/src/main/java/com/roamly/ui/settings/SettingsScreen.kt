package com.roamly.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
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
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.refreshBatteryOptimizationState() }

    // ── Permission handling ────────────────────────────────────────────────
    var showBgLocationRationale by remember { mutableStateOf(false) }

    // Step 2: background location (Android 10+) — only asked after fine is granted
    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — service starts either way; bg location just makes it more reliable */
        viewModel.toggleTracking()
    }

    // Step 1: fine location
    val fineLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) return@rememberLauncherForActivityResult
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PermissionChecker.PERMISSION_GRANTED
        ) {
            showBgLocationRationale = true
        } else {
            viewModel.toggleTracking()
        }
    }

    // Notification permission (Android 13+)
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }
    val batteryOptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refreshBatteryOptimizationState() }

    fun startTrackingWithPermissions() {
        // Request notification permission on Android 13+ (non-blocking)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PermissionChecker.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Check fine location
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PermissionChecker.PERMISSION_GRANTED
        ) {
            fineLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PermissionChecker.PERMISSION_GRANTED
        ) {
            showBgLocationRationale = true
        } else {
            viewModel.toggleTracking()
        }
    }

    // Background location rationale dialog
    if (showBgLocationRationale) {
        AlertDialog(
            onDismissRequest = { showBgLocationRationale = false; viewModel.toggleTracking() },
            title = { Text("Background Location") },
            text = { Text("For tracking while the screen is off, select \"Allow all the time\" on the next screen. You can skip this and tracking will still work while the app is open.") },
            confirmButton = {
                TextButton(onClick = {
                    showBgLocationRationale = false
                    bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { showBgLocationRationale = false; viewModel.toggleTracking() }) {
                    Text("Skip")
                }
            }
        )
    }

    // Stop-tracking confirmation
    var showStopConfirm by remember { mutableStateOf(false) }
    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("Stop tracking?") },
            text = { Text("Location tracking will stop and no more points will be sent. Any cached points will still be uploaded when you reconnect.") },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirm = false
                    viewModel.toggleTracking()
                }) { Text("Stop", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // Edit dialog state
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }

    editTarget?.let { target ->
        EditDialog(
            title = target.title,
            initialValue = target.currentValue,
            keyboardType = target.keyboardType,
            hint = target.hint,
            onConfirm = { value ->
                when (target) {
                    is EditTarget.DeviceId   -> viewModel.setDeviceId(value)
                    is EditTarget.ApiKey     -> viewModel.setApiKey(value)
                    is EditTarget.Interval   -> value.toIntOrNull()?.let { viewModel.setTrackingIntervalSecs(it) }
                    is EditTarget.Accuracy   -> value.toIntOrNull()?.let { viewModel.setMaxAccuracyM(it) }
                }
                editTarget = null
            },
            onDismiss = { editTarget = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Settings, contentDescription = null)
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
                SectionHeader("Connection", Icons.Rounded.Link)

                LabeledValue(Icons.Rounded.Link, "Server", state.serverUrl.ifBlank { "Not set" })

                LabeledValue(Icons.Rounded.Person, "Username", state.username.ifBlank { "Not set" })

                // Device ID — tappable
                EditableRow(
                    icon = Icons.Rounded.Devices,
                    label = "Device ID",
                    value = state.deviceId.ifBlank { "Tap to set" },
                    mono = true,
                    onClick = {
                        editTarget = EditTarget.DeviceId(
                            currentValue = state.deviceId,
                            title = "Device ID",
                            hint = "e.g. pixel9pro — identifies this device on the map"
                        )
                    }
                )

                // API key — tappable, masked
                EditableRow(
                    icon = Icons.Rounded.Key,
                    label = "API Key",
                    value = if (state.apiKey.isBlank()) "Tap to set"
                            else "••••${state.apiKey.takeLast(6)}",
                    mono = true,
                    onClick = {
                        editTarget = EditTarget.ApiKey(
                            currentValue = state.apiKey,
                            title = "API Key",
                            hint = "64-char hex key from Roamly → Settings → API Keys"
                        )
                    }
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Tracking ───────────────────────────────────────────────────
            SettingsCard {
                SectionHeader("Tracking", Icons.Rounded.LocationOn)

                // Start / Stop
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            if (state.isTracking) "Tracking active" else "Tracking stopped",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            if (state.isTracking) "Sending location in background"
                            else "Tap to start sending location",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = {
                            if (state.isTracking) showStopConfirm = true
                            else startTrackingWithPermissions()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isTracking) MaterialTheme.colorScheme.error
                                             else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (state.isTracking) "Stop" else "Start")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Start tracking on startup",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = state.autoStartTracking,
                        onCheckedChange = viewModel::setAutoStartTracking
                    )
                }
                Text(
                    "Automatically starts background tracking after device boot or app launch (when location permission is granted).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Sync on mobile data",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = state.syncOnMobileData,
                        onCheckedChange = viewModel::setSyncOnMobileData
                    )
                }
                Text(
                    "When off, uploads wait for unmetered Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Sync status
                val syncColor = when {
                    state.lastSyncTime == 0L -> MaterialTheme.colorScheme.onSurfaceVariant
                    state.lastSyncSuccess    -> Color(0xFF16A34A)
                    else                     -> MaterialTheme.colorScheme.error
                }
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
                Text(
                    syncText,
                    style = MaterialTheme.typography.bodySmall,
                    color = syncColor,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

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

                // Interval input
                EditableRow(
                    icon = Icons.Rounded.Timer,
                    label = "Interval (seconds)",
                    value = state.trackingIntervalSecs.toString(),
                    onClick = {
                        editTarget = EditTarget.Interval(
                            currentValue = state.trackingIntervalSecs.toString(),
                            title = "Update Interval",
                            hint = "Seconds between GPS fixes (5–3600)"
                        )
                    }
                )

                // Accuracy input
                EditableRow(
                    icon = Icons.Rounded.GpsFixed,
                    label = "Min accuracy (m)",
                    value = state.maxAccuracyM.toString(),
                    subLabel = "Only send if GPS is within this many metres",
                    onClick = {
                        editTarget = EditTarget.Accuracy(
                            currentValue = state.maxAccuracyM.toString(),
                            title = "Min Accuracy (metres)",
                            hint = "Discard fixes with accuracy worse than this (e.g. 50)"
                        )
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                LabeledValue(
                    Icons.Rounded.Save,
                    "CSV export",
                    state.csvPath.ifBlank { "Preparing..." }
                )
                LabeledValue(
                    Icons.Rounded.BatteryFull,
                    "Battery optimization",
                    if (state.batteryOptimizationDisabled) "Disabled for Roamly" else "Enabled"
                )
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        batteryOptLauncher.launch(intent)
                    }
                ) { Text("Improve background reliability") }
            }

            Spacer(Modifier.height(16.dp))

            // ── Appearance ─────────────────────────────────────────────────
            SettingsCard {
                SectionHeader("Appearance", Icons.Rounded.DarkMode)
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
                SectionHeader("About", Icons.Rounded.Info)
                LabeledValue(Icons.Rounded.Info,    "App",     "Roamly for Android")
                LabeledValue(Icons.Rounded.Devices, "Version", "1.0.0")
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { viewModel.logout(onLoggedOut) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Disconnect")
            }
        }
    }
}

// ── Edit dialog ────────────────────────────────────────────────────────────

@Composable
private fun EditDialog(
    title: String,
    initialValue: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    hint: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    var showKey by remember { mutableStateOf(false) }
    val isApiKey = title.contains("API", ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    visualTransformation = if (isApiKey && !showKey)
                        PasswordVisualTransformation() else VisualTransformation.None,
                    trailingIcon = if (isApiKey) {
                        {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (showKey) "Hide" else "Show"
                                )
                            }
                        }
                    } else null,
                    label = { Text(title) }
                )
                if (hint.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(hint, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ── Sealed edit targets ────────────────────────────────────────────────────

private sealed class EditTarget(
    val currentValue: String,
    val title: String,
    val hint: String,
    val keyboardType: KeyboardType = KeyboardType.Text,
) {
    class DeviceId(currentValue: String, title: String, hint: String) :
        EditTarget(currentValue, title, hint, KeyboardType.Text)
    class ApiKey(currentValue: String, title: String, hint: String) :
        EditTarget(currentValue, title, hint, KeyboardType.Text)
    class Interval(currentValue: String, title: String, hint: String) :
        EditTarget(currentValue, title, hint, KeyboardType.Number)
    class Accuracy(currentValue: String, title: String, hint: String) :
        EditTarget(currentValue, title, hint, KeyboardType.Number)
}

// ── Row composables ────────────────────────────────────────────────────────

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
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EditableRow(
    icon: ImageVector,
    label: String,
    value: String,
    subLabel: String = "",
    mono: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (subLabel.isNotBlank()) {
                Text(subLabel, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Rounded.Edit,
            contentDescription = "Edit",
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────

private fun relativeTime(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < 60_000L     -> "just now"
        diff < 3_600_000L  -> "${diff / 60_000}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000}h ago"
        else               -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMs))
    }
}
