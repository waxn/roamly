package com.roamly.ui.trips

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.Comment
import com.roamly.data.api.CreateMilestoneRequest
import com.roamly.data.api.DayNote
import com.roamly.data.api.MediaItem
import com.roamly.data.api.PlannedStop
import com.roamly.data.api.PlannedStopRequest
import com.roamly.data.api.TimelineEvent
import com.roamly.data.api.TripResponse
import com.roamly.data.repository.Result
import com.roamly.data.repository.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

data class DayNoteEditorState(
    val note: DayNote,
    val title: String,
    val body: String,
    val placeId: Int?,
    val photos: List<MediaItem>,
    val saving: Boolean = false,
    val uploading: Boolean = false,
)

data class TripDetailUiState(
    val trip: TripResponse? = null,
    val events: List<TimelineEvent> = emptyList(),
    val comments: Map<Int, List<Comment>> = emptyMap(),  // blurbId -> comments
    val expandedBlurbId: Int? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val serverUrl: String = "",
    val showAddTypeDialog: Boolean = false,
    val showBlurbDialog: Boolean = false,
    val showMilestoneDialog: Boolean = false,
    val dayEditor: DayNoteEditorState? = null,
    // The planned stop being created (id 0) or edited; null when the editor is closed.
    val stopEditor: PlannedStop? = null,
    val showInviteSheet: Boolean = false,
    val inviteUrl: String? = null,
)

