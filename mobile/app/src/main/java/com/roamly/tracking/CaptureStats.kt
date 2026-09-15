package com.roamly.tracking

import android.content.Context
import android.content.SharedPreferences

/**
 * Process-wide counters for **why a fix did or didn't become a point**.
 *
 * Every capture decision used to be a `Log.d` and nothing else, which made the two failure
 * modes indistinguishable from the outside: "the GPS gave us nothing" and "we got a fix and
 * threw it away" both look like an identical hole in the map. The server-side gap stats in
 * Diagnostics can't tell them apart either — they only ever see points that were captured
 * *and* uploaded.
 *
 * Backed by its own [SharedPreferences] file rather than the DataStore
 * [com.roamly.data.prefs.UserPreferences]: counters are bumped from the location callback
 * thread and from `Dispatchers.IO`, several times per interval, and must survive a process
 * kill — `apply()` is a cheap async write, whereas a DataStore edit is a suspend call that
 * would need a coroutine at every call site.
 *
 * [init] is called from the app and the service; [bump] is a no-op until then, so
 * [LocationFilter] and [DriftAnchor] stay context-free.
 */
object CaptureStats {

    /** One counter per capture outcome. [key] is the persisted name, [label] is what
     *  Diagnostics shows. */
    enum class Counter(val key: String, val label: String) {
        SAVED("saved", "Points saved"),
        STALE("stale", "Rejected: stale"),
        DUPLICATE("duplicate", "Rejected: duplicate"),
        TELEPORT("teleport", "Rejected: teleport"),
        DEDUP("dedup", "Rejected: too soon"),
        ACCURACY_DISCARD("accuracy", "Discarded: inaccurate"),
        DWELL("dwell", "Dwell substitutions"),
        DRIFT_SNAP("drift_snap", "Drift snapped"),
        SPIKE_SUPPRESSED("spike", "Spikes suppressed"),
        CYCLE_MISS("cycle_miss", "Fix cycles with no point"),
        UPLOAD_DROPPED("upload_dropped", "Points rejected by server"),
        RACE_DROP("race_drop", "Rejected: lost a save race"),
        CYCLE_SKIPPED_FRESH("cycle_skipped", "Fix cycles skipped (stream fresh)"),
        CYCLE_NO_FIX("cycle_no_fix", "Fix cycles: chip gave nothing"),
        CYCLE_FIX_FILTERED("cycle_filtered", "Fix cycles: fix arrived, filtered out"),
        // Counts SCANS (one per SIM per pass), not rows — the two differ by
        // the gate, and labelling this "readings" made a working phone look
        // like it was losing data on the way to the server.
        CELL_SCANNED("cell_scanned", "Cell scans"),
        CELL_RECORDED("cell_recorded", "Cell readings saved"),
        CELL_GATED("cell_gated", "Cell readings skipped by the gate"),
        CELL_NO_IDENTITY("cell_no_id", "Cell readings with no global id"),
        CELL_UPLOAD_UNSUPPORTED("cell_unsupported", "Cell readings dropped: server too old"),
        CELL_UPLOAD_REJECTED("cell_rejected", "Cell readings the server didn't keep"),
        CELL_UPLOAD_FAILED("cell_upload_failed", "Cell uploads that failed"),
    }

    private const val PREFS = "roamly_capture_stats"
    private const val KEY_SINCE = "since"
    private const val KEY_EXACT_ALARMS_DENIED = "exact_alarms_denied"
    private const val KEY_CELL_UPLOAD_ERROR = "cell_upload_error"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!p.contains(KEY_SINCE)) p.edit().putLong(KEY_SINCE, System.currentTimeMillis()).apply()
            prefs = p
        }
    }

    @Synchronized
    fun bump(counter: Counter, by: Int = 1) {
        val p = prefs ?: return
        p.edit().putLong(counter.key, p.getLong(counter.key, 0L) + by).apply()
    }

    /** True when the OS has denied exact alarms, which throttles the Doze fix cadence to
     *  roughly one wake every 9–15 minutes. Recorded by the service each time it arms an
     *  alarm, so Diagnostics can name a gap cause that is otherwise invisible. */
    var exactAlarmsDenied: Boolean
        get() = prefs?.getBoolean(KEY_EXACT_ALARMS_DENIED, false) ?: false
        set(value) {
            val p = prefs ?: return
            if (p.getBoolean(KEY_EXACT_ALARMS_DENIED, false) != value) {
                p.edit().putBoolean(KEY_EXACT_ALARMS_DENIED, value).apply()
            }
        }

    /** Why the last cell upload failed, or "" if the last one worked. A counter
     *  alone cannot distinguish "server is down" from "server is too old" from
     *  "auth is wrong", and those need different fixes. */
    var lastCellUploadError: String
        get() = prefs?.getString(KEY_CELL_UPLOAD_ERROR, "").orEmpty()
        set(value) {
            val p = prefs ?: return
            if (p.getString(KEY_CELL_UPLOAD_ERROR, "") != value) {
                p.edit().putString(KEY_CELL_UPLOAD_ERROR, value).apply()
            }
        }

    /** Epoch millis the counters were last cleared — Diagnostics shows totals "since" this. */
    val since: Long get() = prefs?.getLong(KEY_SINCE, 0L) ?: 0L

    fun snapshot(): Map<Counter, Long> {
        val p = prefs ?: return Counter.entries.associateWith { 0L }
        return Counter.entries.associateWith { p.getLong(it.key, 0L) }
    }

    @Synchronized
    fun reset() {
        val p = prefs ?: return
        val e = p.edit()
        Counter.entries.forEach { e.remove(it.key) }
        e.remove(KEY_CELL_UPLOAD_ERROR)
        e.putLong(KEY_SINCE, System.currentTimeMillis())
        e.apply()
    }
}
