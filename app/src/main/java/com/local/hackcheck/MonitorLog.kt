package com.local.hackcheck

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(val timestamp: String, val eventType: String, val detail: String)

/**
 * Simple append-only CSV log in app-private internal storage. No database dependency,
 * matching this project's preference for minimal deps -- this file only ever grows via
 * appends from MonitorService and is read back in full for display/export, which is fine
 * at the scale a phone's service-state/WiFi transitions produce.
 */
object MonitorLog {
    private const val FILE_NAME = "monitor_log.csv"
    private val isoTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun logFile(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun append(context: Context, eventType: String, detail: String) {
        val line = "${isoTime.format(Date())},${cell(eventType)},${cell(detail)}\n"
        logFile(context).appendText(line)
    }

    fun readAll(context: Context): List<LogEntry> {
        val file = logFile(context)
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            val parts = splitCsvLine(line)
            if (parts.size >= 3) LogEntry(parts[0], parts[1], parts[2]) else null
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

    // Minimal CSV parser sufficient for our own quoting scheme above.
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
