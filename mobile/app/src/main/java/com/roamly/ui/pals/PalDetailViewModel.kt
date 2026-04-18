package com.roamly.ui.pals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.Comment
import com.roamly.data.api.CreateMilestoneRequest
import com.roamly.data.api.PalMember
import com.roamly.data.api.PalResponse
import com.roamly.data.api.TimelineEvent
import com.roamly.data.repository.PalRepository
import com.roamly.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PalTab { TIMELINE, MEMBERS, SETTINGS }

data class PalDetailUiState(
    val pal: PalResponse? = null,
    val events: List<TimelineEvent> = emptyList(),
    val members: List<PalMember> = emptyList(),
    val comments: Map<Int, List<Comment>> = emptyMap(),   // blurbId -> comments
    val expandedBlurbId: Int? = null,
    val selectedTab: PalTab = PalTab.TIMELINE,
    val isLoading: Boolean = false,
    val isMembersLoading: Boolean = false,
    val error: String? = null,
    // Dialog visibility
    val showAddTypeDialog: Boolean = false,
    val showBlurbDialog: Boolean = false,
    val showMilestoneDialog: Boolean = false,
    val showAddMemberDialog: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    // Settings edit state
    val editName: String = "",
    val editDescription: String = "",
)

@HiltViewModel
class PalDetailViewModel @Inject constructor(
    private val repository: PalRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PalDetailUiState())
    val uiState: StateFlow<PalDetailUiState> = _uiState

    private var palId: Int = -1

    fun load(id: Int) {
        palId = id
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.getPal(id)) {
                is Result.Success -> {
                    val pal = r.data
                    _uiState.update { it.copy(
                        pal = pal,
                        isLoading = false,
                        editName = pal.name,
                        editDescription = pal.description ?: "",
                        members = pal.members ?: emptyList(),
                    ) }
                }
                is Result.Error -> _uiState.update { it.copy(error = r.message, isLoading = false) }
            }
            when (val r = repository.getTimeline(id)) {
                is Result.Success -> _uiState.update { it.copy(events = r.data.events) }
                is Result.Error -> {}
            }
        }
    }

    fun selectTab(tab: PalTab) = _uiState.update { it.copy(selectedTab = tab) }

    // --- FAB / Dialogs ---
    fun showAddTypeDialog() = _uiState.update { it.copy(showAddTypeDialog = true) }
    fun hideAddTypeDialog() = _uiState.update { it.copy(showAddTypeDialog = false) }
    fun showBlurbDialog() = _uiState.update { it.copy(showAddTypeDialog = false, showBlurbDialog = true) }
    fun hideBlurbDialog() = _uiState.update { it.copy(showBlurbDialog = false) }
    fun showMilestoneDialog() = _uiState.update { it.copy(showAddTypeDialog = false, showMilestoneDialog = true) }
    fun hideMilestoneDialog() = _uiState.update { it.copy(showMilestoneDialog = false) }
    fun showAddMemberDialog() = _uiState.update { it.copy(showAddMemberDialog = true) }
    fun hideAddMemberDialog() = _uiState.update { it.copy(showAddMemberDialog = false) }
    fun showDeleteConfirm() = _uiState.update { it.copy(showDeleteConfirm = true) }
    fun hideDeleteConfirm() = _uiState.update { it.copy(showDeleteConfirm = false) }

    // --- Settings edits ---
    fun onEditNameChange(v: String) = _uiState.update { it.copy(editName = v) }
    fun onEditDescriptionChange(v: String) = _uiState.update { it.copy(editDescription = v) }

    fun saveSettings() {
        val state = _uiState.value
        viewModelScope.launch {
            when (repository.updatePal(palId, state.editName, state.editDescription)) {
                is Result.Success -> _uiState.update { it.copy(
                    pal = it.pal?.copy(name = state.editName, description = state.editDescription)
                ) }
                is Result.Error -> _uiState.update { it.copy(error = "Failed to save") }
            }
        }
    }

    fun togglePublic() {
        viewModelScope.launch {
            repository.togglePublic(palId)
            _uiState.update { it.copy(pal = it.pal?.copy(isPublic = !(it.pal.isPublic))) }
        }
    }

    fun deletePal(onDeleted: () -> Unit) {
        viewModelScope.launch {
            when (repository.deletePal(palId)) {
                is Result.Success -> onDeleted()
                is Result.Error -> _uiState.update { it.copy(error = "Failed to delete") }
            }
        }
    }

    // --- Blurbs ---
    fun createBlurb(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            when (val r = repository.createBlurb(palId, text)) {
                is Result.Success -> _uiState.update { state ->
                    state.copy(events = listOf(r.data) + state.events, showBlurbDialog = false)
                }
                is Result.Error -> _uiState.update { it.copy(error = r.message) }
            }
        }
    }

    fun deleteBlurb(blurbId: Int) {
        viewModelScope.launch {
            repository.deleteBlurb(palId, blurbId)
            _uiState.update { it.copy(events = it.events.filter { e -> !(e.type == "blurb" && e.id == blurbId) }) }
        }
    }

    // --- Milestones ---
    fun createMilestone(emoji: String, title: String, description: String, date: String) {
        if (title.isBlank() || date.isBlank()) return
        viewModelScope.launch {
            val req = CreateMilestoneRequest(title, description, emoji.ifBlank { "🏁" }, date + "T00:00:00")
            when (repository.createMilestone(palId, req)) {
                is Result.Success -> {
                    _uiState.update { it.copy(showMilestoneDialog = false) }
                    load(palId) // reload timeline to show new milestone
                }
                is Result.Error -> _uiState.update { it.copy(error = "Failed to create milestone") }
            }
        }
    }

    fun deleteMilestone(milestoneId: Int) {
        viewModelScope.launch {
            // No repository method yet — refresh after
            _uiState.update { it.copy(events = it.events.filter { e -> !(e.type == "milestone" && e.id == milestoneId) }) }
        }
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
            when (val r = repository.getComments(palId, blurbId)) {
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
            when (val r = repository.createComment(palId, blurbId, text)) {
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
            repository.deleteComment(palId, commentId)
            _uiState.update { state ->
                val updated = (state.comments[blurbId] ?: emptyList()).filter { it.id != commentId }
                state.copy(comments = state.comments + (blurbId to updated))
            }
        }
    }

    // --- Members ---
    fun addMember(username: String) {
        if (username.isBlank()) return
        viewModelScope.launch {
            when (val r = repository.addMember(palId, username)) {
                is Result.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            members = state.members + r.data,
                            showAddMemberDialog = false,
                        )
                    }
                }
                is Result.Error -> _uiState.update { it.copy(error = r.message) }
            }
        }
    }

    fun removeMember(userId: Int) {
        viewModelScope.launch {
            repository.removeMember(palId, userId)
            _uiState.update { it.copy(members = it.members.filter { m -> m.userId != userId }) }
        }
    }
}
