package com.local.hackcheck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts monitoring after a reboot, but only if the user had it running (and didn't
 *  explicitly stop it) beforehand -- see MonitorPrefs. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!MonitorPrefs.isEnabled(context)) return
        MonitorLog.append(context.applicationContext, "monitor_boot_restart", "Restarted after device boot")
        MonitorService.start(context.applicationContext, MonitorPrefs.getIntervalMinutes(context))
    }
}
