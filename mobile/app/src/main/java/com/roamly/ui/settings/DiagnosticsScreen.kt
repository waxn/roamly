package com.roamly.ui.settings

import android.os.Build
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roamly.tracking.TrackingCoordinator
import com.roamly.ui.theme.Clay
import com.roamly.ui.theme.ClayCard
import com.roamly.ui.theme.ClaySectionHeader
import com.roamly.ui.theme.Teal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clay = Clay.colors

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, "back", tint = MaterialTheme.colorScheme.onBackground)
                }
                Text(
                    "Diagnostics",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier.size(10.dp).clip(CircleShape)
                        .background(if (state.isTracking) Teal else MaterialTheme.colorScheme.error)
                )
                Spacer(Modifier.width(16.dp))
            }

            Column(
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {

                // ── Tracking status ──────────────────────────────────────────
                ClayCard {
                    ClaySectionHeader("Tracking", Icons.Rounded.MyLocation, gradient = clay.secondaryGradient)
                    Spacer(Modifier.height(12.dp))
                    DiagRow("Status", if (state.isTracking) "● Active" else "○ Stopped",
                        valueColor = if (state.isTracking) Teal else MaterialTheme.colorScheme.error)
                    DiagRow("Battery optimization",
                        if (state.batteryOptimizationDisabled) "Exempt ✓" else "Not exempt — may cause gaps",
                        valueColor = if (state.batteryOptimizationDisabled) Teal else MaterialTheme.colorScheme.error)
                    DiagRow("Auto-start on boot", if (state.autoStartTracking) "On" else "Off")
                }

                // ── GPS settings ─────────────────────────────────────────────
                ClayCard {
                    ClaySectionHeader("GPS settings", Icons.Rounded.GpsFixed, gradient = clay.secondaryGradient)
                    Spacer(Modifier.height(12.dp))
                    DiagRow("Update interval", "${state.trackingIntervalSecs}s")
                    DiagRow("Priority", state.locationPriority)
                    DiagRow("Accuracy filter", "${state.maxAccuracyM} m — discard worse fixes")
                }

                // ── Upload / sync ─────────────────────────────────────────────
                ClayCard {
                    ClaySectionHeader("Upload & sync", Icons.Rounded.CloudUpload, gradient = clay.secondaryGradient)
                    Spacer(Modifier.height(12.dp))
                    val syncColor = when {
                        state.lastSyncTime == 0L -> MaterialTheme.colorScheme.onSurfaceVariant
                        state.lastSyncSuccess -> Teal
                        else -> MaterialTheme.colorScheme.error
                    }
                    DiagRow("Last upload",
                        if (state.lastSyncTime == 0L) "Never" else relativeTime(state.lastSyncTime),
                        valueColor = syncColor)
                    if (!state.lastSyncSuccess && state.lastSyncError.isNotBlank()) {
                        DiagRow("Last error", state.lastSyncError, valueColor = MaterialTheme.colorScheme.error)
                    }
                    DiagRow("Pending upload",
                        if (state.cachedPointCount == 0) "None" else "${state.cachedPointCount} points",
                        valueColor = if (state.cachedPointCount == 0) Teal else MaterialTheme.colorScheme.onSurfaceVariant)
                    DiagRow("Sync on mobile data", if (state.syncOnMobileData) "Yes" else "Wi-Fi only")
                    if (state.lastSyncCount > 0) {
                        DiagRow("Last upload count", "${state.lastSyncCount} points")
                    }
                }

                // ── Connection ────────────────────────────────────────────────
                ClayCard {
                    ClaySectionHeader("Connection", Icons.Rounded.Cloud, gradient = clay.tertiaryGradient)
                    Spacer(Modifier.height(12.dp))
                    DiagRow("Server", state.serverUrl.ifBlank { "Not set" })
                    DiagRow("Username", state.username.ifBlank { "Not set" })
                    DiagRow("Device ID", state.deviceId.ifBlank { "Not set" }, mono = true)
                    DiagRow("API key",
                        if (state.apiKey.isBlank()) "Not set" else "••••${state.apiKey.takeLast(6)}",
                        mono = true)
                }

                // ── Storage ────────────────────────────────────────────────────
                ClayCard {
                    ClaySectionHeader("Storage", Icons.Rounded.Storage, gradient = clay.tertiaryGradient)
                    Spacer(Modifier.height(12.dp))
                    DiagRow("CSV log", state.csvPath.ifBlank { "Not yet created" })
                }

                // ── Device info ────────────────────────────────────────────────
                ClayCard {
                    ClaySectionHeader("Device", Icons.Rounded.Phone, gradient = clay.tertiaryGradient)
                    Spacer(Modifier.height(12.dp))
                    val appVersion = remember {
                        runCatching {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        }.getOrNull() ?: "—"
                    }
                    DiagRow("App version", appVersion)
                    DiagRow("Android", "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
                    DiagRow("Manufacturer", Build.MANUFACTURER)
                    DiagRow("Model", "${Build.MODEL} (${Build.PRODUCT})")
                    DiagRow("Build", Build.FINGERPRINT.take(60), mono = true)
                }
            }
        }
    }
}

@Composable
private fun DiagRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    mono: Boolean = false,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(0.45f),
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = valueColor,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                modifier = Modifier.weight(0.55f),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    }
}

private fun relativeTime(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000}h ago"
        else -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMs))
    }
}
