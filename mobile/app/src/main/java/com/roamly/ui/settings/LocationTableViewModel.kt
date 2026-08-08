package com.roamly.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.LocationTableRow
import com.roamly.data.repository.LocationRepository
import com.roamly.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LocationTableUiState(
    val rows: List<LocationTableRow> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    // Filters / sort — mirror the web /data/ page.
    val range: String = "24",              // "24"/"72"/"168"/"720"/"all"
    val deviceId: String? = null,          // null = all devices
    val sortKey: String = "timestamp",
    val sortDir: String = "desc",
    // Cursor state (keyset pagination, matching locations_api's contract).
    val hasMore: Boolean = false,
    val nextBeforeValue: String? = null,
    val nextBeforeId: Int? = null,
    // device_id -> display name, for the filter dropdown.
    val devices: Map<String, String> = emptyMap(),
)

private const val PAGE_SIZE = 1000

@HiltViewModel
class LocationTableViewModel @Inject constructor(
    private val repository: LocationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocationTableUiState())
    val uiState: StateFlow<LocationTableUiState> = _uiState

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(devices = repository.getDeviceNames()) }
        }
        load(reset = true)
    }

    /** First page (or reload after a filter/sort change). */
    fun load(reset: Boolean = true) {
        val s = _uiState.value
        _uiState.update { it.copy(isLoading = true, error = null, rows = if (reset) emptyList() else it.rows) }
        viewModelScope.launch {
            when (val res = repository.getLocationsTable(
                range = s.range, deviceId = s.deviceId,
                sortKey = s.sortKey, sortDir = s.sortDir,
                limit = PAGE_SIZE, offset = 0,
            )) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        rows = res.data.locations,
                        // Trust the server's echoed sort over local intent — invalid
                        // params silently no-op server-side.
                        sortKey = res.data.sortKey,
                        sortDir = res.data.sortDir,
                        hasMore = res.data.hasMore,
                        nextBeforeValue = res.data.nextBeforeValue,
                        nextBeforeId = res.data.nextBeforeId,
                        isLoading = false,
                    )
                }
                is Result.Error -> _uiState.update { it.copy(isLoading = false, error = res.message) }
            }
        }
    }

    /** Append the next page using the keyset cursor. No-op if nothing more or a
     *  page is already in flight. */
    fun loadMore() {
        val s = _uiState.value
        if (!s.hasMore || s.isLoadingMore || s.isLoading || s.nextBeforeValue == null) return
        _uiState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            when (val res = repository.getLocationsTable(
                range = s.range, deviceId = s.deviceId,
                sortKey = s.sortKey, sortDir = s.sortDir,
                limit = PAGE_SIZE,
                beforeValue = s.nextBeforeValue, beforeId = s.nextBeforeId,
            )) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        rows = it.rows + res.data.locations,
                        hasMore = res.data.hasMore,
                        nextBeforeValue = res.data.nextBeforeValue,
                        nextBeforeId = res.data.nextBeforeId,
                        isLoadingMore = false,
                    )
                }
                is Result.Error -> _uiState.update { it.copy(isLoadingMore = false, error = res.message) }
            }
        }
    }

    fun setRange(range: String) {
        if (range == _uiState.value.range) return
        _uiState.update { it.copy(range = range) }
        load(reset = true)
    }

    fun setDevice(deviceId: String?) {
        if (deviceId == _uiState.value.deviceId) return
        _uiState.update { it.copy(deviceId = deviceId) }
        load(reset = true)
    }

    /** Toggle direction if the same key is picked, else default desc for
     *  timestamp/speed/battery and asc otherwise (matches web sortBy()). */
    fun setSort(key: String) {
        val s = _uiState.value
        val dir = if (s.sortKey == key) {
            if (s.sortDir == "asc") "desc" else "asc"
        } else {
            if (key in listOf("timestamp", "speed", "battery")) "desc" else "asc"
        }
        _uiState.update { it.copy(sortKey = key, sortDir = dir) }
        load(reset = true)
    }

    fun deleteRow(id: Int) {
        viewModelScope.launch {
            when (val res = repository.deleteLocation(id)) {
                is Result.Success -> _uiState.update { it.copy(rows = it.rows.filterNot { r -> r.id == id }) }
                is Result.Error -> _uiState.update { it.copy(error = res.message) }
            }
        }
    }
}
