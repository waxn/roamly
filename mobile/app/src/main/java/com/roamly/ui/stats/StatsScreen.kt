package com.roamly.ui.stats

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.roamly.ui.theme.Clay
import com.roamly.ui.theme.ClayCard
import com.roamly.ui.theme.ClayIconBadge
import com.roamly.ui.theme.Teal
import kotlin.math.max

@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val clay = Clay.colors

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClayIconBadge(Icons.Rounded.BarChart, gradient = clay.secondaryGradient, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Text("Stats", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (state.isLoading && state.stats == null) {
                SkeletonStats()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 0.dp, 16.dp, 100.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    state.stats?.let { item { SummaryCard(it) } }
                    state.yearly?.let { item { YearlyOverview(it) } }
                    state.yearly?.let { item { MonthlyBars(it) } }

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
    val clay = Clay.colors
    ClayCard {
        Text("Overview", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatBlock("${stats.totalPoints}", "points", Icons.Rounded.Flag, clay.primaryGradient)
            StatBlock("${stats.countries}", "countries", Icons.Rounded.Public, clay.secondaryGradient)
            StatBlock("${stats.cities}", "cities", Icons.Rounded.LocationCity, clay.tertiaryGradient)
            StatBlock("${stats.states}", "states", Icons.Rounded.Place, clay.primaryGradient)
        }
        if (stats.firstLocation != null) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CalendarMonth, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("tracking since ${stats.firstLocation.take(10)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatBlock(value: String, label: String, icon: ImageVector, gradient: List<Color>) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ClayIconBadge(icon, gradient = gradient, size = 42.dp, cornerRadius = 14.dp)
        Spacer(Modifier.height(8.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun YearlyOverview(yearly: YearlyOverviewResponse) {
    ClayCard {
        Text("${yearly.year} overview", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PeriodBlock("This week", yearly.thisWeek, yearly.lastWeek, Modifier.weight(1f))
            PeriodBlock("This month", yearly.thisMonth, yearly.lastMonth, Modifier.weight(1f))
            PeriodBlock("This year", yearly.thisYear, yearly.lastYear, Modifier.weight(1f))
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
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text("${current.points}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(deltaIcon, null, tint = deltaColor, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(2.dp))
            Text(pct?.let { "%+.0f%%".format(it) } ?: if (delta == 0) "—" else "+$delta", style = MaterialTheme.typography.labelSmall, color = deltaColor)
        }
        Spacer(Modifier.height(2.dp))
        Text("${current.cities}c · ${current.countries}co", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MonthlyBars(yearly: YearlyOverviewResponse) {
    val buckets = yearly.monthlyBreakdown
    if (buckets.isEmpty()) return
    val clay = Clay.colors
    val maxV = max(1, buckets.maxOf { it.points })
    ClayCard {
        Text("${yearly.year} — monthly activity", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth().height(120.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            buckets.forEach { b ->
                val frac = b.points.toFloat() / maxV
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((frac * 96).dp.coerceAtLeast(4.dp))
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(Brush.verticalGradient(clay.primaryGradient))
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(monthAbbrev(b.month), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun monthAbbrev(month: Int): String = listOf("J","F","M","A","M","J","J","A","S","O","N","D").getOrElse(month - 1) { "" }

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, start = 4.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun CountryRow(country: CountryVisit) {
    ClayCard(contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClayIconBadge(Icons.Rounded.Public, gradient = Clay.colors.secondaryGradient, size = 38.dp, cornerRadius = 13.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(country.country, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Text("${country.cityCount} cities", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${country.locationCount} pts", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CityRow(city: CityVisit) {
    ClayCard(contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClayIconBadge(Icons.Rounded.Place, gradient = Clay.colors.tertiaryGradient, size = 38.dp, cornerRadius = 13.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(city.city, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                val sub = listOfNotNull(city.state, city.country).joinToString(", ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${city.visitCount} visits", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Skeleton loading ─────────────────────────────────────────────────────────

@Composable
private fun SkeletonStats() {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900), repeatMode = RepeatMode.Reverse), label = "a",
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SkeletonCard(150.dp, alpha)
        SkeletonCard(140.dp, alpha)
        SkeletonCard(170.dp, alpha)
    }
}

@Composable
private fun SkeletonCard(height: androidx.compose.ui.unit.Dp, alpha: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha * 0.6f))
    )
}
