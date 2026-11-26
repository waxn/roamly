package com.roamly.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.SearchDay
import com.roamly.data.api.SearchPlace
import com.roamly.ui.kit.AtlasField
import com.roamly.ui.kit.AtlasMotion
import com.roamly.ui.kit.EmptyState
import com.roamly.ui.kit.ErrorNote
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.IconTile
import com.roamly.ui.kit.SectionTitle
import com.roamly.ui.kit.dockAwareBottom
import com.roamly.ui.kit.tappable
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val EXAMPLES = listOf("Lisbon", "May 2026", "airport", "last summer")

/**
 * Search your history: the days you were somewhere matching, plus named
 * places with coordinates. A place with days expands to list them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onPlaceClick: (lat: Double, lng: Double) -> Unit,
    onDayClick: ((SearchDay) -> Unit)? = null,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val c = Atlas.colors
    val focus = LocalFocusManager.current

    Column(Modifier.fillMaxSize()) {
        AtlasField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            placeholder = "City, place or date",
            leading = Icons.Rounded.Search,
            trailing = if (state.query.isNotEmpty()) ({
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.isLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
                    IconAction(Icons.Rounded.Close, "Clear", { viewModel.onQueryChange("") }, container = null, tint = c.textMuted, size = 40.dp, iconSize = 18.dp)
                }
            }) else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        when {
            state.isLoading && state.result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.accent)
            }
            state.error != null && state.result == null -> ErrorNote(state.error!!, Modifier.padding(20.dp), onRetry = viewModel::retry)
            !state.hasSearched -> Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(Icons.Rounded.Search, "Find any day, anywhere", body = "Search by city, a place's name, or a date.")
                FlowRow(
                    Modifier.padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EXAMPLES.forEach { q ->
                        Text(
                            q, style = MaterialTheme.typography.labelLarge, color = c.text,
                            modifier = Modifier.clip(SquircleShape(50)).background(c.surfaceHigh)
                                .tappable(pressedScale = 0.94f, feel = Feel.Select) { viewModel.onQueryChange(q) }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        )
                    }
                }
            }
            else -> {
                val places = state.result?.placeResults.orEmpty()
                val days = state.result?.locationResults.orEmpty()
                if (places.isEmpty() && days.isEmpty()) {
                    EmptyState(Icons.Rounded.SearchOff, "Nothing for “${state.query}”", body = "Try a city name, or a month like “June 2025”.", tint = c.textMuted)
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = dockAwareBottom()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (places.isNotEmpty()) {
                            item { SectionTitle("Places · ${places.size}") }
                            items(places) { place -> PlaceRow(place, onDayClick) { onPlaceClick(place.lat, place.lng) } }
                        }
                        if (days.isNotEmpty()) {
                            item { SectionTitle("Days · ${days.size}", Modifier.padding(top = 10.dp)) }
                            items(days) { day -> DayRow(day, onDayClick?.let { open -> { open(day) } }) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(place: SearchPlace, onDayClick: ((SearchDay) -> Unit)?, onClick: () -> Unit) {
    val c = Atlas.colors
    val expandable = place.days.isNotEmpty() && onDayClick != null
    var expanded by remember { mutableStateOf(false) }
    val angle by animateFloatAsState(if (expanded) 90f else 0f, AtlasMotion.snappy(), label = "placeChevron")
    Column(Modifier.fillMaxWidth().clip(SquircleShape(SquircleRadius.panel)).background(c.surface).animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().tappable(pressedScale = 0.99f, feel = if (expandable) Feel.Tick else Feel.Click) {
                if (expandable) expanded = !expanded else onClick()
            }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(Icons.Rounded.Place, tint = c.accent, size = 38.dp, iconSize = 19.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(place.placeName, style = MaterialTheme.typography.titleSmall, color = c.text)
                Text("${place.days.size} days · %,d points".format(place.totalPoints), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
            if (expandable) {
                IconAction(Icons.Rounded.Place, "Show on map", onClick, container = c.surfaceHigh, tint = c.accent, size = 34.dp, iconSize = 16.dp)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.ChevronRight, null, tint = c.textFaint, modifier = Modifier.rotate(angle))
            } else {
                Icon(Icons.Rounded.ChevronRight, null, tint = c.textFaint)
            }
        }
        AnimatedVisibility(expanded && expandable) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                place.days.forEach { d -> DayRow(d, onDayClick?.let { open -> { open(d) } }, inset = true) }
            }
        }
    }
}

@Composable
private fun DayRow(day: SearchDay, onClick: (() -> Unit)?, inset: Boolean = false) {
    val c = Atlas.colors
    Row(
        Modifier.fillMaxWidth().clip(SquircleShape(if (inset) SquircleRadius.control else SquircleRadius.panel))
            .background(if (inset) c.surfaceHigh else c.surface)
            .then(if (onClick != null) Modifier.tappable(pressedScale = 0.99f, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CalendarMonth, null, tint = c.textMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(prettyDay(day.date), style = MaterialTheme.typography.bodyLarge, color = c.text)
            val where = day.cities.joinToString(", ") { if (it.state.isNotBlank()) "${it.city}, ${it.state}" else it.city }
                .ifBlank { "${day.count} points" }
            Text(where, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        if (day.timeSpent > 0) Text(formatDuration(day.timeSpent), style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        if (onClick != null) { Spacer(Modifier.width(4.dp)); Icon(Icons.Rounded.ChevronRight, null, tint = c.textFaint, modifier = Modifier.size(18.dp)) }
    }
    Spacer(Modifier.height(0.dp))
}

private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private fun prettyDay(iso: String) = runCatching { LocalDate.parse(iso).format(dayFmt) }.getOrDefault(iso)

private fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "<1m"
    }
}
