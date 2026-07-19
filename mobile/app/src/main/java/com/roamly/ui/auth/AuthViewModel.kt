package com.roamly.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.cache.DiskCache
import com.roamly.data.local.LocationStore
import com.roamly.data.prefs.UserPreferences
import com.roamly.data.repository.AuthRepository
import com.roamly.data.repository.LoginResult
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
    val error: String? = null,
    // New-device / signup email verification
    val needsVerification: Boolean = false,
    val verifyEmail: String = "",
    val verifyPurpose: String = "",
    val code: String = "",
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val prefs: UserPreferences,
    private val authRepository: AuthRepository,
    private val disk: DiskCache,
    private val store: LocationStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState

    // Logged in == we have a server + a session. Logging in is session-based and does
    // NOT need an API key — the key is only set up later for tracking. (Browsing
    // endpoints authenticate via the session cookie; the Bearer key, once created,
    // also satisfies them, so the app stays usable even if the cookie lapses.)
    val isLoggedIn: StateFlow<Boolean?> = combine(
        prefs.serverUrl,
        prefs.sessionId
    ) { url, sid -> !url.isNullOrBlank() && !sid.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onServerUrlChange(value: String) = _uiState.update { it.copy(serverUrl = value) }
    fun onUsernameChange(value: String) = _uiState.update { it.copy(username = value) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value) }
    fun onCodeChange(value: String) = _uiState.update { it.copy(code = value.filter { c -> c.isDigit() }.take(6)) }

    fun login() {
        val state = _uiState.value
        if (state.serverUrl.isBlank() || state.username.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(error = "All fields are required") }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = authRepository.login(state.serverUrl, state.username, state.password)) {
                is LoginResult.Success -> _uiState.update { it.copy(isLoading = false) }
                is LoginResult.NeedsVerification -> _uiState.update {
                    it.copy(isLoading = false, error = null, needsVerification = true,
                        verifyEmail = result.email, verifyPurpose = result.purpose, code = "")
                }
                is LoginResult.Error -> _uiState.update { it.copy(isLoading = false, error = result.message) }
            }
        }
    }

    fun verify() {
        val state = _uiState.value
        if (state.code.length < 6) {
            _uiState.update { it.copy(error = "Enter the 6-digit code") }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = authRepository.verifyCode(state.code)) {
                is LoginResult.Success -> _uiState.update { it.copy(isLoading = false, needsVerification = false) }
                is LoginResult.NeedsVerification -> _uiState.update { it.copy(isLoading = false) }
                is LoginResult.Error -> _uiState.update { it.copy(isLoading = false, error = result.message) }
            }
        }
    }

    fun cancelVerification() = _uiState.update {
        it.copy(needsVerification = false, code = "", error = null)
    }

    fun logout() {
        viewModelScope.launch {
            disk.clearAll()
            store.clear()
            prefs.clear()
        }
    }
}
