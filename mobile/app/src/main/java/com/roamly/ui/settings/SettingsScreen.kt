package com.roamly.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NetworkCell
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhonelinkErase
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.tracking.CaptureStats
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasChip
import com.roamly.ui.kit.AtlasDialog
import com.roamly.ui.kit.ButtonKind
import com.roamly.ui.kit.ChipRow
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.ListRow
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PushedScreen
import com.roamly.ui.kit.RowDivider
import com.roamly.ui.kit.RowGroup
import com.roamly.ui.kit.SectionTitle
import com.roamly.ui.kit.SegmentedSwitch
import com.roamly.ui.kit.Tag
import com.roamly.ui.kit.ToggleRow
import com.roamly.ui.kit.dockAwareBottom
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.AtlasType
import com.roamly.ui.update.UpdateViewModel
import java.util.Locale

private val intervalOptions = listOf("5s" to 5, "10s" to 10, "15s" to 15, "30s" to 30, "1m" to 60, "2m" to 120)
private val priorityOptions = listOf("Auto" to "auto", "High" to "high", "Balanced" to "balanced", "Low" to "low")
private val accuracyOptions = listOf("20 m" to 20, "50 m" to 50, "100 m" to 100, "200 m" to 200)

private enum class Edit { DeviceId, ApiKey, Interval, Accuracy }

