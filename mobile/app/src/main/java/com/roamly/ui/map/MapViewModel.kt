package com.roamly.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.LocationPoint
import com.roamly.data.api.StatsResponse
import com.roamly.data.local.LocationStore
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
private const val BBOX_LOAD_LIMIT = 8_000
/** zoom level above which we start fetching detailed dots */
internal const val DETAIL_ZOOM = 12.0
/** debounce for viewport-change-triggered queries */
private const val VIEWPORT_DEBOUNCE_MS = 120L

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
    private val store: LocationStore,
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

    // Accumulator cache: "id" → point. Holds the period overview plus any
    // higher-resolution dots pulled in as the user zooms, so panning never drops
    // what we've already painted.
    private val accumulator: HashMap<Int, LocationPoint> = HashMap()
    private var viewportJob: Job? = null

    init {
        // Paint instantly from the local store, then refresh it from the server in
        // the background. The first call returns whatever's already on-device.
        loadFromStore(showSpinnerIfEmpty = true)
        viewModelScope.launch {
            store.syncIfDue()
            // New points may have landed — repaint the current period silently.
            loadFromStore(showSpinnerIfEmpty = false)
        }
        viewModelScope.launch { loadStats() }
    }

    fun setTimePeriod(period: TimePeriod) {
        accumulator.clear()
        _uiState.update { it.copy(timePeriod = period) }
        loadFromStore(showSpinnerIfEmpty = true)
        viewModelScope.launch { loadStats() }
    }

    fun focusOn(lat: Double, lng: Double, zoom: Double = 15.0) {
        _uiState.update { it.copy(focus = MapFocus(lat, lng, zoom)) }
    }

    fun clearFocus() {
        _uiState.update { it.copy(focus = null) }
    }

    /**
     * "Have I been here?" — scans the *entire* local history within ~11km of the
     * user (independent of the current time-period view) so every past day spent
     * at this spot is counted. Reading locally makes it instant and complete,
     * fixing the old under-count from a server limit on the result set.
     */
    fun checkHaveIBeenHere(userLat: Double, userLng: Double, onResult: (NearHereResult) -> Unit) {
        viewModelScope.launch {
            val pad = 0.1 // ~11 km
            val points = store.allTimeAround(userLat, userLng, pad)
            onResult(buildNearHere(userLat, userLng, points))
        }
    }

    /**
     * Called by the map screen on debounced pan/zoom. Pulls full-resolution dots
     * for the current viewport from the local store (instant) once the user has
     * zoomed past DETAIL_ZOOM, accumulating them across moves.
     */
    fun onViewportChanged(zoom: Double, minLat: Double, maxLat: Double, minLng: Double, maxLng: Double) {
        if (zoom < DETAIL_ZOOM) return
        viewportJob?.cancel()
        viewportJob = viewModelScope.launch {
            delay(VIEWPORT_DEBOUNCE_MS)
            val hours = _uiState.value.timePeriod.hours
            val detail = store.bbox(minLat, maxLat, minLng, maxLng, hours = hours, limit = BBOX_LOAD_LIMIT)
            var added = 0
            detail.forEach { pt -> if (accumulator.put(pt.id, pt) == null) added++ }
            if (added > 0) _uiState.update { it.copy(locations = accumulator.values.toList()) }
        }
    }

    /** Repaint the active time period from the local store. */
    private fun loadFromStore(showSpinnerIfEmpty: Boolean) {
        val hours = _uiState.value.timePeriod.hours
        viewModelScope.launch {
            if (showSpinnerIfEmpty && accumulator.isEmpty()) {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }
            val overview = store.periodOverview(hours, PERIOD_LOAD_LIMIT)
            accumulator.clear()
            overview.forEach { pt -> accumulator[pt.id] = pt }
            _uiState.update {
                it.copy(locations = accumulator.values.toList(), isLoading = false)
            }
        }
    }

    /** Stats come from the server endpoint (cheap, cached server-side). */
    private suspend fun loadStats() {
        val hours = _uiState.value.timePeriod.hours
        when (val result = locationRepository.getStats(hours = hours)) {
            is Result.Success -> _uiState.update { it.copy(stats = result.data) }
            is Result.Error -> { /* stats are optional */ }
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
