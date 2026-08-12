package com.roamly.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.DiagnosticsResponse
import com.roamly.tracking.CaptureStats
import com.roamly.ui.theme.Clay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

@Composable
fun DiagnosticsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var diagResult by remember { mutableStateOf<DiagnosticsResponse?>(null) }
    var diagLoading by remember { mutableStateOf(false) }
    var diagError by remember { mutableStateOf<String?>(null) }
    var selectedHours by remember { mutableStateOf(24) }

    // Read once per screen open (and after a reset). These are plain counters, not a flow —
    // re-reading on every recomposition would be pointless churn.
    var captureStats by remember { mutableStateOf(CaptureStats.snapshot()) }
    var captureSince by remember { mutableStateOf(CaptureStats.since) }

    // Auto-load on first open
    LaunchedEffect(Unit) {
        captureStats = CaptureStats.snapshot()
        captureSince = CaptureStats.since
        viewModel.refreshBatteryOptimizationState()
        if (state.serverUrl.isNotBlank() && state.deviceId.isNotBlank()) {
            diagLoading = true
            diagError = null
            viewModel.fetchDiagnostics(state.deviceId, selectedHours) { result, err ->
                diagLoading = false
                diagResult = result
                diagError = err
            }
        }
    }

    fun runDiag() {
        diagLoading = true
        diagError = null
        viewModel.fetchDiagnostics(state.deviceId.ifBlank { null }, selectedHours) { result, err ->
            diagLoading = false
            diagResult = result
            diagError = err
        }
    }

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
                    Icon(Icons.Rounded.ArrowBack, "back")
                }
                Text(
                    "Diagnostics",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Range selector
                ElevatedCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Time range", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(6 to "6h", 24 to "24h", 72 to "3d", 168 to "7d", 720 to "30d").forEach { (h, label) ->
                                val selected = selectedHours == h
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        )
                                        .clickable { selectedHours = h }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = ::runDiag,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !diagLoading,
                        ) {
                            if (diagLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Running…")
                            } else {
                                Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Run diagnostics")
                            }
                        }
                        if (state.deviceId.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text("device: ${state.deviceId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                diagError?.let { err ->
                    ElevatedCard {
                        Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                    }
                }

                diagResult?.let { d ->
                    if (d.error == "too_many_points") {
                        ElevatedCard {
                            Text(
                                "Too many points (${d.count.toLocaleString()}) — pick a shorter range or use a specific device.",
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    } else if (d.count > 0) {
                        // Hero stats
                        ElevatedCard {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Overview", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(10.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    HeroMetric("${d.count.toLocaleString()}", "points")
                                    HeroMetric(d.span.human ?: "—", "span")
                                    HeroMetric(d.pointsPerHour?.let { "%.0f".format(it) } ?: "—", "pts/hour")
                                    HeroMetric(fmtDuration(d.gaps.medianS), "med. gap")
                                    HeroMetric(d.distanceKm?.let { "%.1f km".format(it) } ?: "—", "distance")
                                }
                                if (d.span.start != null && d.span.end != null) {
                                    Spacer(Modifier.height(6.dp))
                                    HorizontalDivider()
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${d.span.start.take(16).replace("T"," ")} → ${d.span.end.take(16).replace("T"," ")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }

                        // Coverage timeline
                        if (d.timeline.coverage.isNotEmpty()) {
                            ElevatedCard {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Coverage timeline", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                        // Legend
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            LegendDot(Clay.colors.success, "tracked")
                                            LegendDot(Clay.colors.warning, "sparse")
                                            LegendDot(MaterialTheme.colorScheme.error, "poor")
                                        }
                                    }
                                    Text("% of expected points per time slot", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(12.dp))
                                    CoverageChart(d.timeline.coverage)
                                }
                            }
                        }

                        // Gaps
                        ElevatedCard {
                            Column(modifier = Modifier.padding(16.dp)) {
                                val gapHealth = when {
                                    (d.gaps.maxS ?: 0.0) <= 120 -> "healthy" to Clay.colors.success
                                    (d.gaps.maxS ?: 0.0) <= 600 -> "minor gaps" to Clay.colors.warning
                                    else -> "dropouts" to MaterialTheme.colorScheme.error
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Gaps between points", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    Box(
                                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                                            .background(gapHealth.second.copy(alpha = 0.15f))
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                    ) {
                                        Text(gapHealth.first, style = MaterialTheme.typography.labelSmall, color = gapHealth.second, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                DiagRow("Median", fmtDuration(d.gaps.medianS))
                                DiagRow("Average", fmtDuration(d.gaps.avgS))
                                DiagRow("Max", fmtDuration(d.gaps.maxS))
                                DiagRow("90th pct", fmtDuration(d.gaps.p90S))
                                DiagRow("Gaps > 1 min", "${d.gaps.over60}")
                                DiagRow("Gaps > 5 min", "${d.gaps.over300}")
                                DiagRow("Gaps > 15 min", "${d.gaps.over900}")
                            }
                        }

                        // Accuracy
                        val acc = d.accuracy
                        ElevatedCard {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Accuracy", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(8.dp))
                                DiagRow("Best", acc.min?.let { "${it.toInt()} m" } ?: "—")
                                DiagRow("Average", acc.avg?.let { "${it.toInt()} m" } ?: "—")
                                DiagRow("Worst", acc.max?.let { "${it.toInt()} m" } ?: "—")
                                DiagRow("≤ 20 m", "${acc.under20} (${pct(acc.under20, d.count)})")
                                DiagRow("≤ 50 m", "${acc.under50} (${pct(acc.under50, d.count)})")
                                DiagRow("> 100 m", "${acc.over100} (${pct(acc.over100, d.count)})")
                                DiagRow("No accuracy", "${acc.missing}")
                            }
                        }

                        // Battery
                        val bat = d.battery
                        if (bat.min != null || bat.max != null) {
                            ElevatedCard {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Battery during session", style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.height(8.dp))
                                    DiagRow("At start", bat.max?.let { "$it%" } ?: "—")
                                    DiagRow("At end", bat.min?.let { "$it%" } ?: "—")
                                    if (bat.min != null && bat.max != null) {
                                        DiagRow("Drain", "${bat.max - bat.min}%")
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Capture health (local, on-device) ──────────────────────────
                // The coverage/gap stats above come from the server, so they only ever show
                // points that were captured *and* uploaded — a hole there can't distinguish
                // "GPS gave nothing" from "we captured it and threw it away". These counters
                // are recorded at the decision points themselves and answer exactly that.
                ElevatedCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Capture health", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                CaptureStats.reset()
                                captureStats = CaptureStats.snapshot()
                                captureSince = CaptureStats.since
                            }) { Text("Reset") }
                        }
                        if (captureSince > 0L) {
                            Text(
                                "since ${relativeTime(captureSince)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        if (CaptureStats.exactAlarmsDenied) {
                            Text(
                                "⚠ Exact alarms denied — in Doze the OS throttles wakeups to " +
                                    "roughly one every 9–15 min, which shows up as long gaps.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        captureStats.forEach { (counter, value) ->
                            DiagRow(counter.label, value.toInt().toLocaleString())
                        }
                    }
                }

                // ── Static device info ─────────────────────────────────────────
                ElevatedCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Tracking settings", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        DiagRow("Status", if (state.isTracking) "● Active" else "○ Stopped", valueColor = if (state.isTracking) Clay.colors.success else MaterialTheme.colorScheme.error)
                        DiagRow("Battery opt.", if (state.batteryOptimizationDisabled) "Exempt ✓" else "⚠ Not exempt", valueColor = if (state.batteryOptimizationDisabled) Clay.colors.success else MaterialTheme.colorScheme.error)
                        DiagRow("Interval", "${state.trackingIntervalSecs}s")
                        DiagRow("GPS priority", state.locationPriority)
                        DiagRow("Accuracy filter", "${state.maxAccuracyM} m")
                        DiagRow("Last upload", if (state.lastSyncTime == 0L) "Never" else relativeTime(state.lastSyncTime))
                        DiagRow("Pending upload", if (state.cachedPointCount == 0) "None" else "${state.cachedPointCount} points")
                    }
                }

                // ── CSV path (tap to share) ────────────────────────────────────
                if (state.csvPath.isNotBlank()) {
                    ElevatedCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Local CSV log", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                state.csvPath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.clickable {
                                    val file = java.io.File(state.csvPath)
                                    if (!file.exists()) {
                                        // File doesn't exist yet — nothing to open
                                        android.widget.Toast.makeText(context, "No CSV log yet — start tracking to create one", android.widget.Toast.LENGTH_SHORT).show()
                                        return@clickable
                                    }
                                    runCatching {
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file,
                                        )
                                        context.startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/csv"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                },
                                                "Share CSV log",
                                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }.onFailure {
                                        android.widget.Toast.makeText(context, "Could not open: ${it.message}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("tap to share", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // ── Device info ─────────────────────────────────────────────────
                ElevatedCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Device", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        val appVersion = remember {
                            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
                        }
                        DiagRow("App", "Roamly $appVersion")
                        DiagRow("Android", "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
                        DiagRow("Manufacturer", Build.MANUFACTURER)
                        DiagRow("Model", Build.MODEL)
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverageChart(coverage: List<Double>) {
    val goodColor = Clay.colors.success
    val sparseColor = Clay.colors.warning
    val poorColor = MaterialTheme.colorScheme.error
    Canvas(
        modifier = Modifier.fillMaxWidth().height(80.dp),
    ) {
        val n = coverage.size.coerceAtLeast(1)
        val barW = size.width / n
        coverage.forEachIndexed { i, c ->
            val barH = (c.coerceIn(0.0, 1.0).toFloat() * size.height).coerceAtLeast(2f)
            val color = when {
                c >= 0.9 -> goodColor
                c >= 0.5 -> sparseColor
                else     -> poorColor
            }
            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(i * barW + 1f, size.height - barH),
                size = androidx.compose.ui.geometry.Size(barW - 2f, barH),
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HeroMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DiagRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Column {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
            Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor, modifier = Modifier.weight(0.55f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    }
}

private fun fmtDuration(s: Double?): String {
    if (s == null) return "—"
    val sec = s.toLong()
    return when {
        sec < 60 -> "${sec}s"
        sec < 3600 -> "${sec / 60}m ${sec % 60}s"
        else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
    }
}

private fun pct(n: Int, total: Int): String = if (total == 0) "" else "${(n * 100 / total)}%"

private fun relativeTime(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < 60_000L     -> "just now"
        diff < 3_600_000L  -> "${diff / 60_000}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000}h ago"
        else               -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMs))
    }
}

private fun Int.toLocaleString(): String = String.format(Locale.getDefault(), "%,d", this)
