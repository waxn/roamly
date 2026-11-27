package com.roamly.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.InferredBatch
import com.roamly.data.api.LocationPoint
import com.roamly.data.api.MapPlace
import com.roamly.data.api.LocationsResponse
import com.roamly.data.api.StatsResponse
import com.roamly.data.local.LocationStore
import com.roamly.data.prefs.MapLayers
import com.roamly.data.prefs.UserPreferences
import com.roamly.data.repository.LocationRepository
import com.roamly.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

enum class TimePeriod(val label: String) {
    H6("6h"), H24("24h"), D7("7d"), D30("30d"), D90("90d"), ALL("All"), CUSTOM("Custom")
}

data class DateRange(val start: LocalDate, val end: LocalDate) {
    val label: String get() {
        val thisYear = LocalDate.now().year
        fun fmt(d: LocalDate, weekday: Boolean) = d.format(
            java.time.format.DateTimeFormatter.ofPattern(
                (if (weekday) "EEE, " else "") + "MMM d" + (if (d.year != thisYear) ", yyyy" else ""),
                java.util.Locale.getDefault(),
            )
        )
        return if (start == end) fmt(start, weekday = true) else "${fmt(start, false)} – ${fmt(end, false)}"
    }
}

/** How many calendar days a live period covers when it is stepped with ‹ ›. */
internal val TimePeriod.stepDays: Long?
    get() = when (this) {
        TimePeriod.H6, TimePeriod.H24 -> 1
        TimePeriod.D7 -> 7
        TimePeriod.D30 -> 30
        TimePeriod.D90 -> 90
        TimePeriod.ALL, TimePeriod.CUSTOM -> null
    }

/** points loaded for the initial period view (heatmap-first) */
private const val PERIOD_LOAD_LIMIT = 20_000
/** points loaded for a single zoomed-in bbox request */
private const val BBOX_LOAD_LIMIT = 8_000
/** zoom level above which we start fetching detailed dots */
internal const val DETAIL_ZOOM = 10.0
/** Most points held across pans; past this, the ones outside the current view are dropped. */
private const val MAX_ACCUMULATED = 60_000
/**
 * Debounce for viewport-change-triggered queries.
 *
 * osmdroid emits onScroll per touch-move frame and the job is cancelled and
 * restarted on each, so 120ms did not mean "one query per 120ms" -- it meant a
 * query fired in every micro-pause *during* a drag, each one a BBOX_LOAD_LIMIT
 * read. 350ms issues none mid-gesture and costs a fifth of a second after the
 * pan settles. These reads no longer share a database with GPS capture either
 * (see MapCacheDatabase), but there is still no reason to do them 8x a second.
 */
private const val VIEWPORT_DEBOUNCE_MS = 350L

/** Below this zoom a viewport covers enough ground that most of a full-size
 *  batch is culled off-screen before it is ever drawn. */
private const val BBOX_LOAD_LIMIT_WIDE = 3_000
private const val BBOX_WIDE_ZOOM = 15.0

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
    /** A user-triggered refresh (map options → refresh) is in flight. */
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val timePeriod: TimePeriod = TimePeriod.H24,
    val customDateRange: DateRange? = null,
    val focus: MapFocus? = null,
    val showDateRangePicker: Boolean = false,
    // Optional Mapbox token (from the web account). Blank = use the built-in basemap.
    val mapboxToken: String = "",
    // Selected basemap label (e.g. "Streets", "Satellite", "Mapbox Streets").
    // "Dark" (the default) is a night map in either theme; "Streets" is the light one.
    val basemap: String = "Dark",
    // device_id → display name, so a tapped point can name the phone that recorded it.
    val deviceNames: Map<String, String> = emptyMap(),
    /** Map options toggles (fog, filled gaps, outliers, transport, places). */
    val layers: MapLayers = MapLayers(),
    /** Fog-of-war cells as flat `[gy, gx, …]`; null until loaded. Whole history. */
    val fogCells: IntArray? = null,
    val fogPerDeg: Int = 1000,
    /** Filled-gap lines for the current range. */
    val inferred: List<InferredBatch> = emptyList(),
    val places: List<MapPlace> = emptyList(),
) {
    /** The points the track layers draw: hide-track, outlier and transport filters applied. */
    fun visibleLocations(): List<LocationPoint> = when {
        layers.hideTrack -> emptyList()
        !layers.hideSuspect && !layers.modeFilterActive -> locations
        else -> locations.filter { p ->
            (!layers.hideSuspect || p.flag != "suspect") && layers.showsMode(p.transportMode)
        }
    }

    val periodLabel: String get() = if (timePeriod == TimePeriod.CUSTOM && customDateRange != null)
        customDateRange.label else timePeriod.label

    /** The calendar days on screen (null = all time). "6h" just after midnight
     *  starts yesterday, so it counts from when the window really begins. */
    fun rangeDays(now: java.time.LocalDateTime = java.time.LocalDateTime.now()): Pair<LocalDate, LocalDate>? =
        when (timePeriod) {
            TimePeriod.ALL -> null
            TimePeriod.CUSTOM -> customDateRange?.let { it.start to it.end }
            else -> now.minusHours((timePeriod.hours ?: 0).toLong()).toLocalDate() to now.toLocalDate()
        }

    /** The one day the range is about, when it is about one: the hour periods
     *  are today, a stepped-to (or picked) single day is that day. */
    fun noteDay(today: LocalDate = LocalDate.now()): LocalDate? = when (timePeriod) {
        TimePeriod.H6, TimePeriod.H24 -> today
        TimePeriod.CUSTOM -> customDateRange?.takeIf { it.start == it.end }?.start
        else -> null
    }

    val canStepBack: Boolean get() = timePeriod != TimePeriod.ALL &&
        (timePeriod != TimePeriod.CUSTOM || customDateRange != null)
    val canStepForward: Boolean get() = timePeriod == TimePeriod.CUSTOM &&
        customDateRange != null && customDateRange.end.isBefore(LocalDate.now())
}

