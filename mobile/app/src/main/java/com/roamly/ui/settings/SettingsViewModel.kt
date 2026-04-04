package com.roamly.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.prefs.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val serverUrl: String = "",
    val username: String = "",
    val deviceId: String = "",
    val darkMode: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferences
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        prefs.serverUrl,
        prefs.username,
        prefs.deviceId,
        prefs.darkMode
    ) { url, username, deviceId, darkMode ->
        SettingsUiState(
            serverUrl = url ?: "",
            username = username ?: "",
            deviceId = deviceId ?: "",
            darkMode = darkMode ?: false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setDarkMode(enabled) }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            prefs.clear()
            onLoggedOut()
        }
    }
}
