package com.roamly.ui.record

import android.content.Context
import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.ActivityDto
import com.roamly.data.api.RoamlyApi
import com.roamly.data.prefs.ActivitySession
import com.roamly.data.prefs.UserPreferences
import com.roamly.tracking.ActivityCoordinator
import com.roamly.tracking.TrackingDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One point of the live track, in draw order. */
data class TrackPoint(val lat: Double, val lng: Double)

data class RecordUiState(
    val session: ActivitySession? = null,
    val elapsedMs: Long = 0L,
    val distanceM: Double = 0.0,
    val currentSpeedMps: Float? = null,
    val maxSpeedMps: Float = 0f,
    val movingMs: Long = 0L,
    val pointCount: Int = 0,
    val track: List<TrackPoint> = emptyList(),
    val recent: List<ActivityDto> = emptyList(),
    val loadingRecent: Boolean = false,
    val error: String? = null,
    val saving: Boolean = false,
) {
    val recording: Boolean get() = session != null
    /** Distance over *moving* time, matching how the server reports average speed. */
    val avgSpeedMps: Float
        get() = if (movingMs > 0) (distanceM / (movingMs / 1000.0)).toFloat() else 0f
}

/**
 * Live figures for an in-progress recording, folded from the local point queue.
 *
 * The service and this ViewModel share the `@Singleton TrackingDatabase` in one
 * process, so Room emits the service's inserts here directly — the same seam
 * `SettingsViewModel` already uses for the pending-upload count. No binder, no
 * broadcast, nothing new between the two.
 *
 * The count flow is only a *trigger*: a `Flow<List<CachedPoint>>` would re-emit
 * the whole growing list on every insert, which over a 40-minute ride at 2s is
 * quadratic. Pairing the count with a cursored tail read keeps it O(new points).
 *
 * These numbers are provisional. The server recomputes the authoritative ones
 * from the uploaded fixes when the activity is saved, using the same gated
 * distance every other figure in the app comes from; the per-hop gate below is
 * chosen to land close to it rather than to duplicate it exactly.
 */
