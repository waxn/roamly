package com.roamly.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.CountryVisit
import com.roamly.data.api.CityVisit
import com.roamly.data.api.StatsResponse
import com.roamly.data.api.YearlyOverviewResponse
import com.roamly.data.repository.LocationRepository
import com.roamly.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StatsUiState(
    val stats: StatsResponse? = null,
    val yearly: YearlyOverviewResponse? = null,
    val topCountries: List<CountryVisit> = emptyList(),
    val topCities: List<CityVisit> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val locationRepository: LocationRepository,
    private val cache: StatsCache,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState

    init {
        // Paint last-known data instantly, then refresh quietly in the background.
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

    fun load() {
        // Only show the skeleton when we have nothing cached to show.
        _uiState.update { it.copy(isLoading = !cache.hasData, error = null) }
        viewModelScope.launch {
            when (val r = locationRepository.getStats()) {
                is Result.Success -> {
                    cache.stats = r.data
                    _uiState.update { it.copy(stats = r.data, isLoading = false) }
                }
                is Result.Error -> _uiState.update { it.copy(error = if (cache.hasData) null else r.message, isLoading = false) }
            }
            when (val r = locationRepository.getYearlyOverview()) {
                is Result.Success -> {
                    cache.yearly = r.data
                    _uiState.update { it.copy(yearly = r.data) }
                }
                is Result.Error -> { /* yearly is optional */ }
            }
            when (val r = locationRepository.getVisits()) {
                is Result.Success -> {
                    cache.topCountries = r.data.countries.take(10)
                    cache.topCities = r.data.cities.take(15)
                    cache.hasData = true
                    _uiState.update { it.copy(topCountries = cache.topCountries, topCities = cache.topCities) }
                }
                is Result.Error -> { /* visits are optional */ }
            }
            // Mark cache valid even if visits failed but stats succeeded.
            if (cache.stats != null) cache.hasData = true
        }
    }
}
