package com.roamly.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.SearchResponse
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

private const val DEBOUNCE_MS = 350L

data class SearchUiState(
    val query: String = "",
    val result: SearchResponse? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: LocationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState

    private var searchJob: Job? = null

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
        searchJob?.cancel()
        if (value.isBlank()) {
            _uiState.update { it.copy(result = null, isLoading = false, error = null, hasSearched = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            runSearch(value)
        }
    }

    fun retry() {
        val q = _uiState.value.query
        if (q.isNotBlank()) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch { runSearch(q) }
        }
    }

    private suspend fun runSearch(query: String) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        when (val r = repository.search(query)) {
            is Result.Success -> _uiState.update {
                it.copy(result = r.data, isLoading = false, hasSearched = true)
            }
            is Result.Error -> _uiState.update {
                it.copy(error = r.message, isLoading = false, hasSearched = true)
            }
        }
    }
}
