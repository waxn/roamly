package com.roamly.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.Comment
import com.roamly.data.api.CreateMilestoneRequest
import com.roamly.data.api.TimelineEvent
import com.roamly.data.api.TripResponse
import com.roamly.data.repository.Result
import com.roamly.data.repository.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TripDetailUiState(
    val trip: TripResponse? = null,
    val events: List<TimelineEvent> = emptyList(),
    val comments: Map<Int, List<Comment>> = emptyMap(),  // blurbId -> comments
    val expandedBlurbId: Int? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val showAddTypeDialog: Boolean = false,
    val showBlurbDialog: Boolean = false,
    val showMilestoneDialog: Boolean = false,
)

@HiltViewModel
class TripDetailViewModel @Inject constructor(
    private val repository: TripRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TripDetailUiState())
    val uiState: StateFlow<TripDetailUiState> = _uiState

    private var tripId: Int = -1

    fun load(id: Int) {
        tripId = id
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
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
    fun createBlurb(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            when (val r = repository.createBlurb(tripId, text)) {
                is Result.Success -> _uiState.update { state ->
                    state.copy(events = listOf(r.data) + state.events, showBlurbDialog = false)
                }
                is Result.Error -> _uiState.update { it.copy(error = r.message) }
            }
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
