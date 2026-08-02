package com.local.hackcheck

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class NearbyWifiNetwork(
    val ssid: String,
    val bssid: String,
    val signalDbm: Int,
    val isHidden: Boolean,
    val isOpen: Boolean,
)

data class NearbyBleDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
)

private fun hasPermission(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/**
 * Triggers a fresh WiFi scan and returns the results. Requires
 * ACCESS_FINE_LOCATION (already used elsewhere in this app) --
 * WiFi scan results are treated as location-adjacent data by Android.
 *
 * Note: Android throttles how often an app can actively trigger scans
 * (a handful per 2 minutes on API 28+) -- if throttled, this still
 * returns whatever WifiManager.scanResults currently holds (possibly
 * slightly stale) rather than failing outright.
 */
suspend fun scanNearbyWifi(context: Context): List<NearbyWifiNetwork> {
    if (!hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) return emptyList()

    val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()

    val scanTriggered = suspendCancellableCoroutine<Boolean> { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                context.applicationContext.unregisterReceiver(this)
                if (cont.isActive) cont.resume(true)
            }
        }
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        cont.invokeOnCancellation {
            try { context.applicationContext.unregisterReceiver(receiver) } catch (e: Exception) { /* already gone */ }
        }

        val started = try {
            wifiManager.startScan()
        } catch (e: SecurityException) {
            false
        }
        if (!started) {
            // Scan couldn't even start (throttled, WiFi off, etc.) -- don't
            // hang waiting for a broadcast that may never come.
            try { context.applicationContext.unregisterReceiver(receiver) } catch (e: Exception) { /* already gone */ }
            if (cont.isActive) cont.resume(false)
        }
    }

    if (!scanTriggered) {
        // Fall back to whatever's currently cached rather than an empty list.
    }

    val results = try {
        wifiManager.scanResults
    } catch (e: SecurityException) {
        return emptyList()
    }

    return results.map { r ->
        val capabilities = r.capabilities ?: ""
        NearbyWifiNetwork(
            ssid = r.SSID ?: "",
            bssid = r.BSSID ?: "",
            signalDbm = r.level,
            isHidden = r.SSID.isNullOrBlank(),
            isOpen = !capabilities.contains("WPA") && !capabilities.contains("WEP"),
        )
    }.sortedByDescending { it.signalDbm }
}

/**
 * Scans for ALL nearby BLE-advertising devices (not just paired ones) for
 * a fixed window, then stops. Requires BLUETOOTH_SCAN (API 31+) or
 * BLUETOOTH_ADMIN (older) plus ACCESS_FINE_LOCATION.
 */
suspend fun scanNearbyBle(context: Context, scanDurationMillis: Long = 8000): List<NearbyBleDevice> {
    val hasScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (!hasScanPermission) return emptyList()

    val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
    if (!adapter.isEnabled) return emptyList()
    val scanner = adapter.bluetoothLeScanner ?: return emptyList()

    // address -> best-seen reading, deduplicated (a device advertises repeatedly
    // during the scan window; we want one row per device, not one per advertisement).
    val seen = LinkedHashMap<String, NearbyBleDevice>()

    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = try {
                device.name
            } catch (e: SecurityException) {
                null
            }
            seen[device.address] = NearbyBleDevice(
                name = name,
                address = device.address,
                rssi = result.rssi,
            )
        }
    }

    try {
        scanner.startScan(callback)
    } catch (e: SecurityException) {
        return emptyList()
    }

    delay(scanDurationMillis)

    try {
        scanner.stopScan(callback)
    } catch (e: SecurityException) {
        // Scan may already be stopped by the system; nothing to clean up either way.
    }

    return seen.values.sortedByDescending { it.rssi }
}
