package com.roamly.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.roamly.tracking.TrackingCoordinator
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasDialog
import com.roamly.ui.kit.AtlasField
import com.roamly.ui.kit.ButtonKind
import com.roamly.ui.kit.Dot
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.IconTile
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PanelTone
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ask for the battery-optimisation exemption: the direct dialog, else the settings list. */
internal fun Context.batteryExemptionIntents(): List<Intent> = listOf(
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName")),
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
)

/**
 * The tracking switch, with everything it takes to turn it on safely: the
 * notification + fine + background location permissions, then the battery
 * optimisation exemption — the single setting without which Android throttles
 * capture to multi-minute gaps in Doze, so starting is gated on it (with an
 * explicit degraded escape hatch). Also the API-key prompt the ViewModel
 * raises when there's no key yet, and the stop confirmation.
 */
@Composable
fun TrackingHeroCard(viewModel: SettingsViewModel, state: SettingsUiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var showBgRationale by remember { mutableStateOf(false) }
    var showGate by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }
    var showStop by remember { mutableStateOf(false) }
    var manualKey by remember { mutableStateOf(false) }

    fun toggle() { haptics.thud(); viewModel.toggleTracking() }
    fun startGated() {
        if (TrackingCoordinator.isIgnoringBatteryOptimizations(context)) toggle() else showGate = true
    }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startGated() }
    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) { haptics.reject(); return@rememberLauncherForActivityResult }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PermissionChecker.PERMISSION_GRANTED
        ) showBgRationale = true else startGated()
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshBatteryOptimizationState()
        // Back from the system dialog: granted mid start-flow → carry on into tracking.
        if (pendingStart && TrackingCoordinator.isIgnoringBatteryOptimizations(context)) {
            pendingStart = false; showGate = false; toggle()
        }
    }
    fun requestExemption() {
        for (intent in context.batteryExemptionIntents()) {
            if (runCatching { batteryLauncher.launch(intent) }.isSuccess) return
        }
    }
    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PermissionChecker.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PermissionChecker.PERMISSION_GRANTED ->
                fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PermissionChecker.PERMISSION_GRANTED ->
                showBgRationale = true
            else -> startGated()
        }
    }

    val c = Atlas.colors
    val on = state.isTracking
    val glowAlpha by animateColorAsState(if (on) c.success else c.textFaint, label = "trk")
    Panel(modifier.fillMaxWidth(), padding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Beacon(on)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(glowAlpha, size = 7.dp, pulse = on)
                    Spacer(Modifier.width(7.dp))
                    Text(if (on) "LIVE" else "PAUSED", style = MaterialTheme.typography.labelSmall, color = glowAlpha)
                }
                Spacer(Modifier.height(2.dp))
                Text(if (on) "Tracking is on" else "Tracking is off", style = MaterialTheme.typography.titleLarge, color = c.text)
                Text(
                    if (on) "Recording your journey in the background" else "Start to record where you go",
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        // Sync line: when, how much, what's waiting.
        Row(
            Modifier.fillMaxWidth().clip(SquircleShape(14.dp)).background(c.surfaceHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(syncLine(state), style = MaterialTheme.typography.bodySmall,
                    color = if (state.lastSyncTime != 0L && !state.lastSyncSuccess) c.danger else c.text)
                Text(
                    when (state.cachedPointCount) { 0 -> "Everything's uploaded"; 1 -> "1 point waiting"; else -> "%,d points waiting".format(state.cachedPointCount) },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.cachedPointCount == 0) c.success else c.textMuted,
                )
            }
            IconAction(
                Icons.Rounded.CloudUpload, "Sync now", { haptics.click(); viewModel.syncNow() },
                enabled = !state.isSyncing, container = c.surfaceHighest, tint = c.accent, size = 38.dp, iconSize = 18.dp, feel = Feel.None,
            )
        }
        Spacer(Modifier.height(14.dp))
        AtlasButton(
            if (on) "Stop tracking" else "Start tracking",
            { if (on) showStop = true else start() },
            Modifier.fillMaxWidth(),
            kind = if (on) ButtonKind.Danger else ButtonKind.Primary,
            icon = if (on) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
            feel = Feel.None,
        )
    }

    if (!state.batteryOptimizationDisabled) {
        Spacer(Modifier.height(12.dp))
        Panel(Modifier.fillMaxWidth(), tone = PanelTone.Accent, padding = PaddingValues(16.dp), onClick = { requestExemption() }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.Warning, tint = c.warning, size = 38.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Tracking will have gaps", style = MaterialTheme.typography.titleSmall, color = c.text)
                    Text("Android pauses location in Doze unless Roamly is exempt from battery optimisation. Tap to fix.",
                        style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
            }
        }
    }

    if (showBgRationale) {
        AtlasDialog(
            title = "Background location",
            onDismiss = { showBgRationale = false; startGated() },
            confirmText = "Open settings",
            onConfirm = { showBgRationale = false; bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
            dismissText = "Skip",
            icon = Icons.Rounded.MyLocation,
            body = { Text("To keep tracking with the screen off, choose “Allow all the time” on the next screen. You can skip it — tracking still works while the app is open.") },
        )
    }
    if (showGate) {
        AtlasDialog(
            title = "Allow gap-free tracking",
            onDismiss = { },
            confirmText = "Grant exemption",
            onConfirm = { pendingStart = true; requestExemption() },
            dismissText = "Track anyway",
            onDismissAction = { showGate = false; pendingStart = false; toggle() },
            icon = Icons.Rounded.Bolt,
            body = {
                Text("Android throttles Roamly to a fix every several minutes while the screen is off unless it's exempt from battery optimisation — the cause of 5–20 minute gaps. Grant it for reliable 24/7 tracking. “Track anyway” starts with those gaps.")
            },
        )
    }
    if (showStop) {
        AtlasDialog(
            title = "Stop tracking?",
            onDismiss = { showStop = false },
            confirmText = "Stop",
            onConfirm = { showStop = false; toggle() },
            dismissText = "Keep tracking",
            destructive = true,
            icon = Icons.Rounded.LocationOff,
            body = { Text("This turns tracking completely off — including the background keep-alive and starting on reboot. It stays off until you turn it back on. Cached points still upload when you reconnect.") },
        )
    }
    if (state.apiKeyRequired) {
        AtlasDialog(
            title = "Tracking needs an API key",
            onDismiss = { if (!state.apiKeyBusy) viewModel.dismissApiKeyRequired() },
            confirmText = if (state.apiKeyBusy) "Creating…" else "Create key",
            onConfirm = { if (!state.apiKeyBusy) viewModel.createApiKey() },
            dismissText = "Enter manually",
            onDismissAction = { viewModel.dismissApiKeyRequired(); manualKey = true },
            icon = Icons.Rounded.Key,
            body = {
                Column {
                    Text("An API key authenticates your location uploads. Create one now — a single key that works forever — or enter one you already have.")
                    state.apiKeyError?.let { Spacer(Modifier.height(8.dp)); Text(it, color = c.danger, style = MaterialTheme.typography.bodySmall) }
                }
            },
        )
    }
    if (manualKey) {
        ValueDialog(
            title = "API key",
            initial = state.apiKey,
            hint = "64-character key from Roamly → Settings → API keys",
            secret = true,
            onDismiss = { manualKey = false },
            onSave = { viewModel.setApiKey(it); manualKey = false },
        )
    }
}

