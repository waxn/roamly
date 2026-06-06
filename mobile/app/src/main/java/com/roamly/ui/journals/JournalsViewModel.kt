package com.roamly.ui.journals

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.JournalListItem
import com.roamly.data.api.JournalListResponse
import com.roamly.data.api.JournalPhoto
import com.roamly.data.api.JournalSaveRequest
import com.roamly.data.api.JournalStatsResponse
import com.roamly.data.api.JournalTrack
import com.roamly.data.cache.DiskCache
import com.roamly.data.prefs.UserPreferences
import com.roamly.data.repository.JournalRepository
import com.roamly.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

private const val AUTOSAVE_DEBOUNCE_MS = 700L

data class JournalEditorState(
    val date: LocalDate,
    val loading: Boolean = true,
    val exists: Boolean = false,
    val title: String = "",
    val body: String = "",
    val mood: String = "",
    val weather: String = "",
    val isFavorite: Boolean = false,
    val photos: List<JournalPhoto> = emptyList(),
    val track: JournalTrack = JournalTrack(),
    val saving: Boolean = false,
    val uploading: Boolean = false,
)

data class JournalsUiState(
    val stats: JournalStatsResponse? = null,
    /** date string (yyyy-MM-dd) -> calendar entry */
    val byDate: Map<String, JournalListItem> = emptyMap(),
    val recent: List<JournalListItem> = emptyList(),
    val displayMonth: YearMonth = YearMonth.now(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val editor: JournalEditorState? = null,
    val serverUrl: String = "",
)

@HiltViewModel
class JournalsViewModel @Inject constructor(
    private val repo: JournalRepository,
    private val prefs: UserPreferences,
    private val disk: DiskCache,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(JournalsUiState())
    val uiState: StateFlow<JournalsUiState> = _state

    private var autosaveJob: Job? = null

    init {
        // Paint last-known data instantly, then refresh.
        val cachedStats = disk.get<JournalStatsResponse>(DiskCache.JOURNAL_STATS, JournalStatsResponse::class.java)
        val cachedList = disk.get<JournalListResponse>(DiskCache.JOURNAL_LIST, JournalListResponse::class.java)
        if (cachedStats != null || cachedList != null) {
            applyList(cachedList?.entries ?: emptyList())
            _state.update { it.copy(stats = cachedStats ?: it.stats) }
        }
        viewModelScope.launch { _state.update { it.copy(serverUrl = prefs.serverUrl.first() ?: "") } }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            if (_state.value.recent.isEmpty()) _state.update { it.copy(isLoading = true) }
            when (val r = repo.list()) {
                is Result.Success -> {
                    disk.put(DiskCache.JOURNAL_LIST, r.data)
                    applyList(r.data.entries)
                    _state.update { it.copy(isLoading = false, error = null) }
                }
                is Result.Error -> _state.update { it.copy(isLoading = false, error = if (it.recent.isEmpty()) r.message else null) }
            }
            when (val r = repo.stats()) {
                is Result.Success -> {
                    disk.put(DiskCache.JOURNAL_STATS, r.data)
                    _state.update { it.copy(stats = r.data) }
                }
                is Result.Error -> { /* optional */ }
            }
        }
    }

    private fun applyList(entries: List<JournalListItem>) {
        val byDate = entries.associateBy { it.date }
        _state.update { it.copy(byDate = byDate, recent = entries.sortedByDescending { e -> e.date }) }
    }

    fun prevMonth() = _state.update { it.copy(displayMonth = it.displayMonth.minusMonths(1)) }
    fun nextMonth() = _state.update { it.copy(displayMonth = it.displayMonth.plusMonths(1)) }

    // ── Editor ────────────────────────────────────────────────────────────────

    fun openEditor(date: LocalDate) {
        _state.update { it.copy(editor = JournalEditorState(date = date, loading = true)) }
        viewModelScope.launch {
            when (val r = repo.detail(date.toString())) {
                is Result.Success -> {
                    val e = r.data.entry
                    _state.update {
                        it.copy(editor = JournalEditorState(
                            date = date, loading = false, exists = e.exists,
                            title = e.title, body = e.body, mood = e.mood, weather = e.weather,
                            isFavorite = e.isFavorite, photos = e.photos, track = r.data.track,
                        ))
                    }
                }
                is Result.Error -> _state.update {
                    it.copy(editor = it.editor?.copy(loading = false))
                }
            }
        }
    }

    fun openToday() = openEditor(LocalDate.now())

    private fun editEditor(transform: (JournalEditorState) -> JournalEditorState) {
        _state.update { it.copy(editor = it.editor?.let(transform)) }
    }

    fun onTitleChange(v: String) { editEditor { it.copy(title = v) }; scheduleSave() }
    fun onBodyChange(v: String) { editEditor { it.copy(body = v) }; scheduleSave() }
    fun onWeatherChange(v: String) { editEditor { it.copy(weather = v) }; scheduleSave() }
    fun onMoodChange(v: String) { editEditor { it.copy(mood = if (it.mood == v) "" else v) }; scheduleSave() }
    fun onToggleFavorite() { editEditor { it.copy(isFavorite = !it.isFavorite) }; scheduleSave() }

    private fun scheduleSave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            saveNow()
        }
    }

    private suspend fun saveNow() {
        val ed = _state.value.editor ?: return
        editEditor { it.copy(saving = true) }
        val req = JournalSaveRequest(
            title = ed.title, body = ed.body, mood = ed.mood,
            weather = ed.weather, isFavorite = ed.isFavorite,
        )
        repo.save(ed.date.toString(), req)
        editEditor { it.copy(saving = false, exists = true) }
    }

    fun addPhotos(uris: List<Uri>) {
        val ed = _state.value.editor ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            editEditor { it.copy(uploading = true) }
            val resolver = context.contentResolver
            val parts = uris.mapIndexedNotNull { i, uri ->
                val bytes = runCatching { resolver.openInputStream(uri)?.use { s -> s.readBytes() } }.getOrNull()
                    ?: return@mapIndexedNotNull null
                val type = resolver.getType(uri) ?: "image/jpeg"
                val ext = if (type.contains("png")) "png" else "jpg"
                val body = bytes.toRequestBody(type.toMediaTypeOrNull())
                MultipartBody.Part.createFormData("photos", "photo_$i.$ext", body)
            }
            if (parts.isNotEmpty()) {
                when (val r = repo.uploadPhotos(ed.date.toString(), parts)) {
                    is Result.Success -> editEditor { it.copy(photos = it.photos + r.data.photos, exists = true) }
                    is Result.Error -> { /* surfaced via uploading flag clearing */ }
                }
            }
            editEditor { it.copy(uploading = false) }
        }
    }

    fun deletePhoto(id: Int) {
        viewModelScope.launch {
            when (repo.deletePhoto(id)) {
                is Result.Success -> editEditor { it.copy(photos = it.photos.filterNot { p -> p.id == id }) }
                is Result.Error -> { /* ignore */ }
            }
        }
    }

    fun deleteEntry() {
        val ed = _state.value.editor ?: return
        autosaveJob?.cancel()
        viewModelScope.launch {
            repo.delete(ed.date.toString())
            _state.update { it.copy(editor = null) }
            refresh()
        }
    }

    fun closeEditor() {
        autosaveJob?.cancel()
        viewModelScope.launch {
            // Flush any pending edits so nothing is lost on close, then refresh
            // the calendar/list so the new mood/favorite/photo dot shows up.
            saveNow()
            _state.update { it.copy(editor = null) }
            refresh()
        }
    }

    /** Build an absolute URL for a (possibly relative) media path. */
    fun mediaUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) return path
        return _state.value.serverUrl.trimEnd('/') + path
    }
}