/** Every setting, grouped. Tracking on/off itself lives on the You tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val update by updateViewModel.state.collectAsState()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val c = Atlas.colors
    LaunchedEffect(Unit) { viewModel.refreshBatteryOptimizationState() }

    var editing by remember { mutableStateOf<Edit?>(null) }
    var showReminderTime by remember { mutableStateOf(false) }
    var showPhoneRationale by remember { mutableStateOf(false) }
    // Held in state: checkSelfPermission() isn't observable, so a row reading it
    // directly would never update once the permission is granted.
    var phoneStatePermitted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PermissionChecker.PERMISSION_GRANTED)
    }
    // Declining leaves cell logging on: scanning needs only fine location. This
    // permission only tells the SIMs apart.
    val phoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { phoneStatePermitted = it }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshBatteryOptimizationState()
    }
    fun requestExemption() {
        for (intent in context.batteryExemptionIntents()) if (runCatching { batteryLauncher.launch(intent) }.isSuccess) return
    }
    // Android 12+: no dialog for exact alarms, only the per-app settings screen.
    fun requestExactAlarms() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val pkg = Uri.parse("package:${context.packageName}")
        runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(pkg)) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(pkg)) } }
    }

    PushedScreen(title = "Settings", onBack = onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = dockAwareBottom()),
        ) {
            // ── GPS ──────────────────────────────────────────────────────
            SectionTitle("GPS & accuracy", Modifier.padding(top = 8.dp))
            Panel(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 16.dp)) {
                OptionLabel("Update interval", "How often to record a fix")
                OptionChips(
                    intervalOptions.map { it.first }, intervalOptions.indexOfFirst { it.second == state.trackingIntervalSecs },
                    custom = "${state.trackingIntervalSecs}s".takeIf { intervalOptions.none { o -> o.second == state.trackingIntervalSecs } },
                    onCustom = { editing = Edit.Interval },
                ) { viewModel.setTrackingIntervalSecs(intervalOptions[it].second) }
                Spacer(Modifier.height(18.dp))
                OptionLabel("GPS priority", "Higher accuracy uses more battery")
                OptionChips(priorityOptions.map { it.first }, priorityOptions.indexOfFirst { it.second == state.locationPriority }) {
                    viewModel.setLocationPriority(priorityOptions[it].second)
                }
                Spacer(Modifier.height(18.dp))
                OptionLabel("Discard fixes worse than", "Rejects low-accuracy readings")
                OptionChips(
                    accuracyOptions.map { it.first }, accuracyOptions.indexOfFirst { it.second == state.maxAccuracyM },
                    custom = "${state.maxAccuracyM} m".takeIf { accuracyOptions.none { o -> o.second == state.maxAccuracyM } },
                    onCustom = { editing = Edit.Accuracy },
                ) { viewModel.setMaxAccuracyM(accuracyOptions[it].second) }
            }
            Spacer(Modifier.height(12.dp))
            RowGroup {
                ToggleRow("Suppress stationary drift", state.suppressStationaryDrift, viewModel::setSuppressStationaryDrift,
                    subtitle = "Pins you in place when parked instead of letting GPS wander", icon = Icons.Rounded.Waves, iconTint = c.cyan)
                RowDivider()
                ToggleRow("Slow down while still", state.slowWhenStill, viewModel::setSlowWhenStill,
                    subtitle = "One fix a minute after 5 minutes in one spot", icon = Icons.Rounded.Schedule, iconTint = c.cyan)
                RowDivider()
                ToggleRow("Adaptive interval", state.adaptiveIntervalEnabled, viewModel::setAdaptiveIntervalEnabled,
                    subtitle = "Every 1 min moving, 5 min still — instead of the interval above", icon = Icons.Rounded.Speed, iconTint = c.cyan)
                RowDivider()
                ToggleRow(
                    "Log cell towers", state.cellLoggingEnabled,
                    { on -> viewModel.setCellLoggingEnabled(on); if (on && !phoneStatePermitted) showPhoneRationale = true },
                    subtitle = if (state.cellLoggingEnabled && !phoneStatePermitted) "Recording your default SIM only — tap below for both"
                    else "Towers you connect to, with signal and band",
                    icon = Icons.Rounded.CellTower, iconTint = c.cyan,
                )
                if (state.cellLoggingEnabled && !phoneStatePermitted) {
                    AtlasButton("Allow Phone permission for both SIMs", { showPhoneRationale = true },
                        Modifier.padding(start = 66.dp, bottom = 10.dp), kind = ButtonKind.Ghost, compact = true)
                }
            }

            // ── Sync ─────────────────────────────────────────────────────
            SectionTitle("Sync", Modifier.padding(top = 22.dp))
            RowGroup {
                ToggleRow("Sync on mobile data", state.syncOnMobileData, viewModel::setSyncOnMobileData,
                    subtitle = "Off uploads on Wi-Fi only", icon = Icons.Rounded.NetworkCell, iconTint = c.violet)
                RowDivider()
                ListRow(
                    syncLine(state),
                    subtitle = when (state.cachedPointCount) { 0 -> "All points uploaded"; 1 -> "1 point waiting"; else -> "%,d points waiting".format(state.cachedPointCount) },
                    icon = Icons.Rounded.Sync, iconTint = c.violet,
                    trailing = { AtlasButton("Sync now", { haptics.click(); viewModel.syncNow() }, loading = state.isSyncing, kind = ButtonKind.Secondary, compact = true, feel = Feel.None) },
                )
            }

            // ── Reliability ──────────────────────────────────────────────
            SectionTitle("Reliability", Modifier.padding(top = 22.dp))
            RowGroup {
                ToggleRow("Start tracking on boot", state.autoStartTracking, viewModel::setAutoStartTracking,
                    subtitle = "Turns tracking on after a reboot", icon = Icons.Rounded.PowerSettingsNew, iconTint = c.lime)
                RowDivider()
                ListRow(
                    "Battery optimisation",
                    subtitle = if (state.batteryOptimizationDisabled) "Roamly is exempt — no Doze gaps" else "On — tracking will pause in Doze",
                    icon = Icons.Rounded.BatteryChargingFull, iconTint = if (state.batteryOptimizationDisabled) c.success else c.warning,
                    trailing = if (state.batteryOptimizationDisabled) ({ Tag("OK", color = c.success) })
                    else ({ AtlasButton("Fix", { requestExemption() }, compact = true) }),
                )
                // The other permission that silently wrecks the Doze cadence: without it
                // the OS fires our alarms every ~9–15 min instead of every interval.
                if (CaptureStats.exactAlarmsDenied) {
                    RowDivider()
                    ListRow("Exact alarms", subtitle = "Denied — Doze cadence limited to ~15 min", icon = Icons.Rounded.Alarm, iconTint = c.danger,
                        trailing = { AtlasButton("Allow", { requestExactAlarms() }, compact = true) })
                }
                RowDivider()
                // OEM killers: no API to opt out, only per-maker steps (dontkillmyapp.com).
                ListRow(
                    "Keep Roamly alive on ${Build.MANUFACTURER.replaceFirstChar { it.titlecase() }}",
                    subtitle = "Some makers kill background apps — here's how to stop it",
                    icon = Icons.Rounded.PhonelinkErase, iconTint = c.amber,
                    trailing = { IconAction(Icons.Rounded.OpenInNew, null, {
                        val mfr = Build.MANUFACTURER.lowercase(Locale.ROOT).trim()
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/$mfr"))) }
                    }, size = 36.dp, iconSize = 16.dp) },
                )
            }

            // ── Appearance ───────────────────────────────────────────────
            SectionTitle("Appearance & feel", Modifier.padding(top = 22.dp))
            Panel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.roamly.ui.kit.IconTile(Icons.Rounded.DarkMode, tint = c.violet, size = 36.dp, iconSize = 18.dp)
                    Spacer(Modifier.width(14.dp))
                    Text("Theme", style = MaterialTheme.typography.bodyLarge, color = c.text)
                }
                Spacer(Modifier.height(12.dp))
                val themeIndex = when (state.darkModeOverride) { null -> 0; true -> 1; false -> 2 }
                SegmentedSwitch(listOf("System", "Night", "Day"), themeIndex, { i ->
                    viewModel.setThemeOverride(when (i) { 0 -> null; 1 -> true; else -> false })
                }, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            RowGroup {
                ToggleRow("Haptic feedback", state.hapticsEnabled, { on ->
                    viewModel.setHapticsEnabled(on)
                    if (on) haptics.confirm()
                }, subtitle = "Subtle taps as you press, switch and swipe", icon = Icons.Rounded.Vibration, iconTint = c.accent)
                RowDivider()
                ToggleRow("Simple Mode", state.simpleModeEnabled, viewModel::setSimpleModeEnabled,
                    subtitle = "Just the map and You — for someone who only needs Family Circle", icon = Icons.Rounded.ViewAgenda, iconTint = c.accent)
            }

            // ── Daily note ───────────────────────────────────────────────
            SectionTitle("Daily note", Modifier.padding(top = 22.dp))
            RowGroup {
                ToggleRow("Evening reminder", state.noteReminderEnabled, { on ->
                    if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PermissionChecker.PERMISSION_GRANTED
                    ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    viewModel.setNoteReminder(on, state.noteReminderMinute)
                }, subtitle = "A nudge to jot down your day. Skipped when you already have.", icon = Icons.Rounded.EditNote, iconTint = c.amber)
                if (state.noteReminderEnabled) {
                    RowDivider()
                    ListRow("Remind me at", subtitle = formatMinuteOfDay(context, state.noteReminderMinute),
                        icon = Icons.Rounded.Schedule, iconTint = c.amber) { showReminderTime = true }
                    RowDivider()
                    ListRow("Send a test", subtitle = "Shows the reminder now, to check it arrives",
                        icon = Icons.Rounded.NotificationsActive, iconTint = c.amber) {
                        if (!com.roamly.notes.QuickNoteReminder.sendTest(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
            }

            // ── Connection ───────────────────────────────────────────────
            SectionTitle("Connection", Modifier.padding(top = 22.dp))
            RowGroup {
                ValueRow(Icons.Rounded.Cloud, "Server", state.serverUrl.ifBlank { "Not set" })
                RowDivider()
                ValueRow(Icons.Rounded.Person, "Username", state.username.ifBlank { "Not set" })
                RowDivider()
                ValueRow(Icons.Rounded.Smartphone, "Device ID", state.deviceId.ifBlank { "Tap to set" }, mono = true) { editing = Edit.DeviceId }
                RowDivider()
                ValueRow(Icons.Rounded.Key, "API key", if (state.apiKey.isBlank()) "Tap to set" else "••••${state.apiKey.takeLast(6)}", mono = true) { editing = Edit.ApiKey }
                RowDivider()
                ValueRow(Icons.Rounded.Save, "CSV log", state.csvPath.ifBlank { "Preparing…" }, mono = true)
            }

            // ── About ────────────────────────────────────────────────────
            SectionTitle("About", Modifier.padding(top = 22.dp))
            RowGroup {
                val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—" }
                ListRow(
                    when {
                        update.available != null -> "Update to ${update.available!!.versionName}"
                        update.upToDate -> "You're up to date"
                        else -> "Check for updates"
                    },
                    subtitle = when {
                        update.downloading -> "Downloading… ${update.progress}%"
                        update.needsInstallPermission -> "Allow “install unknown apps” for Roamly, then tap again"
                        update.error != null -> update.error
                        else -> "Roamly $version"
                    },
                    icon = Icons.Rounded.SystemUpdate, iconTint = c.cyan,
                    trailing = {
                        AtlasButton(
                            if (update.available != null) "Update" else "Check",
                            { if (update.available != null) updateViewModel.downloadAndInstall() else updateViewModel.checkNow() },
                            loading = update.checking || update.downloading,
                            kind = if (update.available != null) ButtonKind.Primary else ButtonKind.Secondary, compact = true,
                        )
                    },
                )
                RowDivider()
                ListRow("Diagnostics", subtitle = "Coverage, gaps, the capture log", icon = Icons.Rounded.TouchApp, iconTint = c.textMuted, onClick = onOpenDiagnostics)
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────
    when (editing) {
        Edit.DeviceId -> ValueDialog("Device ID", state.deviceId, "Identifies this phone on the map", { editing = null }, { viewModel.setDeviceId(it); editing = null })
        Edit.ApiKey -> ValueDialog("API key", state.apiKey, "64-character key from Roamly → Settings → API keys", { editing = null }, { viewModel.setApiKey(it); editing = null }, secret = true)
        Edit.Interval -> ValueDialog("Update interval", state.trackingIntervalSecs.toString(), "Seconds between fixes (5–120)", { editing = null },
            { it.toIntOrNull()?.let(viewModel::setTrackingIntervalSecs); editing = null }, numeric = true)
        Edit.Accuracy -> ValueDialog("Max accuracy", state.maxAccuracyM.toString(), "Discard fixes worse than this many metres", { editing = null },
            { it.toIntOrNull()?.let(viewModel::setMaxAccuracyM); editing = null }, numeric = true)
        null -> Unit
    }
    if (showPhoneRationale) {
        AtlasDialog(
            title = "Phone permission (optional)",
            onDismiss = { showPhoneRationale = false },
            confirmText = "Continue",
            onConfirm = { showPhoneRationale = false; phoneLauncher.launch(Manifest.permission.READ_PHONE_STATE) },
            dismissText = "Skip",
            icon = Icons.Rounded.CellTower,
            body = {
                Text("Roamly uses this only to tell your SIMs apart when logging towers. It never reads your number, IMEI, SIM serial or call history, and never places calls.\n\nSkip it and tower logging still works — for your default SIM only.")
            },
        )
    }
    if (showReminderTime) {
        val picker = rememberTimePickerState(
            initialHour = state.noteReminderMinute / 60,
            initialMinute = state.noteReminderMinute % 60,
            is24Hour = android.text.format.DateFormat.is24HourFormat(context),
        )
        AtlasDialog(
            title = "Remind me at",
            onDismiss = { showReminderTime = false },
            confirmText = "Set",
            onConfirm = { showReminderTime = false; viewModel.setNoteReminder(true, picker.hour * 60 + picker.minute) },
            body = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TimePicker(state = picker, colors = TimePickerDefaults.colors(
                        clockDialColor = c.surfaceHigh, selectorColor = c.accent,
                        timeSelectorSelectedContainerColor = c.accentSoft, timeSelectorSelectedContentColor = c.accent,
                        timeSelectorUnselectedContainerColor = c.surfaceHigh,
                        periodSelectorSelectedContainerColor = c.accentSoft, periodSelectorSelectedContentColor = c.accent,
                    ))
                    Text("Early evening works best: late enough that the day has happened, early enough that you're still up for it.",
                        style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
            },
        )
    }
}

@Composable
private fun OptionLabel(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 18.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Atlas.colors.text)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Atlas.colors.textMuted)
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun OptionChips(
    options: List<String>,
    selected: Int,
    custom: String? = null,
    onCustom: (() -> Unit)? = null,
    onSelect: (Int) -> Unit,
) {
    ChipRow(contentPadding = PaddingValues(horizontal = 18.dp)) {
        options.forEachIndexed { i, label -> AtlasChip(label, i == selected, { onSelect(i) }) }
        if (custom != null && onCustom != null) AtlasChip(custom, true, onCustom)
        if (onCustom != null) AtlasChip("Custom", false, onCustom, icon = Icons.Rounded.Edit)
    }
}

/** A label/value row; tappable to edit when [onClick] is given. */
@Composable
private fun ValueRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, mono: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = Atlas.colors
    ListRow(
        label,
        icon = icon, iconTint = c.textMuted,
        onClick = onClick, showChevron = false,
        trailing = {
            Text(
                value,
                style = if (mono) AtlasType.mono else MaterialTheme.typography.bodySmall,
                color = if (onClick != null) c.accent else c.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 190.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        },
    )
}

private fun formatMinuteOfDay(context: android.content.Context, minuteOfDay: Int): String {
    val time = java.time.LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
    val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    return time.format(java.time.format.DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}
