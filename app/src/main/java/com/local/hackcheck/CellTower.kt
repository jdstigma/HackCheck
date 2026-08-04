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
 * Tells the caller how the last visibleCellTowers() result was actually
 * obtained -- added after a fresh-scan fix (requestCellInfoUpdate) still
 * reportedly returned the same tower after driving well away from it, to
 * distinguish "the modem gave us fresh data and it's genuinely unchanged"
 * from "the app silently fell back to a cached/errored read again."
 */
enum class CellScanSource {
    FRESH,              // requestCellInfoUpdate's callback fired with data before the timeout
    FRESH_EMPTY,        // callback fired but with an empty list (modem reported no cells)
    CALLBACK_ERROR,     // onError fired -- fell back to the cached allCellInfo read
    TIMEOUT,            // callback never fired within 5s -- fell back to the cached allCellInfo read
    NO_PERMISSION,
    NO_TELEPHONY_MANAGER,
    SECURITY_EXCEPTION,
}

data class CellScanDiagnostics(
    val source: CellScanSource,
    val rawCellCount: Int,
    val elapsedMs: Long,
    val timestampMillis: Long,
    val detail: String? = null,
)

data class CellScanResult(
    val cells: List<CellTowerInfo>,
    val diagnostics: CellScanDiagnostics,
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
 * OEM basebands don't reliably invoke the callback). Returns diagnostics
 * alongside the result so the UI can show which path actually produced the
 * data -- a repeat of the "same tower persists" symptom even after this fix
 * needs to distinguish a real fresh-but-unchanged reading (e.g. the modem
 * genuinely never re-registered to a new cell) from the fresh-scan path
 * silently failing and quietly falling back to the stale cache again.
 */
suspend fun visibleCellTowers(context: Context): CellScanResult {
    val now = System.currentTimeMillis()

    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return CellScanResult(emptyList(), CellScanDiagnostics(CellScanSource.NO_PERMISSION, 0, 0, now))
    }
    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        ?: return CellScanResult(emptyList(), CellScanDiagnostics(CellScanSource.NO_TELEPHONY_MANAGER, 0, 0, now))

    val startMs = System.currentTimeMillis()
    var source = CellScanSource.FRESH
    var detail: String? = null

    val rawCells: List<CellInfo> = try {
        withTimeoutOrNull(5000) {
            suspendCancellableCoroutine<List<CellInfo>> { cont ->
                tm.requestCellInfoUpdate(
                    context.mainExecutor,
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            if (cont.isActive) cont.resume(cellInfo)
                        }
                        override fun onError(errorCode: Int, detail_: Throwable?) {
                            source = CellScanSource.CALLBACK_ERROR
                            detail = "errorCode=$errorCode ${detail_?.javaClass?.simpleName ?: ""} ${detail_?.message ?: ""}".trim()
                            if (cont.isActive) cont.resume(tm.allCellInfo ?: emptyList())
                        }
                    },
                )
            }
        } ?: run {
            source = CellScanSource.TIMEOUT
            tm.allCellInfo ?: emptyList()
        }
    } catch (e: SecurityException) {
        source = CellScanSource.SECURITY_EXCEPTION
        detail = e.message
        emptyList()
    }

    if (source == CellScanSource.FRESH && rawCells.isEmpty()) {
        source = CellScanSource.FRESH_EMPTY
    }

    val elapsedMs = System.currentTimeMillis() - startMs
    val diagnostics = CellScanDiagnostics(source, rawCells.size, elapsedMs, System.currentTimeMillis(), detail)

    val cells = rawCells.mapNotNull { cell ->
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
        // CellInfo.UNAVAILABLE (Int.MAX_VALUE, 2147483647) is Android's sentinel for
        // "this field wasn't decoded" -- neighbor cells the modem can see signal-wise
        // but hasn't fully identified yet report this instead of a real cell ID.
        // Filter these out rather than showing a fake-looking "tower" with that ID.
    }.filter { it.cellId != CellInfo.UNAVAILABLE.toLong() }

    return CellScanResult(cells, diagnostics)
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
