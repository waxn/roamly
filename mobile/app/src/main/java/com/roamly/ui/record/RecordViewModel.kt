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
import com.roamly.tracking.ActivitySport
import com.roamly.tracking.KalmanFilter2D
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
    /** Accuracy of the newest fix, or null before the first one lands. */
    val gpsAccuracyM: Float? = null,
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
 * Live figures for an in-progress recording, folded from the recorder's private
 * buffer ([com.roamly.tracking.ActivityPoint]).
 *
 * The service and this ViewModel share the `@Singleton TrackingDatabase` in one
 * process, so Room emits the recorder's writes here directly. The count flow is
 * only a *trigger*; a cursored tail read keeps each update O(new points).
 *
 * Every fix goes through a forward [KalmanFilter2D], so the line drawn while you
 * ride and the distance shown follow the smoothed path rather than the raw
 * zig-zag. Distance and moving time only accrue while moving (Doppler speed over
 * the sport's pause threshold), so standing at a junction adds nothing.
 *
 * These numbers are provisional. The server re-smooths the uploaded track forwards
 * *and* backwards (which a live view can't — it has no future fixes) and its
 * figures are the ones kept.
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
    private var kalman = KalmanFilter2D(ActivitySport.OTHER.processNoise)
    private var sport = ActivitySport.OTHER
    private var lastEst: KalmanFilter2D.Estimate? = null
    private var lastTs = 0L
    private var lastDrawn: TrackPoint? = null
    private val recentSpeeds = ArrayDeque<Float>()

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

    private fun resetFold(session: ActivitySession) {
        cursorId = 0L
        sport = ActivitySport.of(session.kind)
        kalman = KalmanFilter2D(sport.processNoise)
        lastEst = null
        lastTs = 0L
        lastDrawn = null
        recentSpeeds.clear()
    }

    private fun startWatching(session: ActivitySession) {
        pointsJob?.cancel()
        tickJob?.cancel()
        resetFold(session)
        _state.update {
            it.copy(distanceM = 0.0, maxSpeedMps = 0f, movingMs = 0L, gpsAccuracyM = null,
                    pointCount = 0, track = emptyList(), currentSpeedMps = null)
        }

        pointsJob = viewModelScope.launch {
            db.activityPointDao().countFlow(session.id)
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
        val rows = db.activityPointDao().after(session.id, cursorId)
        if (rows.isEmpty()) return

        var distance = _state.value.distanceM
        var maxSpeed = _state.value.maxSpeedMps
        var moving = _state.value.movingMs
        val added = ArrayList<TrackPoint>()
        var latest: Float? = _state.value.currentSpeedMps
        var acc: Float? = _state.value.gpsAccuracyM

        for (p in rows) {
            cursorId = maxOf(cursorId, p.id)
            val est = kalman.update(p.t, p.lat, p.lon, p.acc)
            // Doppler is the honest "am I moving" signal: it stays ~0 standing still
            // even while the position wanders. Without it (rare at HIGH), fall back
            // to the filter's own velocity.
            val speed = p.spd ?: est.speedMps.toFloat()
            val prev = lastEst
            val dt = p.t - lastTs
            if (prev != null && dt in 1..MAX_MOVING_GAP_MS && speed.toDouble() >= sport.movingMps) {
                val out = FloatArray(1)
                Location.distanceBetween(prev.lat, prev.lon, est.lat, est.lon, out)
                distance += out[0]
                moving += dt
            }
            lastEst = est
            lastTs = p.t
            acc = p.acc

            // Max speed off a short rolling median, so one bad Doppler reading
            // can't claim a top speed you never hit.
            recentSpeeds.addLast(speed)
            if (recentSpeeds.size > 5) recentSpeeds.removeFirst()
            val median = recentSpeeds.sorted()[recentSpeeds.size / 2]
            if (median > maxSpeed && median <= sport.ceilingMps) maxSpeed = median
            latest = speed

            // Thin the drawn line to vertices a few metres apart: the map draws the
            // same shape with a fraction of the points over a long ride.
            val tp = TrackPoint(est.lat, est.lon)
            val ld = lastDrawn
            val far = ld == null || FloatArray(1).also {
                Location.distanceBetween(ld.lat, ld.lng, tp.lat, tp.lng, it)
            }[0] >= DRAW_MIN_SPACING_M
            if (far) { added += tp; lastDrawn = tp }
        }

        _state.update {
            it.copy(distanceM = distance, maxSpeedMps = maxSpeed, movingMs = moving,
                    pointCount = it.pointCount + rows.size,
                    track = if (added.isEmpty()) it.track else it.track + added,
                    currentSpeedMps = latest, gpsAccuracyM = acc)
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
        viewModelScope.launch { ActivityCoordinator.discard(context, prefs) }
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
        const val MAX_MOVING_GAP_MS = 60_000L
        const val DRAW_MIN_SPACING_M = 3f
    }
}
