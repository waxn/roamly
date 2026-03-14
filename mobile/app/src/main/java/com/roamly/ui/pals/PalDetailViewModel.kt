package com.roamly.ui.pals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

data class PalDetailUiState(
    val pal: PalResponse? = null,
    val events: List<TimelineEvent> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showBlurbDialog: Boolean = false
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
                is Result.Success -> _uiState.update { it.copy(pal = r.data, isLoading = false) }
                is Result.Error -> _uiState.update { it.copy(error = r.message, isLoading = false) }
            }
            when (val r = repository.getTimeline(id)) {
                is Result.Success -> _uiState.update { it.copy(events = r.data.events) }
                is Result.Error -> {}
            }
        }
    }

    fun showBlurbDialog() = _uiState.update { it.copy(showBlurbDialog = true) }
    fun hideBlurbDialog() = _uiState.update { it.copy(showBlurbDialog = false) }

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
}
