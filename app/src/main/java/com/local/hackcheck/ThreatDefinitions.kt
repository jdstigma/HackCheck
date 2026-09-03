package com.local.hackcheck

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ThreatDefinitions"
private const val DEFINITIONS_URL =
    "https://raw.githubusercontent.com/jdstigma/HackCheck/main/threat_definitions.json"
private const val PREFS_NAME = "threat_definitions_cache"
private const val KEY_STALKERWARE_JSON = "stalkerware_packages_json"
private const val KEY_DUAL_USE_SET = "dual_use_packages"
private const val KEY_UPDATED = "updated"
private const val TIMEOUT_MS = 8000

// Best-effort, publicly documented package identifiers associated with consumer
// stalkerware/spyware -- the permanent floor this never falls below, even with
// no network access ever. The real, much larger list comes from
// ThreatDefinitions.stalkerwarePackages() below, which unions this with
// whatever's been fetched from threat_definitions.json (see
// analysis/forensics/generate_threat_definitions.ipynb -- extracts real
// indicators from MVT's already-downloaded IOC feeds, not hand-researched).
private val BUNDLED_STALKERWARE_PACKAGES: Map<String, String> = mapOf(
    "com.flexispy.android" to "FlexiSPY",
    "com.hoverwatch.rem" to "Hoverwatch",
    "com.mobile.spy" to "mSpy-family",
    "com.mspyagent" to "mSpy",
    "com.google.services" to "TheTruthSpy-family (disguise name)",
    "com.system.update.service" to "Generic disguise name",
    "com.android.protect" to "Unnamed",
    "com.transfer.wsb" to "Unnamed",
)

// Legitimate apps that CAN be used for covert monitoring of another person.
// Not malware -- flagged separately so a genuine, disclosed use isn't confused
// with the stalkerware list above. Unlike the stalkerware list, this one isn't
// fed by the notebook pipeline (there's no "threat feed" for legitimate apps),
// so it stays this small, hand-picked set.
private val BUNDLED_DUAL_USE_PACKAGES = setOf(
    "com.familysafe.production",
    "com.wondershare.famisafe",
    "com.life360.android.safetymapd",
    "com.google.android.apps.kids.familylink",
    "com.eset.parentalcontrol",
    "com.mmguardian.parentapp",
)

/**
 * Fetches and caches threat_definitions.json (see
 * analysis/forensics/generate_threat_definitions.ipynb for how that file
 * itself gets produced -- real indicators extracted from MVT's IOC feeds,
 * never hand-typed). Cached locally so a scan always has *something* even
 * if this fetch hasn't happened yet, is offline, or fails outright -- a
 * scan never blocks on or depends on network access. Bundled package maps
 * above are the permanent floor; fetched data only ever adds to them
 * (union, never replace), so a bad/empty fetch can't reduce detection
 * coverage below the floor.
 */
object ThreatDefinitions {

    /** Kicks off a background refresh; safe to call on every app launch --
     *  cheap (~a few KB), and failures are silent/logged only, never
     *  surfaced to the user, since the bundled+cached set is always a safe
     *  fallback regardless of outcome. */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(DEFINITIONS_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Refresh failed: HTTP ${conn.responseCode}")
                return@withContext
            }
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val stalkerwareObj = json.optJSONObject("stalkerware_packages")
            if (stalkerwareObj == null) {
                Log.w(TAG, "Refresh failed: no stalkerware_packages object in response")
                return@withContext
            }
            val dualUseArr = json.optJSONArray("dual_use_packages")
            val dualUseSet = dualUseArr?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            } ?: emptySet()

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_STALKERWARE_JSON, stalkerwareObj.toString())
                .putStringSet(KEY_DUAL_USE_SET, dualUseSet)
                .putString(KEY_UPDATED, json.optString("updated"))
                .apply()
            Log.i(TAG, "Refreshed: ${stalkerwareObj.length()} stalkerware packages cached")
        } catch (e: Exception) {
            Log.e(TAG, "Refresh failed", e)
        } finally {
            conn?.disconnect()
        }
    }

    /** Bundled ∪ cached-remote package -> family-name map. Synchronous, no
     *  network wait, safe to call mid-scan. */
    fun stalkerwarePackages(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedJson = prefs.getString(KEY_STALKERWARE_JSON, null)
        val cached: Map<String, String> = if (cachedJson != null) {
            try {
                val obj = JSONObject(cachedJson)
                obj.keys().asSequence().associateWith { obj.getString(it) }
            } catch (e: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }
        return BUNDLED_STALKERWARE_PACKAGES + cached
    }

    fun dualUsePackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getStringSet(KEY_DUAL_USE_SET, emptySet()) ?: emptySet()
        return BUNDLED_DUAL_USE_PACKAGES + cached
    }

    /** Timestamp from the definitions file's own "updated" field (when the
     *  notebook last generated it), not device time or fetch time -- null
     *  if a remote fetch has never succeeded on this device. */
    fun lastUpdated(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_UPDATED, null)
    }
}
