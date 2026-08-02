package com.local.hackcheck

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One tower you've personally seen and successfully geolocated, with the
 *  running history of when. */
data class SeenTower(
    val towerKey: String,
    val networkType: String,
    val lat: Double,
    val lon: Double,
    val rangeMeters: Int?,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val timesSeen: Int,
)

/**
 * Local database of every cell tower this device has successfully
 * geolocated, building up into a personal coverage map over time.
 *
 * A tower is identified by mcc-mnc-tac-cellId together (cell IDs alone
 * aren't globally unique -- the same numeric ID can appear under a
 * different carrier or tracking area), so towerKey below is that
 * composite, not just the raw cell ID.
 *
 * Plain SQLiteOpenHelper rather than Room -- matches the rest of this
 * codebase's no-extra-dependencies approach, and it's a genuinely small
 * enough schema (one table) that Room wouldn't buy much.
 */
class CellTowerHistoryDb(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "cell_tower_history.db"
        private const val DB_VERSION = 1
        private const val TABLE = "seen_towers"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                tower_key TEXT PRIMARY KEY,
                network_type TEXT NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                range_meters INTEGER,
                first_seen_millis INTEGER NOT NULL,
                last_seen_millis INTEGER NOT NULL,
                times_seen INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No schema changes yet -- first version. When this grows, prefer
        // ALTER TABLE migrations here over dropping the table, so people
        // don't lose their tower history on an app update.
    }

    /** Builds the composite key used to identify a tower across sightings.
     *  Public so callers can look up a specific tower's history if needed. */
    fun towerKey(cell: CellTowerInfo): String =
        "${cell.mcc}-${cell.mnc}-${cell.tac}-${cell.cellId}"

    /**
     * Records a sighting: inserts a new row if this tower hasn't been seen
     * before, or updates last_seen/times_seen (and the location, in case
     * OpenCellID's data improved) if it has. Call this every time a tower
     * is successfully geolocated.
     */
    fun recordSighting(cell: CellTowerInfo, location: CellTowerLocation, nowMillis: Long) {
        val key = towerKey(cell)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.query(
                TABLE, arrayOf("times_seen", "first_seen_millis"),
                "tower_key = ?", arrayOf(key),
                null, null, null,
            )
            val values = ContentValues().apply {
                put("tower_key", key)
                put("network_type", cell.networkType)
                put("lat", location.lat)
                put("lon", location.lon)
                put("range_meters", location.rangeMeters)
                put("last_seen_millis", nowMillis)
            }

            existing.use { cursor ->
                if (cursor.moveToFirst()) {
                    val timesSeen = cursor.getInt(cursor.getColumnIndexOrThrow("times_seen"))
                    val firstSeen = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_millis"))
                    values.put("times_seen", timesSeen + 1)
                    values.put("first_seen_millis", firstSeen)
                    db.update(TABLE, values, "tower_key = ?", arrayOf(key))
                } else {
                    values.put("first_seen_millis", nowMillis)
                    values.put("times_seen", 1)
                    db.insert(TABLE, null, values)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Every tower ever seen, most recently seen first. */
    fun allSeenTowers(): List<SeenTower> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE, null, null, null, null, null,
            "last_seen_millis DESC",
        )
        val results = mutableListOf<SeenTower>()
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    SeenTower(
                        towerKey = it.getString(it.getColumnIndexOrThrow("tower_key")),
                        networkType = it.getString(it.getColumnIndexOrThrow("network_type")),
                        lat = it.getDouble(it.getColumnIndexOrThrow("lat")),
                        lon = it.getDouble(it.getColumnIndexOrThrow("lon")),
                        rangeMeters = it.getInt(it.getColumnIndexOrThrow("range_meters"))
                            .takeIf { v -> !it.isNull(it.getColumnIndexOrThrow("range_meters")) },
                        firstSeenMillis = it.getLong(it.getColumnIndexOrThrow("first_seen_millis")),
                        lastSeenMillis = it.getLong(it.getColumnIndexOrThrow("last_seen_millis")),
                        timesSeen = it.getInt(it.getColumnIndexOrThrow("times_seen")),
                    )
                )
            }
        }
        return results
    }
}
