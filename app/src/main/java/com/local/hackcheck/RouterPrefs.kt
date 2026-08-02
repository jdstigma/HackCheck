package com.local.hackcheck

import android.content.Context

/** Persists the router backend's base URL (e.g. http://192.168.1.50:8000)
 *  so it doesn't need retyping every time the Router screen opens. */
object RouterPrefs {
    private const val PREFS_NAME = "hackcheck_router_prefs"
    private const val KEY_BASE_URL = "base_url"
    const val DEFAULT_BASE_URL = "http://192.168.1.50:8000"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBaseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    fun setBaseUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_BASE_URL, url.trimEnd('/')).apply()
    }
}
