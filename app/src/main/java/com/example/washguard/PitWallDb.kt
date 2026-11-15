package com.example.washguard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * The car's own database (SQLite file pitwall.db, on the device only; nothing is sent anywhere).
 *
 * - drives         one row per finished drive, per driver profile → what the Pit Wall learns from. Never shared.
 * - place_reports  one row per "this place could / could not do this" → PIT SERVICE ranking. Kept in the shape a
 *                  server would take later (place id, what was done, ok, car sensor confirmed, time, and what the
 *                  driver added in one tap: free/paid, self/staff, 24 h, why it failed); `uploaded` stays 0 until a
 *                  sharing server exists.
 */
object PitWallDb {
    const val DRIVES = "drives"
    const val REPORTS = "place_reports"
    const val SAVED = "saved_places"
    const val USER_PLACES = "user_places"
    private const val TAG = "PitWallDb"
    @Volatile private var helper: Helper? = null

    /** The open database, or null when it cannot be opened (callers then keep working in memory). */
    fun open(context: Context): SQLiteDatabase? = runCatching {
        (helper ?: synchronized(this) { helper ?: Helper(context.applicationContext).also { helper = it } }).writableDatabase
    }.onFailure { Log.w(TAG, "open failed", it) }.getOrNull()

    /** One line for SETTINGS: whether the database works and how much it holds. */
    fun summary(context: Context): String {
        val db = open(context) ?: return "차 안 DB · 열지 못했어요 (이번 실행은 메모리에서만 동작)"
        fun count(sql: String, args: Array<String>? = null) = runCatching { db.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 } }.getOrDefault(-1)
        val drives = count("SELECT COUNT(*) FROM $DRIVES WHERE profile = ?", arrayOf(profile.toString()))
        val reports = count("SELECT COUNT(*) FROM $REPORTS")
        return if (drives < 0 || reports < 0) "차 안 DB · 읽기 오류" else "차 안 DB 정상 · 주행 기록 ${drives}개(이 운전자) · 장소 확인 ${reports}건 · 차 안에만 저장"
    }

    /** Driver profile key: the Android user the app runs as (AAOS gives each driver profile its own user). */
    val profile: Int get() = android.os.Process.myUserHandle().hashCode()

    private class Helper(context: Context) : SQLiteOpenHelper(context, "pitwall.db", null, 5) {
        override fun onCreate(db: SQLiteDatabase) { createDrives(db); createReports(db); createSaved(db); createUserPlaces(db) }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createReports(db)
            else if (oldVersion < 3) listOf("free INTEGER", "self_service INTEGER", "all_day INTEGER", "fail_reason TEXT").forEach { db.execSQL("ALTER TABLE $REPORTS ADD COLUMN $it") }
            if (oldVersion < 4) createSaved(db)
            if (oldVersion < 5) createUserPlaces(db)
        }

        private fun createUserPlaces(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $USER_PLACES (id TEXT PRIMARY KEY, name TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, needs TEXT NOT NULL, created_at INTEGER NOT NULL)")
        }

        private fun createSaved(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $SAVED (place_id TEXT PRIMARY KEY, saved_at INTEGER NOT NULL)")
        }

        private fun createDrives(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $DRIVES (id INTEGER PRIMARY KEY AUTOINCREMENT, profile INTEGER NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, " +
                "start_soc REAL, end_soc REAL, start_odo REAL, end_odo REAL, predicted REAL, box_calls INTEGER NOT NULL DEFAULT 0, box_accepted INTEGER NOT NULL DEFAULT 0, " +
                "route_km REAL, temp_c REAL, rainy INTEGER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS drives_profile_time ON $DRIVES (profile, start_ms)")
        }

        private fun createReports(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $REPORTS (id INTEGER PRIMARY KEY AUTOINCREMENT, place_id TEXT NOT NULL, need TEXT NOT NULL, ok INTEGER NOT NULL, " +
                "car_confirmed INTEGER NOT NULL DEFAULT 0, at_ms INTEGER NOT NULL, uploaded INTEGER NOT NULL DEFAULT 0, " +
                "free INTEGER, self_service INTEGER, all_day INTEGER, fail_reason TEXT)")
            db.execSQL("CREATE INDEX IF NOT EXISTS reports_place ON $REPORTS (place_id, need)")
        }
    }
}
