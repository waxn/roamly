package com.roamly.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.roamly.data.api.DistanceResponse
import com.roamly.data.api.TransportResponse
import com.roamly.ui.kit.AtlasChip
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.StatBlock
import com.roamly.ui.kit.Units
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.AtlasType
import com.roamly.ui.theme.SquircleShape
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One day of the gated distance series. */
private data class DayKm(val date: LocalDate, val km: Double)

private fun DistanceResponse.series(): List<DayKm> {
    val d = days.orEmpty(); val v = distances.orEmpty()
    return d.indices.mapNotNull { i ->
        val day = runCatching { LocalDate.parse(d[i].take(10)) }.getOrNull() ?: return@mapNotNull null
        DayKm(day, v.getOrElse(i) { 0.0 })
    }
}

private fun km(raw: Double): String { val v = Units.dist(raw); return kmFmt(v) }
private fun kmFmt(v: Double) = if (v >= 100) "%,.0f".format(Locale.US, v) else String.format(Locale.US, "%.1f", v)
private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())

/** Total, active days, the typical active day and the best one. */
@Composable
internal fun DistanceTiles(d: DistanceResponse) {
    val c = Atlas.colors
    val series = remember(d) { d.series() }
    val active = series.filter { it.km > 0.05 }
    val best = active.maxByOrNull { it.km }
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBlock(km(d.totalKm ?: series.sumOf { it.km }), "${Units.distUnit} travelled", color = c.accent)
            StatBlock("%,d".format(active.size), "active days", small = true)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBlock(if (active.isEmpty()) "—" else km(active.sumOf { it.km } / active.size), "${Units.distUnit} per active day", small = true)
            StatBlock(best?.let { km(it.km) } ?: "—", best?.date?.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))?.let { "best · $it" } ?: "best day", small = true)
        }
    }
}

/** Distance by day as bars; drag a finger along to read each one. */
@Composable
internal fun DistanceChart(d: DistanceResponse, from: LocalDate? = null, to: LocalDate? = null) {
    val fromDay = from; val toDay = to
    val c = Atlas.colors
    val haptics = rememberHaptics()
    val all = remember(d) { d.series() }
    var sel by remember(d) { mutableIntStateOf(-1) }
    // Bars are one day each up to ~120 of them; past that, a bar is several days summed.
    val bars = remember(all, fromDay, toDay) {
        val end = toDay ?: all.lastOrNull()?.date ?: LocalDate.now()
        val from = fromDay ?: all.firstOrNull()?.date ?: end
        val byDay = all.associate { it.date to it.km }
        val span = java.time.temporal.ChronoUnit.DAYS.between(from, end).toInt() + 1
        val stride = ((span + 119) / 120).coerceAtLeast(1)
        (0 until span step stride).map { off ->
            val start = from.plusDays(off.toLong())
            val last = from.plusDays(minOf(off + stride - 1, span - 1).toLong())
            var sum = 0.0
            var dd = start
            while (!dd.isAfter(last)) { sum += byDay[dd] ?: 0.0; dd = dd.plusDays(1) }
            Triple(start, last, sum)
        }
    }
    val max = (bars.maxOfOrNull { it.third } ?: 0.0).coerceAtLeast(1e-6)
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)) {
        Text("Distance travelled", style = MaterialTheme.typography.titleMedium, color = c.text)
        val s = bars.getOrNull(sel)
        Text(
            if (s != null) "${s.first.format(DAY_FMT)}${if (s.second != s.first) " – ${s.second.format(DAY_FMT)}" else ""} · ${km(s.third)} ${Units.distUnit}"
            else "${km(max)} ${Units.distUnit} at most · drag to read a day",
            style = MaterialTheme.typography.bodySmall, color = if (s != null) c.accent else c.textMuted,
        )
        Spacer(Modifier.height(12.dp))
        Canvas(
            Modifier.fillMaxWidth().height(150.dp)
                .pointerInput(bars) { detectTapGestures { o -> sel = (o.x / size.width * bars.size).toInt().coerceIn(0, bars.lastIndex) } }
                .pointerInput(bars) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> sel = (o.x / size.width * bars.size).toInt().coerceIn(0, bars.lastIndex) },
                        onHorizontalDrag = { ch, _ ->
                            val i = (ch.position.x / size.width * bars.size).toInt().coerceIn(0, bars.lastIndex)
                            if (i != sel) { haptics.tick(); sel = i }
                        },
                    )
                },
        ) {
            val n = bars.size.coerceAtLeast(1)
            val slot = size.width / n
            val gap = if (slot > 5.dp.toPx()) 1.5.dp.toPx() else 0.5f
            val w = (slot - gap).coerceAtLeast(1f)
            drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            bars.forEachIndexed { i, b ->
                val h = (size.height * (b.third / max)).toFloat().coerceAtLeast(if (b.third > 0) 2.dp.toPx() else 0f)
                drawRoundRect(
                    color = if (i == sel) c.accent else c.accent.copy(alpha = if (sel >= 0) 0.4f else 0.75f),
                    topLeft = Offset(i * slot + gap / 2, size.height - h), size = Size(w, h),
                    cornerRadius = CornerRadius(minOf(w / 2, 3.dp.toPx())),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            bars.firstOrNull()?.let { Text(it.first.format(DateTimeFormatter.ofPattern("d MMM yy", Locale.getDefault())), style = MaterialTheme.typography.labelSmall, color = c.textFaint) }
            bars.lastOrNull()?.let { Text(it.second.format(DateTimeFormatter.ofPattern("d MMM yy", Locale.getDefault())), style = MaterialTheme.typography.labelSmall, color = c.textFaint) }
        }
    }
}

