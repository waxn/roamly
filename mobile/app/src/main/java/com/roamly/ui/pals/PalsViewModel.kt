package com.roamly.ui.pals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.PalResponse
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
    val error: String? = null
)

@HiltViewModel
class PalsViewModel @Inject constructor(
    private val repository: PalRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PalsUiState())
    val uiState: StateFlow<PalsUiState> = _uiState

    init { loadPals() }

    fun loadPals() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.getPals()) {
                is Result.Success -> _uiState.update { it.copy(pals = result.data.pals, isLoading = false) }
                is Result.Error -> _uiState.update { it.copy(error = result.message, isLoading = false) }
            }
        }
    }
}
