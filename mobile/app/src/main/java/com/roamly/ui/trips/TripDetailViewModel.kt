package com.roamly.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val isLoading: Boolean = false,
    val error: String? = null,
    val showBlurbDialog: Boolean = false
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

    fun showBlurbDialog() = _uiState.update { it.copy(showBlurbDialog = true) }
    fun hideBlurbDialog() = _uiState.update { it.copy(showBlurbDialog = false) }

    fun createBlurb(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            when (val r = repository.createBlurb(tripId, text)) {
                is Result.Success -> {
                    _uiState.update { state ->
                        state.copy(events = listOf(r.data) + state.events, showBlurbDialog = false)
                    }
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

    fun togglePublic() {
        viewModelScope.launch { repository.togglePublic(tripId) }
    }
}
