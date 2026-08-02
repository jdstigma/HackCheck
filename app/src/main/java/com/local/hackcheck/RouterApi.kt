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

data class RouterFlow(
    val id: Int,
    val deviceId: Int?,
    val timestamp: String,
    val srcIp: String,
    val dstIp: String,
    val dstPort: Int?,
    val protocol: String?,
    val bytesSent: Long,
    val bytesRecv: Long,
)

data class NtopngControlResult(
    val ok: Boolean,
    val message: String,
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
    private const val POST_TIMEOUT_MS = 15000  // systemctl start/stop can take a few seconds

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

    /** POST with no body, used for the ntopng box start/stop controls.
     *  Longer timeout than GET requests -- systemctl start/stop can take
     *  a few seconds on a Pi, not just a normal API round-trip. */
    private suspend fun postJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = POST_TIMEOUT_MS
                requestMethod = "POST"
                doOutput = false
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val body = conn.inputStream.bufferedReader().readText()
            JSONObject(body.trim())
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

    suspend fun fetchFlows(
        baseUrl: String,
        deviceId: Int? = null,
        sinceMinutes: Int = 60,
        limit: Int = 100,
    ): List<RouterFlow> {
        val deviceParam = deviceId?.let { "&device_id=$it" } ?: ""
        val url = "$baseUrl/flows?since_minutes=$sinceMinutes&limit=$limit$deviceParam"
        val array = getJson(url) as? JSONArray ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            RouterFlow(
                id = obj.optInt("id"),
                deviceId = obj.optInt("device_id").takeIf { obj.has("device_id") && !obj.isNull("device_id") },
                timestamp = obj.optString("timestamp"),
                srcIp = obj.optString("src_ip"),
                dstIp = obj.optString("dst_ip"),
                dstPort = obj.optInt("dst_port").takeIf { obj.has("dst_port") && !obj.isNull("dst_port") },
                protocol = obj.optString("protocol").takeIf { it != "null" && it.isNotBlank() },
                bytesSent = obj.optLong("bytes_sent"),
                bytesRecv = obj.optLong("bytes_recv"),
            )
        }
    }

    /** True if the ntopng systemd service is running -- only meaningful
     *  when the backend runs on the same box as ntopng. False (rather
     *  than throwing) if the backend is unreachable or the box doesn't
     *  support control at all. */
    suspend fun ntopngStatus(baseUrl: String): Boolean {
        val result = getJson("$baseUrl/ntopng/status") as? JSONObject ?: return false
        return result.optBoolean("active", false)
    }

    suspend fun startNtopngBox(baseUrl: String): NtopngControlResult {
        val result = postJson("$baseUrl/ntopng/start")
            ?: return NtopngControlResult(false, "Could not reach backend at $baseUrl")
        val ok = result.optBoolean("ok", false)
        val message = if (ok) "Started" else result.optString("stderr", "Failed to start -- check backend logs")
        return NtopngControlResult(ok, message)
    }

    suspend fun stopNtopngBox(baseUrl: String): NtopngControlResult {
        val result = postJson("$baseUrl/ntopng/stop")
            ?: return NtopngControlResult(false, "Could not reach backend at $baseUrl")
        val ok = result.optBoolean("ok", false)
        val message = if (ok) "Stopped" else result.optString("stderr", "Failed to stop -- check backend logs")
        return NtopngControlResult(ok, message)
    }
}
