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
    val darkMode: Boolean = false,
    // Tracking
    val isTracking: Boolean = false,
    val trackingMode: String = "balanced",
    val maxAccuracyM: Int = 100,
    val minDisplacementM: Int = 5,
    // Sync status
    val lastSyncTime: Long = 0L,
    val lastSyncSuccess: Boolean = false,
    val lastSyncCount: Int = 0,
    val lastSyncError: String = "",
    val isSyncing: Boolean = false,
    // Cache counts
    val unsyncedCount: Int = 0,
    val totalCount: Int = 0,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferences,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        // Collect every pref individually and update the relevant state field
        collectPref(prefs.serverUrl)      { v -> _state.update { it.copy(serverUrl = v ?: "") } }
        collectPref(prefs.username)       { v -> _state.update { it.copy(username = v ?: "") } }
        collectPref(prefs.deviceId)       { v -> _state.update { it.copy(deviceId = v ?: "") } }
        collectPref(prefs.darkMode)       { v -> _state.update { it.copy(darkMode = v ?: false) } }
        collectPref(prefs.isTrackingActive) { v -> _state.update { it.copy(isTracking = v) } }
        collectPref(prefs.trackingMode)   { v -> _state.update { it.copy(trackingMode = v) } }
        collectPref(prefs.maxAccuracyM)   { v -> _state.update { it.copy(maxAccuracyM = v) } }
        collectPref(prefs.minDisplacementM) { v -> _state.update { it.copy(minDisplacementM = v) } }
        collectPref(prefs.lastSyncTime)   { v -> _state.update { it.copy(lastSyncTime = v) } }
        collectPref(prefs.lastSyncSuccess){ v -> _state.update { it.copy(lastSyncSuccess = v) } }
        collectPref(prefs.lastSyncCount)  { v -> _state.update { it.copy(lastSyncCount = v) } }
        collectPref(prefs.lastSyncError)  { v -> _state.update { it.copy(lastSyncError = v) } }

        // Observe WorkManager to show spinner while upload is running
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

    private fun <T> collectPref(flow: Flow<T>, update: (T) -> Unit) {
        viewModelScope.launch { flow.collect { update(it) } }
    }

    // ── Actions ────────────────────────────────────────────────────────────

    fun toggleTracking() {
        val current = _state.value.isTracking
        if (current) {
            LocationTrackingService.stop(context)
        } else {
            LocationTrackingService.start(context)
        }
        // isTracking state updates via prefs flow when service writes setTrackingActive()
    }

    fun syncNow() {
        UploadWorker.scheduleNow(context)
    }

    fun setTrackingMode(mode: String) {
        viewModelScope.launch { prefs.setTrackingMode(mode) }
    }

    fun setMaxAccuracyM(metres: Int) {
        viewModelScope.launch { prefs.setMaxAccuracyM(metres) }
    }

    fun setMinDisplacementM(metres: Int) {
        viewModelScope.launch { prefs.setMinDisplacementM(metres) }
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
