package com.roamly.ui.settings

import com.roamly.ui.kit.Units
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.TableRows
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.LocationTableRow
import com.roamly.ui.kit.AtlasDialog
import com.roamly.ui.kit.AtlasSheet
import com.roamly.ui.kit.EmptyState
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.InfoPair
import com.roamly.ui.kit.ListRow
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PushedScreen
import com.roamly.ui.kit.Tag
import com.roamly.ui.kit.dockAwareBottom
import com.roamly.ui.kit.tappable
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.AtlasType
import com.roamly.ui.theme.SquircleShape
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Sort keys the server accepts, paired with a phone-friendly label.
private val SORT_OPTIONS = listOf(
    "timestamp" to "Time", "device" to "Device", "city" to "City", "state" to "State",
    "country_code" to "Country", "poi_name" to "Place", "speed" to "Speed", "battery" to "Battery",
    "lat" to "Latitude", "lng" to "Longitude",
)
private val RANGE_OPTIONS = listOf("24" to "Last 24h", "72" to "Last 3 days", "168" to "Last week", "720" to "Last 30 days", "all" to "All time")

private enum class Picker { Range, Device, Sort, Source }

/** Every raw point, as the web's /data/ page: filter, sort, page, delete. */
@Composable
fun LocationTableScreen(
    onBack: (() -> Unit)? = null,
    viewModel: LocationTableViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val c = Atlas.colors
    val listState = rememberLazyListState()
    var confirmDelete by remember { mutableStateOf<LocationTableRow?>(null) }
    var picker by remember { mutableStateOf<Picker?>(null) }

    val nearEnd by remember {
        derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= (if (state.source != "locations") state.rawRows.size else state.rows.size) - 8 }
    }
    LaunchedEffect(nearEnd, state.rows.size, state.rawRows.size, state.hasMore) { if (nearEnd && state.hasMore) viewModel.loadMore() }

    PushedScreen(title = "Raw data", onBack = onBack) {
        val raw = state.source != "locations"
        if (state.sources.size > 1) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Pill(Icons.Rounded.TableRows, state.sourceInfo?.label ?: "Recorded points", Modifier.fillMaxWidth()) { picker = Picker.Source }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!raw || state.sourceInfo?.range != false)
                Pill(Icons.Rounded.DateRange, RANGE_OPTIONS.firstOrNull { it.first == state.range }?.second ?: "Range", Modifier.weight(1f)) { picker = Picker.Range }
            if (!raw || state.sourceInfo?.device != false)
                Pill(Icons.Rounded.Smartphone, state.deviceId?.let { state.devices[it] } ?: "All devices", Modifier.weight(1f)) { picker = Picker.Device }
            if (!raw) Pill(if (state.sortDir == "asc") Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                SORT_OPTIONS.firstOrNull { it.first == state.sortKey }?.second ?: "Sort") { picker = Picker.Sort }
        }
        Box(Modifier.fillMaxSize()) {
            when {
                state.isLoading && state.rows.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.accent)
                raw && state.rawRows.isEmpty() -> EmptyState(Icons.Rounded.TableRows, state.error ?: "Nothing here yet", tint = c.lime)
                raw -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = dockAwareBottom()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.rawRows, key = { (it["id"] as? Number)?.toLong() ?: it.hashCode().toLong() }) { row -> RawRow(row, state.rawColumns) }
                    if (state.isLoadingMore) item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = c.accent) }
                    }
                }
                state.rows.isEmpty() -> EmptyState(Icons.Rounded.TableRows, state.error ?: "No points in this range", tint = c.lime)
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = dockAwareBottom()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.rows, key = { it.id }) { row -> Point(row) { confirmDelete = row } }
                    if (state.isLoadingMore) item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = c.accent) }
                    }
                }
            }
        }
    }

    picker?.let { p ->
        val options: List<Pair<String, String>> = when (p) {
            Picker.Range -> RANGE_OPTIONS
            Picker.Device -> listOf("" to "All devices") + state.devices.map { (id, name) -> id to name }
            Picker.Sort -> SORT_OPTIONS
            Picker.Source -> state.sources.map { (it.key ?: "") to (it.label ?: it.key.orEmpty()) }
        }
        val current = when (p) { Picker.Range -> state.range; Picker.Device -> state.deviceId.orEmpty(); Picker.Sort -> state.sortKey; Picker.Source -> state.source }
        AtlasSheet(onDismiss = { picker = null }, title = when (p) { Picker.Range -> "Range"; Picker.Device -> "Device"; Picker.Sort -> "Sort by"; Picker.Source -> "Data source" }) {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(options) { (value, label) ->
                    ListRow(
                        label,
                        subtitle = if (p == Picker.Sort && value == current) (if (state.sortDir == "asc") "Ascending — tap to flip" else "Descending — tap to flip") else null,
                        onClick = {
                            when (p) {
                                Picker.Range -> viewModel.setRange(value)
                                Picker.Device -> viewModel.setDevice(value.ifEmpty { null })
                                Picker.Sort -> viewModel.setSort(value)
                                Picker.Source -> viewModel.setSource(value)
                            }
                            if (p != Picker.Sort || value != current) picker = null
                        },
                        showChevron = false,
                        trailing = if (value == current) ({ Icon(Icons.Rounded.Check, null, tint = c.accent) }) else null,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }

    confirmDelete?.let { row ->
        AtlasDialog(
            title = "Delete this point?",
            onDismiss = { confirmDelete = null },
            confirmText = "Delete",
            onConfirm = { viewModel.deleteRow(row.id); confirmDelete = null },
            destructive = true,
            icon = Icons.Rounded.DeleteOutline,
            body = { Text("It's permanently removed from your history. This can't be undone.") },
        )
    }
}

@Composable
private fun Pill(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Atlas.colors
    Row(
        modifier.clip(SquircleShape(50)).background(c.surfaceHigh).tappable(feel = Feel.Tick, pressedScale = 0.95f, onClick = onClick)
            .heightIn(min = 40.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Rounded.ExpandMore, null, tint = c.textMuted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Point(row: LocationTableRow, onDelete: () -> Unit) {
    val c = Atlas.colors
    val place = row.customPlace?.takeIf { it.isNotBlank() } ?: row.poiName?.takeIf { it.isNotBlank() }
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(formatTimestamp(row.timestamp), style = AtlasType.mono, color = c.textMuted)
                val locality = listOfNotNull(row.city, row.state, row.countryCode).filter { it.isNotBlank() }.joinToString(", ")
                Text(locality.ifBlank { row.device.ifBlank { "—" } }, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (row.flag == "suspect") { Tag("Suspect", color = c.warning); Spacer(Modifier.width(4.dp)) }
            IconAction(Icons.Rounded.DeleteOutline, "Delete", onDelete, container = null, tint = c.textMuted, size = 36.dp, iconSize = 18.dp)
        }
        place?.let {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Icon(Icons.Rounded.Place, null, Modifier.size(13.dp), tint = c.accent)
                Spacer(Modifier.width(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = c.accent)
            }
        }
        Spacer(Modifier.size(10.dp))
        Row(Modifier.fillMaxWidth().padding(end = 8.dp)) {
            InfoPair("Lat", fmt(row.lat, 5), Modifier.weight(1f), mono = true)
            InfoPair("Lng", fmt(row.lng, 5), Modifier.weight(1f), mono = true)
            InfoPair("Speed", row.speed?.let { "%.1f".format(it * 3.6) } ?: "—", Modifier.weight(0.7f), mono = true)
            InfoPair("Batt", row.battery?.let { "${it.toInt()}%" } ?: "—", Modifier.weight(0.6f), mono = true)
        }
        if (row.device.isNotBlank()) {
            Spacer(Modifier.size(8.dp))
            Text(row.device, style = MaterialTheme.typography.labelMedium, color = c.textFaint)
        }
    }
}

/** One row of any non-location source, drawn from the columns the server describes. */
@Composable
private fun RawRow(row: Map<String, Any?>, columns: List<com.roamly.data.api.RawColumn>) {
    val c = Atlas.colors
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        columns.forEachIndexed { i, col ->
            val raw = row[col.key]
            if (raw == null || raw.toString().isBlank()) return@forEachIndexed
            val text = when (col.type) {
                "time" -> formatTimestamp(raw.toString())
                "coord" -> (raw as? Number)?.let { "%.5f".format(it.toDouble()) } ?: raw.toString()
                "speed" -> (raw as? Number)?.let { "%.1f %s".format(Units.speed(it.toDouble() * 3.6), Units.speedUnit) } ?: raw.toString()
                "num" -> (raw as? Number)?.let { n -> if (n.toDouble() % 1.0 == 0.0) "%,d".format(n.toLong()) else "%.2f".format(n.toDouble()) } ?: raw.toString()
                else -> raw.toString()
            }
            if (i > 0) Spacer(Modifier.size(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(col.label ?: col.key.orEmpty(), style = MaterialTheme.typography.labelMedium, color = c.textFaint, modifier = Modifier.width(96.dp))
                Text(text, style = if (col.type == "text") MaterialTheme.typography.bodySmall else AtlasType.mono, color = c.text, modifier = Modifier.weight(1f), maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun fmt(v: Double?, decimals: Int): String = v?.let { "%.${decimals}f".format(it) } ?: "—"

private val tsFormat = SimpleDateFormat("d MMM yyyy · HH:mm:ss", Locale.getDefault())
private fun formatTimestamp(iso: String): String = try {
    // ISO-8601 from the server; parse the leading yyyy-MM-ddTHH:mm:ss as UTC.
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val clean = iso.substringBefore('.').substringBefore('+').removeSuffix("Z")
    parser.parse(clean)?.let { tsFormat.format(it) } ?: iso
} catch (_: Exception) { iso }
