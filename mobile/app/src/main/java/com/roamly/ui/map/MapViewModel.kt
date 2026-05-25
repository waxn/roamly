package com.roamly.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.LocationPoint
import com.roamly.data.api.StatsResponse
import com.roamly.data.repository.LocationRepository
import com.roamly.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TimePeriod(val label: String) {
    H24("24h"), D7("7d"), D30("30d"), D90("90d"), ALL("All")
}

/** points loaded for the initial period view (heatmap-first) */
private const val PERIOD_LOAD_LIMIT = 20_000
/** points loaded for a single zoomed-in bbox request */
private const val BBOX_LOAD_LIMIT = 5_000
/** zoom level above which we start fetching detailed dots */
internal const val DETAIL_ZOOM = 12.0
/** debounce for viewport-change-triggered requests */
private const val VIEWPORT_DEBOUNCE_MS = 350L

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

    // Accumulator cache: "deviceId:timestamp" → point. Survives pan/zoom so we
    // never re-download points we've already seen.
    private val accumulator: HashMap<String, LocationPoint> = HashMap()
    private var viewportJob: Job? = null

    init {
        loadData()
    }

    fun setTimePeriod(period: TimePeriod) {
        accumulator.clear()
        _uiState.update { it.copy(timePeriod = period) }
        loadData()
    }

    fun focusOn(lat: Double, lng: Double, zoom: Double = 15.0) {
        _uiState.update { it.copy(focus = MapFocus(lat, lng, zoom)) }
    }

    fun clearFocus() {
        _uiState.update { it.copy(focus = null) }
    }

    /**
     * Called by the map screen on debounced pan/zoom. Fetches dots for the
     * current viewport when the user has zoomed in past DETAIL_ZOOM, and
     * accumulates them so subsequent moves don't lose what we already have.
     */
    fun onViewportChanged(zoom: Double, minLat: Double, maxLat: Double, minLng: Double, maxLng: Double) {
        if (zoom < DETAIL_ZOOM) return
        viewportJob?.cancel()
        viewportJob = viewModelScope.launch {
            delay(VIEWPORT_DEBOUNCE_MS)
            val hours = _uiState.value.timePeriod.hours
            _uiState.update { it.copy(isLoadingMore = true) }
            when (val r = locationRepository.getLocationsInBbox(minLat, maxLat, minLng, maxLng, hours = hours, limit = BBOX_LOAD_LIMIT)) {
                is Result.Success -> {
                    var added = 0
                    r.data.devices.forEach { dev ->
                        dev.locations.forEach { pt ->
                            val key = "${dev.deviceId}:${pt.timestamp}"
                            if (accumulator.put(key, pt) == null) added++
                        }
                    }
                    if (added > 0) _uiState.update { it.copy(locations = accumulator.values.toList(), isLoadingMore = false) }
                    else _uiState.update { it.copy(isLoadingMore = false) }
                }
                is Result.Error -> _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun loadData() {
        val hours = _uiState.value.timePeriod.hours
        accumulator.clear()
        _uiState.update {
            it.copy(
                isLoading = true,
                error = null,
                locations = emptyList()
            )
        }
        viewModelScope.launch {
            when (val result = locationRepository.getLocations(hours = hours, limit = PERIOD_LOAD_LIMIT)) {
                is Result.Success -> {
                    result.data.devices.forEach { dev ->
                        dev.locations.forEach { pt ->
                            accumulator["${dev.deviceId}:${pt.timestamp}"] = pt
                        }
                    }
                    _uiState.update {
                        it.copy(
                            locations = accumulator.values.toList(),
                            isLoading = false,
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
