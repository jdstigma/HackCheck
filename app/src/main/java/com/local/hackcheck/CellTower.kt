package com.local.hackcheck

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

data class CellTowerInfo(
    val cellId: Long,
    val mcc: Int?,
    val mnc: Int?,
    val tac: Int?,
    val isServingCell: Boolean,
    val signalStrengthDbm: Int?,
    val networkType: String,
)

data class CellTowerLocation(
    val lat: Double,
    val lon: Double,
    val rangeMeters: Int?,
)

/**
 * Reads currently visible cell towers (serving + neighbors). Requires
 * ACCESS_FINE_LOCATION -- Android treats cell tower data as location data
 * even though this isn't a GPS read. Both permissions are already requested
 * as part of the existing Network & Devices permission set.
 *
 * Uses requestCellInfoUpdate() to force a fresh modem scan rather than
 * TelephonyManager.allCellInfo, which is documented as a cached snapshot --
 * observed returning a tower from miles away after driving well outside its
 * range, since the modem hadn't been prompted to re-scan. Falls back to the
 * cached allCellInfo if the fresh request doesn't respond within 5s (some
 * OEM basebands don't reliably invoke the callback).
 */
suspend fun visibleCellTowers(context: Context): List<CellTowerInfo> {
    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return emptyList()
    }
    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        ?: return emptyList()

    val cells = try {
        withTimeoutOrNull(5000) {
            suspendCancellableCoroutine<List<CellInfo>> { cont ->
                tm.requestCellInfoUpdate(
                    context.mainExecutor,
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            if (cont.isActive) cont.resume(cellInfo)
                        }
                        override fun onError(errorCode: Int, detail: Throwable?) {
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    },
                )
            }
        } ?: tm.allCellInfo ?: emptyList()
    } catch (e: SecurityException) {
        emptyList()
    }

    return cells.mapNotNull { cell ->
        when (cell) {
            is CellInfoLte -> {
                val id = cell.cellIdentity
                CellTowerInfo(
                    cellId = id.ci.toLong(),
                    mcc = id.mccString?.toIntOrNull(),
                    mnc = id.mncString?.toIntOrNull(),
                    tac = id.tac,
                    isServingCell = cell.isRegistered,
                    signalStrengthDbm = cell.cellSignalStrength.dbm,
                    networkType = "LTE",
                )
            }
            is CellInfoNr -> {
                val id = cell.cellIdentity as android.telephony.CellIdentityNr
                val ss = cell.cellSignalStrength as android.telephony.CellSignalStrengthNr
                CellTowerInfo(
                    cellId = id.nci,
                    mcc = id.mccString?.toIntOrNull(),
                    mnc = id.mncString?.toIntOrNull(),
                    tac = id.tac,
                    isServingCell = cell.isRegistered,
                    signalStrengthDbm = ss.dbm,
                    networkType = "5G NR",
                )
            }
            is CellInfoWcdma -> {
                val id = cell.cellIdentity
                CellTowerInfo(
                    cellId = id.cid.toLong(),
                    mcc = id.mccString?.toIntOrNull(),
                    mnc = id.mncString?.toIntOrNull(),
                    tac = id.lac,
                    isServingCell = cell.isRegistered,
                    signalStrengthDbm = cell.cellSignalStrength.dbm,
                    networkType = "WCDMA/3G",
                )
            }
            is CellInfoGsm -> {
                val id = cell.cellIdentity
                CellTowerInfo(
                    cellId = id.cid.toLong(),
                    mcc = id.mccString?.toIntOrNull(),
                    mnc = id.mncString?.toIntOrNull(),
                    tac = id.lac,
                    isServingCell = cell.isRegistered,
                    signalStrengthDbm = cell.cellSignalStrength.dbm,
                    networkType = "GSM/2G",
                )
            }
            else -> null
        }
    }
}

/**
 * Geolocates a cell tower via the OpenCellID crowdsourced database
 * (https://opencellid.org -- free API key required, rate-limited).
 * Coverage is crowdsourced, so returns null for cells not in the database --
 * common outside dense urban areas. Treat coordinates as approximate.
 */
suspend fun geolocateCellTower(cell: CellTowerInfo, apiKey: String): CellTowerLocation? =
    withContext(Dispatchers.IO) {
        val mcc = cell.mcc ?: return@withContext null
        val mnc = cell.mnc ?: return@withContext null
        val tac = cell.tac ?: return@withContext null

        val url = "https://opencellid.org/cell/get" +
            "?key=$apiKey&mcc=$mcc&mnc=$mnc&lac=$tac&cellid=${cell.cellId}&format=json"

        try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }.use { conn ->
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                if (json.has("error")) return@withContext null
                CellTowerLocation(
                    lat = json.getDouble("lat"),
                    lon = json.getDouble("lon"),
                    rangeMeters = json.optInt("range", -1).takeIf { it >= 0 },
                )
            }
        } catch (e: Exception) {
            null
        }
    }

// HttpURLConnection doesn't implement Closeable pre-API 19 semantics cleanly;
// this small extension keeps the `.use { }` pattern consistent with the rest
// of the codebase's try/use conventions.
private inline fun HttpURLConnection.use(block: (HttpURLConnection) -> CellTowerLocation?): CellTowerLocation? {
    return try {
        block(this)
    } finally {
        disconnect()
    }
}
