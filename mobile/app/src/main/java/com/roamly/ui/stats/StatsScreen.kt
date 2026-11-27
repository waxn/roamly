package com.roamly.ui.stats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingFlat
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.CityVisit
import com.roamly.data.api.CountryVisit
import com.roamly.data.api.PeriodStats
import com.roamly.data.api.StatsResponse
import com.roamly.data.api.YearlyOverviewResponse
import com.roamly.ui.kit.ErrorNote
import com.roamly.ui.kit.Eyebrow
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PushedScreen
import com.roamly.ui.kit.SectionTitle
import com.roamly.ui.kit.SegmentedSwitch
import com.roamly.ui.kit.Skeleton
import com.roamly.ui.kit.StatBlock
import com.roamly.ui.kit.dockAwareBottom
import com.roamly.ui.kit.tappable
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.AtlasType
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max

/** Your numbers: the totals, this period against the last, the year by month, places ranked. */
@Composable
fun StatsScreen(
    viewModel: StatsViewModel = hiltViewModel(),
    onNavigateToMap: ((String) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    PushedScreen(title = "Stats", onBack = onBack) {
        if (state.isLoading && state.stats == null) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Skeleton(Modifier.fillMaxWidth().height(180.dp), SquircleShape(SquircleRadius.panel))
                Skeleton(Modifier.fillMaxWidth().height(110.dp), SquircleShape(SquircleRadius.panel))
                Skeleton(Modifier.fillMaxWidth().height(170.dp), SquircleShape(SquircleRadius.panel))
            }
            return@PushedScreen
        }
        val countryMax = remember(state.topCountries) { state.topCountries.maxOfOrNull { it.locationCount } ?: 1 }
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = dockAwareBottom()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { item { ErrorNote(it, onRetry = viewModel::load) } }
            // Lifetime, whatever the range below says.
            state.stats?.let { item { Hero(it) } }
            item { RangePicker(state.range, viewModel::setRange) }
            state.rangeStats?.takeIf { state.range != StatsRange.All }?.let { item { RangeSummary(state.range, it) } }
            val window = state.range.window()
            state.distance?.takeIf { !it.days.isNullOrEmpty() }?.let { d ->
                item { DistanceTiles(d) }
                item { DistanceChart(d, from = window?.first, to = window?.second) }
                // A year-per-page grid only reads as a year when the range is a year or more.
                if (state.range == StatsRange.Year || state.range == StatsRange.All) item { YearHeatmap(d) }
            }
            state.transport?.takeIf { !it.modes.isNullOrEmpty() }?.let { item { TransportBreakdown(it) } }
            // Calendar-year figures: only meaningful for a year or all time.
            state.yearly?.takeIf { state.range == StatsRange.Year || state.range == StatsRange.All }?.let { y ->
                if (state.range == StatsRange.All) item { Periods(y) }
                item {
                    Monthly(y, state.selectedMonth, viewModel::selectMonth)
                }
                item {
                    AnimatedVisibility(state.selectedMonth != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        state.selectedMonth?.let { m ->
                            Drilldown(m, y.year, state.monthDrilldown, state.drilldownLoading) { onNavigateToMap?.invoke(it) }
                        }
                    }
                }
            }
            if (state.topCountries.isNotEmpty()) {
                item { SectionTitle("Countries · ${state.topCountries.size}", Modifier.padding(top = 10.dp)) }
                itemsIndexed(state.topCountries) { i, cv -> CountryRow(i + 1, cv, countryMax) }
            }
            if (state.topCities.isNotEmpty()) {
                item { SectionTitle("Cities", Modifier.padding(top = 10.dp)) }
                itemsIndexed(state.topCities) { i, cv -> CityRow(i + 1, cv) }
            }
        }
    }
}

@Composable
private fun Hero(stats: StatsResponse) {
    val c = Atlas.colors
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
        Box(
            Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(c.accent.copy(alpha = 0.22f), c.violet.copy(alpha = 0.10f), c.surface)))
                .padding(20.dp),
        ) {
            Column {
                Eyebrow(stats.firstLocation?.let { "Since ${it.take(10)}" } ?: "All time")
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${stats.countries}", style = MaterialTheme.typography.displayLarge, color = c.text)
                    Spacer(Modifier.width(10.dp))
                    Text(if (stats.countries == 1) "country" else "countries", style = MaterialTheme.typography.titleLarge, color = c.textMuted, modifier = Modifier.padding(bottom = 10.dp))
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatBlock("%,d".format(stats.cities), "cities", small = true)
                    StatBlock("%,d".format(stats.states), "regions", small = true)
                    StatBlock("%,d".format(stats.totalPoints), "points", small = true)
                }
            }
        }
    }
}

/** One switch that moves everything below it; the hero above stays lifetime. */
@Composable
private fun RangePicker(range: StatsRange, onPick: (StatsRange) -> Unit) {
    val c = Atlas.colors
    Column(Modifier.padding(top = 4.dp)) {
        Text("Time range", style = MaterialTheme.typography.labelMedium, color = c.textMuted, modifier = Modifier.padding(bottom = 6.dp, start = 2.dp))
        SegmentedSwitch(
            options = StatsRange.entries.map { it.label },
            selected = range.ordinal,
            onSelect = { onPick(StatsRange.entries[it]) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Totals for the chosen range only. */
@Composable
private fun RangeSummary(range: StatsRange, s: StatsResponse) {
    val c = Atlas.colors
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
        Eyebrow("Last ${range.days} days")
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBlock("%,d".format(s.totalPoints), "points", color = c.accent)
            StatBlock("%,d".format(s.countries), if (s.countries == 1) "country" else "countries", small = true)
            StatBlock("%,d".format(s.cities), if (s.cities == 1) "city" else "cities", small = true)
        }
    }
}

@Composable
private fun Periods(y: YearlyOverviewResponse) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Period("Week", y.thisWeek, y.lastWeek, Modifier.weight(1f))
        Period("Month", y.thisMonth, y.lastMonth, Modifier.weight(1f))
        Period("Year", y.thisYear, y.lastYear, Modifier.weight(1f))
    }
}

