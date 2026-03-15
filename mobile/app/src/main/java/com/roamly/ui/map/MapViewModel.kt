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
import java.util.Calendar
import javax.inject.Inject

enum class TimePeriod(val label: String) {
    WEEK("7D"), MONTH("30D"), THREE_MONTHS("3M"), YEAR("1Y"), ALL("All")
}

data class MapUiState(
    val locations: List<LocationPoint> = emptyList(),
    val stats: StatsResponse? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val timePeriod: TimePeriod = TimePeriod.ALL
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
        val (startDate, endDate) = periodDates(period)
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = locationRepository.getLocations(startDate, endDate)) {
                is Result.Success -> {
                    val allPoints = result.data.devices.flatMap { it.locations }
                    _uiState.update { it.copy(locations = allPoints, isLoading = false) }
                }
                is Result.Error -> _uiState.update { it.copy(error = result.message, isLoading = false) }
            }
            when (val result = locationRepository.getStats(startDate, endDate)) {
                is Result.Success -> _uiState.update { it.copy(stats = result.data) }
                is Result.Error -> { /* stats are optional */ }
            }
        }
    }

    private fun periodDates(period: TimePeriod): Pair<String?, String?> {
        if (period == TimePeriod.ALL) return null to null
        val end = Calendar.getInstance()
        val start = Calendar.getInstance()
        when (period) {
            TimePeriod.WEEK -> start.add(Calendar.DAY_OF_YEAR, -7)
            TimePeriod.MONTH -> start.add(Calendar.DAY_OF_YEAR, -30)
            TimePeriod.THREE_MONTHS -> start.add(Calendar.DAY_OF_YEAR, -90)
            TimePeriod.YEAR -> start.add(Calendar.DAY_OF_YEAR, -365)
            TimePeriod.ALL -> {}
        }
        fun fmt(c: Calendar) = "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        return fmt(start) to fmt(end)
    }
}
