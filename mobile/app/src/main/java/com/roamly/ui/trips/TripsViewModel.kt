package com.roamly.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.CreateTripRequest
import com.roamly.data.api.TripResponse
import com.roamly.data.repository.Result
import com.roamly.data.repository.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TripsUiState(
    val trips: List<TripResponse> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showCreateDialog: Boolean = false
)

@HiltViewModel
class TripsViewModel @Inject constructor(
    private val repository: TripRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TripsUiState())
    val uiState: StateFlow<TripsUiState> = _uiState

    init { loadTrips() }

    fun loadTrips() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.getTrips()) {
                is Result.Success -> _uiState.update { it.copy(trips = result.data.trips, isLoading = false) }
                is Result.Error -> _uiState.update { it.copy(error = result.message, isLoading = false) }
            }
        }
    }

    fun showCreateDialog() = _uiState.update { it.copy(showCreateDialog = true) }
    fun hideCreateDialog() = _uiState.update { it.copy(showCreateDialog = false) }

    fun createTrip(name: String, description: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            when (val result = repository.createTrip(CreateTripRequest(name, description.ifBlank { null }))) {
                is Result.Success -> {
                    _uiState.update { it.copy(showCreateDialog = false) }
                    loadTrips()
                }
                is Result.Error -> _uiState.update { it.copy(error = result.message) }
            }
        }
    }

    fun deleteTrip(id: Int) {
        viewModelScope.launch {
            repository.deleteTrip(id)
            loadTrips()
        }
    }
}
