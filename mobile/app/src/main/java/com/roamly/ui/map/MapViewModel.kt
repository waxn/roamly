package com.roamly.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.LocationPoint
import com.roamly.data.api.LocationsResponse
import com.roamly.data.api.StatsResponse
import com.roamly.data.cache.DiskCache
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
    private val locationRepository: LocationRepository,
    private val disk: DiskCache,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState

    // The osmdroid MapView is created once (with the application context) and
    // retained here for the life of this ViewModel — which survives bottom-nav
    // tab switches because the Map back-stack entry is saved. Re-creating a fresh
    // MapView on every revisit reopens osmdroid's shared SQLite tile cache, and a
    // stale MapView closing it on GC crashed the new one ("attempt to re-open an
    // already-closed object"). Reusing one instance sidesteps that entirely.
    // It's detached in onCleared(), i.e. only when the screen is truly gone.
    internal var mapHolder: MapHolder? = null

    override fun onCleared() {
        super.onCleared()
        mapHolder?.detach()
        mapHolder = null
    }

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

    /**
     * "Have I been here?" — fetches ALL-TIME history within ~11km of the user
     * (independent of the current time-period view, which is usually just 24h) so
     * it can actually surface every past day spent at this spot.
     */
    fun checkHaveIBeenHere(userLat: Double, userLng: Double, onResult: (NearHereResult) -> Unit) {
        viewModelScope.launch {
            val pad = 0.1 // ~11 km
            val result = locationRepository.getLocationsInBbox(
                minLat = userLat - pad, maxLat = userLat + pad,
                minLng = userLng - pad, maxLng = userLng + pad,
                hours = null, limit = 25_000,
            )
            val points = when (result) {
                is Result.Success -> result.data.devices.flatMap { it.locations }
                is Result.Error -> _uiState.value.locations // fall back to what's loaded
            }
            onResult(buildNearHere(userLat, userLng, points))
        }
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
        val period = _uiState.value.timePeriod
        val hours = period.hours
        accumulator.clear()

        // Paint last-known points for this period instantly (cold start / offline),
        // then refresh in the background. Only show the spinner when we have nothing.
        val cachedLocs: LocationsResponse? = disk.get(DiskCache.mapLocations(period.name), LocationsResponse::class.java)
        val cachedStats: StatsResponse? = disk.get(DiskCache.mapStats(period.name), StatsResponse::class.java)
        cachedLocs?.devices?.forEach { dev ->
            dev.locations.forEach { pt -> accumulator["${dev.deviceId}:${pt.timestamp}"] = pt }
        }
        _uiState.update {
            it.copy(
                isLoading = accumulator.isEmpty(),
                error = null,
                locations = accumulator.values.toList(),
                stats = cachedStats ?: it.stats,
            )
        }

        viewModelScope.launch {
            when (val result = locationRepository.getLocations(hours = hours, limit = PERIOD_LOAD_LIMIT)) {
                is Result.Success -> {
                    accumulator.clear()
                    result.data.devices.forEach { dev ->
                        dev.locations.forEach { pt ->
                            accumulator["${dev.deviceId}:${pt.timestamp}"] = pt
                        }
                    }
                    disk.put(DiskCache.mapLocations(period.name), result.data)
                    _uiState.update {
                        it.copy(
                            locations = accumulator.values.toList(),
                            isLoading = false,
                        )
                    }
                }
                is Result.Error -> _uiState.update { it.copy(error = if (accumulator.isEmpty()) result.message else null, isLoading = false) }
            }
            when (val result = locationRepository.getStats(hours = hours)) {
                is Result.Success -> {
                    disk.put(DiskCache.mapStats(period.name), result.data)
                    _uiState.update { it.copy(stats = result.data) }
                }
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
