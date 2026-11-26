package com.roamly.ui.settings

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.roamly.data.api.DiagnosticsResponse
import com.roamly.tracking.CaptureStats
import com.roamly.tracking.CellScanner
import com.roamly.tracking.LocationSource
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasChip
import com.roamly.ui.kit.ButtonKind
import com.roamly.ui.kit.ChipRow
import com.roamly.ui.kit.Dot
import com.roamly.ui.kit.ErrorNote
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PushedScreen
import com.roamly.ui.kit.SectionTitle
import com.roamly.ui.kit.StatBlock
import com.roamly.ui.kit.Tag
import com.roamly.ui.kit.dockAwareBottom
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.AtlasType
import java.util.Locale

/**
 * Tracking diagnostics: the server's view of this device's coverage and gaps
 * for a window, then the phone's own capture counters (which can tell "GPS
 * gave nothing" from "captured and thrown away" — the server never sees the
 * difference), the active settings, and the CSV log.
 */
@Composable
fun DiagnosticsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val c = Atlas.colors

    var result by remember { mutableStateOf<DiagnosticsResponse?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hours by remember { mutableIntStateOf(24) }
    // describe() reaches LocationManager over binder and only changes with the
    // provider set, so read it once per screen.
    val locationSource = remember { LocationSource.get(context).describe() }
    // A phone recording nothing because Phone permission was declined must not
    // look like one parked out of tower range.
    val cellSource = remember { CellScanner.describe(context) }
    var cellUploadError by remember { mutableStateOf(CaptureStats.lastCellUploadError) }
    // Plain counters, not a flow: read on open and after a reset.
    var capture by remember { mutableStateOf(CaptureStats.snapshot()) }
    var captureSince by remember { mutableStateOf(CaptureStats.since) }

    fun run() {
        loading = true; error = null
        viewModel.fetchDiagnostics(state.deviceId.ifBlank { null }, hours) { r, err ->
            loading = false; result = r; error = err
            if (err == null) haptics.confirm() else haptics.reject()
        }
    }
    LaunchedEffect(Unit) {
        capture = CaptureStats.snapshot()
        cellUploadError = CaptureStats.lastCellUploadError
        captureSince = CaptureStats.since
        viewModel.refreshBatteryOptimizationState()
        if (state.serverUrl.isNotBlank() && state.deviceId.isNotBlank()) {
            loading = true
            viewModel.fetchDiagnostics(state.deviceId, hours) { r, err -> loading = false; result = r; error = err }
        }
    }

    PushedScreen(title = "Diagnostics", onBack = onBack, subtitle = state.deviceId.ifBlank { null }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = dockAwareBottom()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Panel(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 16.dp)) {
                Text("Window", style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.padding(horizontal = 18.dp))
                Spacer(Modifier.height(10.dp))
                ChipRow(contentPadding = PaddingValues(horizontal = 18.dp)) {
                    listOf(6 to "6h", 24 to "24h", 72 to "3d", 168 to "7d", 720 to "30d").forEach { (h, label) ->
                        AtlasChip(label, hours == h, { hours = h })
                    }
                }
                Spacer(Modifier.height(14.dp))
                AtlasButton("Run diagnostics", ::run, Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    loading = loading, icon = Icons.Rounded.Refresh, kind = ButtonKind.Secondary)
            }

            error?.let { ErrorNote(it, onRetry = ::run) }

            result?.let { d ->
                if (d.error == "too_many_points") {
                    ErrorNote("Too many points (${d.count.fmt()}) — pick a shorter window.")
                } else if (d.count > 0) {
                    Panel(Modifier.fillMaxWidth()) {
                        SectionTitle("Overview")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatBlock(d.count.fmt(), "points", small = true)
                            StatBlock(d.pointsPerHour?.let { "%.0f".format(it) } ?: "—", "per hour", small = true)
                            StatBlock(fmtDuration(d.gaps.medianS), "median gap", small = true)
                            StatBlock(d.distanceKm?.let { "%.1f".format(it) } ?: "—", "km", small = true)
                        }
                        if (d.span.start != null && d.span.end != null) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "${d.span.start.take(16).replace("T", " ")} → ${d.span.end.take(16).replace("T", " ")}" + (d.span.human?.let { " · $it" } ?: ""),
                                style = AtlasType.mono, color = c.textMuted,
                            )
                        }
                    }
                    if (d.timeline.coverage.isNotEmpty()) {
                        Panel(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SectionTitle("Coverage", Modifier.weight(1f))
                                Legend(c.success, "tracked"); Spacer(Modifier.width(10.dp))
                                Legend(c.warning, "sparse"); Spacer(Modifier.width(10.dp))
                                Legend(c.danger, "poor")
                            }
                            Text("Share of expected points per slot", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                            Spacer(Modifier.height(12.dp))
                            CoverageChart(d.timeline.coverage)
                        }
                    }
                    Panel(Modifier.fillMaxWidth()) {
                        val (label, tone) = when {
                            (d.gaps.maxS ?: 0.0) <= 120 -> "Healthy" to c.success
                            (d.gaps.maxS ?: 0.0) <= 600 -> "Minor gaps" to c.warning
                            else -> "Dropouts" to c.danger
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) { SectionTitle("Gaps", Modifier.weight(1f)); Tag(label, color = tone) }
                        Line("Median", fmtDuration(d.gaps.medianS))
                        Line("Average", fmtDuration(d.gaps.avgS))
                        Line("Max", fmtDuration(d.gaps.maxS))
                        Line("90th percentile", fmtDuration(d.gaps.p90S))
                        Line("Over 1 min", "${d.gaps.over60}")
                        Line("Over 5 min", "${d.gaps.over300}")
                        Line("Over 15 min", "${d.gaps.over900}", last = true)
                    }
                    val acc = d.accuracy
                    Panel(Modifier.fillMaxWidth()) {
                        SectionTitle("Accuracy")
                        Line("Best", acc.min?.let { "${it.toInt()} m" } ?: "—")
                        Line("Average", acc.avg?.let { "${it.toInt()} m" } ?: "—")
                        Line("Worst", acc.max?.let { "${it.toInt()} m" } ?: "—")
                        Line("≤ 20 m", "${acc.under20} (${pct(acc.under20, d.count)})")
                        Line("≤ 50 m", "${acc.under50} (${pct(acc.under50, d.count)})")
                        Line("> 100 m", "${acc.over100} (${pct(acc.over100, d.count)})")
                        Line("No accuracy", "${acc.missing}", last = true)
                    }
                    val bat = d.battery
                    if (bat.min != null || bat.max != null) {
                        Panel(Modifier.fillMaxWidth()) {
                            SectionTitle("Battery")
                            Line("At start", bat.max?.let { "$it%" } ?: "—")
                            Line("At end", bat.min?.let { "$it%" } ?: "—", last = bat.min == null || bat.max == null)
                            if (bat.min != null && bat.max != null) Line("Drain", "${bat.max - bat.min}%", last = true)
                        }
                    }
                }
            }

            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle("Capture health · on this phone")
                        if (captureSince > 0L) Text("since ${relativeTime(captureSince)}", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                    }
                    IconAction(Icons.Rounded.RestartAlt, "Reset counters", {
                        CaptureStats.reset()
                        capture = CaptureStats.snapshot()
                        cellUploadError = CaptureStats.lastCellUploadError
                        captureSince = CaptureStats.since
                    }, size = 36.dp, iconSize = 18.dp)
                }
                Spacer(Modifier.height(8.dp))
                if (CaptureStats.exactAlarmsDenied) {
                    ErrorNote("Exact alarms denied — in Doze the OS throttles wakeups to roughly one every 9–15 min.")
                    Spacer(Modifier.height(8.dp))
                }
                capture.toList().forEachIndexed { i, (counter, value) ->
                    Line(counter.label, value.toInt().fmt(), last = i == capture.size - 1)
                }
            }

            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("Tracking settings")
                Line("Status", if (state.isTracking) "Active" else "Stopped", if (state.isTracking) c.success else c.danger)
                Line("Battery optimisation", if (state.batteryOptimizationDisabled) "Exempt" else "Not exempt", if (state.batteryOptimizationDisabled) c.success else c.danger)
                Line("Interval", "${state.trackingIntervalSecs}s")
                Line("GPS priority", state.locationPriority)
                Line("Location source", locationSource)
                Line("Cell logging", if (state.cellLoggingEnabled) cellSource else "Off")
                if (cellUploadError.isNotBlank()) Line("Cell upload", cellUploadError, c.danger)
                Line("Accuracy filter", "${state.maxAccuracyM} m")
                Line("Last upload", if (state.lastSyncTime == 0L) "Never" else relativeTime(state.lastSyncTime))
                Line("Pending upload", if (state.cachedPointCount == 0) "None" else "${state.cachedPointCount.fmt()} points", last = true)
            }

            if (state.csvPath.isNotBlank()) {
                Panel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            SectionTitle("Local CSV log")
                            Text(state.csvPath, style = AtlasType.mono, color = c.textMuted)
                        }
                        IconAction(Icons.Rounded.IosShare, "Share CSV log", { shareCsv(context, state.csvPath) }, tint = c.accent)
                    }
                }
            }

            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("Device")
                val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—" }
                Line("App", "Roamly $version")
                Line("Android", "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
                Line("Manufacturer", Build.MANUFACTURER)
                Line("Model", Build.MODEL, last = true)
            }
        }
    }
}

