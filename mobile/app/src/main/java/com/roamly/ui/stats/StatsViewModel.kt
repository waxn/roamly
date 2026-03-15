package com.roamly.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.CountryVisit
import com.roamly.data.api.CityVisit
import com.roamly.data.api.StatsResponse
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
    val topCountries: List<CountryVisit> = emptyList(),
    val topCities: List<CityVisit> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val locationRepository: LocationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val r = locationRepository.getStats()) {
                is Result.Success -> _uiState.update { it.copy(stats = r.data, isLoading = false) }
                is Result.Error -> _uiState.update { it.copy(error = r.message, isLoading = false) }
            }
            when (val r = locationRepository.getVisits()) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        topCountries = r.data.countries.take(10),
                        topCities = r.data.cities.take(15)
                    )
                }
                is Result.Error -> { /* visits are optional */ }
            }
        }
    }
}