/** The web's Daily Distance heatmap: a year of days, one cell each, darker = further. */
@Composable
internal fun YearHeatmap(d: DistanceResponse) {
    val c = Atlas.colors
    val haptics = rememberHaptics()
    val series = remember(d) { d.series() }
    val years = remember(series) { series.map { it.date.year }.distinct().sorted() }
    var yearIdx by rememberSaveable { mutableIntStateOf(Int.MAX_VALUE) }
    val idx = yearIdx.coerceIn(0, (years.size - 1).coerceAtLeast(0))
    val year = years.getOrNull(idx) ?: LocalDate.now().year
    val byDay = remember(series, year) { series.filter { it.date.year == year }.associate { it.date to it.km } }
    val maxKm = (byDay.values.maxOrNull() ?: 0.0).coerceAtLeast(1e-6)
    var sel by remember(year) { mutableStateOf<LocalDate?>(null) }
    val jan1 = LocalDate.of(year, 1, 1)
    val lead = (jan1.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7   // Monday-first rows
    val daysInYear = jan1.lengthOfYear()
    val weeks = (lead + daysInYear + 6) / 7

    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Daily distance", style = MaterialTheme.typography.titleMedium, color = c.text)
                val s = sel
                Text(
                    if (s != null) "${s.format(DAY_FMT)} · ${km(byDay[s] ?: 0.0)} ${Units.distUnit}"
                    else "${byDay.count { it.value > 0.05 }} active days in $year",
                    style = MaterialTheme.typography.bodySmall, color = if (s != null) c.accent else c.textMuted,
                )
            }
            AtlasChip("‹", false, { if (idx > 0) { haptics.tick(); yearIdx = idx - 1 } })
            Spacer(Modifier.width(6.dp))
            Text("$year", style = AtlasType.mono, color = c.text)
            Spacer(Modifier.width(6.dp))
            AtlasChip("›", false, { if (idx < years.lastIndex) { haptics.tick(); yearIdx = idx + 1 } })
        }
        Spacer(Modifier.height(12.dp))
        fun dateAt(col: Int, row: Int): LocalDate? {
            val n = col * 7 + row - lead
            return if (n in 0 until daysInYear) jan1.plusDays(n.toLong()) else null
        }
        Canvas(
            Modifier.fillMaxWidth().height(110.dp)
                .pointerInput(year, weeks) {
                    fun pick(o: Offset) {
                        val cell = size.width.toFloat() / weeks
                        val col = (o.x / cell).toInt().coerceIn(0, weeks - 1)
                        val row = (o.y / (size.height / 7f)).toInt().coerceIn(0, 6)
                        val dt = dateAt(col, row)
                        if (dt != sel) { sel = dt; if (dt != null) haptics.tick() }
                    }
                    detectTapGestures { o -> pick(o) }
                }
                .pointerInput(year, weeks) {
                    detectHorizontalDragGestures { ch, _ ->
                        val cell = size.width.toFloat() / weeks
                        val col = (ch.position.x / cell).toInt().coerceIn(0, weeks - 1)
                        val row = (ch.position.y / (size.height / 7f)).toInt().coerceIn(0, 6)
                        val dt = dateAt(col, row)
                        if (dt != sel) { sel = dt; if (dt != null) haptics.tick() }
                    }
                },
        ) {
            val cell = size.width / weeks
            val rowH = size.height / 7f
            val gap = 1.5.dp.toPx()
            for (col in 0 until weeks) for (row in 0 until 7) {
                val dt = dateAt(col, row) ?: continue
                val v = byDay[dt] ?: 0.0
                val level = when {
                    v <= 0.05 -> 0
                    v < maxKm * 0.15 -> 1
                    v < maxKm * 0.35 -> 2
                    v < maxKm * 0.65 -> 3
                    else -> 4
                }
                val color = if (level == 0) c.surfaceHigh else c.accent.copy(alpha = 0.22f + level * 0.2f)
                drawRoundRect(
                    color, Offset(col * cell + gap / 2, row * rowH + gap / 2),
                    Size((cell - gap).coerceAtLeast(1f), (rowH - gap).coerceAtLeast(1f)), CornerRadius(2.dp.toPx()),
                )
                if (dt == sel) drawRoundRect(
                    c.text, Offset(col * cell + gap / 2, row * rowH + gap / 2),
                    Size((cell - gap).coerceAtLeast(1f), (rowH - gap).coerceAtLeast(1f)), CornerRadius(2.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("less", style = MaterialTheme.typography.labelSmall, color = c.textFaint)
            Spacer(Modifier.width(6.dp))
            repeat(5) { l ->
                Box(Modifier.padding(horizontal = 1.5.dp).size(10.dp).clip(SquircleShape(3.dp)).background(if (l == 0) c.surfaceHigh else c.accent.copy(alpha = 0.22f + l * 0.2f)))
            }
            Spacer(Modifier.width(6.dp))
            Text("more", style = MaterialTheme.typography.labelSmall, color = c.textFaint)
        }
    }
}

private fun modeLabel(m: String?) = when (m) {
    "still" -> "Still"; "walk" -> "Walking"; "cycle" -> "Cycling"; "vehicle" -> "Vehicle"; "plane" -> "Flying"
    else -> m.orEmpty().replaceFirstChar { it.uppercase() }
}

/** How You Travelled: distance and time per transport mode. */
@Composable
internal fun TransportBreakdown(t: TransportResponse) {
    val c = Atlas.colors
    val modes = t.modes.orEmpty()
    val tones = listOf(c.cyan, c.lime, c.amber, c.rose, c.violet, c.sky)
    val maxKm = (modes.maxOfOrNull { it.km ?: 0.0 } ?: 0.0).coerceAtLeast(1e-6)
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        Text("How you travelled", style = MaterialTheme.typography.titleMedium, color = c.text)
        Spacer(Modifier.height(12.dp))
        modes.forEachIndexed { i, m ->
            val tone = tones[i % tones.size]
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(modeLabel(m.mode), style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
                Text("${km(m.km ?: 0.0)} ${Units.distUnit}", style = MaterialTheme.typography.labelLarge, color = tone)
                Text("  ·  ${String.format(Locale.US, "%.1f", m.hours ?: 0.0)} h", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(SquircleShape(50)).background(c.surfaceHigh)) {
                Box(Modifier.fillMaxWidth(((m.km ?: 0.0) / maxKm).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().clip(SquircleShape(50)).background(tone))
            }
            if (i < modes.lastIndex) Spacer(Modifier.height(14.dp))
        }
    }
}
