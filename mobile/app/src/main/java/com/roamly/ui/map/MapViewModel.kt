package com.roamly.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.LocationPoint
import com.roamly.data.api.StatsResponse
import com.roamly.data.repository.LocationRepository
import com.roamly.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TimePeriod(val label: String) {
    H24("24h"), D7("7d"), D30("30d"), ALL("All")
}

data class MapUiState(
    val locations: List<LocationPoint> = emptyList(),
    val stats: StatsResponse? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val timePeriod: TimePeriod = TimePeriod.H24
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val locationRepository: LocationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState

    init {
        loadData()
    }

    fun setTimePeriod(period: TimePeriod) {
        _uiState.update { it.copy(timePeriod = period) }
        loadData()
    }

    fun loadData() {
        val period = _uiState.value.timePeriod
        val hours = period.hours
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = locationRepository.getLocations(hours = hours)) {
                is Result.Success -> {
                    val allPoints = result.data.devices.flatMap { it.locations }
                    _uiState.update { it.copy(locations = allPoints, isLoading = false) }
                }
                is Result.Error -> _uiState.update { it.copy(error = result.message, isLoading = false) }
            }
            when (val result = locationRepository.getStats(hours = hours)) {
                is Result.Success -> _uiState.update { it.copy(stats = result.data) }
                is Result.Error -> { /* stats are optional */ }
            }
        }
    }
}

private val TimePeriod.hours: Int?
    get() = when (this) {
        TimePeriod.H24 -> 24
        TimePeriod.D7 -> 24 * 7
        TimePeriod.D30 -> 24 * 30
        TimePeriod.ALL -> null
    }
