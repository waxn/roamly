package com.roamly.ui.journals

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.roamly.data.api.JournalListItem
import com.roamly.data.api.JournalTrack
import com.roamly.ui.theme.Clay
import com.roamly.ui.theme.ClayCard
import com.roamly.ui.theme.ClayIconBadge
import com.roamly.ui.theme.Coral
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val MOODS = listOf("😊", "😍", "😌", "😐", "😔", "😢", "😡", "😴", "🤩", "😎")
private val WEATHERS = listOf("☀️", "⛅", "☁️", "🌧️", "⛈️", "❄️", "🌫️", "🌈")

@Composable
fun JournalsScreen(
    viewModel: JournalsViewModel = hiltViewModel(),
    onOpenMap: ((String) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val clay = Clay.colors

    // Refresh data each time this screen enters composition (e.g. tab switch back)
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 0.dp, 16.dp, 110.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ClayIconBadge(Icons.Rounded.AutoStories, gradient = clay.tertiaryGradient, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Journal", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                }
            }

            state.stats?.let { stats ->
                item {
                    ClayCard {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            StreakBlock("${stats.currentStreak}", "day streak", highlight = stats.currentStreak > 0)
                            StreakBlock("${stats.longestStreak}", "longest")
                            StreakBlock("${stats.totalEntries}", "entries")
                            StreakBlock("${stats.thisMonth}", "this month")
                        }
                    }
                }
            }

            item { MonthCalendar(state, onPrev = viewModel::prevMonth, onNext = viewModel::nextMonth, onDay = viewModel::openEditor) }

            if (state.recent.isNotEmpty()) {
                item {
                    Text("Recent", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                }
                items(state.recent.take(30)) { entry ->
                    RecentRow(entry, cover = viewModel.mediaUrl(entry.cover)) {
                        runCatching { LocalDate.parse(entry.date) }.getOrNull()?.let(viewModel::openEditor)
                    }
                }
            } else if (!state.isLoading) {
                item {
                    Text(
                        "No entries yet — tap “Today” to write your first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = viewModel::openToday,
            text = { Text("Today") },
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
    }

    state.editor?.let { EntryEditor(it, viewModel, onOpenMap) }
}

@Composable
private fun StreakBlock(value: String, label: String, highlight: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (highlight) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LocalFireDepartment, null, tint = Coral, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(2.dp))
                Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
            }
        } else {
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MonthCalendar(
    state: JournalsUiState,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDay: (LocalDate) -> Unit,
) {
    val ym = state.displayMonth
    val today = LocalDate.now()
    ClayCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev) { Icon(Icons.Rounded.ChevronLeft, "previous month", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                "${ym.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${ym.year}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = onNext) { Icon(Icons.Rounded.ChevronRight, "next month", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { d ->
                Text(d, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))

        val daysInMonth = ym.lengthOfMonth()
        val leadBlanks = ym.atDay(1).dayOfWeek.value - 1 // Mon=0
        val cells = leadBlanks + daysInMonth
        val rows = (cells + 6) / 7
        for (r in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val cellIndex = r * 7 + c
                    val dayNum = cellIndex - leadBlanks + 1
                    Box(modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (dayNum in 1..daysInMonth) {
                            val date = ym.atDay(dayNum)
                            val entry = state.byDate[date.toString()]
                            DayCell(dayNum, entry, isToday = date == today, onClick = { onDay(date) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: Int, entry: JournalListItem?, isToday: Boolean, onClick: () -> Unit) {
    val clay = Clay.colors
    val hasEntry = entry != null
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (hasEntry) Modifier.background(MaterialTheme.colorScheme.tertiaryContainer)
                else if (isToday) Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (entry != null && entry.mood.isNotBlank()) {
                Text(entry.mood, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "$day",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hasEntry) Color.White else MaterialTheme.colorScheme.onBackground,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                )
            }
            if (entry != null && (entry.isFavorite || entry.photoCount > 0)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.isFavorite) Text("★", style = MaterialTheme.typography.labelSmall, color = Color.White)
                    if (entry.photoCount > 0) Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(Color.White))
                }
            }
        }
    }
}

@Composable
private fun RecentRow(entry: JournalListItem, cover: String?, onClick: () -> Unit) {
    ClayCard(contentPadding = 12.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(entry.mood.ifBlank { "📝" }, style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title.ifBlank { prettyDate(entry.date) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (entry.title.isBlank()) entry.snippet else "${prettyDate(entry.date)} · ${entry.snippet}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.isFavorite) Icon(Icons.Rounded.Favorite, null, tint = Coral, modifier = Modifier.size(18.dp))
        }
    }
}

// ── Editor ──────────────────────────────────────────────────────────────────

@Composable
private fun EntryEditor(editor: JournalEditorState, viewModel: JournalsViewModel, onOpenMap: ((String) -> Unit)? = null) {
    var confirmDelete by remember { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) viewModel.addPhotos(uris) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::closeEditor) { Icon(Icons.Rounded.Close, "close") }
                Column(Modifier.weight(1f)) {
                    Text(prettyDate(editor.date.toString()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        if (editor.saving) "saving…" else if (editor.exists) "saved" else "new entry",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = viewModel::onToggleFavorite) {
                    Icon(
                        if (editor.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        "favorite", tint = if (editor.isFavorite) Coral else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (editor.exists) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Rounded.Delete, "delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (editor.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }

            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Day track
                if (editor.track.pointCount > 0) TrackCard(
                    track = editor.track,
                    onExpand = if (onOpenMap != null) ({ onOpenMap(editor.date.toString()) }) else null,
                )

                OutlinedTextField(
                    value = editor.title,
                    onValueChange = viewModel::onTitleChange,
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Mood picker
                Text("Mood", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MOODS) { m -> EmojiChip(m, selected = editor.mood == m) { viewModel.onMoodChange(m) } }
                }

                // Weather picker
                Text("Weather", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(WEATHERS) { w -> EmojiChip(w, selected = editor.weather == w) { viewModel.onWeatherChange(if (editor.weather == w) "" else w) } }
                }

                OutlinedTextField(
                    value = editor.body,
                    onValueChange = viewModel::onBodyChange,
                    label = { Text("Write about your day…") },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    keyboardOptions = KeyboardOptions.Default,
                )

                // Photos
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Photos", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (editor.uploading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    TextButton(onClick = { photoPicker.launch("image/*") }) {
                        Icon(Icons.Rounded.Add, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Add")
                    }
                }
                if (editor.photos.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(editor.photos) { photo ->
                            Box {
                                AsyncImage(
                                    model = viewModel.mediaUrl(photo.thumbnail ?: photo.url),
                                    contentDescription = null,
                                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                                IconButton(
                                    onClick = { viewModel.deletePhoto(photo.id) },
                                    modifier = Modifier.align(Alignment.TopEnd).size(26.dp)
                                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                                ) { Icon(Icons.Rounded.Close, "remove", tint = Color.White, modifier = Modifier.size(16.dp)) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this entry?") },
            text = { Text("This permanently removes the journal entry and its photos for this day.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deleteEntry() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmojiChip(emoji: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, style = MaterialTheme.typography.titleLarge)
    }
}

/** Day track map — real osmdroid tiles + teal polyline, same as the adventure view. */
@Composable
private fun TrackCard(track: JournalTrack, onExpand: (() -> Unit)? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mapView = remember {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))
        Configuration.getInstance().userAgentValue = "Roamly/1.0"
        Configuration.getInstance().osmdroidBasePath = context.cacheDir
        Configuration.getInstance().osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid_tiles").apply { mkdirs() }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setDestroyMode(false)
            controller.setZoom(12.0)
        }
    }

    LaunchedEffect(track) {
        mapView.overlays.clear()
        if (track.points.size >= 2) {
            // Points are [lng, lat] pairs
            val geo = track.points.map { p -> GeoPoint(p[1], p[0]) }
            val line = Polyline(mapView).apply {
                setPoints(geo)
                outlinePaint.color = android.graphics.Color.rgb(45, 212, 191) // Teal
                outlinePaint.strokeWidth = 7f
                outlinePaint.isAntiAlias = true
            }
            mapView.overlays.add(line)
            mapView.post {
                runCatching {
                    mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(geo), false, 48)
                }
                mapView.invalidate()
            }
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    ClayCard(contentPadding = 0.dp, onClick = onExpand) {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp))) {
            AndroidView(factory = { (mapView.parent as? android.view.ViewGroup)?.removeView(mapView); mapView }, modifier = Modifier.fillMaxSize())

            // Stats overlay at the bottom (semi-transparent scrim)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TrackStat("${"%.1f".format(track.distanceKm)} km", "distance")
                    TrackStat("${track.pointCount}", "points")
                    if (track.cities.isNotEmpty()) TrackStat("${track.cities.size}", "places")
                    Spacer(Modifier.weight(1f))
                    if (onExpand != null) {
                        Text("open map ↗", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (track.cities.isNotEmpty()) {
                Row(
                    modifier = Modifier.align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Place, null, tint = Coral, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(track.cities.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun TrackStat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val prettyFmt = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
private fun prettyDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(prettyFmt) }.getOrDefault(iso)
