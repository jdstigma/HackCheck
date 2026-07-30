package com.local.hackcheck

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Process
import android.telephony.ServiceState
import android.telephony.TelephonyManager

data class DataUsageRow(
    val uid: Int,
    val packageName: String,
    val label: String,
    val wifiBytes: Long,
    val mobileBytes: Long,
)

data class DataUsageResult(
    val rows: List<DataUsageRow>,
    val windowDays: Int,
    val wifiAvailable: Boolean,
    val mobileAvailable: Boolean,
)

data class BtDeviceRow(
    val name: String,
    val address: String,
)

data class WifiSnapshot(
    val ssid: String?,
    val bssid: String?,
    val linkSpeedMbps: Int,
    val frequencyMHz: Int,
)

fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

fun dataUsageByApp(context: Context, days: Int = 7): DataUsageResult {
    val pm = context.packageManager
    val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
    val end = System.currentTimeMillis()
    val start = end - days * 24L * 60 * 60 * 1000

    // uid -> [wifiBytes, mobileBytes]
    val totals = mutableMapOf<Int, LongArray>()

    fun accumulate(networkType: Int, index: Int): Boolean {
        return try {
            val stats = nsm.querySummary(networkType, null, start, end)
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                val arr = totals.getOrPut(bucket.uid) { longArrayOf(0, 0) }
                arr[index] += bucket.rxBytes + bucket.txBytes
            }
            stats.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    val wifiOk = accumulate(ConnectivityManager.TYPE_WIFI, 0)
    // Mobile usage query can require carrier-privileged access on some Android versions;
    // degrade gracefully rather than failing the whole feature.
    val mobileOk = accumulate(ConnectivityManager.TYPE_MOBILE, 1)

    val rows = totals.entries.mapNotNull { (uid, arr) ->
        val pkgName = try { pm.getPackagesForUid(uid)?.firstOrNull() } catch (e: Exception) { null }
            ?: return@mapNotNull null
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkgName, 0)).toString()
        } catch (e: Exception) {
            pkgName
        }
        DataUsageRow(uid, pkgName, label, wifiBytes = arr[0], mobileBytes = arr[1])
    }.sortedByDescending { it.wifiBytes + it.mobileBytes }

    return DataUsageResult(rows, days, wifiAvailable = wifiOk, mobileAvailable = mobileOk)
}

fun pairedBluetoothDevices(context: Context): List<BtDeviceRow> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val granted = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return emptyList()
    }
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        ?: return emptyList()
    return try {
        adapter.bondedDevices.map { d ->
            val name = try { d.name } catch (e: SecurityException) { null }
            BtDeviceRow(name = name ?: "(unnamed device)", address = d.address)
        }
    } catch (e: SecurityException) {
        emptyList()
    }
}

fun currentWifiInfo(context: Context): WifiSnapshot? {
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
    return try {
        @Suppress("DEPRECATION")
        val info = wifiManager.connectionInfo ?: return null
        val rawSsid = info.ssid?.trim('"')
        val ssid = if (rawSsid.isNullOrBlank() || rawSsid == "<unknown ssid>") null else rawSsid
        WifiSnapshot(
            ssid = ssid,
            bssid = info.bssid,
            linkSpeedMbps = info.linkSpeed,
            frequencyMHz = info.frequency,
        )
    } catch (e: Exception) {
        null
    }
}

fun cellServiceState(context: Context): String {
    if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
        return "Permission not granted"
    }
    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        ?: return "Unavailable"
    return try {
        when (tm.serviceState?.state) {
            ServiceState.STATE_IN_SERVICE -> "In service"
            ServiceState.STATE_OUT_OF_SERVICE -> "OUT OF SERVICE"
            ServiceState.STATE_EMERGENCY_ONLY -> "Emergency calls only"
            ServiceState.STATE_POWER_OFF -> "Radio off (airplane mode)"
            else -> "Unknown"
        }
    } catch (e: SecurityException) {
        "Permission not granted"
    } catch (e: Exception) {
        "Unavailable"
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
