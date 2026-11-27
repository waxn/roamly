package com.roamly.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.CountryVisit
import com.roamly.data.api.CityVisit
import com.roamly.data.api.DistanceResponse
import com.roamly.data.api.StatsResponse
import com.roamly.data.api.TransportResponse
import com.roamly.data.api.VisitsResponse
import com.roamly.data.api.YearlyOverviewResponse
import com.roamly.data.cache.DiskCache
import com.roamly.data.local.LocationStore
import com.roamly.data.repository.LocationRepository
import com.roamly.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class DayCount(val day: Int, val points: Int)

/** The window every section below the lifetime hero follows. */
enum class StatsRange(val label: String, val days: Int?) {
    Week("7d", 7), Month("30d", 30), Quarter("90d", 90), Year("1y", 365), All("All", null);

    /** First and last local day of the window, or null for all time. */
    fun window(today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate>? =
        days?.let { today.minusDays(it - 1L) to today }
}

data class StatsUiState(
    val stats: StatsResponse? = null,
    val yearly: YearlyOverviewResponse? = null,
    val topCountries: List<CountryVisit> = emptyList(),
    val topCities: List<CityVisit> = emptyList(),
    val range: StatsRange = StatsRange.All,
    /** Totals for just the chosen range (null for All, where the hero says it). */
    val rangeStats: StatsResponse? = null,
    val distance: DistanceResponse? = null,
    val transport: TransportResponse? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedMonth: Int? = null,
    val monthDrilldown: List<DayCount> = emptyList(),
    val drilldownLoading: Boolean = false,
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val locationRepository: LocationRepository,
    private val cache: StatsCache,
    private val disk: DiskCache,
    private val store: LocationStore,
    private val api: com.roamly.data.api.RoamlyApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState

    private var distanceAll: DistanceResponse? = null
    private var transportAll: TransportResponse? = null
    private var rangeJob: kotlinx.coroutines.Job? = null

    init {
        if (!cache.hasData) hydrateFromDisk()
        if (cache.hasData) {
            _uiState.update {
                it.copy(
                    stats = cache.stats,
                    yearly = cache.yearly,
                    topCountries = cache.topCountries,
                    topCities = cache.topCities,
                    isLoading = false,
                )
            }
        }
        load()
    }

    private fun hydrateFromDisk() {
        val stats: StatsResponse? = disk.get(DiskCache.STATS, StatsResponse::class.java)
        val yearly: YearlyOverviewResponse? = disk.get(DiskCache.STATS_YEARLY, YearlyOverviewResponse::class.java)
        val visits: VisitsResponse? = disk.get(DiskCache.VISITS, VisitsResponse::class.java)
        if (stats != null) {
            cache.stats = stats
            cache.yearly = yearly
            cache.topCountries = visits?.countries?.take(10) ?: emptyList()
            cache.topCities = visits?.cities?.take(15) ?: emptyList()
            cache.hasData = true
        }
    }

    fun load() {
        _uiState.update { it.copy(isLoading = !cache.hasData, error = null) }
        // Distance and transport are independent of the rest, so they land as they arrive.
        viewModelScope.launch {
            val d = runCatching { api.getDistance().body() }.getOrNull()
            if (d != null) { distanceAll = d; _uiState.update { it.copy(distance = filterDistance(d, it.range)) } }
        }
        viewModelScope.launch {
            val t = runCatching { api.getTransport().body() }.getOrNull()
            if (t != null) {
                transportAll = t
                _uiState.update { if (it.range == StatsRange.All) it.copy(transport = t) else it }
            }
        }
        viewModelScope.launch {
            when (val r = locationRepository.getStats()) {
                is Result.Success -> {
                    cache.stats = r.data
                    disk.put(DiskCache.STATS, r.data)
                    _uiState.update { it.copy(stats = r.data, isLoading = false) }
                }
                is Result.Error -> _uiState.update { it.copy(error = if (cache.hasData) null else r.message, isLoading = false) }
            }
            when (val r = locationRepository.getYearlyOverview()) {
                is Result.Success -> {
                    cache.yearly = r.data
                    disk.put(DiskCache.STATS_YEARLY, r.data)
                    _uiState.update { it.copy(yearly = r.data) }
                }
                is Result.Error -> { }
            }
            when (val r = locationRepository.getVisits()) {
                is Result.Success -> {
                    cache.topCountries = r.data.countries.take(10)
                    cache.topCities = r.data.cities.take(15)
                    cache.hasData = true
                    disk.put(DiskCache.VISITS, r.data)
                    _uiState.update {
                        if (it.range == StatsRange.All) it.copy(topCountries = cache.topCountries, topCities = cache.topCities) else it
                    }
                }
                is Result.Error -> { }
            }
            if (cache.stats != null) cache.hasData = true
        }
        loadRange()
    }

    /** Pick the window the whole page below the hero follows. */
    fun setRange(range: StatsRange) {
        if (_uiState.value.range == range) return
        _uiState.update {
            it.copy(
                range = range, distance = distanceAll?.let { d -> filterDistance(d, range) },
                // Keep the old lists until the new ones land, except where All has them ready.
                transport = if (range == StatsRange.All) transportAll ?: it.transport else it.transport,
                topCountries = if (range == StatsRange.All) cache.topCountries else it.topCountries,
                topCities = if (range == StatsRange.All) cache.topCities else it.topCities,
                rangeStats = if (range == StatsRange.All) null else it.rangeStats,
                selectedMonth = null, monthDrilldown = emptyList(),
            )
        }
        loadRange()
    }

    /** Fetch what is range-specific: totals, transport split and places. All uses the cached lifetime copies. */
    private fun loadRange() {
        rangeJob?.cancel()
        val range = _uiState.value.range
        val (from, to) = range.window() ?: return
        val start = from.toString(); val end = to.toString()
        rangeJob = viewModelScope.launch {
            launch {
                val st = (locationRepository.getStats(startDate = start, endDate = end) as? Result.Success)?.data
                if (st != null) _uiState.update { if (it.range == range) it.copy(rangeStats = st) else it }
            }
            launch {
                val t = runCatching { api.getTransport(all = null, startDate = start, endDate = end).body() }.getOrNull()
                if (t != null) _uiState.update { if (it.range == range) it.copy(transport = t) else it }
            }
            launch {
                val v = runCatching { api.getVisits(startDate = start, endDate = end).body() }.getOrNull()
                if (v != null) _uiState.update {
                    if (it.range == range) it.copy(topCountries = v.countries.take(10), topCities = v.cities.take(15)) else it
                }
            }
        }
    }

    /** The lifetime daily-distance series cut to [range], totals recomputed to match. */
    private fun filterDistance(d: DistanceResponse, range: StatsRange): DistanceResponse {
        val (from, to) = range.window() ?: return d
        val days = d.days.orEmpty(); val km = d.distances.orEmpty()
        val keep = days.indices.filter { i ->
            val day = runCatching { LocalDate.parse(days[i].take(10)) }.getOrNull()
            day != null && !day.isBefore(from) && !day.isAfter(to)
        }
        val kept = keep.map { km.getOrElse(it) { 0.0 } }
        return DistanceResponse(days = keep.map { days[it] }, distances = kept, totalKm = kept.sum())
    }

    fun selectMonth(month: Int) {
        val current = _uiState.value
        if (current.selectedMonth == month) {
            // Toggle off
            _uiState.update { it.copy(selectedMonth = null, monthDrilldown = emptyList()) }
            return
        }
        _uiState.update { it.copy(selectedMonth = month, drilldownLoading = true, monthDrilldown = emptyList()) }
        val year = current.yearly?.year ?: LocalDate.now().year
        viewModelScope.launch {
            val ym = YearMonth.of(year, month)
            val zone = ZoneId.systemDefault()
            val startMs = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val endMs   = ym.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            // Fetch all points for this month from local store, then bucket by day
            val points = store.timeRange(startMs, endMs, maxPoints = 50_000)
            val byDay = HashMap<Int, Int>()
            for (pt in points) {
                runCatching {
                    val ts = java.time.OffsetDateTime.parse(pt.timestamp)
                        .atZoneSameInstant(zone).toLocalDate()
                    if (ts.monthValue == month && ts.year == year) {
                        byDay[ts.dayOfMonth] = (byDay[ts.dayOfMonth] ?: 0) + 1
                    }
                }
            }
            val drilldown = (1..ym.lengthOfMonth()).map { day -> DayCount(day, byDay[day] ?: 0) }
            _uiState.update { it.copy(drilldownLoading = false, monthDrilldown = drilldown) }
        }
    }
}