private fun shareCsv(context: android.content.Context, path: String) {
    val file = java.io.File(path)
    if (!file.exists()) {
        android.widget.Toast.makeText(context, "No CSV log yet — start tracking to create one", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
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
}

@Composable
private fun CoverageChart(coverage: List<Double>) {
    val c = Atlas.colors
    Canvas(Modifier.fillMaxWidth().height(84.dp)) {
        val n = coverage.size.coerceAtLeast(1)
        val w = size.width / n
        coverage.forEachIndexed { i, v ->
            val h = (v.coerceIn(0.0, 1.0).toFloat() * size.height).coerceAtLeast(3f)
            val color = when { v >= 0.9 -> c.success; v >= 0.5 -> c.warning; else -> c.danger }
            drawRoundRect(
                color, Offset(i * w + 1f, size.height - h), Size((w - 2f).coerceAtLeast(1f), h),
                CornerRadius((w / 2f).coerceAtMost(4.dp.toPx())),
            )
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color, size = 7.dp)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Atlas.colors.textMuted)
    }
}

@Composable
private fun ColumnScope.Line(label: String, value: String, valueColor: Color = Atlas.colors.text, last: Boolean = false) {
    val c = Atlas.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, modifier = Modifier.weight(0.5f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, textAlign = TextAlign.End, modifier = Modifier.weight(0.5f))
    }
    if (!last) HorizontalDivider(color = c.line)
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

private fun pct(n: Int, total: Int): String = if (total == 0) "" else "${n * 100 / total}%"

private fun Int.fmt(): String = String.format(Locale.getDefault(), "%,d", this)
