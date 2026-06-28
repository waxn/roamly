package com.roamly.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.CountryVisit
import com.roamly.data.api.CityVisit
import com.roamly.data.api.StatsResponse
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

data class StatsUiState(
    val stats: StatsResponse? = null,
    val yearly: YearlyOverviewResponse? = null,
    val topCountries: List<CountryVisit> = emptyList(),
    val topCities: List<CityVisit> = emptyList(),
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState

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
                    _uiState.update { it.copy(topCountries = cache.topCountries, topCities = cache.topCities) }
                }
                is Result.Error -> { }
            }
            if (cache.stats != null) cache.hasData = true
        }
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
