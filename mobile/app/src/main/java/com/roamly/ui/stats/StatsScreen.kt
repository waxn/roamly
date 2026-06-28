package com.roamly.ui.stats

import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingFlat
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.CityVisit
import com.roamly.data.api.CountryVisit
import com.roamly.data.api.PeriodStats
import com.roamly.data.api.StatsResponse
import com.roamly.data.api.YearlyOverviewResponse
import com.roamly.ui.theme.Teal
import kotlin.math.max

@Composable
fun StatsScreen(
    viewModel: StatsViewModel = hiltViewModel(),
    onNavigateToMap: ((String) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.BarChart, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text("Stats", style = MaterialTheme.typography.headlineSmall)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (state.isLoading && state.stats == null) {
                SkeletonStats()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 0.dp, 16.dp, 100.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.stats?.let { item { SummaryCard(it) } }
                    state.yearly?.let { item { YearlyOverview(it) } }
                    state.yearly?.let { yearly ->
                        item {
                            MonthlyBars(
                                yearly = yearly,
                                selectedMonth = state.selectedMonth,
                                onMonthClick = viewModel::selectMonth,
                            )
                        }
                        if (state.selectedMonth != null) {
                            item {
                                MonthDrilldown(
                                    month = state.selectedMonth!!,
                                    year = yearly.year,
                                    data = state.monthDrilldown,
                                    loading = state.drilldownLoading,
                                    onDayClick = { dateStr -> onNavigateToMap?.invoke(dateStr) },
                                )
                            }
                        }
                    }

                    if (state.topCountries.isNotEmpty()) {
                        item { SectionHeader("Countries", Icons.Rounded.Public) }
                        items(state.topCountries) { CountryRow(it) }
                    }
                    if (state.topCities.isNotEmpty()) {
                        item { SectionHeader("Cities", Icons.Rounded.LocationCity) }
                        items(state.topCities) { CityRow(it) }
                    }
                }
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
    }
}

