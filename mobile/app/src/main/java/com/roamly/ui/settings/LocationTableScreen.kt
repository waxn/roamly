package com.roamly.ui.settings

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.LocationTableRow
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Sort keys the server accepts, paired with a phone-friendly label.
private val SORT_OPTIONS = listOf(
    "timestamp" to "Time",
    "device" to "Device",
    "city" to "City",
    "state" to "State",
    "country_code" to "Country",
    "poi_name" to "Place",
    "speed" to "Speed",
    "battery" to "Battery",
    "lat" to "Latitude",
    "lng" to "Longitude",
)

private val RANGE_OPTIONS = listOf(
    "24" to "Last 24h",
    "72" to "Last 3 days",
    "168" to "Last week",
    "720" to "Last 30 days",
    "all" to "All time",
)

@Composable
fun LocationTableScreen(
    onBack: () -> Unit,
    viewModel: LocationTableViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var confirmDelete by remember { mutableStateOf<LocationTableRow?>(null) }

    // Trigger loadMore when scrolled near the end.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.rows.size - 8
        }
    }
    LaunchedEffect(nearEnd, state.rows.size, state.hasMore) {
        if (nearEnd && state.hasMore) viewModel.loadMore()
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Text("Location Data", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        }

        // Filter / sort toolbar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PickerChip(
                label = RANGE_OPTIONS.firstOrNull { it.first == state.range }?.second ?: "Range",
                options = RANGE_OPTIONS,
                onSelect = { viewModel.setRange(it) },
                modifier = Modifier.weight(1f),
            )
            val deviceOptions = listOf<Pair<String?, String>>(null to "All devices") +
                state.devices.map { (id, name) -> id as String? to name }
            PickerChip(
                label = state.deviceId?.let { state.devices[it] } ?: "All devices",
                options = deviceOptions.map { (it.first ?: "") to it.second },
                onSelect = { viewModel.setDevice(it.ifEmpty { null }) },
                modifier = Modifier.weight(1f),
            )
            SortChip(
                sortKey = state.sortKey,
                sortDir = state.sortDir,
                onSelect = { viewModel.setSort(it) },
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.isLoading && state.rows.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
                state.rows.isEmpty() ->
                    Text(
                        state.error ?: "No points in this range",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                else -> LazyColumn(
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 0.dp, 12.dp, 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.rows, key = { it.id }) { row ->
                        LocationRowCard(row, onDelete = { confirmDelete = row })
                    }
                    if (state.isLoadingMore) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete point") },
            text = { Text("Permanently delete this GPS point? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteRow(row.id); confirmDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PickerChip(
    label: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelect(value) })
            }
        }
    }
}

@Composable
private fun SortChip(
    sortKey: String,
    sortDir: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = SORT_OPTIONS.firstOrNull { it.first == sortKey }?.second ?: "Sort"
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.FilterList, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(4.dp))
            Icon(
                if (sortDir == "asc") Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SORT_OPTIONS.forEach { (key, text) ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text)
                            if (key == sortKey) {
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    if (sortDir == "asc") Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                                    null, Modifier.size(14.dp),
                                )
                            }
                        }
                    },
                    onClick = { expanded = false; onSelect(key) },
                )
            }
        }
    }
}

private val tsFormat = SimpleDateFormat("MMM d, yyyy · HH:mm:ss", Locale.getDefault())

@Composable
private fun LocationRowCard(row: LocationTableRow, onDelete: () -> Unit) {
    val place = row.customPlace?.takeIf { it.isNotBlank() }
        ?: row.poiName?.takeIf { it.isNotBlank() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.device.ifBlank { "—" }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (row.flag == "suspect") {
                Text("suspect", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.Delete, "Delete", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            formatTimestamp(row.timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(8.dp))

        val locality = listOfNotNull(
            row.city?.takeIf { it.isNotBlank() },
            row.state?.takeIf { it.isNotBlank() },
            row.countryCode?.takeIf { it.isNotBlank() },
        ).joinToString(", ")
        if (locality.isNotBlank()) {
            Text(locality, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        place?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Place, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MetaField("lat", fmt(row.lat, 5))
            MetaField("lng", fmt(row.lng, 5))
            row.speed?.let { MetaField("speed", "%.1f km/h".format(it * 3.6)) }
            row.battery?.let { MetaField("batt", "${it.toInt()}%") }
        }
    }
}

@Composable
private fun MetaField(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace)
    }
}

private fun fmt(v: Double?, decimals: Int): String = v?.let { "%.${decimals}f".format(it) } ?: "—"

private fun formatTimestamp(iso: String): String = try {
    // Server sends ISO-8601; parse the leading yyyy-MM-ddTHH:mm:ss portion.
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val clean = iso.substringBefore('.').substringBefore('+').removeSuffix("Z")
    val d = parser.parse(clean)
    if (d != null) tsFormat.format(d) else iso
} catch (e: Exception) {
    iso
}
