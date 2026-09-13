package com.roamly.tracking

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Locale

private const val CSV_HEADER =
    "timestamp,latitude,longitude,accuracy_m,altitude_m,speed_mps,battery_percent,provider,synced," +
        "source,fix_accuracy,consecutive_misses,streaming,screen_on,recording,gap_ms,fix_count"
private const val CSV_BUFFER_SIZE_BYTES = 8 * 1024

object CsvPointLogger {

    private const val FILE_NAME = "points.csv"
    private const val ROTATED_NAME = "points.csv.1"
    private const val DIR_NAME = "tracking"
    private val lock = Any()

    /** Previous saved point's timestamp, so each row can carry the gap that preceded
     *  it. This is the column that makes the file directly comparable to the
     *  server-side gap stats *and* sliceable by screen_on and source, which the
     *  server data can never be — it only ever sees points that were captured and
     *  uploaded, with no idea which ones were dwell substitutions. */
    @Volatile private var lastTimestampMs: Long = 0L

    /** The per-save context the [CachedPoint] itself doesn't carry. */
    data class SaveContext(
        val source: String,
        val fixAccuracy: String,
        val consecutiveMisses: Int,
        val streaming: Boolean,
        val screenOn: Boolean,
        val recording: Boolean,
        val fixCount: Int,
    )

    fun appendPoint(context: Context, point: CachedPoint, ctx: SaveContext) {
        synchronized(lock) {
            val dir = File(context.filesDir, DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            val csvFile = File(dir, FILE_NAME)
            // Rotate on a header change. The old check was "is the file empty", which
            // would have appended v2 rows under a v1 header — a file that silently
            // parses wrong is worse than one that starts over, and the point of these
            // columns is to be analysed.
            if (csvFile.exists() && csvFile.length() > 0L && !headerMatches(csvFile)) {
                runCatching { File(dir, ROTATED_NAME).delete() }
                runCatching { csvFile.renameTo(File(dir, ROTATED_NAME)) }
            }
            val needsHeader = !csvFile.exists() || csvFile.length() == 0L
            val gapMs = if (lastTimestampMs > 0L) point.timestamp - lastTimestampMs else -1L
            lastTimestampMs = point.timestamp
            OutputStreamWriter(FileOutputStream(csvFile, true), Charsets.UTF_8)
                .buffered(CSV_BUFFER_SIZE_BYTES).use { writer ->
                    if (needsHeader) writer.appendLine(CSV_HEADER)
                    writer.appendLine(
                        listOf(
                            point.timestamp.toString(),
                            format(point.latitude),
                            format(point.longitude),
                            format(point.accuracy),
                            format(point.altitude),
                            format(point.speed),
                            point.battery?.toString().orEmpty(),
                            escape(point.provider),
                            if (point.synced) "1" else "0",
                            ctx.source,
                            ctx.fixAccuracy,
                            ctx.consecutiveMisses.toString(),
                            if (ctx.streaming) "1" else "0",
                            if (ctx.screenOn) "1" else "0",
                            if (ctx.recording) "1" else "0",
                            if (gapMs >= 0L) gapMs.toString() else "",
                            if (ctx.fixCount > 0) ctx.fixCount.toString() else "",
                        ).joinToString(",")
                    )
                }
        }
    }

    private fun headerMatches(file: File): Boolean =
        runCatching { file.bufferedReader().use { it.readLine() } == CSV_HEADER }.getOrDefault(false)

    fun csvPath(context: Context): String =
        File(File(context.filesDir, DIR_NAME), FILE_NAME).absolutePath

    private fun format(value: Double?): String = value?.let { String.format(Locale.US, "%.7f", it) } ?: ""
    private fun format(value: Float?): String = value?.let { String.format(Locale.US, "%.3f", it) } ?: ""

    private fun escape(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
