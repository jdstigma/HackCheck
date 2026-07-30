package com.local.hackcheck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/**
 * Foreground service that logs cell-service-state transitions and WiFi connection changes
 * to MonitorLog as they happen. Android requires a persistent, visible notification for any
 * foreground service -- there's no way around that by OS design, and it's intentional: it's
 * what stops apps from silently monitoring in the background without the phone's user
 * knowing. This only captures events from whenever it's started forward, never retroactively.
 */
class MonitorService : Service() {

    private var lastServiceState: Int? = null
    private var lastWifiSsid: String? = null

    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var wifiReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        MonitorLog.append(applicationContext, "monitor_started", "Background monitoring started")
        registerTelephonyListener()
        registerWifiReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        MonitorLog.append(applicationContext, "monitor_stopped", "Background monitoring stopped")
        unregisterTelephonyListener()
        wifiReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        super.onDestroy()
    }

    private fun registerTelephonyListener() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            MonitorLog.append(applicationContext, "monitor_warning", "READ_PHONE_STATE not granted; cell-service tracking disabled")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
                override fun onServiceStateChanged(serviceState: ServiceState) {
                    handleServiceState(serviceState.state)
                }
            }
            telephonyCallback = callback
            tm.registerTelephonyCallback(mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onServiceStateChanged(serviceState: ServiceState?) {
                    if (serviceState != null) handleServiceState(serviceState.state)
                }
            }
            phoneStateListener = listener
            @Suppress("DEPRECATION")
            tm.listen(listener, PhoneStateListener.LISTEN_SERVICE_STATE)
        }
    }

    private fun unregisterTelephonyListener() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        telephonyCallback?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) tm.unregisterTelephonyCallback(it)
        }
        phoneStateListener?.let {
            @Suppress("DEPRECATION")
            tm.listen(it, PhoneStateListener.LISTEN_NONE)
        }
    }

    private fun handleServiceState(state: Int) {
        if (state == lastServiceState) return
        lastServiceState = state
        val label = when (state) {
            ServiceState.STATE_IN_SERVICE -> "In service"
            ServiceState.STATE_OUT_OF_SERVICE -> "OUT OF SERVICE"
            ServiceState.STATE_EMERGENCY_ONLY -> "Emergency calls only"
            ServiceState.STATE_POWER_OFF -> "Radio off (airplane mode)"
            else -> "Unknown ($state)"
        }
        MonitorLog.append(applicationContext, "cell_service_change", label)
    }

    private fun registerWifiReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val snapshot = currentWifiInfo(context)
                val ssid = snapshot?.ssid
                if (ssid == lastWifiSsid) return
                lastWifiSsid = ssid
                val detail = if (ssid != null) "Connected: $ssid (${snapshot.bssid})" else "Disconnected"
                MonitorLog.append(context, "wifi_change", detail)
            }
        }
        wifiReceiver = receiver
        @Suppress("DEPRECATION")
        registerReceiver(receiver, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION))
    }

    private fun buildNotification(): Notification {
        val channelId = "hackcheck_monitor"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Background monitoring", NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("HackCheck monitoring")
            .setContentText("Logging cell-service and WiFi connection changes")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 4201

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }

        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            return manager.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == MonitorService::class.java.name
            }
        }
    }
}
