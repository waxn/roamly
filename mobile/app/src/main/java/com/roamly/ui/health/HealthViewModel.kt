package com.roamly.ui.health

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.HealthStatusResponse
import com.roamly.data.prefs.UserPreferences
import com.roamly.data.repository.AuthRepository
import com.roamly.data.repository.HealthRepository
import com.roamly.data.repository.Result
import com.roamly.health.HealthConnectManager
import com.roamly.health.HealthSyncWorker
import com.roamly.health.exerciseLabel
import com.roamly.health.toHealthWorkout
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** One browsable Health Connect exercise session. */
data class WorkoutRow(
    val hcId: String,
    val label: String,
    val exerciseType: Int,
    val startMs: Long,
    val endMs: Long,
    val durationS: Int,
    val steps: Int? = null,
    val distanceM: Double? = null,
    val caloriesKcal: Double? = null,
    val totalsLoaded: Boolean = false,
    val imported: Boolean = false,
    val importing: Boolean = false,
)

data class HealthUiState(
    val availability: HealthConnectManager.Availability = HealthConnectManager.Availability.UNAVAILABLE,
    val hasPermissions: Boolean = false,
    val hasHistoryPermission: Boolean = false,
    val hasBackgroundPermission: Boolean = false,
    /** True once a permission request came back with nothing changed — Health
     *  Connect will not show its dialog again, so the UI must offer Settings. */
    val permissionPermanentlyDenied: Boolean = false,
    val syncEnabled: Boolean = false,
    val backfillDays: Int = 30,
    val lastSyncTime: Long = 0L,
    val lastSyncSuccess: Boolean = false,
    val lastSyncCount: Int = 0,
    val lastSyncError: String = "",
    val status: HealthStatusResponse? = null,
    val busy: Boolean = false,
    val message: String? = null,

    val workoutsLoading: Boolean = false,
    val workouts: List<WorkoutRow> = emptyList(),
    val workoutWindowDays: Long = 90,
)