@HiltViewModel
class RecordViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferences,
    private val db: TrackingDatabase,
    private val api: RoamlyApi,
) : ViewModel() {

    private val _state = MutableStateFlow(RecordUiState())
    val state: StateFlow<RecordUiState> = _state.asStateFlow()

    private var pointsJob: Job? = null
    private var tickJob: Job? = null

    // Fold state for the live accumulation.
    private var cursorId = 0L
    private var lastLat: Double? = null
    private var lastLng: Double? = null
    private var lastTs = 0L

    init {
        viewModelScope.launch {
            // Survives a process kill: a recording in progress is re-adopted on
            // launch rather than being lost with the UI that started it.
            prefs.activitySession.collectLatest { session ->
                _state.update { it.copy(session = session) }
                if (session != null) startWatching(session) else stopWatching()
            }
        }
        loadRecent()
    }

    private fun resetFold() {
        cursorId = 0L
        lastLat = null
        lastLng = null
        lastTs = 0L
    }

    private fun startWatching(session: ActivitySession) {
        pointsJob?.cancel()
        tickJob?.cancel()
        resetFold()
        _state.update {
            it.copy(distanceM = 0.0, maxSpeedMps = 0f, movingMs = 0L,
                    pointCount = 0, track = emptyList(), currentSpeedMps = null)
        }

        pointsJob = viewModelScope.launch {
            db.pointDao().countSinceFlow(session.startedAtMs)
                // conflate + collect, deliberately not collectLatest: conflate drops
                // intermediate *emissions* under load, which is what we want, whereas
                // collectLatest would cancel drainTail mid-loop — and it advances the
                // cursor as it goes, so a cancelled fold would lose those points'
                // distance permanently rather than re-reading them next tick.
                .conflate()
                .collect { drainTail(session) }
        }
        // The clock has to run even when no fix lands, or a stationary minute at a
        // junction looks like the recording froze.
        tickJob = viewModelScope.launch {
            while (isActive) {
                _state.update {
                    it.copy(elapsedMs = System.currentTimeMillis() - session.startedAtMs)
                }
                kotlinx.coroutines.delay(1_000L)
            }
        }
    }

    private fun stopWatching() {
        pointsJob?.cancel(); pointsJob = null
        tickJob?.cancel(); tickJob = null
    }

    private suspend fun drainTail(session: ActivitySession) {
        val rows = db.pointDao().sinceTail(cursorId, session.startedAtMs)
        if (rows.isEmpty()) return

        var distance = _state.value.distanceM
        var maxSpeed = _state.value.maxSpeedMps
        var moving = _state.value.movingMs
        val track = _state.value.track.toMutableList()
        var latest: Float? = _state.value.currentSpeedMps

        for (p in rows) {
            cursorId = maxOf(cursorId, p.id)
            val pl = lastLat
            val pn = lastLng
            if (pl != null && pn != null) {
                val out = FloatArray(1)
                Location.distanceBetween(pl, pn, p.latitude, p.longitude, out)
                val hop = out[0]
                // Mirror the server's per-hop gate: credit only movement that clears
                // the noise floor, scaled by how good the fix claims to be. Without
                // it a stationary phone accumulates phantom metres all session.
                val gate = maxOf(MIN_HOP_M, (p.accuracy ?: DEFAULT_ACC_M) * ACC_GATE_MULT)
                if (hop >= gate) {
                    distance += hop
                    lastLat = p.latitude
                    lastLng = p.longitude
                }
                val dt = p.timestamp - lastTs
                if (dt in 1..MAX_MOVING_GAP_MS && (p.speed ?: 0f) >= MOVING_SPEED_MPS) {
                    moving += dt
                }
            } else {
                lastLat = p.latitude
                lastLng = p.longitude
            }
            lastTs = p.timestamp
            p.speed?.let {
                if (it > maxSpeed && it <= MAX_PLAUSIBLE_MPS) maxSpeed = it
                latest = it
            }
            track.add(TrackPoint(p.latitude, p.longitude))
        }

        _state.update {
            it.copy(distanceM = distance, maxSpeedMps = maxSpeed, movingMs = moving,
                    pointCount = it.pointCount + rows.size, track = track,
                    currentSpeedMps = latest)
        }
    }

    fun start(kind: String) {
        viewModelScope.launch {
            _state.update { it.copy(error = null) }
            val session = ActivityCoordinator.start(context, prefs, kind)
            if (session == null) {
                _state.update {
                    it.copy(error = "Start tracking in Settings before recording an activity.")
                }
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            ActivityCoordinator.stop(context, prefs)
            _state.update { it.copy(saving = false) }
            // The save is queued, not immediate, so give the list a moment before
            // asking for it — and it refreshes again whenever the screen is reopened.
            loadRecent()
        }
    }

    fun discard() {
        viewModelScope.launch { ActivityCoordinator.discard(prefs) }
    }

    fun loadRecent() {
        viewModelScope.launch {
            _state.update { it.copy(loadingRecent = true) }
            val rows = runCatching {
                val resp = api.getActivities(limit = 20)
                if (resp.isSuccessful) resp.body()?.activities.orEmpty() else emptyList()
            }.getOrDefault(emptyList())
            _state.update { it.copy(recent = rows, loadingRecent = false) }
        }
    }

    fun deleteActivity(id: Int) {
        viewModelScope.launch {
            runCatching { api.deleteActivity(id) }
            loadRecent()
        }
    }

    override fun onCleared() {
        stopWatching()
        super.onCleared()
    }

    private companion object {
        const val MIN_HOP_M = 10f
        const val ACC_GATE_MULT = 1.5f
        const val DEFAULT_ACC_M = 15f
        const val MOVING_SPEED_MPS = 0.5f
        const val MAX_MOVING_GAP_MS = 60_000L
        const val MAX_PLAUSIBLE_MPS = 60f   // ~216 km/h; above this it is a Doppler glitch
    }
}
