package com.roamly.tracking

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Locale

private const val CSV_HEADER = "timestamp,latitude,longitude,accuracy_m,altitude_m,speed_mps,battery_percent,provider,synced"

object CsvPointLogger {

    private const val FILE_NAME = "points.csv"
    private const val DIR_NAME = "tracking"

    fun appendPoint(context: Context, point: CachedPoint) {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        val csvFile = File(dir, FILE_NAME)
        val needsHeader = !csvFile.exists() || csvFile.length() == 0L
        OutputStreamWriter(FileOutputStream(csvFile, true), Charsets.UTF_8).buffered(8 * 1024).use { writer ->
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
                ).joinToString(",")
            )
        }
    }

    fun csvPath(context: Context): String = File(File(context.filesDir, DIR_NAME), FILE_NAME).absolutePath

    private fun format(value: Double?): String = value?.let { String.format(Locale.US, "%.7f", it) } ?: ""
    private fun format(value: Float?): String = value?.let { String.format(Locale.US, "%.3f", it) } ?: ""

    private fun escape(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
