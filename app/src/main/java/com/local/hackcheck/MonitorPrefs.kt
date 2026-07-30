package com.local.hackcheck

import android.content.Context

/** Persists whether monitoring should be running and at what interval, so BootReceiver
 *  knows whether to restart it after a reboot, and so the chosen interval survives too. */
object MonitorPrefs {
    private const val PREFS_NAME = "hackcheck_monitor_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INTERVAL_MINUTES = "interval_minutes"
    const val DEFAULT_INTERVAL_MINUTES = 30

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getIntervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)

    fun setIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL_MINUTES, minutes).apply()
    }
}
