package com.roamly.ui.journals

import com.roamly.ui.kit.RefreshBox
import com.roamly.ui.kit.Units
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.roamly.data.api.JournalListItem
import com.roamly.data.api.JournalTrack
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasDialog
import com.roamly.ui.kit.AtlasField
import com.roamly.ui.kit.AtlasSheet
import com.roamly.ui.kit.ButtonKind
import com.roamly.ui.kit.EmptyState
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PanelTone
import com.roamly.ui.kit.SectionTitle
import com.roamly.ui.kit.StatBlock
import com.roamly.ui.kit.TrackMap
import com.roamly.ui.kit.TrackSketch
import com.roamly.ui.kit.dockAwareBottom
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.kit.tappable
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.AtlasType
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape
import org.osmdroid.util.GeoPoint
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val MOODS = listOf("😊", "😍", "😌", "😐", "😔", "😢", "😡", "😴", "🤩", "😎")
private val WEATHERS = listOf("☀️", "⛅", "☁️", "🌧️", "⛈️", "❄️", "🌫️", "🌈")

/** The private journal: streaks, a month of days, recent entries; tap a day to write. */
@Composable
fun JournalsScreen(
    viewModel: JournalsViewModel = hiltViewModel(),
    onOpenMap: ((String) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val c = Atlas.colors

    Box(Modifier.fillMaxSize()) {
        RefreshBox(onRefresh = {viewModel.refresh()}, modifier = Modifier.fillMaxSize()) {
LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = dockAwareBottom(96.dp)),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.stats?.let { s ->
                item {
                    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.LocalFireDepartment, null, tint = if (s.currentStreak > 0) c.accent else c.textFaint, modifier = Modifier.size(30.dp))
                                Spacer(Modifier.width(6.dp))
                                StatBlock("${s.currentStreak}", "day streak", color = if (s.currentStreak > 0) c.accent else c.text)
                            }
                            StatBlock("${s.longestStreak}", "longest", small = true)
                            StatBlock("${s.totalEntries}", "entries", small = true)
                            StatBlock("${s.thisMonth}", "this month", small = true)
                        }
                    }
                }
            }
            item { MonthCalendar(state, onPrev = viewModel::prevMonth, onNext = viewModel::nextMonth, onDay = viewModel::openEditor) }
            if (state.recent.isNotEmpty()) {
                item { SectionTitle("Recent", Modifier.padding(top = 4.dp)) }
                items(state.recent.distinctBy { it.date }.take(30), key = { it.date }) { entry ->
                    EntryRow(entry, cover = viewModel.mediaUrl(entry.cover)) {
                        runCatching { LocalDate.parse(entry.date) }.getOrNull()?.let(viewModel::openEditor)
                    }
                }
            } else if (!state.isLoading) {
                item {
                    EmptyState(
                        Icons.Rounded.AutoStories, "Your journal is empty",
                        body = "Tap a day to write about it. Your route for that day shows up alongside.",
                        action = "Write today", onAction = viewModel::openToday,
                    )
                }
            }
        }
}
        // With no entries the empty state carries this action instead.
        if (state.recent.isNotEmpty()) {
            AtlasButton(
                "Write today",
                viewModel::openToday,
                Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = dockAwareBottom(16.dp)),
                icon = Icons.Rounded.EditNote,
            )
        }
    }

    state.editor?.let { EntryEditor(it, viewModel, onOpenMap) }
}