@Composable
private fun Period(label: String, current: PeriodStats, previous: PeriodStats, modifier: Modifier) {
    val c = Atlas.colors
    val delta = current.points - previous.points
    val pct = if (previous.points > 0) delta * 100f / previous.points else null
    val (tone, icon) = when {
        delta > 0 -> c.success to Icons.Rounded.TrendingUp
        delta < 0 -> c.danger to Icons.Rounded.TrendingDown
        else -> c.textMuted to Icons.Rounded.TrendingFlat
    }
    Panel(modifier, padding = PaddingValues(14.dp)) {
        Text("This $label", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
        Spacer(Modifier.height(6.dp))
        Text(compact(current.points), style = AtlasType.figureSmall, color = c.text)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tone, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(3.dp))
            Text(pct?.let { "%+.0f%%".format(it) } ?: if (delta == 0) "—" else "new", style = MaterialTheme.typography.labelMedium, color = tone)
        }
    }
}

@Composable
private fun Monthly(y: YearlyOverviewResponse, selected: Int?, onPick: (Int) -> Unit) {
    val c = Atlas.colors
    val buckets = y.monthlyBreakdown
    if (buckets.isEmpty()) return
    val maxV = max(1, buckets.maxOf { it.points })
    // Bars grow in on first show.
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(700)) }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${y.year} by month", style = MaterialTheme.typography.titleMedium, color = c.text)
                Text("Tap a month for its days", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().height(130.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            buckets.forEach { b ->
                val on = b.month == selected
                val frac by animateFloatAsState(b.points.toFloat() / maxV * grow.value, label = "bar")
                val color by animateColorAsState(if (on) c.accent else if (selected == null) c.accent.copy(alpha = 0.55f) else c.surfaceHighest, label = "barC")
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(SquircleShape(8.dp)).tappable(feel = Feel.Select, pressedScale = 0.9f) { onPick(b.month) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Box(Modifier.fillMaxWidth().weight(1f, fill = true), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight(frac.coerceAtLeast(0.03f)).clip(SquircleShape(6.dp)).background(color))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        Month.of(b.month).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall, color = if (on) c.accent else c.textFaint,
                    )
                }
            }
        }
    }
}

@Composable
private fun Drilldown(month: Int, year: Int, data: List<DayCount>, loading: Boolean, onDay: (String) -> Unit) {
    val c = Atlas.colors
    val name = Month.of(month).getDisplayName(TextStyle.FULL, Locale.getDefault())
    Panel(Modifier.fillMaxWidth()) {
        Text("$name $year", style = MaterialTheme.typography.titleMedium, color = c.text)
        Text("Tap a day to see it on the map", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        Spacer(Modifier.height(14.dp))
        if (loading) {
            Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = c.accent) }
            return@Panel
        }
        val maxV = max(1, data.maxOfOrNull { it.points } ?: 1)
        Box(Modifier.fillMaxWidth().height(90.dp)) {
            val grid = c.line
            val bar = c.cyan
            Canvas(Modifier.fillMaxSize()) {
                val n = data.size.coerceAtLeast(1)
                val w = size.width / n
                listOf(0.25f, 0.5f, 0.75f, 1f).forEach { p -> drawLine(grid, Offset(0f, size.height * (1 - p)), Offset(size.width, size.height * (1 - p)), 1f) }
                data.forEachIndexed { i, d ->
                    val h = (d.points.toFloat() / maxV * size.height).coerceAtLeast(if (d.points > 0) 3f else 0f)
                    drawRoundRect(bar, Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h), CornerRadius(w * 0.35f))
                }
            }
            Row(Modifier.fillMaxSize()) {
                data.forEach { d ->
                    Box(Modifier.weight(1f).fillMaxHeight().then(
                        if (d.points > 0) Modifier.tappable(feel = Feel.Tick, pressedScale = 0.9f) { onDay("%04d-%02d-%02d".format(year, month, d.day)) } else Modifier
                    ))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            data.forEach { d ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (d.day == 1 || d.day % 7 == 0) Text("${d.day}", style = MaterialTheme.typography.labelSmall, color = c.textFaint, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun CountryRow(rank: Int, country: CountryVisit, maxPoints: Int) {
    val c = Atlas.colors
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("%02d".format(rank), style = AtlasType.mono, color = c.textFaint, modifier = Modifier.width(30.dp))
            Column(Modifier.weight(1f)) {
                Text(country.country, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${country.cityCount} cities", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
            Text(compact(country.locationCount), style = MaterialTheme.typography.labelLarge, color = c.accent)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.padding(start = 30.dp).fillMaxWidth().height(4.dp).clip(SquircleShape(50)).background(c.surfaceHigh)) {
            Box(Modifier.fillMaxWidth(country.locationCount.toFloat() / maxPoints).fillMaxHeight().clip(SquircleShape(50)).background(c.accent))
        }
    }
}

@Composable
private fun CityRow(rank: Int, city: CityVisit) {
    val c = Atlas.colors
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("%02d".format(rank), style = AtlasType.mono, color = c.textFaint, modifier = Modifier.width(30.dp))
            Column(Modifier.weight(1f)) {
                Text(city.city, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(city.state, city.country).joinToString(", ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${city.visitCount} visits", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        }
    }
}

/** 12,345 → "12.3k"; small numbers stay exact. */
private fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
    n >= 10_000 -> "%.1fk".format(n / 1_000f)
    else -> "%,d".format(n)
}
