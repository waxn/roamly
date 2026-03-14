package com.roamly.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.prefs.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class LoginUiState(
    val serverUrl: String = "",
    val apiKey: String = "",
    val deviceName: String = "Android",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isLoggedIn: Boolean = false
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val prefs: UserPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState

    val isLoggedIn: StateFlow<Boolean> = combine(
        prefs.serverUrl,
        prefs.apiKey
    ) { url, key -> !url.isNullOrBlank() && !key.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun onServerUrlChange(value: String) = _uiState.update { it.copy(serverUrl = value) }
    fun onApiKeyChange(value: String) = _uiState.update { it.copy(apiKey = value) }
    fun onDeviceNameChange(value: String) = _uiState.update { it.copy(deviceName = value) }

    fun save() {
        val state = _uiState.value
        if (state.serverUrl.isBlank() || state.apiKey.isBlank()) {
            _uiState.update { it.copy(error = "Server URL and API key are required") }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val existingDeviceId = prefs.deviceId.first()
            if (existingDeviceId.isNullOrBlank()) {
                prefs.setDeviceId(UUID.randomUUID().toString())
            }
            prefs.setServerUrl(state.serverUrl)
            prefs.setApiKey(state.apiKey)
            prefs.setUsername(state.deviceName)
            _uiState.update { it.copy(isLoading = false, isLoggedIn = true) }
        }
    }

    fun logout() {
        viewModelScope.launch { prefs.clear() }
    }
}