@HiltViewModel
class TripDetailViewModel @Inject constructor(
    private val repository: TripRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TripDetailUiState())
    val uiState: StateFlow<TripDetailUiState> = _uiState

    private var tripId: Int = -1

    fun load(id: Int) {
        tripId = id
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            if (_uiState.value.serverUrl.isBlank()) {
                _uiState.update { it.copy(serverUrl = repository.serverUrl()) }
            }
            when (val r = repository.getTrip(id)) {
                is Result.Success -> _uiState.update { it.copy(trip = r.data, isLoading = false) }
                is Result.Error -> _uiState.update { it.copy(error = r.message, isLoading = false) }
            }
            when (val r = repository.getTimeline(id)) {
                is Result.Success -> _uiState.update { it.copy(events = r.data.events) }
                is Result.Error -> {}
            }
        }
    }

    // --- FAB / Dialogs ---
    fun showAddTypeDialog() = _uiState.update { it.copy(showAddTypeDialog = true) }
    fun hideAddTypeDialog() = _uiState.update { it.copy(showAddTypeDialog = false) }
    fun showBlurbDialog() = _uiState.update { it.copy(showAddTypeDialog = false, showBlurbDialog = true) }
    fun hideBlurbDialog() = _uiState.update { it.copy(showBlurbDialog = false) }
    fun showMilestoneDialog() = _uiState.update { it.copy(showAddTypeDialog = false, showMilestoneDialog = true) }
    fun hideMilestoneDialog() = _uiState.update { it.copy(showMilestoneDialog = false) }

    // --- Blurbs ---
    fun createBlurb(text: String, title: String, rating: Int?, category: String?, uris: List<Uri>) {
        if (text.isBlank() && title.isBlank() && uris.isEmpty()) return
        viewModelScope.launch {
            val parts = buildPhotoParts(uris)
            when (repository.createBlurb(tripId, text, title, rating, category?.ifBlank { null }, null, parts)) {
                is Result.Success -> {
                    _uiState.update { it.copy(showBlurbDialog = false) }
                    reloadTimeline()  // refetch so photos/rating/category render
                }
                is Result.Error -> _uiState.update { it.copy(error = "Failed to post update") }
            }
        }
    }

    private suspend fun reloadTimeline() {
        when (val r = repository.getTimeline(tripId)) {
            is Result.Success -> _uiState.update { it.copy(events = r.data.events) }
            is Result.Error -> {}
        }
    }

    private fun buildPhotoParts(uris: List<Uri>): List<MultipartBody.Part> {
        val resolver = context.contentResolver
        return uris.mapIndexedNotNull { i, uri ->
            val bytes = runCatching { resolver.openInputStream(uri)?.use { s -> s.readBytes() } }.getOrNull()
                ?: return@mapIndexedNotNull null
            val type = resolver.getType(uri) ?: "image/jpeg"
            val body = bytes.toRequestBody(type.toMediaTypeOrNull())
            MultipartBody.Part.createFormData("photos", "media_$i.${mediaExt(type)}", body)
        }
    }

    fun deleteBlurb(blurbId: Int) {
        viewModelScope.launch {
            repository.deleteBlurb(tripId, blurbId)
            _uiState.update { it.copy(events = it.events.filter { e -> !(e.type == "blurb" && e.id == blurbId) }) }
        }
    }

    // --- Milestones ---
    fun createMilestone(emoji: String, title: String, description: String, date: String) {
        if (title.isBlank() || date.isBlank()) return
        viewModelScope.launch {
            val req = CreateMilestoneRequest(title, description, emoji.ifBlank { "🏁" }, date + "T00:00:00")
            when (repository.createMilestone(tripId, req)) {
                is Result.Success -> {
                    _uiState.update { it.copy(showMilestoneDialog = false) }
                    load(tripId)
                }
                is Result.Error -> _uiState.update { it.copy(error = "Failed to create milestone") }
            }
        }
    }

    fun togglePublic() {
        viewModelScope.launch { repository.togglePublic(tripId) }
    }

    // --- Day Log ---
    private fun upsertDayNote(note: DayNote) = _uiState.update { st ->
        val trip = st.trip ?: return@update st
        val others = trip.dayNotes.filterNot { it.id == note.id }
        val merged = (others + note).sortedWith(compareBy({ it.date }, { it.id }))
        st.copy(trip = trip.copy(dayNotes = merged))
    }

    fun startNewDayNote(date: String) {
        viewModelScope.launch {
            when (val r = repository.createDayNote(tripId, date)) {
                is Result.Success -> r.data.dayNote?.let { note ->
                    upsertDayNote(note)
                    _uiState.update { it.copy(dayEditor = DayNoteEditorState(note, note.title, note.body, note.placeId, note.photos)) }
                }
                is Result.Error -> _uiState.update { it.copy(error = r.message) }
            }
        }
    }

    fun openDayNote(note: DayNote) {
        _uiState.update { it.copy(dayEditor = DayNoteEditorState(note, note.title, note.body, note.placeId, note.photos)) }
    }

    fun editDayTitle(v: String) = _uiState.update { it.copy(dayEditor = it.dayEditor?.copy(title = v)) }
    fun editDayBody(v: String) = _uiState.update { it.copy(dayEditor = it.dayEditor?.copy(body = v)) }
    fun editDayPlace(placeId: Int?) = _uiState.update { it.copy(dayEditor = it.dayEditor?.copy(placeId = placeId)) }

    fun saveDayNote() {
        val ed = _uiState.value.dayEditor ?: return
        _uiState.update { it.copy(dayEditor = it.dayEditor?.copy(saving = true)) }
        viewModelScope.launch {
            when (val r = repository.saveDayNote(tripId, ed.note.id, ed.title, ed.body, ed.placeId)) {
                is Result.Success -> r.data.dayNote?.let { note ->
                    upsertDayNote(note)
                    _uiState.update { it.copy(dayEditor = it.dayEditor?.copy(saving = false, note = note, photos = note.photos)) }
                }
                is Result.Error -> _uiState.update { it.copy(error = r.message, dayEditor = it.dayEditor?.copy(saving = false)) }
            }
        }
    }

    fun addDayNotePhotos(uris: List<Uri>) {
        val ed = _uiState.value.dayEditor ?: return
        if (uris.isEmpty()) return
        _uiState.update { it.copy(dayEditor = it.dayEditor?.copy(uploading = true)) }
        viewModelScope.launch {
            val parts = buildPhotoParts(uris)
            if (parts.isNotEmpty()) {
                when (val r = repository.uploadDayNotePhotos(tripId, ed.note.id, parts)) {
                    is Result.Success -> r.data.dayNote?.let { note ->
                        upsertDayNote(note)
                        _uiState.update { it.copy(dayEditor = it.dayEditor?.copy(photos = note.photos)) }
                    }
                    is Result.Error -> _uiState.update { it.copy(error = r.message) }
                }
            }
            _uiState.update { it.copy(dayEditor = it.dayEditor?.copy(uploading = false)) }
        }
    }

    fun deleteDayNotePhoto(photoId: Int) {
        viewModelScope.launch {
            when (repository.deleteDayNotePhoto(tripId, photoId)) {
                is Result.Success -> _uiState.update {
                    it.copy(dayEditor = it.dayEditor?.let { ed -> ed.copy(photos = ed.photos.filterNot { p -> p.id == photoId }) })
                }
                is Result.Error -> {}
            }
        }
    }

    fun deleteDayNote() {
        val ed = _uiState.value.dayEditor ?: return
        viewModelScope.launch {
            repository.deleteDayNote(tripId, ed.note.id)
            _uiState.update { st ->
                val trip = st.trip
                st.copy(
                    dayEditor = null,
                    trip = trip?.copy(dayNotes = trip.dayNotes.filterNot { it.id == ed.note.id }),
                )
            }
        }
    }

    fun closeDayEditor() = _uiState.update { it.copy(dayEditor = null) }

    // --- Itinerary (planned stops) ---
    private fun upsertStop(stop: PlannedStop) = _uiState.update { st ->
        val trip = st.trip ?: return@update st
        val others = trip.plannedStops.filterNot { it.id == stop.id }
        val merged = (others + stop).sortedWith(compareBy({ it.order }, { it.id }))
        st.copy(trip = trip.copy(plannedStops = merged))
    }

    fun newStop() = _uiState.update { it.copy(stopEditor = PlannedStop(id = 0)) }
    fun editStop(stop: PlannedStop) = _uiState.update { it.copy(stopEditor = stop) }
    fun closeStopEditor() = _uiState.update { it.copy(stopEditor = null) }

    /** Create (id 0) or update a planned stop from the editor form. */
    fun saveStop(stop: PlannedStop) {
        if (stop.name.isBlank()) { _uiState.update { it.copy(error = "Stop name required") }; return }
        val req = PlannedStopRequest(
            name = stop.name, locationName = stop.locationName,
            latitude = stop.latitude, longitude = stop.longitude,
            arrivalDate = stop.arrivalDate?.ifBlank { null }, nights = stop.nights ?: 0,
            transport = stop.transport ?: "", notes = stop.notes ?: "",
            accommodation = stop.accommodation ?: "",
        )
        viewModelScope.launch {
            val r = if (stop.id == 0) repository.createPlannedStop(tripId, req)
                    else repository.updatePlannedStop(tripId, stop.id, req)
            when (r) {
                is Result.Success -> r.data.stop?.let { upsertStop(it); _uiState.update { s -> s.copy(stopEditor = null) } }
                is Result.Error -> _uiState.update { it.copy(error = r.message) }
            }
        }
    }

    fun deleteStop(stopId: Int) {
        viewModelScope.launch {
            repository.deletePlannedStop(tripId, stopId)
            _uiState.update { st ->
                val trip = st.trip
                st.copy(stopEditor = null, trip = trip?.copy(plannedStops = trip.plannedStops.filterNot { it.id == stopId }))
            }
        }
    }

    /** Swap this stop's `order` with its neighbour (up = earlier). */
    fun moveStop(stopId: Int, up: Boolean) {
        val trip = _uiState.value.trip ?: return
        val stops = trip.plannedStops.sortedWith(compareBy({ it.order }, { it.id }))
        val idx = stops.indexOfFirst { it.id == stopId }
        if (idx < 0) return
        val target = if (up) idx - 1 else idx + 1
        if (target < 0 || target >= stops.size) return
        val a = stops[idx]; val b = stops[target]
        // Optimistic local swap so the UI reorders immediately.
        upsertStop(a.copy(order = b.order))
        upsertStop(b.copy(order = a.order))
        viewModelScope.launch {
            repository.updatePlannedStop(tripId, a.id, PlannedStopRequest(order = b.order))
            repository.updatePlannedStop(tripId, b.id, PlannedStopRequest(order = a.order))
        }
    }

    // --- Invite / members ---
    fun openInvite() {
        _uiState.update { it.copy(showInviteSheet = true, inviteUrl = it.trip?.inviteUrl) }
        // Mint a token if the trip doesn't have one yet.
        if (_uiState.value.trip?.inviteUrl.isNullOrBlank()) generateInvite(rotate = false)
    }

    fun closeInvite() = _uiState.update { it.copy(showInviteSheet = false) }

    fun generateInvite(rotate: Boolean) {
        viewModelScope.launch {
            when (val r = repository.generateInviteLink(tripId, rotate)) {
                is Result.Success -> _uiState.update { st ->
                    st.copy(inviteUrl = r.data.inviteUrl, trip = st.trip?.copy(inviteUrl = r.data.inviteUrl, inviteToken = r.data.inviteToken))
                }
                is Result.Error -> _uiState.update { it.copy(error = r.message) }
            }
        }
    }

    fun removeMember(userId: Int) {
        viewModelScope.launch {
            when (repository.removeMember(tripId, userId)) {
                is Result.Success -> _uiState.update { st ->
                    val trip = st.trip
                    st.copy(trip = trip?.copy(members = trip.members?.filterNot { it.userId == userId }))
                }
                is Result.Error -> {}
            }
        }
    }

    private fun mediaExt(mime: String): String = when {
        mime.contains("png") -> "png"
        mime.startsWith("video/") -> mime.substringAfter('/').substringBefore(';').ifBlank { "mp4" }
        else -> "jpg"
    }

    // --- Comments ---
    fun toggleComments(blurbId: Int) {
        val current = _uiState.value.expandedBlurbId
        if (current == blurbId) {
            _uiState.update { it.copy(expandedBlurbId = null) }
        } else {
            _uiState.update { it.copy(expandedBlurbId = blurbId) }
            if (_uiState.value.comments[blurbId] == null) loadComments(blurbId)
        }
    }

    private fun loadComments(blurbId: Int) {
        viewModelScope.launch {
            when (val r = repository.getComments(tripId, blurbId)) {
                is Result.Success -> _uiState.update { state ->
                    state.copy(comments = state.comments + (blurbId to r.data.comments))
                }
                is Result.Error -> {}
            }
        }
    }

    fun createComment(blurbId: Int, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            when (val r = repository.createComment(tripId, blurbId, text)) {
                is Result.Success -> _uiState.update { state ->
                    val existing = state.comments[blurbId] ?: emptyList()
                    state.copy(comments = state.comments + (blurbId to (existing + r.data)))
                }
                is Result.Error -> _uiState.update { it.copy(error = r.message) }
            }
        }
    }

    fun deleteComment(blurbId: Int, commentId: Int) {
        viewModelScope.launch {
            repository.deleteComment(tripId, commentId)
            _uiState.update { state ->
                val updated = (state.comments[blurbId] ?: emptyList()).filter { it.id != commentId }
                state.copy(comments = state.comments + (blurbId to updated))
            }
        }
    }
}