/** The status beacon: a ring pulses out from it while tracking. */
@Composable
private fun Beacon(on: Boolean) {
    val c = Atlas.colors
    val t = rememberInfiniteTransition(label = "beacon")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800)), label = "ring")
    Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
        if (on) {
            Box(Modifier.size(60.dp).graphicsLayer { scaleX = 0.6f + ring * 0.5f; scaleY = 0.6f + ring * 0.5f; alpha = 1f - ring }
                .clip(SquircleShape(50)).background(c.success.copy(alpha = 0.35f)))
        }
        Box(
            Modifier.size(48.dp).clip(SquircleShape(50)).background(
                if (on) Brush.linearGradient(listOf(c.success, c.cyan)) else Brush.linearGradient(listOf(c.surfaceHighest, c.surfaceHigh))
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (on) Icons.Rounded.MyLocation else Icons.Rounded.LocationOff, null, tint = if (on) c.onAccent else c.textMuted, modifier = Modifier.size(22.dp))
        }
    }
}

internal fun syncLine(state: SettingsUiState): String = when {
    state.isSyncing -> "Syncing…"
    state.lastSyncTime == 0L -> "Never synced yet"
    state.lastSyncSuccess -> {
        val pts = when (state.lastSyncCount) { 0 -> "nothing to send"; 1 -> "1 point sent"; else -> "%,d points sent".format(state.lastSyncCount) }
        "Synced ${relativeTime(state.lastSyncTime)} · $pts"
    }
    else -> "Sync failed ${relativeTime(state.lastSyncTime)}" + if (state.lastSyncError.isNotBlank()) " — ${state.lastSyncError}" else ""
}

internal fun relativeTime(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000}h ago"
        else -> SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(epochMs))
    }
}

/** Edit one value: a device id, an API key, a number. */
@Composable
internal fun ValueDialog(
    title: String,
    initial: String,
    hint: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    secret: Boolean = false,
    numeric: Boolean = false,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    var reveal by remember { mutableStateOf(false) }
    AtlasDialog(
        title = title,
        onDismiss = onDismiss,
        confirmText = "Save",
        onConfirm = { onSave(value.trim()) },
        body = {
            AtlasField(
                value, { value = it },
                supportingText = hint,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
                visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                trailing = if (secret) ({
                    IconAction(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (reveal) "Hide" else "Show",
                        { reveal = !reveal }, container = null, tint = Atlas.colors.textMuted, size = 40.dp)
                }) else null,
            )
        },
    )
}
