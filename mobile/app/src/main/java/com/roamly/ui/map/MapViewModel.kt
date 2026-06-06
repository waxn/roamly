package com.roamly.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.LocationPoint
import com.roamly.data.api.LocationsResponse
import com.roamly.data.api.StatsResponse
import com.roamly.data.local.LocationStore
import com.roamly.data.repository.LocationRepository
import com.roamly.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // retained here for the life of this ViewModel — surviving bottom-nav tab
    // switches. Re-creating a fresh MapView on every revisit reopens osmdroid's
    // shared SQLite tile cache and a stale instance closing it on GC crashed the
    // new one. Reusing one instance sidesteps that.
    internal var mapHolder: MapHolder? = null

    override fun onCleared() {
        super.onCleared()
        mapHolder?.detach()
        mapHolder = null
    }

    // Accumulator: server-id -> point. Holds the period overview plus any
    // higher-resolution dots pulled in as the user zooms.
    private val accumulator: HashMap<Int, LocationPoint> = HashMap()
    private var viewportJob: Job? = null

    init {
        loadData(showSpinnerIfEmpty = true)
        // Build/refresh the on-device history in the background, then repaint so
        // the local store becomes the instant source for subsequent opens and for
        // an accurate "have I been here?".
        viewModelScope.launch {
            store.syncIfDue()
            loadData(showSpinnerIfEmpty = false)
        }
        viewModelScope.launch { loadStats() }
    }

    fun setTimePeriod(period: TimePeriod) {
        accumulator.clear()
        _uiState.update { it.copy(timePeriod = period) }
        loadData(showSpinnerIfEmpty = true)
        viewModelScope.launch { loadStats() }
    }

    fun focusOn(lat: Double, lng: Double, zoom: Double = 15.0) {
        _uiState.update { it.copy(focus = MapFocus(lat, lng, zoom)) }
    }

    fun clearFocus() {
        _uiState.update { it.copy(focus = null) }
    }

    /**
     * "Have I been here?" — scans the on-device history within ~11km of the user
     * (independent of the current time window) so every past day at this spot is
     * counted. The heavy haversine scan runs off the main thread. If the local
     * store isn't populated yet (first run, sync still going) it falls back to a
     * server query so the feature still works immediately.
     */
    fun checkHaveIBeenHere(userLat: Double, userLng: Double, onResult: (NearHereResult) -> Unit) {
        viewModelScope.launch {
            val pad = 0.1 // ~11 km
            var points = store.allTimeAround(userLat, userLng, pad)
            if (points.isEmpty()) {
                points = when (val r = locationRepository.getLocationsInBbox(
                    minLat = userLat - pad, maxLat = userLat + pad,
                    minLng = userLng - pad, maxLng = userLng + pad,
                    hours = null, limit = 25_000,
                )) {
                    is Result.Success -> r.data.devices.flatMap { it.locations }
                    is Result.Error -> emptyList()
                }
            }
            val result = withContext(Dispatchers.Default) { buildNearHere(userLat, userLng, points) }
            onResult(result)
        }
    }

    /**
     * Debounced pan/zoom handler. Pulls full-resolution dots for the viewport from
     * the local store (instant) past DETAIL_ZOOM, accumulating across moves.
     */
    fun onViewportChanged(zoom: Double, minLat: Double, maxLat: Double, minLng: Double, maxLng: Double) {
        if (zoom < DETAIL_ZOOM) return
        viewportJob?.cancel()
        viewportJob = viewModelScope.launch {
            delay(VIEWPORT_DEBOUNCE_MS)
            val hours = _uiState.value.timePeriod.hours
            val detail = store.bbox(minLat, maxLat, minLng, maxLng, hours = hours, limit = BBOX_LOAD_LIMIT)
            if (detail.isEmpty()) return@launch
            // The DAO query already ran off-main; accumulator ops stay confined to
            // this (main) dispatcher so the map is only mutated from one thread.
            var added = 0
            detail.forEach { pt -> if (accumulator.put(pt.id, pt) == null) added++ }
            if (added > 0) _uiState.update { it.copy(locations = accumulator.values.toList()) }
        }
    }

    /**
     * Load the active period. Paints instantly from the local store; on a fresh
     * install (store empty for this period) it fetches the period straight from
     * the server so points appear right away while the full sync runs.
     */
    fun loadData(showSpinnerIfEmpty: Boolean) {
        val period = _uiState.value.timePeriod
        val hours = period.hours
        viewModelScope.launch {
            val local = store.periodOverview(hours, PERIOD_LOAD_LIMIT)
            if (local.isNotEmpty()) {
                setLocations(local)
                return@launch
            }
            // Nothing local yet — show the spinner and fetch this period directly.
            if (showSpinnerIfEmpty && accumulator.isEmpty()) {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }
            when (val r = locationRepository.getLocations(hours = hours, limit = PERIOD_LOAD_LIMIT)) {
                is Result.Success -> setLocations(flatten(r.data))
                is Result.Error -> _uiState.update {
                    it.copy(isLoading = false, error = if (accumulator.isEmpty()) r.message else null)
                }
            }
        }
    }

    private fun setLocations(points: List<LocationPoint>) {
        // points already came off-main (DAO/JSON). The accumulator is only ever
        // touched on this dispatcher, so there's no cross-thread race with the
        // viewport loader.
        accumulator.clear()
        points.forEach { accumulator[it.id] = it }
        _uiState.update { it.copy(locations = accumulator.values.toList(), isLoading = false, error = null) }
    }

    private fun flatten(resp: LocationsResponse): List<LocationPoint> =
        resp.devices.flatMap { it.locations }

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
