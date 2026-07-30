package com.local.hackcheck

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CaptureEntry(
    val timestamp: String,
    val protocol: String,
    val app: String,
    val remoteAddress: String,
    val bytesSent: Long,
    val bytesReceived: Long,
    val durationMs: Long,
)

/** Append-only CSV log of completed capture flows, same pattern as MonitorLog -- no DB. */
object CaptureLog {
    private const val FILE_NAME = "capture_log.csv"
    private val isoTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun logFile(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun appendFlow(
        context: Context,
        protocol: String,
        app: String,
        remoteAddress: String,
        bytesSent: Long,
        bytesReceived: Long,
        durationMs: Long,
    ) {
        val line = "${isoTime.format(Date())},${cell(protocol)},${cell(app)},${cell(remoteAddress)}," +
            "$bytesSent,$bytesReceived,$durationMs\n"
        logFile(context).appendText(line)
    }

    fun readAll(context: Context): List<CaptureEntry> {
        val file = logFile(context)
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            val parts = splitCsvLine(line)
            if (parts.size >= 7) {
                CaptureEntry(
                    parts[0], parts[1], parts[2], parts[3],
                    parts[4].toLongOrNull() ?: 0L,
                    parts[5].toLongOrNull() ?: 0L,
                    parts[6].toLongOrNull() ?: 0L,
                )
            } else null
        }
    }

    fun clear(context: Context) {
        logFile(context).delete()
    }

    private fun cell(value: String): String {
        val needsQuote = value.contains(',') || value.contains('"') || value.contains('\n')
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuote) "\"$escaped\"" else escaped
    }

    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    result += sb.toString(); sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        result += sb.toString()
        return result
    }
}
