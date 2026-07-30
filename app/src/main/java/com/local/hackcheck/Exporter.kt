package com.local.hackcheck

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes scan output to CSV files in the phone's public Downloads folder (MediaStore,
 * no storage permission needed on minSdk 29+), so it can be pulled off the device with
 * `adb pull` and analyzed on a computer.
 */
object Exporter {

    private val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
    private val isoTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Returns the Downloads-relative paths of the files written. */
    fun exportAll(
        context: Context,
        result: ScanResult,
        inventory: List<AppRow>,
        network: NetworkSnapshot? = null,
    ): List<String> {
        val ts = stamp.format(Date())
        val findingsPath = writeCsv(
            context,
            "hackcheck_findings_$ts.csv",
            "Level,Title,Detail\n" + result.findings.joinToString("") { f ->
                "${cell(f.level.name)},${cell(f.title)},${cell(f.detail)}\n"
            },
        )
        val inventoryPath = writeCsv(
            context,
            "hackcheck_app_inventory_$ts.csv",
            buildString {
                append(
                    "PackageName,Label,IsSystem,HasLauncherIcon,VersionName," +
                        "FirstInstalled,LastUpdated,GrantedDangerousPermissions," +
                        "IgnoringBatteryOptimizations,KnownStalkerwareMatch,DualUseMonitoringMatch\n",
                )
                inventory.forEach { row ->
                    append(cell(row.packageName)).append(',')
                    append(cell(row.label)).append(',')
                    append(row.isSystem).append(',')
                    append(row.hasLauncherIcon).append(',')
                    append(cell(row.versionName ?: "")).append(',')
                    append(cell(isoTime.format(Date(row.firstInstallTimeMillis)))).append(',')
                    append(cell(isoTime.format(Date(row.lastUpdateTimeMillis)))).append(',')
                    append(cell(row.grantedDangerousPermissions.joinToString(";"))).append(',')
                    append(row.ignoringBatteryOptimizations).append(',')
                    append(row.knownStalkerwareMatch).append(',')
                    append(row.dualUseMonitoringMatch).append('\n')
                }
            },
        )

        val networkPath = network?.let { n ->
            writeCsv(
                context,
                "hackcheck_network_$ts.csv",
                buildString {
                    append("Section,Key,Value\n")
                    append("cell,service_state,${cell(n.cellState)}\n")
                    append("wifi,ssid,${cell(n.wifi?.ssid ?: "")}\n")
                    append("wifi,bssid,${cell(n.wifi?.bssid ?: "")}\n")
                    append("wifi,frequency_mhz,${n.wifi?.frequencyMHz ?: ""}\n")
                    append("wifi,link_speed_mbps,${n.wifi?.linkSpeedMbps ?: ""}\n")
                    n.bluetooth.forEach { d ->
                        append("bluetooth_paired,${cell(d.address)},${cell(d.name)}\n")
                    }
                    n.dataUsage?.rows?.forEach { row ->
                        append(
                            "data_usage_${n.dataUsage.windowDays}d,${cell(row.packageName)}," +
                                "${cell("wifi=${row.wifiBytes};mobile=${row.mobileBytes}")}\n",
                        )
                    }
                },
            )
        }

        return listOfNotNull(findingsPath, inventoryPath, networkPath)
    }

    private fun writeCsv(context: Context, fileName: String, csv: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
        return "Download/$fileName"
    }

    private fun cell(value: String): String {
        val needsQuote = value.contains(',') || value.contains('"') || value.contains('\n')
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuote) "\"$escaped\"" else escaped
    }
}