@Composable
private fun MonthCalendar(state: JournalsUiState, onPrev: () -> Unit, onNext: () -> Unit, onDay: (LocalDate) -> Unit) {
    val c = Atlas.colors
    val haptics = rememberHaptics()
    val ym = state.displayMonth
    val today = LocalDate.now()
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Text(ym.month.getDisplayName(TextStyle.FULL, Locale.getDefault()), style = MaterialTheme.typography.titleLarge, color = c.text)
                Text("${ym.year}", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            }
            IconAction(Icons.Rounded.ChevronLeft, "Previous month", { haptics.tick(); onPrev() }, size = 38.dp, feel = Feel.None)
            Spacer(Modifier.width(6.dp))
            IconAction(Icons.Rounded.ChevronRight, "Next month", { haptics.tick(); onNext() }, size = 38.dp, feel = Feel.None)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { d ->
                Text(d, style = MaterialTheme.typography.labelSmall, color = c.textFaint, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(6.dp))
        val days = ym.lengthOfMonth()
        val lead = ym.atDay(1).dayOfWeek.value - 1
        val rows = (lead + days + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val n = r * 7 + col - lead + 1
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (n in 1..days) {
                            val date = ym.atDay(n)
                            DayCell(n, state.byDate[date.toString()], date == today, date.isAfter(today)) { onDay(date) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: Int, entry: JournalListItem?, isToday: Boolean, future: Boolean, onClick: () -> Unit) {
    val c = Atlas.colors
    val bg by animateColorAsState(
        when {
            entry != null -> c.accent.copy(alpha = if (entry.isFavorite) 0.9f else 0.55f)
            isToday -> c.surfaceHighest
            else -> Color.Transparent
        }, label = "day",
    )
    val shape = SquircleShape(SquircleRadius.tag)
    Box(
        Modifier.fillMaxSize().clip(shape).background(bg)
            .then(if (isToday) Modifier.border(1.5.dp, c.accent, shape) else Modifier)
            .tappable(feel = Feel.Tick, pressedScale = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (entry != null && entry.mood.isNotBlank()) {
            Text(entry.mood, style = MaterialTheme.typography.bodyLarge)
        } else {
            Text(
                "$day",
                style = AtlasType.mono.copy(fontSize = MaterialTheme.typography.labelLarge.fontSize),
                fontWeight = if (isToday || entry != null) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    entry != null -> c.onAccent
                    future -> c.textFaint
                    else -> c.text
                },
            )
        }
        if (entry != null && entry.photoCount > 0) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(4.dp).clip(SquircleShape(50)).background(c.onAccent))
        }
    }
}

@Composable
private fun EntryRow(entry: JournalListItem, cover: String?, onClick: () -> Unit) {
    val c = Atlas.colors
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(12.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(SquircleShape(SquircleRadius.control)).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
                val day = remember(entry.track) { entry.track.orEmpty().filter { it.size >= 2 }.map { it[0] to it[1] } }
                if (cover != null) AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else if (day.size >= 2) TrackSketch(listOf(day to c.accent), Modifier.fillMaxSize(), background = c.surfaceHigh, pad = 8.dp, grid = false)
                else Text(entry.mood.ifBlank { "✏️" }, style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(prettyDate(entry.date), style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                Spacer(Modifier.height(2.dp))
                val snippet = entry.snippet.ifBlank { entry.quickNote.orEmpty().trim().substringBefore('\n') }
                Text(entry.title.ifBlank { snippet.ifBlank { "Untitled day" } }, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.title.isNotBlank() && snippet.isNotBlank()) {
                    Text(snippet, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (entry.isFavorite) Icon(Icons.Rounded.Favorite, null, tint = c.rose, modifier = Modifier.size(18.dp))
        }
    }
}

// ── Editor ──────────────────────────────────────────────────────────────────

/** A day's entry, in a full-height sheet. Every field autosaves. */
@Composable
private fun EntryEditor(editor: JournalEditorState, viewModel: JournalsViewModel, onOpenMap: ((String) -> Unit)?) {
    val c = Atlas.colors
    val haptics = rememberHaptics()
    var confirmDelete by remember { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) viewModel.addPhotos(uris)
    }

    AtlasSheet(onDismiss = viewModel::closeEditor) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(prettyDate(editor.date.toString()), style = MaterialTheme.typography.headlineSmall, color = c.text)
                Text(
                    when { editor.saving -> "Saving…"; editor.exists -> "Saved"; else -> "New entry" },
                    style = MaterialTheme.typography.labelMedium, color = if (editor.saving) c.accent else c.textMuted,
                )
            }
            IconAction(
                if (editor.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favourite",
                { haptics.toggle(!editor.isFavorite); viewModel.onToggleFavorite() },
                tint = if (editor.isFavorite) c.rose else c.textMuted, feel = Feel.None,
            )
            if (editor.exists) {
                Spacer(Modifier.width(6.dp))
                IconAction(Icons.Rounded.DeleteOutline, "Delete", { confirmDelete = true }, tint = c.danger)
            }
            Spacer(Modifier.width(6.dp))
            IconAction(Icons.Rounded.Close, "Close", viewModel::closeEditor)
        }
        if (editor.loading) {
            Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            return@AtlasSheet
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (editor.track.pointCount > 0) DayTrackCard(editor.track, onOpenMap?.let { open -> { open(editor.date.toString()) } })

            AtlasField(editor.title, viewModel::onTitleChange, placeholder = "Give the day a title",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            AtlasField(editor.quickNote, viewModel::onQuickNoteChange, label = "Quick note", placeholder = "What you did, in a line",
                singleLine = false, maxLines = 4, supportingText = "Shows on the map",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))

            EmojiPicker("Mood", MOODS, editor.mood) { viewModel.onMoodChange(it) }
            EmojiPicker("Weather", WEATHERS, editor.weather) { viewModel.onWeatherChange(if (editor.weather == it) "" else it) }

            OutlinedTextField(
                value = editor.body,
                onValueChange = viewModel::onBodyChange,
                placeholder = { Text("Write about your day…", color = c.textFaint) },
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = SquircleShape(SquircleRadius.panel),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.surfaceHigh, unfocusedContainerColor = c.surfaceHigh,
                    focusedBorderColor = c.accent, unfocusedBorderColor = Color.Transparent, cursorColor = c.accent,
                ),
                modifier = Modifier.fillMaxWidth().height(240.dp),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Photos", Modifier.weight(1f))
                if (editor.uploading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Box(
                        Modifier.size(96.dp).clip(SquircleShape(SquircleRadius.control)).background(c.surfaceHigh)
                            .border(1.dp, c.lineStrong, SquircleShape(SquircleRadius.control))
                            .tappable { photoPicker.launch("image/*") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.AddPhotoAlternate, null, tint = c.accent)
                            Text("Add", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                        }
                    }
                }
                items(editor.photos, key = { it.id }) { photo ->
                    Box {
                        AsyncImage(
                            viewModel.mediaUrl(photo.thumbnail ?: photo.url), null,
                            Modifier.size(96.dp).clip(SquircleShape(SquircleRadius.control)),
                            contentScale = ContentScale.Crop,
                        )
                        IconAction(
                            Icons.Rounded.Close, "Remove photo", { viewModel.deletePhoto(photo.id) },
                            Modifier.align(Alignment.TopEnd).padding(4.dp),
                            tint = Color.White, container = Color.Black.copy(alpha = 0.55f), size = 26.dp, iconSize = 15.dp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AtlasDialog(
            title = "Delete this entry?",
            onDismiss = { confirmDelete = false },
            confirmText = "Delete",
            onConfirm = { confirmDelete = false; viewModel.deleteEntry() },
            destructive = true,
            icon = Icons.Rounded.DeleteOutline,
            body = { Text("This permanently removes the journal entry and its photos for this day.") },
        )
    }
}

@Composable
private fun EmojiPicker(label: String, options: List<String>, selected: String, onPick: (String) -> Unit) {
    val c = Atlas.colors
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textMuted)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options) { e ->
                val on = e == selected
                val bg by animateColorAsState(if (on) c.accentSoft else c.surfaceHigh, label = "emoji")
                Box(
                    Modifier.size(46.dp).clip(SquircleShape(SquircleRadius.control)).background(bg)
                        .border(1.dp, if (on) c.accent else Color.Transparent, SquircleShape(SquircleRadius.control))
                        .tappable(feel = Feel.Select, pressedScale = 0.85f) { onPick(e) },
                    contentAlignment = Alignment.Center,
                ) { Text(e, style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}

@Composable
private fun DayTrackCard(track: JournalTrack, onExpand: (() -> Unit)?) {
    val c = Atlas.colors
    val points = remember(track) { track.points.mapNotNull { p -> if (p.size >= 2) GeoPoint(p[1], p[0]) else null } }
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp), onClick = onExpand) {
        Box(Modifier.fillMaxWidth().height(190.dp).clip(SquircleShape(topStart = SquircleRadius.panel, topEnd = SquircleRadius.panel))) {
            TrackMap(points, Modifier.fillMaxSize())
            if (onExpand != null) {
                Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                    IconAction(Icons.Rounded.OpenInFull, "Open on the map", onExpand, container = c.glass, size = 36.dp, iconSize = 16.dp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            StatBlock("%.1f".format(Units.dist(track.distanceKm)), "distance", unit = Units.distUnit, small = true)
            StatBlock("%,d".format(track.pointCount), "points", small = true)
            if (track.cities.isNotEmpty()) StatBlock("${track.cities.size}", "places", small = true)
        }
        if (track.cities.isNotEmpty()) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Place, null, tint = c.accent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(track.cities.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val prettyFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private fun prettyDate(iso: String): String = runCatching { LocalDate.parse(iso).format(prettyFmt) }.getOrDefault(iso)
