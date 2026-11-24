package com.roamly.ui.notes

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roamly.data.api.QuickNote
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
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.EditNote, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            val day = state.day
            val note = state.dayNote.trim().substringBefore('\n')
            when {
                day != null && note.isNotEmpty() -> {
                    Text("${dayLabel(day, state.today)} · ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(
                        note,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                day != null -> Text(
                    "What did you do ${askDay(day, state.today)}?",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    val n = state.notes.size
                    Text(
                        when (n) { 0 -> "No daily notes"; 1 -> "1 daily note"; else -> "$n daily notes" },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (n > 0) FontWeight.SemiBold else null,
                        color = if (n > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (editingToday) "What did you do today?" else dayLabel(state.editing, state.today, long = true),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (editingToday) "A line is plenty. It shows on the map and in Journals."
                        else "Editing an earlier day",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.editing != state.focus) TextButton(onClick = { onEdit(state.focus) }) {
                    Text(if (state.focus == state.today) "Back to today" else "Back to ${dayLabel(state.focus, state.today)}")
                }
            }

            OutlinedTextField(
                value = state.draft,
                onValueChange = onDraftChange,
                placeholder = { Text("Coffee with Sam, long walk by the river…") },
                minLines = 2,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.status.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                val removing = state.draft.isBlank() && state.saved.isNotBlank()
                Button(onClick = onSave, enabled = state.dirty && !state.saving) {
                    Text(if (state.saving) "Saving…" else if (removing) "Remove" else "Save")
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Text(
                rangeLabel(state.range, state.today),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.notes.isEmpty()) {
                Text(
                    if (state.range != null) "No notes for these days yet. Step the time range back with ‹ to look further."
                    else "No notes yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val single = state.range != null && state.range.first == state.range.second
            state.notes.take(60).forEach { n ->
                NoteRow(
                    note = n,
                    today = state.today,
                    isEditing = n.date == state.editing.toString(),
                    showDay = !single,
                    onEdit = { parseDay(n.date)?.let(onEdit); scope.launch { runCatching { focus.requestFocus() } } },
                    onShowDay = { parseDay(n.date)?.let(onShowDay) },
                )
            }
        }
    }
}

@Composable
private fun NoteRow(
    note: QuickNote,
    today: LocalDate,
    isEditing: Boolean,
    showDay: Boolean,
    onEdit: () -> Unit,
    onShowDay: () -> Unit,
) {
    var expanded by rememberSaveable(note.date) { mutableStateOf(false) }
    Surface(
        color = if (isEditing) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (isEditing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    parseDay(note.date)?.let { dayLabel(it, today, long = true) } ?: note.date,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (showDay) TextButton(onClick = onShowDay) { Text("Show day") }
                if (!isEditing) TextButton(onClick = onEdit) { Text("Edit") }
            }
            Text(
                note.note,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
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
