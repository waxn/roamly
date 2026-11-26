package com.roamly.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.HealthBucket
import com.roamly.data.api.HealthDailyResponse
import com.roamly.data.api.HealthDayResponse
import com.roamly.data.api.RoamlyApi
import com.roamly.ui.kit.AtlasChip
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.SectionTitle
import com.roamly.ui.kit.SegmentedSwitch
import com.roamly.ui.kit.Skeleton
import com.roamly.ui.kit.StatBlock
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

data class HealthDataState(
    val daily: HealthDailyResponse? = null,
    val day: HealthDayResponse? = null,
    val dayLoading: Boolean = false,
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/** Reads what the server already has: the same daily series and day drill-down the web Health page draws. */
@HiltViewModel
class HealthDataViewModel @Inject constructor(private val api: RoamlyApi) : ViewModel() {
    private val _state = MutableStateFlow(HealthDataState())
    val state: StateFlow<HealthDataState> = _state.asStateFlow()
    private val cache = HashMap<Int, HealthDailyResponse>()

    /** JS `getTimezoneOffset()` convention, which the server's health reads expect. */
    private fun tzOffset() = -TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000

    fun load(rangeDays: Int) {
        cache[rangeDays]?.let { c -> _state.update { it.copy(daily = c, loading = false, failed = false, day = null) } }
        viewModelScope.launch {
            _state.update { it.copy(loading = it.daily == null || cache[rangeDays] == null) }
            val end = LocalDate.now()
            val start = end.minusDays((rangeDays - 1).toLong())
            val body = runCatching {
                api.getHealthDaily(start.toString(), end.toString(), tzOffset()).body()
            }.getOrNull()
            if (body != null) cache[rangeDays] = body
            _state.update { it.copy(daily = body ?: it.daily, loading = false, failed = body == null && it.daily == null, day = null) }
        }
    }

    fun openDay(date: String) {
        viewModelScope.launch {
            _state.update { it.copy(dayLoading = true) }
            val body = runCatching { api.getHealthDay(date, tzOffset()).body() }.getOrNull()
            _state.update { it.copy(day = body, dayLoading = false) }
        }
    }

    fun closeDay() = _state.update { it.copy(day = null) }
}

private enum class Metric(val label: String) { Steps("Steps"), Distance("Distance"), Calories("Calories") }

private fun HealthBucket.valueOf(m: Metric): Double = when (m) {
    Metric.Steps -> steps
    Metric.Distance -> distance / 1000.0
    Metric.Calories -> caloriesActive
}

private fun fmt(m: Metric, v: Double): String = when (m) {
    Metric.Steps -> "%,d".format(v.toLong())
    Metric.Distance -> String.format(Locale.US, "%.1f", v)
    Metric.Calories -> "%,d".format(v.toLong())
}

private fun unit(m: Metric) = when (m) { Metric.Steps -> "steps"; Metric.Distance -> "km"; Metric.Calories -> "kcal" }

private val RANGES = listOf(7 to "7 days", 30 to "30 days", 90 to "90 days", 365 to "1 year")

/** The Health page's data view: a metric chart you can tap into, and one day's detail beneath it. */
@Composable
fun HealthDataSection(viewModel: HealthDataViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val c = Atlas.colors
    val haptics = rememberHaptics()
    var rangeIdx by rememberSaveable { mutableIntStateOf(1) }
    var metricIdx by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableIntStateOf(-1) }
    val metric = Metric.entries[metricIdx]
    LaunchedEffect(rangeIdx) { selected = -1; viewModel.load(RANGES[rangeIdx].first) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SegmentedSwitch(Metric.entries.map { it.label }, metricIdx, { metricIdx = it }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RANGES.forEachIndexed { i, (_, label) -> AtlasChip(label, i == rangeIdx, { haptics.select(); rangeIdx = i }) }
        }
        val daily = state.daily
        when {
            daily == null && state.loading -> Skeleton(Modifier.fillMaxWidth().height(220.dp), SquircleShape(SquircleRadius.panel))
            daily == null || daily.buckets.isEmpty() -> Panel(Modifier.fillMaxWidth()) {
                Text(
                    if (state.failed) "Couldn't load health data. Check your connection." else "No health data in this range yet. Turn on sync below to bring in steps.",
                    style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
                )
            }
            else -> {
                val t = daily.totals
                Panel(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatBlock("%,d".format(t.steps.toLong()), "steps", small = true)
                        StatBlock(String.format(Locale.US, "%.1f", t.distanceM / 1000.0), "km", small = true)
                        StatBlock("%,d".format(t.caloriesActive.toLong()), "active kcal", small = true)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("${t.activeDays} active days · avg ${"%,d".format(t.avgSteps.toLong())} steps",
                        style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
                BarChart(
                    buckets = daily.buckets, metric = metric, selected = selected,
                    onSelect = { i ->
                        haptics.tick()
                        selected = i
                        // Day-sized bars open that day; wider bars just report their span.
                        if (daily.granularity == "day") viewModel.openDay(daily.buckets[i].start) else viewModel.closeDay()
                    },
                )
                if (selected in daily.buckets.indices) {
                    val b = daily.buckets[selected]
                    Text(
                        "${prettyDate(b.start)}${if (b.end != b.start) " – ${prettyDate(b.end)}" else ""}: ${fmt(metric, b.valueOf(metric))} ${unit(metric)}",
                        style = MaterialTheme.typography.labelLarge, color = c.text,
                    )
                }
            }
        }
        state.day?.let { DayDetail(it, metric) }
    }
}

private fun prettyDate(iso: String): String = runCatching {
    LocalDate.parse(iso.take(10)).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
}.getOrDefault(iso)

@Composable
private fun BarChart(buckets: List<HealthBucket>, metric: Metric, selected: Int, onSelect: (Int) -> Unit) {
    val c = Atlas.colors
    val values = remember(buckets, metric) { buckets.map { it.valueOf(metric) } }
    val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1e-6)
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 12.dp)) {
        Text("${fmt(metric, max)} ${unit(metric)} at most", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        Spacer(Modifier.height(10.dp))
        Canvas(
            Modifier.fillMaxWidth().height(150.dp).pointerInput(buckets) {
                detectTapGestures { o ->
                    val i = ((o.x / size.width) * buckets.size).toInt().coerceIn(0, buckets.lastIndex)
                    onSelect(i)
                }
            },
        ) {
            val n = values.size
            val slot = size.width / n
            val gap = if (slot > 6.dp.toPx()) 2.dp.toPx() else 0.5f
            val barW = (slot - gap).coerceAtLeast(1f)
            // A faint baseline so a run of empty days still reads as a chart.
            drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            values.forEachIndexed { i, v ->
                val h = (size.height * (v / max)).toFloat().coerceAtLeast(if (v > 0) 2.dp.toPx() else 0f)
                val on = i == selected
                drawRoundRect(
                    color = if (on) c.accent else c.accent.copy(alpha = if (selected >= 0) 0.35f else 0.7f),
                    topLeft = Offset(i * slot + gap / 2, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(minOf(barW / 2, 4.dp.toPx())),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(prettyDate(buckets.first().start), style = MaterialTheme.typography.labelSmall, color = c.textFaint)
            Text(prettyDate(buckets.last().end), style = MaterialTheme.typography.labelSmall, color = c.textFaint)
        }
    }
}

@Composable
private fun DayDetail(day: HealthDayResponse, metric: Metric) {
    val c = Atlas.colors
    val key = when (metric) { Metric.Steps -> "steps"; Metric.Distance -> "distance"; Metric.Calories -> "calories_active" }
    val hourly = day.hourly[key].orEmpty()
    SectionTitle(prettyDate(day.date))
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBlock("%,d".format((day.totals["steps"] ?: 0.0).toLong()), "steps", small = true)
            StatBlock(String.format(Locale.US, "%.1f", (day.totals["distance"] ?: 0.0) / 1000.0), "km", small = true)
            StatBlock("%,d".format((day.totals["calories_active"] ?: 0.0).toLong()), "active kcal", small = true)
        }
        if (hourly.size == 24 && hourly.any { it > 0 }) {
            Spacer(Modifier.height(14.dp))
            val hmax = hourly.max().coerceAtLeast(1e-6)
            Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                val slot = size.width / 24f
                hourly.forEachIndexed { h, v ->
                    val bh = (size.height * (v / hmax)).toFloat().coerceAtLeast(if (v > 0) 2.dp.toPx() else 0f)
                    drawRoundRect(c.accent.copy(alpha = 0.8f), Offset(h * slot + 1.5f, size.height - bh), Size(slot - 3f, bh), CornerRadius(3.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("0", "6", "12", "18", "24").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = c.textFaint) }
            }
        }
    }
    day.workouts.forEach { w ->
        Panel(Modifier.fillMaxWidth(), padding = PaddingValues(14.dp)) {
            Text(w.title.ifBlank { w.exerciseSlug.replace('_', ' ').replaceFirstChar { it.uppercase() }.ifBlank { "Workout" } },
                style = MaterialTheme.typography.titleSmall, color = c.text)
            Text(
                listOfNotNull(
                    w.durationS?.let { "${it / 60} min" },
                    w.distanceM?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f km", it / 1000.0) },
                    w.caloriesKcal?.takeIf { it > 0 }?.let { "${it.toInt()} kcal" },
                    w.avgHeartRate?.takeIf { it > 0 }?.let { "${it.toInt()} bpm" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = c.textMuted,
            )
        }
    }
}