@Composable
private fun SummaryCard(stats: StatsResponse) {
    ElevatedCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Overview", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatBlock("${stats.totalPoints}", "points", Icons.Rounded.Flag, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                StatBlock("${stats.countries}", "countries", Icons.Rounded.Public, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                StatBlock("${stats.cities}", "cities", Icons.Rounded.LocationCity, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                StatBlock("${stats.states}", "states", Icons.Rounded.Place, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (stats.firstLocation != null) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CalendarMonth, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("tracking since ${stats.firstLocation.take(10)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StatBlock(value: String, label: String, icon: ImageVector, containerColor: Color, contentColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(containerColor),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = contentColor, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun YearlyOverview(yearly: YearlyOverviewResponse) {
    ElevatedCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("${yearly.year} overview", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PeriodBlock("This week",  yearly.thisWeek,  yearly.lastWeek,  Modifier.weight(1f))
                PeriodBlock("This month", yearly.thisMonth, yearly.lastMonth, Modifier.weight(1f))
                PeriodBlock("This year",  yearly.thisYear,  yearly.lastYear,  Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PeriodBlock(label: String, current: PeriodStats, previous: PeriodStats, modifier: Modifier = Modifier) {
    val delta = current.points - previous.points
    val pct = if (previous.points > 0) (delta.toFloat() / previous.points * 100f) else null
    val (deltaColor, deltaIcon) = when {
        delta > 0 -> Teal to Icons.Rounded.TrendingUp
        delta < 0 -> MaterialTheme.colorScheme.error to Icons.Rounded.TrendingDown
        else -> MaterialTheme.colorScheme.onSurfaceVariant to Icons.Rounded.TrendingFlat
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(10.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text("${current.points}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(deltaIcon, null, tint = deltaColor, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(2.dp))
            Text(pct?.let { "%+.0f%%".format(it) } ?: if (delta == 0) "—" else "+$delta", style = MaterialTheme.typography.labelSmall, color = deltaColor)
        }
    }
}

@Composable
private fun MonthlyBars(
    yearly: YearlyOverviewResponse,
    selectedMonth: Int?,
    onMonthClick: (Int) -> Unit,
) {
    val buckets = yearly.monthlyBreakdown
    if (buckets.isEmpty()) return
    val maxV = max(1, buckets.maxOf { it.points })
    ElevatedCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("${yearly.year} — monthly activity", style = MaterialTheme.typography.titleSmall)
            Text("tap a month for daily breakdown", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth().height(100.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                buckets.forEach { b ->
                    val frac = b.points.toFloat() / maxV
                    val isSelected = b.month == selectedMonth
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onMonthClick(b.month) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((frac * 76).dp.coerceAtLeast(3.dp))
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primaryContainer
                                )
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            monthAbbrev(b.month),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthDrilldown(
    month: Int,
    year: Int,
    data: List<DayCount>,
    loading: Boolean,
    onDayClick: (String) -> Unit,
) {
    val monthName = java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
    ElevatedCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("$monthName $year — daily", style = MaterialTheme.typography.titleSmall)
            Text("tap a day to view it on the map", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            if (loading) {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                val maxV = max(1, data.maxOfOrNull { it.points } ?: 1)
                val chartH = 72.dp
                val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                val barColor = MaterialTheme.colorScheme.secondary
                Box(modifier = Modifier.fillMaxWidth()) {
                    // Canvas: grid lines + bars
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(chartH)) {
                        val w = size.width; val h = size.height
                        val n = data.size.coerceAtLeast(1)
                        val barW = w / n; val gap = barW * 0.15f
                        listOf(0.25f, 0.5f, 0.75f, 1f).forEach { pct ->
                            val y = h * (1f - pct)
                            drawLine(gridColor, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(w, y), 1f)
                        }
                        data.forEachIndexed { i, d ->
                            val bH = (d.points.toFloat() / maxV * h).coerceAtLeast(if (d.points > 0) 2f else 0f)
                            drawRect(barColor, androidx.compose.ui.geometry.Offset(i * barW + gap / 2f, h - bH), androidx.compose.ui.geometry.Size(barW - gap, bH))
                        }
                    }
                    // Transparent tap targets on top of Canvas
                    Row(modifier = Modifier.fillMaxWidth().height(chartH)) {
                        data.forEach { d ->
                            Box(modifier = Modifier.weight(1f).fillMaxSize().clickable(enabled = d.points > 0) {
                                onDayClick("%04d-%02d-%02d".format(year, month, d.day))
                            })
                        }
                    }
                }
                // Day labels row — separate so they never push bars
                Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    data.forEach { d ->
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (d.day == 1 || d.day % 7 == 0) {
                                Text(
                                    "${d.day}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = androidx.compose.ui.unit.TextUnit(7f, androidx.compose.ui.unit.TextUnitType.Sp)
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun monthAbbrev(month: Int): String = listOf("J","F","M","A","M","J","J","A","S","O","N","D").getOrElse(month - 1) { "" }

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, start = 4.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun CountryRow(country: CountryVisit) {
    ElevatedCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Public, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(country.country, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("${country.cityCount} cities", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${country.locationCount} pts", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CityRow(city: CityVisit) {
    ElevatedCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Place, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(city.city, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                val sub = listOfNotNull(city.state, city.country).joinToString(", ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${city.visitCount} visits", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Skeleton ──────────────────────────────────────────────────────────────────

@Composable
private fun SkeletonStats() {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.3f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(900), repeatMode = RepeatMode.Reverse), label = "a",
    )
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SkeletonCard(140.dp, alpha); SkeletonCard(120.dp, alpha); SkeletonCard(160.dp, alpha)
    }
}

@Composable
private fun SkeletonCard(height: androidx.compose.ui.unit.Dp, alpha: Float) {
    Box(
        modifier = Modifier.fillMaxWidth().height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
    )
}
