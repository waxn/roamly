package com.roamly.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.roamly.data.cache.DiskCache
import com.roamly.data.prefs.UserPreferences
import com.roamly.tracking.CsvPointLogger
import com.roamly.tracking.LocationTrackingService
import com.roamly.tracking.TrackingCoordinator
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
    /** null = follow system; the switch falls back to the system theme for display. */
    val darkModeOverride: Boolean? = null,
    // Tracking
    val isTracking: Boolean = false,
    val trackingIntervalSecs: Int = 30,
    val maxAccuracyM: Int = 100,
    val minDistanceM: Int = 10,
    val locationPriority: String = "auto",
    val autoStartTracking: Boolean = true,
    val syncOnMobileData: Boolean = true,
    val batteryOptimizationDisabled: Boolean = false,
    val csvPath: String = "",
    // Sync status
    val lastSyncTime: Long = 0L,
    val lastSyncSuccess: Boolean = false,
    val lastSyncCount: Int = 0,
    val lastSyncError: String = "",
    val isSyncing: Boolean = false,
    // API key (mandatory for tracking) — prompt shown when starting without one
    val apiKeyRequired: Boolean = false,
    val apiKeyBusy: Boolean = false,
    val apiKeyError: String? = null,
    // One-shot: raised when tracking actually starts and the app isn't yet exempt
    // from battery optimization, so the screen can nudge the user to grant it.
    val promptBatteryExemption: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferences,
    private val disk: DiskCache,
    private val store: com.roamly.data.local.LocationStore,
    private val authRepository: com.roamly.data.repository.AuthRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        collect(prefs.serverUrl)             { v -> _state.update { it.copy(serverUrl = v ?: "") } }
        collect(prefs.username)              { v -> _state.update { it.copy(username = v ?: "") } }
        collect(prefs.deviceId)              { v -> _state.update { it.copy(deviceId = v ?: "") } }
        collect(prefs.apiKey)                { v -> _state.update { it.copy(apiKey = v ?: "") } }
        collect(prefs.darkMode)              { v -> _state.update { it.copy(darkModeOverride = v) } }
        collect(prefs.isTrackingActive)      { v -> _state.update { it.copy(isTracking = v) } }
        collect(prefs.trackingIntervalSecs)  { v -> _state.update { it.copy(trackingIntervalSecs = v) } }
        collect(prefs.maxAccuracyM)          { v -> _state.update { it.copy(maxAccuracyM = v) } }
        collect(prefs.minDistanceM)          { v -> _state.update { it.copy(minDistanceM = v) } }
        collect(prefs.locationPriority)      { v -> _state.update { it.copy(locationPriority = v) } }
        collect(prefs.autoStartTracking)     { v -> _state.update { it.copy(autoStartTracking = v) } }
        collect(prefs.syncOnMobileData)      { v -> _state.update { it.copy(syncOnMobileData = v) } }
        collect(prefs.lastSyncTime)          { v -> _state.update { it.copy(lastSyncTime = v) } }
        collect(prefs.lastSyncSuccess)       { v -> _state.update { it.copy(lastSyncSuccess = v) } }
        collect(prefs.lastSyncCount)         { v -> _state.update { it.copy(lastSyncCount = v) } }
        collect(prefs.lastSyncError)         { v -> _state.update { it.copy(lastSyncError = v) } }
        _state.update {
            it.copy(
                batteryOptimizationDisabled = TrackingCoordinator.isIgnoringBatteryOptimizations(context),
                csvPath = CsvPointLogger.csvPath(context),
            )
        }

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
        if (_state.value.isTracking) stopTrackingCompletely()
        else startTracking()
    }

    private fun startTracking() {
        // An API key is mandatory for tracking (it authenticates location uploads).
        // If none is set, prompt the user to create one in-app or enter their own
        // instead of silently starting a tracker that can never sync.
        if (_state.value.apiKey.isBlank()) {
            _state.update { it.copy(apiKeyRequired = true, apiKeyError = null) }
            return
        }
        doStartTracking()
    }

    private fun doStartTracking() {
        // Starting tracking implies it should survive reboots by default; the user
        // can still turn "start on boot" off afterward for a start-now-only session.
        viewModelScope.launch { prefs.setAutoStartTracking(true) }
        LocationTrackingService.start(context)
        // The Doze exemption is the single biggest factor in reliable screen-off
        // tracking, so nudge for it the moment tracking starts — but only if it
        // isn't already granted. Fires for every real start path (direct or after
        // creating the API key), never on stop or a plain settings visit.
        if (!TrackingCoordinator.isIgnoringBatteryOptimizations(context)) {
            _state.update { it.copy(promptBatteryExemption = true) }
        }
    }

    fun dismissBatteryPrompt() {
        _state.update { it.copy(promptBatteryExemption = false) }
    }

    /** Create (or reuse) the account's single durable tracking key, then continue
     *  starting tracking. Idempotent — never spawns duplicate keys. */
    fun createApiKey() {
        viewModelScope.launch {
            _state.update { it.copy(apiKeyBusy = true, apiKeyError = null) }
            when (val result = authRepository.ensureApiKey()) {
                is com.roamly.data.repository.Result.Success -> {
                    _state.update { it.copy(apiKeyBusy = false, apiKeyRequired = false) }
                    doStartTracking()
                }
                is com.roamly.data.repository.Result.Error ->
                    _state.update { it.copy(apiKeyBusy = false, apiKeyError = result.message) }
            }
        }
    }

    fun dismissApiKeyRequired() {
        _state.update { it.copy(apiKeyRequired = false, apiKeyError = null) }
    }

    /** The Stop button (after its confirmation). Tears the whole stack down so
     *  tracking is completely off and stays off across reboot. */
    fun stopTrackingCompletely() {
        viewModelScope.launch { TrackingCoordinator.stopCompletely(context, prefs) }
    }

    fun syncNow() {
        viewModelScope.launch {
            UploadWorker.scheduleNow(context, prefs.syncOnMobileData.first())
        }
    }

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

    fun setMinDistanceM(metres: Int) {
        viewModelScope.launch { prefs.setMinDistanceM(metres.coerceIn(0, 1000)) }
    }

    fun setLocationPriority(priority: String) {
        viewModelScope.launch { prefs.setLocationPriority(priority) }
    }

    fun setAutoStartTracking(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setAutoStartTracking(enabled)
            if (enabled) TrackingCoordinator.resumeTrackingIfNeeded(context, prefs)
        }
    }

    fun setSyncOnMobileData(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setSyncOnMobileData(enabled)
            UploadWorker.reschedulePeriodic(context, enabled)
            if (_state.value.isTracking) UploadWorker.scheduleNow(context, enabled)
        }
    }

    fun refreshBatteryOptimizationState() {
        _state.update {
            it.copy(batteryOptimizationDisabled = TrackingCoordinator.isIgnoringBatteryOptimizations(context))
        }
    }

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setDarkMode(enabled) }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            LocationTrackingService.stop(context)
            disk.clearAll()
            store.clear()
            prefs.clear()
            onLoggedOut()
        }
    }
}
