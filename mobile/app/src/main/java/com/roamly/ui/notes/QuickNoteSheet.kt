package com.roamly.ui.notes

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roamly.data.api.QuickNote
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasSheet
import com.roamly.ui.kit.ButtonKind
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.SectionTitle
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.kit.tappable
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The map's "what I did today" pill. It follows the map's range: a one-day
 * range shows that day's note (first line), a longer one up to a month says
 * how many daily notes it holds. MapScreen hides it for longer ranges.
 */
@Composable
fun QuickNotePill(state: QuickNotesUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Atlas.colors
    val shape = SquircleShape(50)
    Row(
        modifier = modifier
            .shadow(14.dp, shape, ambientColor = c.shadow, spotColor = c.shadow)
            .clip(shape)
            .background(c.glass)
            .border(1.dp, c.lineStrong, shape)
            .tappable(pressedScale = 0.95f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.EditNote, null, tint = c.accent, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        val day = state.day
        val note = state.dayNote.trim().substringBefore('\n')
        when {
            day != null && note.isNotEmpty() -> {
                Text("${dayLabel(day, state.today)} · ", style = MaterialTheme.typography.labelLarge, color = c.accent)
                Text(note, style = MaterialTheme.typography.labelLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            day != null -> Text("What did you do ${askDay(day, state.today)}?", style = MaterialTheme.typography.labelLarge, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else -> {
                val n = state.notes.size
                Text(
                    when (n) { 0 -> "No daily notes"; 1 -> "1 daily note"; else -> "$n daily notes" },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (n > 0) c.text else c.textMuted,
                )
            }
        }
    }
}

/**
 * Write today's note, and read the notes for the days the map shows.
 * [focusComposer] puts the cursor in the box at once — used when the evening
 * reminder opened the sheet, since writing is all that tap was for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickNoteSheet(
    state: QuickNotesUiState,
    focusComposer: Boolean,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onEdit: (LocalDate) -> Unit,
    onShowDay: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Atlas.colors
    val haptics = rememberHaptics()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val editingToday = state.editing == state.today

    LaunchedEffect(focusComposer) {
        if (focusComposer || state.saved.isBlank()) {
            // Let the sheet attach and start sliding up; focusing a field that is
            // not laid out yet throws, and the keyboard would race the animation.
            delay(250)
            runCatching { focus.requestFocus() }
        }
    }
    // A save landed: a confirming tap (or a refusal), once per save.
    var wasSaving by remember { mutableStateOf(false) }
    LaunchedEffect(state.saving) {
        if (wasSaving && !state.saving) { if (state.statusIsError) haptics.reject() else haptics.confirm() }
        wasSaving = state.saving
    }

    AtlasSheet(onDismiss = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (editingToday) "What did you do today?" else dayLabel(state.editing, state.today, long = true),
                        style = MaterialTheme.typography.headlineSmall, color = c.text,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (editingToday) "A line is plenty. It shows on the map and in your journal." else "Editing an earlier day",
                        style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                    )
                }
                if (state.editing != state.focus) {
                    AtlasButton(
                        if (state.focus == state.today) "Today" else dayLabel(state.focus, state.today),
                        { onEdit(state.focus) }, kind = ButtonKind.Ghost, compact = true,
                    )
                }
            }

            OutlinedTextField(
                value = state.draft,
                onValueChange = onDraftChange,
                placeholder = { Text("Coffee with Sam, long walk by the river…", color = c.textFaint) },
                minLines = 3,
                maxLines = 8,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = SquircleShape(SquircleRadius.panel),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.surfaceHigh, unfocusedContainerColor = c.surfaceHigh,
                    focusedBorderColor = c.accent, unfocusedBorderColor = Color.Transparent, cursorColor = c.accent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.status.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.statusIsError) c.danger else c.textMuted,
                    modifier = Modifier.weight(1f),
                )
                val removing = state.draft.isBlank() && state.saved.isNotBlank()
                AtlasButton(
                    if (removing) "Remove" else "Save",
                    onSave,
                    enabled = state.dirty,
                    loading = state.saving,
                    kind = if (removing) ButtonKind.Danger else ButtonKind.Primary,
                    compact = true,
                    feel = Feel.None,
                )
            }

            SectionTitle(rangeLabel(state.range, state.today), Modifier.padding(top = 8.dp))
            if (state.notes.isEmpty()) {
                Text(
                    if (state.range != null) "No notes for these days yet. Step the map's range back with ‹ to look further."
                    else "No notes yet.",
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                )
            }
            val single = state.range != null && state.range.first == state.range.second
            state.notes.take(60).forEach { n ->
                NoteCard(
                    note = n,
                    today = state.today,
                    isEditing = n.date == state.editing.toString(),
                    showDay = !single,
                    onEdit = { parseDay(n.date)?.let(onEdit); scope.launch { runCatching { focus.requestFocus() } } },
                    onShowDay = { parseDay(n.date)?.let(onShowDay) },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun NoteCard(
    note: QuickNote,
    today: LocalDate,
    isEditing: Boolean,
    showDay: Boolean,
    onEdit: () -> Unit,
    onShowDay: () -> Unit,
) {
    val c = Atlas.colors
    var expanded by rememberSaveable(note.date) { mutableStateOf(false) }
    val shape = SquircleShape(SquircleRadius.control)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (isEditing) c.accentSoft else c.surfaceHigh)
            .border(1.dp, if (isEditing) c.accent.copy(alpha = 0.5f) else Color.Transparent, shape)
            .animateContentSize()
            .tappable(feel = Feel.Tick, pressedScale = 0.99f) { expanded = !expanded }
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                parseDay(note.date)?.let { dayLabel(it, today, long = true) } ?: note.date,
                style = MaterialTheme.typography.labelMedium, color = c.textMuted, modifier = Modifier.weight(1f),
            )
            if (showDay) AtlasButton("Map", onShowDay, kind = ButtonKind.Ghost, compact = true)
            if (!isEditing) AtlasButton("Edit", onEdit, kind = ButtonKind.Ghost, compact = true)
        }
        Text(
            note.note,
            style = MaterialTheme.typography.bodyMedium, color = c.text,
            maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

private fun parseDay(s: String): LocalDate? = runCatching { LocalDate.parse(s) }.getOrNull()

/** "today", "yesterday" or "on Nov 20", for "What did you do …?". */
private fun askDay(d: LocalDate, today: LocalDate): String = when (d) {
    today -> "today"
    today.minusDays(1) -> "yesterday"
    else -> "on ${dayLabel(d, today)}"
}

internal fun dayLabel(d: LocalDate, today: LocalDate, long: Boolean = false): String = when (d) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> {
        val pattern = buildString {
            if (long) append("EEE, ")
            append("MMM d")
            if (d.year != today.year) append(", yyyy")
        }
        d.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }
}

private fun rangeLabel(range: Pair<LocalDate, LocalDate>?, today: LocalDate): String = when {
    range == null -> "All notes"
    range.first == range.second -> "Notes · ${dayLabel(range.first, today, long = true)}"
    else -> "Notes · ${dayLabel(range.first, today)} – ${dayLabel(range.second, today)}"
}
