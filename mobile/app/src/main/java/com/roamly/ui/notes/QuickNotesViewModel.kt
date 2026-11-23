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
import javax.inject.Inject

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
    val notes: List<QuickNote> = emptyList(),
) {
    val dirty: Boolean get() = draft.trim() != saved
    /** Other days in the range with a note: the pill's "+n". */
    val otherCount: Int get() = notes.count { it.date != today.toString() }
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

    /** Called by the map whenever its time range changes. */
    fun setRange(range: Pair<LocalDate, LocalDate>?) {
        if (rangeSet && range == _state.value.range) return
        rangeSet = true
        _state.update { it.copy(range = range) }
        rangeJob?.cancel()
        rangeJob = viewModelScope.launch {
            val r = repo.quickNotes(range?.first?.toString(), range?.second?.toString())
            if (r is Result.Success) {
                r.data.notes.forEach { known[it.date] = it.note }
                _state.update { it.copy(notes = r.data.notes) }
            }
        }
        refreshToday()
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
        val note = known[date.toString()].orEmpty()
        _state.update { it.copy(editing = date, draft = note, saved = note, status = null, statusIsError = false) }
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
