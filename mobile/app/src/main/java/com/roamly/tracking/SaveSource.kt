package com.roamly.tracking

/**
 * Where a saved point came from.
 *
 * Until this existed the only distinction recorded was a boolean `isDwell`, so
 * nothing downstream — not the CSV, not CaptureStats, not the server — could tell
 * a real GPS fix from a re-stamped copy of a ten-minute-old position, or say
 * whether the cadence was being carried by the warm stream or by the alarm. That
 * is precisely the difference that matters when the complaint is "timing and
 * accuracy got worse and I can't tell when".
 */
enum class SaveSource(val csv: String) {
    /** The continuous location stream — what carries the cadence at short intervals. */
    STREAM("stream"),
    /** The exact-alarm fix cycle (acquireBestFix). */
    CYCLE("cycle"),
    /** A synthetic re-stamp of the last known position; not a real fix. */
    DWELL("dwell"),
    /** The immediate point taken when capture is (re)armed. */
    SEED("seed"),
}