@HiltViewModel
class MapViewModel @Inject constructor(
    private val locationRepository: LocationRepository,
    private val store: LocationStore,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState

    // The osmdroid MapView is created once (with the application context) and
    // retained here for the life of this ViewModel — surviving bottom-nav tab
    // switches. Re-creating a fresh MapView on every revisit reopens osmdroid's
    // shared SQLite tile cache and a stale instance closing it on GC crashed the
    // new one. Reusing one instance sidesteps that.
    internal var mapHolder: MapHolder? = null

    // Whether the camera has already been fitted to the current dataset. Held
    // here rather than in a MapScreen `remember` because a remember dies with
    // the composable: every bottom-nav tab switch away and back re-ran the fit,
    // which both cost a full pass over the accumulated points on the main
    // thread and yanked the camera away from wherever the user had panned.
    // Reset wherever the dataset itself changes (below).
    internal var didAutoFit: Boolean = false

    override fun onCleared() {
        super.onCleared()
        mapHolder?.detach()
        mapHolder = null
    }

    // Accumulator: server-id -> point. Holds the period overview plus any
    // higher-resolution dots pulled in as the user zooms.
    private val accumulator: HashMap<Int, LocationPoint> = HashMap()
    private var viewportJob: Job? = null
    /** The viewport the last detail query covered, so panning back and forth across
     *  already-loaded ground re-queries nothing. Cleared whenever the dataset changes. */
    private var lastQueried: DoubleArray? = null
    private var lastQueriedZoom = 0.0
    private var lastKnownSyncMs = 0L

    init {
        // Wait for a real server URL before touching the network. This VM is
        // Activity-scoped and constructed at the top of RoamlyNavHost — during the
        // splash, before login resolves — so firing requests immediately hits the
        // placeholder base URL ("http://roamly.placeholder/") and leaves an
        // "unable to resolve host" error stuck on the first map view. Once logged
        // in the value is already present, so this returns instantly.
        viewModelScope.launch {
            userPreferences.serverUrl.first { !it.isNullOrBlank() }
            loadData(showSpinnerIfEmpty = true)
            // Build/refresh the on-device history in the background, then repaint
            // so the local store becomes the instant source for subsequent opens
            // and for an accurate "have I been here?".
            store.syncIfDue()
            loadData(showSpinnerIfEmpty = false)
        }
        viewModelScope.launch {
            userPreferences.serverUrl.first { !it.isNullOrBlank() }
            loadStats()
        }

        // Mapbox basemap token: paint immediately from the cached value, then
        // refresh from the server so a change made on the web propagates here.
        viewModelScope.launch {
            userPreferences.mapboxToken.collect { token ->
                _uiState.update { it.copy(mapboxToken = token) }
            }
        }
        viewModelScope.launch {
            userPreferences.serverUrl.first { !it.isNullOrBlank() }
            val token = locationRepository.getMapboxToken()
            if (token != null) userPreferences.setMapboxToken(token)
        }
        // Selected basemap (persisted per device).
        viewModelScope.launch {
            userPreferences.mapBasemap.collect { name ->
                _uiState.update { it.copy(basemap = if (name == "Auto") "Dark" else name) }
            }
        }
        // Map options toggles: read once (updateLayers keeps state and prefs in
        // step from then on), then fetch whatever the switched-on layers need.
        viewModelScope.launch {
            val saved = userPreferences.mapLayers.first()
            _uiState.update { it.copy(layers = saved) }
            userPreferences.serverUrl.first { !it.isNullOrBlank() }
            loadLayerData()
        }

        // Device names, so the point-detail dialog can say which phone logged it.
        viewModelScope.launch {
            userPreferences.serverUrl.first { !it.isNullOrBlank() }
            val names = locationRepository.getDeviceNames()
            if (names.isNotEmpty()) _uiState.update { it.copy(deviceNames = names) }
        }

        // Re-paint automatically whenever a background sync completes (e.g. from
        // MainActivity.onResume after the user was away tracking for a while).
        viewModelScope.launch {
            store.state.collect { s ->
                if (!s.syncing && s.lastSyncMs > lastKnownSyncMs) {
                    lastKnownSyncMs = s.lastSyncMs
                    loadData(showSpinnerIfEmpty = false)
                }
            }
        }
    }

    /** Flip map options toggles. Applied at once, persisted per device, and any
     *  layer data a newly switched-on toggle needs is fetched. */
    fun updateLayers(change: (MapLayers) -> MapLayers) {
        val next = change(_uiState.value.layers)
        _uiState.update { it.copy(layers = next) }
        viewModelScope.launch { userPreferences.setMapLayers(next) }
        viewModelScope.launch { loadLayerData() }
    }

    private var fogLoaded = false
    private var placesLoaded = false
    /** The range the filled-gap lines on screen were fetched for. */
    private var inferredKey: String? = null

    private suspend fun loadLayerData() {
        val layers = _uiState.value.layers
        if (layers.fog && !fogLoaded) {
            fogLoaded = true
            when (val r = locationRepository.getFog()) {
                is Result.Success -> _uiState.update {
                    it.copy(fogCells = r.data.cells.toIntArray(), fogPerDeg = r.data.perDeg)
                }
                is Result.Error -> fogLoaded = false
            }
        }
        if (layers.places && !placesLoaded) {
            placesLoaded = true
            when (val r = locationRepository.getMapPlaces()) {
                is Result.Success -> _uiState.update { it.copy(places = r.data.places) }
                is Result.Error -> placesLoaded = false
            }
        }
        if (layers.filledGaps) loadInferred()
    }

    /** Filled gaps follow the time range, like the track. */
    private suspend fun loadInferred() {
        val s = _uiState.value
        val custom = s.customDateRange.takeIf { s.timePeriod == TimePeriod.CUSTOM }
        val key = custom?.let { "${it.start}:${it.end}" } ?: "h:${s.timePeriod.hours}"
        if (key == inferredKey) return
        inferredKey = key
        val r = if (custom != null) {
            locationRepository.getInferred(startDate = custom.start.toString(), endDate = custom.end.toString())
        } else {
            locationRepository.getInferred(hours = s.timePeriod.hours)
        }
        when (r) {
            is Result.Success -> if (inferredKey == key) _uiState.update { it.copy(inferred = r.data.batches) }
            is Result.Error -> if (inferredKey == key) inferredKey = null
        }
    }

    private fun onRangeChanged() {
        viewModelScope.launch { if (_uiState.value.layers.filledGaps) loadInferred() }
    }

    /** Change the map basemap. Persisted so the choice survives app restarts;
     *  the collector above pushes it back into uiState. */
    fun setBasemap(name: String) {
        viewModelScope.launch { userPreferences.setMapBasemap(name) }
    }

    /** The live period the ‹ arrow stepped away from, so › can land back on it. */
    private var stepOrigin: TimePeriod? = null

    /**
     * ‹ / › beside the time range: move the window by its own length. A live
     * period ("7d") becomes the custom range before it; stepping forward onto
     * the current window returns to the live period rather than a frozen copy.
     */
    fun stepRange(dir: Int) {
        val s = _uiState.value
        val today = LocalDate.now()
        val (start, end) = when (s.timePeriod) {
            TimePeriod.ALL -> return
            TimePeriod.CUSTOM -> s.customDateRange?.let { it.start to it.end } ?: return
            else -> {
                val len = s.timePeriod.stepDays ?: return
                if (dir > 0) return
                stepOrigin = s.timePeriod
                today.minusDays(len - 1) to today
            }
        }
        val len = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1
        if (dir > 0 && !end.isBefore(today)) return
        val newStart = start.plusDays(dir * len)
        val newEnd = end.plusDays(dir * len)
        if (dir > 0 && !newEnd.isBefore(today)) {
            val origin = stepOrigin?.takeIf { it.stepDays == len }
                ?: TimePeriod.entries.firstOrNull { it != TimePeriod.H6 && it.stepDays == len }
            if (origin != null) { setTimePeriod(origin); return }
            applyCustomRange(DateRange(today.minusDays(len - 1), today))
            return
        }
        applyCustomRange(DateRange(newStart, newEnd))
    }

    fun setTimePeriod(period: TimePeriod) {
        if (period == TimePeriod.CUSTOM) {
            _uiState.update { it.copy(showDateRangePicker = true) }
            return
        }
        stepOrigin = null
        accumulator.clear()
        lastQueried = null
        didAutoFit = false
        // Clear any existing focus so the auto-fit in MapScreen fires on the new dataset
        _uiState.update { it.copy(timePeriod = period, customDateRange = null, focus = null) }
        loadData(showSpinnerIfEmpty = true)
        onRangeChanged()
        viewModelScope.launch { loadStats() }
    }

    fun setCustomDateRange(range: DateRange) {
        stepOrigin = null
        applyCustomRange(range)
    }

    private fun applyCustomRange(range: DateRange) {
        accumulator.clear()
        lastQueried = null
        didAutoFit = false
        // Clear focus so MapScreen's LaunchedEffect auto-fits the viewport to the new data
        _uiState.update { it.copy(timePeriod = TimePeriod.CUSTOM, customDateRange = range, showDateRangePicker = false, focus = null) }
        loadData(showSpinnerIfEmpty = true)
        onRangeChanged()
    }

    /**
     * User-triggered refresh: pull whatever the server has that we don't (a
     * forced sync, not the throttled one), then repaint the current period and
     * its stats. Any detail dots accumulated from panning are dropped so the
     * refreshed data is what's on screen.
     */
    fun refresh() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            store.sync()
            accumulator.clear()
            lastQueried = null
            loadDataNow(showSpinnerIfEmpty = false)
            loadStats()
            // Places and filled gaps may have changed on the web; fog is whole-history
            // and cached server-side for an hour, so it stays.
            placesLoaded = false
            inferredKey = null
            loadLayerData()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun dismissDateRangePicker() {
        _uiState.update { it.copy(showDateRangePicker = false) }
    }

    /** Navigate to a specific date: set the custom date range to just that day and focus the map. */
    fun navigateToDate(dateStr: String, lat: Double? = null, lng: Double? = null) {
        runCatching { LocalDate.parse(dateStr) }.getOrNull()?.let { date ->
            setCustomDateRange(DateRange(date, date))
            if (lat != null && lng != null) {
                _uiState.update { it.copy(focus = MapFocus(lat, lng, zoom = 14.0)) }
            }
        }
    }

    /**
     * Delete a single GPS point. Removes it server-side first; on success drops it
     * from the local mirror and repaints the map immediately (so it's gone whether
     * or not the next sync runs). [onResult] reports success for a toast.
     */
    fun deletePoint(id: Int, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            when (locationRepository.deleteLocation(id)) {
                is Result.Success -> {
                    store.deletePoint(id)
                    accumulator.remove(id)
                    _uiState.update { it.copy(locations = accumulator.values.toList()) }
                    onResult(true)
                }
                is Result.Error -> onResult(false)
            }
        }
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
        // Already covered: the new viewport sits inside the one we last queried and
        // the zoom hasn't moved much, so every point in it is already in the
        // accumulator. This is what stops a re-entry to the Map tab, or a pan back
        // over ground just visited, from re-issuing a full-size read.
        lastQueried?.let { q ->
            if (minLat >= q[0] && maxLat <= q[1] && minLng >= q[2] && maxLng <= q[3] &&
                kotlin.math.abs(zoom - lastQueriedZoom) < 0.5
            ) return
        }
        viewportJob?.cancel()
        viewportJob = viewModelScope.launch {
            delay(VIEWPORT_DEBOUNCE_MS)
            val detail = loadDetailForViewport(minLat, maxLat, minLng, maxLng, zoom)
            lastQueried = doubleArrayOf(minLat, maxLat, minLng, maxLng)
            lastQueriedZoom = zoom
            if (detail.isEmpty()) return@launch
            var added = 0
            detail.forEach { pt -> if (accumulator.put(pt.id, pt) == null) added++ }
            if (accumulator.size > MAX_ACCUMULATED) {
                val padLat = (maxLat - minLat) / 2; val padLng = (maxLng - minLng) / 2
                accumulator.values.removeAll { it.lat !in (minLat - padLat)..(maxLat + padLat) || it.lng !in (minLng - padLng)..(maxLng + padLng) }
                lastQueried = null
                added = added.coerceAtLeast(1)
            }
            if (added > 0) _uiState.update { it.copy(locations = accumulator.values.toList()) }
        }
    }

    private suspend fun loadDetailForViewport(
        minLat: Double, maxLat: Double, minLng: Double, maxLng: Double, zoom: Double,
    ): List<LocationPoint> {
        val state = _uiState.value
        val limit = if (zoom >= BBOX_WIDE_ZOOM) BBOX_LOAD_LIMIT else BBOX_LOAD_LIMIT_WIDE
        return if (state.timePeriod == TimePeriod.CUSTOM && state.customDateRange != null) {
            val range = state.customDateRange
            val zone = ZoneId.systemDefault()
            val startMs = range.start.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMs = range.end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            store.timeRange(startMs, endMs, limit)
                .filter { it.lat in minLat..maxLat && it.lng in minLng..maxLng }
        } else {
            store.bbox(minLat, maxLat, minLng, maxLng, hours = state.timePeriod.hours, limit = limit)
        }
    }

    /**
     * Load the active period. Paints instantly from the local store; on a fresh
     * install (store empty for this period) it fetches the period straight from
     * the server so points appear right away while the full sync runs.
     */
    fun loadData(showSpinnerIfEmpty: Boolean) {
        viewModelScope.launch { loadDataNow(showSpinnerIfEmpty) }
    }

    /** The body of [loadData], awaitable so [refresh] can hold its spinner until
     *  the repaint has actually landed. */
    private suspend fun loadDataNow(showSpinnerIfEmpty: Boolean) {
        val state = _uiState.value
        val local = loadPeriodFromStore(state)
        if (local.isNotEmpty()) {
            setLocations(local)
            return
        }
        if (showSpinnerIfEmpty && accumulator.isEmpty()) {
            _uiState.update { it.copy(isLoading = true, error = null) }
        }
        // Custom date range: no server fallback (store should have the data)
        if (state.timePeriod == TimePeriod.CUSTOM) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        when (val r = locationRepository.getLocations(hours = state.timePeriod.hours, limit = PERIOD_LOAD_LIMIT)) {
            is Result.Success -> setLocations(flatten(r.data))
            is Result.Error -> _uiState.update {
                it.copy(isLoading = false, error = if (accumulator.isEmpty()) r.message else null)
            }
        }
    }

    private suspend fun loadPeriodFromStore(state: MapUiState): List<LocationPoint> {
        return if (state.timePeriod == TimePeriod.CUSTOM && state.customDateRange != null) {
            val range = state.customDateRange
            val zone = ZoneId.systemDefault()
            val startMs = range.start.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMs = range.end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            store.timeRange(startMs, endMs, PERIOD_LOAD_LIMIT)
        } else {
            store.periodOverview(state.timePeriod.hours, PERIOD_LOAD_LIMIT)
        }
    }

    private fun setLocations(points: List<LocationPoint>) {
        accumulator.clear()
        lastQueried = null
        points.forEach { accumulator[it.id] = it }
        _uiState.update { it.copy(locations = accumulator.values.toList(), isLoading = false, error = null) }
    }

    // The API groups points under their device; carry that down onto each point
    // so the map can name the device without a second lookup per point.
    private fun flatten(resp: LocationsResponse): List<LocationPoint> =
        resp.devices.flatMap { dev -> dev.locations.map { it.copy(deviceId = dev.deviceId) } }

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
        TimePeriod.H6 -> 6
        TimePeriod.H24 -> 24
        TimePeriod.D7 -> 24 * 7
        TimePeriod.D30 -> 24 * 30
        TimePeriod.D90 -> 24 * 90
        TimePeriod.ALL -> null
        TimePeriod.CUSTOM -> null
    }
