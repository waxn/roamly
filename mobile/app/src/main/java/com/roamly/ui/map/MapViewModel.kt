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
    H24("24h"), D7("7d"), D30("30d"), D90("90d"), ALL("All")
}

private const val AUTO_LOAD_LIMIT = 10_000
private const val MORE_LOAD_LIMIT = 50_000

data class MapFocus(
    val lat: Double,
    val lng: Double,
    val zoom: Double = 14.0,
    val key: Long = System.currentTimeMillis(),
)

data class MapUiState(
    val locations: List<LocationPoint> = emptyList(),
    val stats: StatsResponse? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val detailLimited: Boolean = false,
    val error: String? = null,
    val timePeriod: TimePeriod = TimePeriod.H24,
    val focus: MapFocus? = null,
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

    fun focusOn(lat: Double, lng: Double, zoom: Double = 15.0) {
        _uiState.update { it.copy(focus = MapFocus(lat, lng, zoom)) }
    }

    fun clearFocus() {
        _uiState.update { it.copy(focus = null) }
    }

    fun loadAllPoints() {
        val hours = _uiState.value.timePeriod.hours
        _uiState.update { it.copy(isLoadingMore = true, error = null) }
        viewModelScope.launch {
            when (val result = locationRepository.getLocations(hours = hours, limit = MORE_LOAD_LIMIT)) {
                is Result.Success -> {
                    val allPoints = result.data.devices.flatMap { it.locations }
                    _uiState.update {
                        it.copy(
                            locations = allPoints,
                            isLoadingMore = false,
                            detailLimited = false
                        )
                    }
                }
                is Result.Error -> _uiState.update { it.copy(error = result.message, isLoadingMore = false) }
            }
        }
    }

    fun loadData() {
        val hours = _uiState.value.timePeriod.hours
        _uiState.update {
            it.copy(
                isLoading = true,
                error = null,
                detailLimited = false,
                locations = emptyList()
            )
        }
        viewModelScope.launch {
            when (val result = locationRepository.getLocations(hours = hours, limit = AUTO_LOAD_LIMIT)) {
                is Result.Success -> {
                    val allPoints = result.data.devices.flatMap { it.locations }
                    val hitLimit = allPoints.size >= (AUTO_LOAD_LIMIT * 0.95)
                    _uiState.update {
                        it.copy(
                            locations = allPoints,
                            isLoading = false,
                            detailLimited = hitLimit
                        )
                    }
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

internal val TimePeriod.hours: Int?
    get() = when (this) {
        TimePeriod.H24 -> 24
        TimePeriod.D7 -> 24 * 7
        TimePeriod.D30 -> 24 * 30
        TimePeriod.D90 -> 24 * 90
        TimePeriod.ALL -> null
    }
