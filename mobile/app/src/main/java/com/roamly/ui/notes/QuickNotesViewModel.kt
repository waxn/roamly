package com.roamly.ui.notes

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.QuickNote
import com.roamly.data.prefs.UserPreferences
import com.roamly.data.repository.JournalRepository
import com.roamly.data.repository.Result
import com.roamly.notes.QuickNoteReminder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** Ranges longer than this hide the map's notes pill. */
internal const val NOTES_MAX_DAYS = 31L

data class QuickNotesUiState(
    val today: LocalDate = LocalDate.now(),
    /** Today's saved note, for the map pill. */
    val todayNote: String = "",
    /** The day the composer writes to. */
    val editing: LocalDate = LocalDate.now(),
    val draft: String = "",
    /** What the server holds for [editing], to tell an edit from a no-op. */
    val saved: String = "",
    val saving: Boolean = false,
    val status: String? = null,
    val statusIsError: Boolean = false,
    /** The map's range as calendar days; null = all time. */
    val range: Pair<LocalDate, LocalDate>? = null,
    /** Set when the range is about one day (6h, 24h, or a stepped-to day): that day. */
    val day: LocalDate? = null,
    val notes: List<QuickNote> = emptyList(),
) {
    val dirty: Boolean get() = draft.trim() != saved
    /** No pill for all time or anything longer than a month. */
    val hidden: Boolean get() = range == null ||
        ChronoUnit.DAYS.between(range.first, range.second) + 1 > NOTES_MAX_DAYS
    /** The day the composer opens on: the range's day, else today when the range
     *  reaches it, else the range's last day. */
    val focus: LocalDate get() = day ?: range?.let { (start, end) ->
        if (!today.isBefore(start) && !today.isAfter(end)) today else end
    } ?: today
    /** The note for [day], for the pill. */
    val dayNote: String get() = day?.let { d ->
        if (d == today) todayNote else notes.firstOrNull { it.date == d.toString() }?.note
    }.orEmpty()
}

/**
 * "What I did today" on the map. Activity-scoped (see RoamlyNavHost) and
 * lazy: nothing loads until the map hands it a range, so it never calls the
 * server before sign-in. Only ever reads and writes the note itself; the
 * journal's own text is not part of these endpoints.
 */
@HiltViewModel
class QuickNotesViewModel @Inject constructor(
    private val repo: JournalRepository,
    private val prefs: UserPreferences,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(QuickNotesUiState())
    val uiState: StateFlow<QuickNotesUiState> = _state

    private val known = HashMap<String, String>()
    private var rangeJob: Job? = null
    private var rangeSet = false

    /**
     * Called by the map whenever its time range changes. [day] is the one day
     * the range is about, when it is about one. Unsaved typing stays on its
     * day; otherwise the composer follows the range.
     */
    fun setRange(range: Pair<LocalDate, LocalDate>?, day: LocalDate? = null) {
        val cur = _state.value
        if (rangeSet && range == cur.range && day == cur.day) return
        rangeSet = true
        val inRange = { d: String -> range != null && d >= range.first.toString() && d <= range.second.toString() }
        _state.update { s ->
            val next = s.copy(range = range, day = day, notes = s.notes.filter { inRange(it.date) })
            if (s.dirty) next else next.withEditing(next.focus)
        }
        rangeJob?.cancel()
        rangeJob = viewModelScope.launch {
            val r = repo.quickNotes(range?.first?.toString(), range?.second?.toString())
            if (r is Result.Success) {
                // The answer covers every day in the range, so a day missing from it has no note.
                if (range != null) known.keys.removeAll { inRange(it) }
                r.data.notes.forEach { known[it.date] = it.note }
                _state.update { s ->
                    val next = s.copy(notes = r.data.notes)
                    if (s.dirty || s.editing != s.focus) next else next.withEditing(s.editing)
                }
            }
        }
        refreshToday()
    }

    private fun QuickNotesUiState.withEditing(date: LocalDate): QuickNotesUiState {
        val note = known[date.toString()].orEmpty()
        return copy(editing = date, draft = note, saved = note, status = null, statusIsError = false)
    }

    /** Re-read today's note; also rolls "today" over after midnight. */
    fun refreshToday() {
        val now = LocalDate.now()
        if (now != _state.value.today) {
            _state.update { s ->
                val follow = s.editing == s.today && !s.dirty
                s.copy(today = now, editing = if (follow) now else s.editing)
            }
        }
        viewModelScope.launch {
            val day = _state.value.today
            val r = repo.quickNote(day.toString())
            if (r is Result.Success) {
                val note = r.data.note
                if (note.isBlank()) known.remove(day.toString()) else known[day.toString()] = note
                _state.update { s ->
                    val editingToday = s.editing == day
                    s.copy(
                        todayNote = note,
                        saved = if (editingToday) note else s.saved,
                        // Fill the composer only if nothing has been typed into it.
                        draft = if (editingToday && !s.dirty) note else s.draft,
                    )
                }
            }
        }
    }

    fun edit(date: LocalDate) {
        _state.update { it.withEditing(date) }
    }

    fun onDraftChange(v: String) {
        _state.update { it.copy(draft = v.take(2000), status = null, statusIsError = false) }
    }

    fun save() {
        val s = _state.value
        if (s.saving || !s.dirty) return
        val day = s.editing
        _state.update { it.copy(saving = true, status = null) }
        viewModelScope.launch {
            when (val r = repo.saveQuickNote(day.toString(), s.draft)) {
                is Result.Success -> {
                    val note = r.data.note
                    val key = day.toString()
                    if (note.isBlank()) known.remove(key) else known[key] = note
                    _state.update { st ->
                        val inRange = st.range == null || (!day.isBefore(st.range.first) && !day.isAfter(st.range.second))
                        val others = st.notes.filter { it.date != key }
                        val notes = if (note.isNotBlank() && inRange) (others + QuickNote(key, note)).sortedByDescending { it.date } else others
                        st.copy(
                            saving = false,
                            saved = note,
                            draft = if (st.editing == day) note else st.draft,
                            todayNote = if (day == st.today) note else st.todayNote,
                            notes = notes,
                            status = if (note.isBlank()) "Removed" else "Saved",
                            statusIsError = false,
                        )
                    }
                    if (day == _state.value.today && note.isNotBlank()) QuickNoteReminder.dismiss(context)
                }
                is Result.Error -> _state.update {
                    it.copy(saving = false, status = "Couldn't save. Check your connection and try again.", statusIsError = true)
                }
            }
        }
    }

    /** Arm the evening reminder once signed in (RoamlyApp's start-up arm ran before login). */
    fun ensureReminderScheduled() {
        viewModelScope.launch { QuickNoteReminder.scheduleFromPrefs(context, prefs) }
    }
}
