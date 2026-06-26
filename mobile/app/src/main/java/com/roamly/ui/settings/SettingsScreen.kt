package com.roamly.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.tracking.TrackingCoordinator
import com.roamly.ui.theme.Clay
import com.roamly.ui.theme.ClayButton
import com.roamly.ui.theme.ClayCard
import com.roamly.ui.theme.ClayIconBadge
import com.roamly.ui.theme.ClaySectionHeader
import com.roamly.ui.theme.Coral
import com.roamly.ui.theme.Sunshine
import com.roamly.ui.theme.Teal
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
    val clay = Clay.colors
    LaunchedEffect(Unit) { viewModel.refreshBatteryOptimizationState() }

    // ── Permission handling ────────────────────────────────────────────────
    var showBgLocationRationale by remember { mutableStateOf(false) }
    // Block-until-granted battery-exemption gate, shown before tracking actually starts.
    var showExemptionGate by remember { mutableStateOf(false) }
    // True while we're mid-flow waiting for the system exemption dialog to return, so
    // granting it auto-continues into starting tracking.
    var pendingStart by remember { mutableStateOf(false) }

    // The exemption is the single make-or-break setting: without it Android throttles
    // location to once every several minutes in Doze. So starting tracking is gated on it,
    // with an explicit degraded escape hatch.
    fun startTrackingGated() {
        if (TrackingCoordinator.isIgnoringBatteryOptimizations(context)) viewModel.toggleTracking()
        else showExemptionGate = true
    }

    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startTrackingGated() }

    val fineLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) return@rememberLauncherForActivityResult
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PermissionChecker.PERMISSION_GRANTED
        ) showBgLocationRationale = true else startTrackingGated()
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val batteryOptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshBatteryOptimizationState()
        // Returned from the system exemption dialog. If it's now granted and we were mid
        // start-flow, dismiss the gate and continue into tracking automatically.
        if (pendingStart && TrackingCoordinator.isIgnoringBatteryOptimizations(context)) {
            pendingStart = false
            showExemptionGate = false
            viewModel.toggleTracking()
        }
    }

    // Fire the direct system exemption dialog; fall back to the battery-optimization
    // settings list if the OEM blocks the direct intent.
    fun requestBatteryExemption() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        val ok = runCatching { batteryOptLauncher.launch(direct) }.isSuccess
        if (!ok) runCatching {
            batteryOptLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    fun startTrackingWithPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PermissionChecker.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PermissionChecker.PERMISSION_GRANTED
        ) fineLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PermissionChecker.PERMISSION_GRANTED
        ) showBgLocationRationale = true
        else startTrackingGated()
    }

    if (showBgLocationRationale) {
        AlertDialog(
            onDismissRequest = { showBgLocationRationale = false; startTrackingGated() },
            title = { Text("Background location") },
            text = { Text("For tracking while the screen is off, choose \"Allow all the time\" on the next screen. You can skip it — tracking still works while the app is open.") },
            confirmButton = {
                TextButton(onClick = {
                    showBgLocationRationale = false
                    bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { showBgLocationRationale = false; startTrackingGated() }) { Text("Skip") }
            }
        )
    }

    // Block-until-granted gate: tracking is throttled to multi-minute gaps in Doze without
    // the battery-optimization exemption, so we require it before starting (with an explicit
    // degraded escape hatch). The system grant dialog auto-continues into tracking.
    if (showExemptionGate) {
        AlertDialog(
            onDismissRequest = { /* required choice — ignore outside taps */ },
            title = { Text("Allow gap-free tracking") },
            text = {
                Text(
                    "Android throttles Roamly's location to once every several minutes while the " +
                        "screen is off unless it's exempt from battery optimization — the cause of " +
                        "5–20 minute gaps. Grant the exemption for reliable 24/7 tracking."
                )
            },
            confirmButton = {
                TextButton(onClick = { pendingStart = true; requestBatteryExemption() }) {
                    Text("Grant exemption")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExemptionGate = false
                    pendingStart = false
                    viewModel.toggleTracking()
                }) { Text("Track anyway (gaps)", color = MaterialTheme.colorScheme.error) }
            }
        )
    }

    var showStopConfirm by remember { mutableStateOf(false) }
    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("Stop tracking?") },
            text = {
                Text(
                    "This turns location tracking completely off — including the background " +
                        "keep-alive and starting on reboot. It stays off until you turn it back " +
                        "on. Cached points still upload when you reconnect."
                )
            },
            confirmButton = {
                TextButton(onClick = { showStopConfirm = false; viewModel.toggleTracking() }) {
                    Text("Stop", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showStopConfirm = false }) { Text("Keep tracking") } }
        )
    }

    var advancedGps by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }

    // Tracking needs an API key. When the user tries to start without one, offer to
    // create the single durable key automatically or paste an existing one.
    if (state.apiKeyRequired) {
        AlertDialog(
            onDismissRequest = { if (!state.apiKeyBusy) viewModel.dismissApiKeyRequired() },
            title = { Text("Tracking needs an API key") },
            text = {
                Column {
                    Text(
                        "An API key authenticates your location uploads. Create one now — " +
                            "it's a single key that works forever — or enter a key you already have."
                    )
                    if (state.apiKeyError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(state.apiKeyError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.createApiKey() }, enabled = !state.apiKeyBusy) {
                    if (state.apiKeyBusy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Create key")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissApiKeyRequired()
                        editTarget = EditTarget.ApiKey(state.apiKey, "API Key", "64-char hex key from Roamly → Settings → API Keys")
                    },
                    enabled = !state.apiKeyBusy,
                ) { Text("Enter manually") }
            },
        )
    }
    editTarget?.let { target ->
        EditDialog(
            title = target.title,
            initialValue = target.currentValue,
            keyboardType = target.keyboardType,
            hint = target.hint,
            onConfirm = { value ->
                when (target) {
                    is EditTarget.DeviceId -> viewModel.setDeviceId(value)
                    is EditTarget.ApiKey   -> viewModel.setApiKey(value)
                    is EditTarget.Interval -> value.toIntOrNull()?.let { viewModel.setTrackingIntervalSecs(it) }
                    is EditTarget.Accuracy -> value.toIntOrNull()?.let { viewModel.setMaxAccuracyM(it) }
                }
                editTarget = null
            },
            onDismiss = { editTarget = null }
        )
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Title
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            ClayIconBadge(Icons.Rounded.Settings, gradient = clay.tertiaryGradient, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Text("Settings", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        }

        // ── Tracking hero ────────────────────────────────────────────────
        TrackingHero(
            isTracking = state.isTracking,
            onToggle = { if (state.isTracking) showStopConfirm = true else startTrackingWithPermissions() },
        )

        // Persistent make-or-break warning: while not exempt, tracking will have multi-minute
        // gaps in Doze no matter what. Keep it loud and one tap from the fix.
        if (!state.batteryOptimizationDisabled) {
            ExemptionWarningBanner(onFix = { requestBatteryExemption() })
        }

        // ── GPS settings ─────────────────────────────────────────────────
        ClayCard {
            ClaySectionHeader("GPS & accuracy", Icons.Rounded.GpsFixed, gradient = clay.secondaryGradient)
            Spacer(Modifier.height(14.dp))

            SettingLabel("Update interval", "How often to record a fix")
            Spacer(Modifier.height(8.dp))
            ChipSelector(
                options = intervalOptions.map { it.first },
                selectedIndex = intervalOptions.indexOfFirst { it.second == state.trackingIntervalSecs }.let { if (it < 0) -1 else it },
                onSelect = { viewModel.setTrackingIntervalSecs(intervalOptions[it].second) },
                onLongEdit = { editTarget = EditTarget.Interval(state.trackingIntervalSecs.toString(), "Update interval", "Seconds between fixes (5–120)") },
                customLabel = if (intervalOptions.none { it.second == state.trackingIntervalSecs }) "${state.trackingIntervalSecs}s" else null,
            )

            Spacer(Modifier.height(16.dp))
            SettingLabel("GPS priority", "Higher accuracy uses more battery")
            Spacer(Modifier.height(8.dp))
            ChipSelector(
                options = priorityOptions.map { it.first },
                selectedIndex = priorityOptions.indexOfFirst { it.second == state.locationPriority },
                onSelect = { viewModel.setLocationPriority(priorityOptions[it].second) },
            )

            Spacer(Modifier.height(16.dp))
            SettingLabel("Discard fixes worse than", "Reject low-accuracy GPS readings")
            Spacer(Modifier.height(8.dp))
            ChipSelector(
                options = accuracyOptions.map { it.first },
                selectedIndex = accuracyOptions.indexOfFirst { it.second == state.maxAccuracyM },
                onSelect = { viewModel.setMaxAccuracyM(accuracyOptions[it].second) },
                onLongEdit = { editTarget = EditTarget.Accuracy(state.maxAccuracyM.toString(), "Min accuracy (m)", "Discard fixes worse than this many metres") },
                customLabel = if (accuracyOptions.none { it.second == state.maxAccuracyM }) "${state.maxAccuracyM}m" else null,
            )

            Spacer(Modifier.height(16.dp))
            ToggleRow(
                title = "Advanced",
                subtitle = "Type exact values for each setting",
                checked = advancedGps,
                onCheckedChange = { advancedGps = it },
            )
            if (advancedGps) {
                Spacer(Modifier.height(12.dp))
                AdvancedNumberField("Interval (seconds)", state.trackingIntervalSecs, "5–120") { viewModel.setTrackingIntervalSecs(it) }
                Spacer(Modifier.height(10.dp))
                AdvancedNumberField("Max accuracy (metres)", state.maxAccuracyM, "discard worse fixes") { viewModel.setMaxAccuracyM(it) }
            }
        }

        // ── Sync ─────────────────────────────────────────────────────────
        ClayCard {
            ClaySectionHeader("Sync", Icons.Rounded.CloudSync, gradient = clay.secondaryGradient)
            Spacer(Modifier.height(12.dp))

            ToggleRow(
                title = "Sync on mobile data",
                subtitle = "Off = upload only on Wi-Fi",
                checked = state.syncOnMobileData,
                onCheckedChange = viewModel::setSyncOnMobileData,
            )

            Spacer(Modifier.height(12.dp))
            val syncColor = when {
                state.lastSyncTime == 0L -> MaterialTheme.colorScheme.onSurfaceVariant
                state.lastSyncSuccess    -> Teal
                else                     -> MaterialTheme.colorScheme.error
            }
            val syncText = when {
                state.lastSyncTime == 0L -> "Never synced yet"
                state.lastSyncSuccess    -> {
                    val pts = when (state.lastSyncCount) {
                        0 -> "nothing to send"; 1 -> "1 point sent"; else -> "${state.lastSyncCount} points sent"
                    }
                    "Last sync ${relativeTime(state.lastSyncTime)} · $pts"
                }
                else -> "Last sync failed ${relativeTime(state.lastSyncTime)}" +
                        if (state.lastSyncError.isNotBlank()) " — ${state.lastSyncError}" else ""
            }
            Text(syncText, style = MaterialTheme.typography.bodySmall, color = syncColor)

            Spacer(Modifier.height(6.dp))
            val cached = state.cachedPointCount
            Text(
                when (cached) {
                    0 -> "All points uploaded"
                    1 -> "1 point waiting to upload"
                    else -> "$cached points waiting to upload"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (cached == 0) Teal else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            ClayButton(
                onClick = viewModel::syncNow,
                enabled = !state.isSyncing,
                gradient = clay.secondaryGradient,
                contentColor = Color(0xFF052B26),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isSyncing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF052B26))
                    Spacer(Modifier.width(8.dp))
                    Text("Syncing…", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Rounded.CloudUpload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sync now", fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── Reliability ──────────────────────────────────────────────────
        ClayCard {
            ClaySectionHeader("Reliability", Icons.Rounded.Bolt, gradient = listOf(Sunshine, Coral))
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                title = "Start tracking on boot",
                subtitle = "Turn tracking on automatically after a reboot — works even while tracking is off",
                checked = state.autoStartTracking,
                onCheckedChange = viewModel::setAutoStartTracking,
            )
            Spacer(Modifier.height(8.dp))
            InfoRow(
                Icons.Rounded.BatteryFull,
                "Battery optimization",
                if (state.batteryOptimizationDisabled) "Disabled for Roamly ✓" else "Enabled (may pause tracking)",
                valueColor = if (state.batteryOptimizationDisabled) Teal else MaterialTheme.colorScheme.error,
            )
            if (!state.batteryOptimizationDisabled) {
                Spacer(Modifier.height(10.dp))
                ClayButton(
                    onClick = { requestBatteryExemption() },
                    gradient = listOf(Sunshine, Coral),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Bolt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Disable battery optimization", fontWeight = FontWeight.Bold)
                }
            }

            // OEM-killer onboarding. Xiaomi/Samsung/Huawei/Oppo etc. kill background
            // apps aggressively with no API to opt out — the only real fix is guiding
            // the user to the per-OEM settings catalogued at dontkillmyapp.com.
            Spacer(Modifier.height(10.dp))
            NoteRow(
                Icons.Rounded.PhonelinkErase,
                "Aggressive OEM battery killers",
                "Xiaomi, Samsung, Huawei & others need a manual allowlist step",
            )
            Spacer(Modifier.height(10.dp))
            ClayButton(
                onClick = {
                    val mfr = Build.MANUFACTURER.lowercase(Locale.ROOT).trim()
                    val url = "https://dontkillmyapp.com/${if (mfr.isBlank()) "" else mfr}"
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                },
                gradient = listOf(Coral, Sunshine),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("How to keep Roamly alive on ${Build.MANUFACTURER}", fontWeight = FontWeight.Bold)
            }
        }

        // ── Connection ───────────────────────────────────────────────────
        ClayCard {
            ClaySectionHeader("Connection", Icons.Rounded.Link, gradient = clay.tertiaryGradient)
            Spacer(Modifier.height(12.dp))
            InfoRow(Icons.Rounded.Cloud, "Server", state.serverUrl.ifBlank { "Not set" })
            Spacer(Modifier.height(8.dp))
            InfoRow(Icons.Rounded.Person, "Username", state.username.ifBlank { "Not set" })
            Spacer(Modifier.height(8.dp))
            EditableRow(
                Icons.Rounded.Smartphone, "Device ID", state.deviceId.ifBlank { "Tap to set" }, mono = true,
                onClick = { editTarget = EditTarget.DeviceId(state.deviceId, "Device ID", "Identifies this device on the map") },
            )
            Spacer(Modifier.height(8.dp))
            EditableRow(
                Icons.Rounded.Key, "API key",
                if (state.apiKey.isBlank()) "Tap to set" else "••••${state.apiKey.takeLast(6)}", mono = true,
                onClick = { editTarget = EditTarget.ApiKey(state.apiKey, "API Key", "64-char hex key from Roamly → Settings → API Keys") },
            )
            Spacer(Modifier.height(8.dp))
            InfoRow(Icons.Rounded.Save, "CSV export", state.csvPath.ifBlank { "Preparing…" })
        }

        // ── Appearance ───────────────────────────────────────────────────
        ClayCard {
            ClaySectionHeader("Appearance", Icons.Rounded.Palette, gradient = clay.tertiaryGradient)
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                title = "Dark mode",
                subtitle = "Switch between the night and day clay themes",
                // Until the user picks explicitly, mirror the system theme so the
                // switch matches what's actually on screen.
                checked = state.darkModeOverride ?: androidx.compose.foundation.isSystemInDarkTheme(),
                onCheckedChange = viewModel::setDarkMode,
            )
        }

        // ── About ────────────────────────────────────────────────────────
        ClayCard {
            ClaySectionHeader("About", Icons.Rounded.Info, gradient = clay.secondaryGradient)
            Spacer(Modifier.height(12.dp))
            InfoRow(Icons.Rounded.Explore, "App", "Roamly for Android")
            Spacer(Modifier.height(8.dp))
            // Read the real installed version so it never drifts out of sync with the build.
            val appVersion = remember {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "—"
            }
            InfoRow(Icons.Rounded.Tag, "Version", appVersion)
        }

        ClayButton(
            onClick = { viewModel.logout(onLoggedOut) },
            gradient = listOf(Color(0xFFF4604D), Color(0xFFD23B57)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Logout, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Sign out", fontWeight = FontWeight.Bold)
        }
    }
    }
}

// ── Tracking hero ────────────────────────────────────────────────────────────

@Composable
private fun TrackingHero(isTracking: Boolean, onToggle: () -> Unit) {
    val clay = Clay.colors
    ClayCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClayIconBadge(
                if (isTracking) Icons.Rounded.MyLocation else Icons.Rounded.LocationDisabled,
                gradient = if (isTracking) clay.secondaryGradient else listOf(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant),
                size = 52.dp,
                cornerRadius = 18.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (isTracking) "Tracking is on" else "Tracking is off",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    if (isTracking) "Recording your location in the background"
                    else "Tap start to begin recording your journey",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        ClayButton(
            onClick = onToggle,
            gradient = if (isTracking) listOf(Color(0xFFF4604D), Color(0xFFD23B57)) else clay.secondaryGradient,
            contentColor = if (isTracking) Color.White else Color(0xFF052B26),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(if (isTracking) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (isTracking) "Stop tracking" else "Start tracking", fontWeight = FontWeight.Bold)
        }
    }
}

// ── Battery-exemption warning banner ───────────────────────────────────────────

/** Loud, persistent warning shown whenever Roamly isn't exempt from battery optimization —
 *  the single setting that, if missing, guarantees multi-minute Doze gaps. One tap to fix. */
@Composable
private fun ExemptionWarningBanner(onFix: () -> Unit) {
    val warn = MaterialTheme.colorScheme.error
    ClayCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, null, tint = warn, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Tracking will have gaps",
                    style = MaterialTheme.typography.titleSmall,
                    color = warn,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Android pauses location in Doze unless Roamly is exempt from battery optimization.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        ClayButton(
            onClick = onFix,
            gradient = listOf(Sunshine, Coral),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Bolt, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Fix it — disable battery optimization", fontWeight = FontWeight.Bold)
        }
    }
}

// ── Reusable rows ──────────────────────────────────────────────────────────────

@Composable
private fun SettingLabel(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChipSelector(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onLongEdit: (() -> Unit)? = null,
    customLabel: String? = null,
) {
    val clay = Clay.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .then(
                        if (selected) Modifier.background(Brush.verticalGradient(clay.primaryGradient))
                        else Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    )
                    .clickable { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (customLabel != null && onLongEdit != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.verticalGradient(clay.primaryGradient))
                    .clickable { onLongEdit() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(customLabel, style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
        if (onLongEdit != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { onLongEdit() }
                    .padding(10.dp),
            ) {
                Icon(Icons.Rounded.Edit, "Custom value", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AdvancedNumberField(label: String, value: Int, hint: String, onApply: (Int) -> Unit) {
    // Seed from the current value but let the user type freely; apply valid numbers.
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.filter { c -> c.isDigit() }
            text.toIntOrNull()?.let(onApply)
        },
        label = { Text(label) },
        supportingText = { Text(hint) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        // Cap the value at half the row and let it wrap. Without a weight an
        // overflowing value (e.g. a long URL or sentence) is measured first and
        // steals the whole row, collapsing the weighted label to ~1 char wide so
        // it stacks vertically. fill=false keeps short values compact + right-hugged.
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** Like InfoRow but for a label + a longer descriptive sentence that should
 *  wrap as a left-aligned subtitle (not be squeezed into a right-hand value). */
@Composable
private fun NoteRow(icon: ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EditableRow(icon: ImageVector, label: String, value: String, mono: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        Text(
            value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.Edit, "Edit", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    visualTransformation = if (isApiKey && !showKey) PasswordVisualTransformation() else VisualTransformation.None,
                    trailingIcon = if (isApiKey) {
                        {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
                            }
                        }
                    } else null,
                    label = { Text(title) },
                )
                if (hint.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private sealed class EditTarget(
    val currentValue: String,
    val title: String,
    val hint: String,
    val keyboardType: KeyboardType = KeyboardType.Text,
) {
    class DeviceId(currentValue: String, title: String, hint: String) : EditTarget(currentValue, title, hint, KeyboardType.Text)
    class ApiKey(currentValue: String, title: String, hint: String) : EditTarget(currentValue, title, hint, KeyboardType.Text)
    class Interval(currentValue: String, title: String, hint: String) : EditTarget(currentValue, title, hint, KeyboardType.Number)
    class Accuracy(currentValue: String, title: String, hint: String) : EditTarget(currentValue, title, hint, KeyboardType.Number)
}

// ── Option tables ──────────────────────────────────────────────────────────────

private val intervalOptions = listOf(
    "5s" to 5, "10s" to 10, "15s" to 15, "30s" to 30, "1m" to 60, "2m" to 120,
)
private val priorityOptions = listOf(
    "Auto" to "auto", "High" to "high", "Balanced" to "balanced", "Low" to "low",
)
private val accuracyOptions = listOf(
    "20m" to 20, "50m" to 50, "100m" to 100, "200m" to 200,
)

private fun relativeTime(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < 60_000L     -> "just now"
        diff < 3_600_000L  -> "${diff / 60_000}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000}h ago"
        else               -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMs))
    }
}
