package com.roamly.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.roamly.data.prefs.UserPreferences
import com.roamly.tracking.LocationTrackingService
import com.roamly.tracking.UploadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    // Connection
    val serverUrl: String = "",
    val username: String = "",
    val deviceId: String = "",
    val apiKey: String = "",
    val darkMode: Boolean = false,
    // Tracking
    val isTracking: Boolean = false,
    val trackingIntervalSecs: Int = 30,
    val maxAccuracyM: Int = 100,
    // Sync status
    val lastSyncTime: Long = 0L,
    val lastSyncSuccess: Boolean = false,
    val lastSyncCount: Int = 0,
    val lastSyncError: String = "",
    val isSyncing: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferences,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        collect(prefs.serverUrl)             { v -> _state.update { it.copy(serverUrl = v ?: "") } }
        collect(prefs.username)              { v -> _state.update { it.copy(username = v ?: "") } }
        collect(prefs.deviceId)              { v -> _state.update { it.copy(deviceId = v ?: "") } }
        collect(prefs.apiKey)                { v -> _state.update { it.copy(apiKey = v ?: "") } }
        collect(prefs.darkMode)              { v -> _state.update { it.copy(darkMode = v ?: false) } }
        collect(prefs.isTrackingActive)      { v -> _state.update { it.copy(isTracking = v) } }
        collect(prefs.trackingIntervalSecs)  { v -> _state.update { it.copy(trackingIntervalSecs = v) } }
        collect(prefs.maxAccuracyM)          { v -> _state.update { it.copy(maxAccuracyM = v) } }
        collect(prefs.lastSyncTime)          { v -> _state.update { it.copy(lastSyncTime = v) } }
        collect(prefs.lastSyncSuccess)       { v -> _state.update { it.copy(lastSyncSuccess = v) } }
        collect(prefs.lastSyncCount)         { v -> _state.update { it.copy(lastSyncCount = v) } }
        collect(prefs.lastSyncError)         { v -> _state.update { it.copy(lastSyncError = v) } }

        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosByTagFlow(UploadWorker.ONETIME_TAG)
                .collect { infos ->
                    val syncing = infos.any {
                        it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                    }
                    _state.update { it.copy(isSyncing = syncing) }
                }
        }
    }

    private fun <T> collect(flow: Flow<T>, update: (T) -> Unit) {
        viewModelScope.launch { flow.collect { update(it) } }
    }

    // ── Actions ────────────────────────────────────────────────────────────

    fun toggleTracking() {
        if (_state.value.isTracking) LocationTrackingService.stop(context)
        else LocationTrackingService.start(context)
    }

    fun syncNow() = UploadWorker.scheduleNow(context)

    fun setDeviceId(id: String) {
        viewModelScope.launch { prefs.setDeviceId(id.trim()) }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch { prefs.setApiKey(key.trim()) }
    }

    fun setTrackingIntervalSecs(secs: Int) {
        viewModelScope.launch { prefs.setTrackingIntervalSecs(secs.coerceIn(5, 3600)) }
    }

    fun setMaxAccuracyM(metres: Int) {
        viewModelScope.launch { prefs.setMaxAccuracyM(metres.coerceAtLeast(1)) }
    }

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setDarkMode(enabled) }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            LocationTrackingService.stop(context)
            prefs.clear()
            onLoggedOut()
        }
    }
}
