package com.roamly.ui.pals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.CreatePalRequest
import com.roamly.data.api.PalResponse
import com.roamly.data.api.PalsListResponse
import com.roamly.data.cache.DiskCache
import com.roamly.data.repository.PalRepository
import com.roamly.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PalsUiState(
    val pals: List<PalResponse> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showCreateDialog: Boolean = false,
)

@HiltViewModel
class PalsViewModel @Inject constructor(
    private val repository: PalRepository,
    private val disk: DiskCache,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PalsUiState())
    val uiState: StateFlow<PalsUiState> = _uiState

    init {
        // Paint the last-known list instantly (cold start / offline), then refresh.
        val cached: PalsListResponse? = disk.get(DiskCache.PALS, PalsListResponse::class.java)
        if (cached != null) _uiState.update { it.copy(pals = cached.pals) }
        loadPals()
    }

    fun loadPals() {
        _uiState.update { it.copy(isLoading = it.pals.isEmpty(), error = null) }
        viewModelScope.launch {
            when (val result = repository.getPals()) {
                is Result.Success -> {
                    disk.put(DiskCache.PALS, result.data)
                    _uiState.update { it.copy(pals = result.data.pals, isLoading = false) }
                }
                is Result.Error -> _uiState.update { it.copy(error = if (it.pals.isEmpty()) result.message else null, isLoading = false) }
            }
        }
    }

    fun showCreateDialog() = _uiState.update { it.copy(showCreateDialog = true) }
    fun hideCreateDialog() = _uiState.update { it.copy(showCreateDialog = false) }

    fun createPal(name: String, description: String, startDate: String, endDate: String) {
        if (name.isBlank() || startDate.isBlank() || endDate.isBlank()) return
        viewModelScope.launch {
            when (repository.createPal(CreatePalRequest(name, description, startDate, endDate))) {
                is Result.Success -> {
                    _uiState.update { it.copy(showCreateDialog = false) }
                    loadPals()
                }
                is Result.Error -> _uiState.update { it.copy(error = "Failed to create") }
            }
        }
    }
}
