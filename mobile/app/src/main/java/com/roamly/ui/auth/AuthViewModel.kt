package com.roamly.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.cache.DiskCache
import com.roamly.data.prefs.UserPreferences
import com.roamly.data.repository.AuthRepository
import com.roamly.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val prefs: UserPreferences,
    private val authRepository: AuthRepository,
    private val disk: DiskCache,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState

    // Logged in == we have a server + an API key. The API key never expires and the
    // server authenticates it on every request, so the session cookie can lapse
    // without ever signing the user out. This is what makes login "once, forever".
    val isLoggedIn: StateFlow<Boolean?> = combine(
        prefs.serverUrl,
        prefs.apiKey
    ) { url, key -> !url.isNullOrBlank() && !key.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onServerUrlChange(value: String) = _uiState.update { it.copy(serverUrl = value) }
    fun onUsernameChange(value: String) = _uiState.update { it.copy(username = value) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value) }

    fun login() {
        val state = _uiState.value
        if (state.serverUrl.isBlank() || state.username.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(error = "All fields are required") }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = authRepository.login(state.serverUrl, state.username, state.password)) {
                is Result.Success -> _uiState.update { it.copy(isLoading = false) }
                is Result.Error -> _uiState.update { it.copy(isLoading = false, error = result.message) }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            disk.clearAll()
            prefs.clear()
        }
    }
}
