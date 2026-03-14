package com.roamly.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.prefs.UserPreferences
import com.roamly.service.LocationTrackingService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val serverUrl: String = "",
    val apiKey: String = "",
    val deviceId: String = "",
    val username: String = "",
    val trackingEnabled: Boolean = false,
    val trackingIntervalSeconds: Int = 30
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferences,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        prefs.serverUrl,
        prefs.apiKey,
        prefs.deviceId,
        prefs.username,
        prefs.trackingEnabled,
        prefs.trackingIntervalSeconds
    ) { values ->
        SettingsUiState(
            serverUrl = values[0] as? String ?: "",
            apiKey = values[1] as? String ?: "",
            deviceId = values[2] as? String ?: "",
            username = values[3] as? String ?: "",
            trackingEnabled = values[4] as? Boolean ?: false,
            trackingIntervalSeconds = values[5] as? Int ?: 30
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTrackingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setTrackingEnabled(enabled)
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = if (enabled) LocationTrackingService.ACTION_START else LocationTrackingService.ACTION_STOP
            }
            if (enabled) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    fun setTrackingInterval(seconds: Int) {
        viewModelScope.launch { prefs.setTrackingInterval(seconds) }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            // Stop tracking first
            context.startService(Intent(context, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_STOP
            })
            prefs.clear()
            onLoggedOut()
        }
    }
}