@HiltViewModel
class HealthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val health: HealthConnectManager,
    private val repo: HealthRepository,
    private val auth: AuthRepository,
    private val prefs: UserPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(HealthUiState())
    val state: StateFlow<HealthUiState> = _state.asStateFlow()

    init {
        collect(prefs.healthEnabled) { v -> _state.update { it.copy(syncEnabled = v) } }
        collect(prefs.healthBackfillDays) { v -> _state.update { it.copy(backfillDays = v) } }
        collect(prefs.healthLastSyncTime) { v -> _state.update { it.copy(lastSyncTime = v) } }
        collect(prefs.healthLastSyncSuccess) { v -> _state.update { it.copy(lastSyncSuccess = v) } }
        collect(prefs.healthLastSyncCount) { v -> _state.update { it.copy(lastSyncCount = v) } }
        collect(prefs.healthLastSyncError) { v -> _state.update { it.copy(lastSyncError = v) } }
        refresh()
    }

    private fun <T> collect(flow: Flow<T>, update: (T) -> Unit) {
        viewModelScope.launch { flow.collect { update(it) } }
    }

    // Pass-throughs so the composable never has to reach past the ViewModel for
    // Health Connect plumbing.
    fun permissionContract() = health.permissionContract()
    fun requiredPermissions() = health.requiredPermissions
    fun historyPermission() = health.historyPermission
    fun backgroundPermission() = health.backgroundPermission
    fun settingsIntent() = health.settingsIntent()

    fun refresh() {
        viewModelScope.launch {
            val availability = health.availability()
            _state.update { it.copy(availability = availability) }
            if (availability == HealthConnectManager.Availability.AVAILABLE) {
                refreshPermissions()
            }
            loadStatus()
        }
    }

    private suspend fun refreshPermissions() {
        val granted = health.grantedPermissions()
        _state.update {
            it.copy(
                hasPermissions = granted.containsAll(health.requiredPermissions),
                hasHistoryPermission = granted.contains(health.historyPermission),
                hasBackgroundPermission = granted.contains(health.backgroundPermission),
            )
        }
    }

    private suspend fun loadStatus() {
        when (val r = repo.status()) {
            is Result.Success -> _state.update { it.copy(status = r.data) }
            is Result.Error -> Unit  // status is a nicety; a failure shouldn't shout
        }
    }

    /**
     * Result of a Health Connect permission request.
     *
     * Health Connect shows its dialog at most twice per install; after that the
     * launcher returns immediately with the same set it already had. Detecting
     * that "nothing changed" is the only way to know the user must be sent to
     * Health Connect's own settings instead of shown a dialog that will never
     * appear again.
     */
    fun onPermissionsResult(granted: Set<String>) {
        viewModelScope.launch {
            val had = _state.value.hasPermissions
            val nowHas = granted.containsAll(health.requiredPermissions)
            refreshPermissions()
            _state.update {
                it.copy(permissionPermanentlyDenied = !nowHas && !had && granted.isEmpty())
            }
            if (nowHas && !had) enableSync()
        }
    }

    fun onOptionalPermissionsResult() {
        viewModelScope.launch {
            refreshPermissions()
            // The backfill depth follows the history permission — without it
            // Health Connect only answers for the trailing 30 days anyway.
            val days = if (_state.value.hasHistoryPermission) 365 else 30
            prefs.setHealthBackfillDays(days)
        }
    }

    fun setSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) enableSync() else disableSync()
        }
    }

    private suspend fun enableSync() {
        _state.update { it.copy(busy = true, message = null) }
        // The app only ever mints an API key on the start-tracking path, so a
        // health-only user would otherwise authenticate by session cookie alone —
        // and the session guard clears that on expiry, silently killing the
        // background sync. Idempotent server-side.
        when (val r = auth.ensureApiKey()) {
            is Result.Error -> {
                _state.update { it.copy(busy = false, message = "Could not set up syncing: ${r.message}") }
                return
            }
            is Result.Success -> Unit
        }
        prefs.setHealthEnabled(true)
        prefs.setHealthBackfillDays(if (_state.value.hasHistoryPermission) 365 else 30)
        HealthSyncWorker.schedulePeriodic(context)
        HealthSyncWorker.scheduleNow(context)
        _state.update { it.copy(busy = false, message = "Syncing started") }
    }

    private suspend fun disableSync() {
        prefs.setHealthEnabled(false)
        HealthSyncWorker.cancelPeriodic(context)
        _state.update { it.copy(message = "Syncing stopped") }
    }

    fun syncNow() {
        HealthSyncWorker.scheduleNow(context, replace = true)
        _state.update { it.copy(message = "Sync queued") }
    }

    fun deleteAllServerData() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            when (repo.deleteAll()) {
                is Result.Success -> {
                    // The cursors have to go too, or the next incremental sync
                    // only reports what changed since the deletion and the
                    // history never comes back.
                    prefs.resetHealthSyncState()
                    _state.update { it.copy(busy = false, message = "Health data deleted") }
                    loadStatus()
                }
                is Result.Error -> _state.update {
                    it.copy(busy = false, message = "Could not delete health data")
                }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    // ── Workout browsing / import ──────────────────────────────────────────

    fun loadWorkouts(windowDays: Long = _state.value.workoutWindowDays) {
        viewModelScope.launch {
            if (health.availability() != HealthConnectManager.Availability.AVAILABLE) return@launch
            _state.update { it.copy(workoutsLoading = true, workoutWindowDays = windowDays) }

            val end = Instant.now()
            val start = end.minus(windowDays, ChronoUnit.DAYS)
            val sessions = try {
                health.exerciseSessions(start, end)
            } catch (e: Exception) {
                _state.update { it.copy(workoutsLoading = false, message = "Could not read workouts") }
                return@launch
            }

            // The server is the source of truth for what's imported, so browsing
            // on a second phone agrees and a server-side delete shows up here.
            val imported = when (val r = repo.importedWorkoutIds()) {
                is Result.Success -> r.data.hcIds.toSet()
                is Result.Error -> emptySet()
            }

            val rows = sessions.sortedByDescending { it.startTime }.map { s ->
                WorkoutRow(
                    hcId = s.metadata.id,
                    label = s.title?.takeIf { it.isNotBlank() } ?: exerciseLabel(s.exerciseType),
                    exerciseType = s.exerciseType,
                    startMs = s.startTime.toEpochMilli(),
                    endMs = s.endTime.toEpochMilli(),
                    durationS = (s.endTime.epochSecond - s.startTime.epochSecond).toInt(),
                    imported = imported.contains(s.metadata.id),
                )
            }
            _state.update { it.copy(workoutsLoading = false, workouts = rows) }
        }
    }

    /**
     * Fill in one row's totals. Called as the row scrolls into view rather than
     * up front — aggregating a whole window of sessions at once is slow enough
     * to be noticeable.
     */
    fun loadWorkoutTotals(hcId: String) {
        val row = _state.value.workouts.firstOrNull { it.hcId == hcId } ?: return
        if (row.totalsLoaded) return
        viewModelScope.launch {
            val totals = health.sessionTotals(
                Instant.ofEpochMilli(row.startMs), Instant.ofEpochMilli(row.endMs))
            _state.update { st ->
                st.copy(workouts = st.workouts.map {
                    if (it.hcId == hcId) it.copy(
                        steps = totals.steps,
                        distanceM = totals.distanceM,
                        caloriesKcal = totals.caloriesKcal,
                        totalsLoaded = true,
                    ) else it
                })
            }
        }
    }

    fun importWorkout(hcId: String) {
        viewModelScope.launch {
            val end = Instant.now()
            val start = end.minus(_state.value.workoutWindowDays, ChronoUnit.DAYS)
            val session = try {
                health.exerciseSessions(start, end).firstOrNull { it.metadata.id == hcId }
            } catch (e: Exception) {
                null
            } ?: return@launch

            setImporting(hcId, true)
            val totals = health.sessionTotals(session.startTime, session.endTime)
            val deviceId = prefs.deviceId.first()?.trim().orEmpty()
            val dto = session.toHealthWorkout(deviceId, totals)

            when (repo.importWorkouts(listOf(dto))) {
                is Result.Success -> {
                    _state.update { st ->
                        st.copy(workouts = st.workouts.map {
                            if (it.hcId == hcId) it.copy(imported = true, importing = false) else it
                        }, message = "Workout imported")
                    }
                    loadStatus()
                }
                is Result.Error -> {
                    setImporting(hcId, false)
                    _state.update { it.copy(message = "Could not import that workout") }
                }
            }
        }
    }

    private fun setImporting(hcId: String, value: Boolean) {
        _state.update { st ->
            st.copy(workouts = st.workouts.map {
                if (it.hcId == hcId) it.copy(importing = value) else it
            })
        }
    }
}
