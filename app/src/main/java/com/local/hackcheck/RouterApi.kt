package com.local.hackcheck

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class RouterDevice(
    val id: Int,
    val macAddress: String,
    val hostname: String?,
    val lastSeen: String,
)

data class TopTalker(
    val deviceId: Int?,
    val hostname: String?,
    val macAddress: String?,
    val totalBytes: Long,
)

data class TrafficSummary(
    val deviceId: Int,
    val sinceMinutes: Int,
    val totalBytesSent: Long,
    val totalBytesRecv: Long,
    val flowCount: Int,
)

/**
 * Thin client for the router-backend FastAPI service (see /router-backend
 * in the repo). Base URL is your backend's LAN address, e.g.
 * http://192.168.1.50:8000 -- stored via RouterPrefs, editable in the UI
 * since it'll differ per network/deployment.
 *
 * Same pattern as geolocateCellTower() in CellTower.kt: plain
 * HttpURLConnection + org.json, no third-party HTTP library, to match
 * the rest of this codebase.
 */
object RouterApi {

    private const val TIMEOUT_MS = 6000

    private suspend fun getJson(url: String): Any? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val body = conn.inputStream.bufferedReader().readText()
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** True if the backend responded to /health -- doesn't confirm Postgres
     *  is reachable behind it, only that the API process is up. */
    suspend fun healthCheck(baseUrl: String): Boolean {
        val result = getJson("$baseUrl/health") as? JSONObject ?: return false
        return result.optString("status") == "ok"
    }

    suspend fun fetchDevices(baseUrl: String): List<RouterDevice> {
        val array = getJson("$baseUrl/devices") as? JSONArray ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            RouterDevice(
                id = obj.optInt("id"),
                macAddress = obj.optString("mac_address"),
                hostname = obj.optString("hostname").takeIf { it != "null" && it.isNotBlank() },
                lastSeen = obj.optString("last_seen"),
            )
        }
    }

    suspend fun fetchTopTalkers(baseUrl: String, sinceMinutes: Int = 60, limit: Int = 10): List<TopTalker> {
        val url = "$baseUrl/top-talkers?since_minutes=$sinceMinutes&limit=$limit"
        val array = getJson(url) as? JSONArray ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            TopTalker(
                deviceId = obj.optInt("device_id").takeIf { obj.has("device_id") && !obj.isNull("device_id") },
                hostname = obj.optString("hostname").takeIf { it != "null" && it.isNotBlank() },
                macAddress = obj.optString("mac_address").takeIf { it != "null" && it.isNotBlank() },
                totalBytes = obj.optLong("total_bytes"),
            )
        }
    }

    suspend fun fetchTrafficSummary(baseUrl: String, deviceId: Int, sinceMinutes: Int = 60): TrafficSummary? {
        val url = "$baseUrl/devices/$deviceId/traffic-summary?since_minutes=$sinceMinutes"
        val obj = getJson(url) as? JSONObject ?: return null
        return TrafficSummary(
            deviceId = obj.optInt("device_id"),
            sinceMinutes = obj.optInt("since_minutes"),
            totalBytesSent = obj.optLong("total_bytes_sent"),
            totalBytesRecv = obj.optLong("total_bytes_recv"),
            flowCount = obj.optInt("flow_count"),
        )
    }
}
