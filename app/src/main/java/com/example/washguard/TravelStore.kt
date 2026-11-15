package com.example.washguard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TravelStore(context: Context) : SQLiteOpenHelper(context, "pitstop_travel.db", null, 1) {
    @Volatile var epoch: Long = 0
        private set
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE points(time INTEGER PRIMARY KEY,lat REAL NOT NULL,lon REAL NOT NULL)")
        db.execSQL("CREATE TABLE observations(station INTEGER,hour TEXT,rain REAL,snow REAL,checked INTEGER,PRIMARY KEY(station,hour))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) {
            val cutoff = (System.currentTimeMillis() - 30L * 24 * 60 * 60_000).toString()
            db.delete("points", "time < ?", arrayOf(cutoff))
            db.delete("observations", "checked < ?", arrayOf(cutoff))
        }
    }
    @Synchronized fun record(point: GeoPoint) {
        writableDatabase.insertWithOnConflict("points", null, ContentValues().apply {
            put("time", point.time); put("lat", point.lat); put("lon", point.lon)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60_000
        writableDatabase.delete("points", "time < ?", arrayOf(cutoff.toString()))
        writableDatabase.delete("observations", "checked < ?", arrayOf(cutoff.toString()))
    }
    @Synchronized fun points(since: Long = 0): List<GeoPoint> = readableDatabase.rawQuery(
        "SELECT time,lat,lon FROM points WHERE time>=? ORDER BY time", arrayOf(since.toString())
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(GeoPoint(cursor.getLong(0), cursor.getDouble(1), cursor.getDouble(2))) } }
    @Synchronized fun observation(station: Int, hour: String): Pair<Double?, Double?>? = readableDatabase.rawQuery(
        "SELECT rain,snow,checked FROM observations WHERE station=? AND hour=?", arrayOf(station.toString(), hour)
    ).use { c -> if (!c.moveToFirst() || (c.isNull(0) && c.isNull(1) && System.currentTimeMillis() - c.getLong(2) > 6 * 60 * 60_000L)) null else Pair(if (c.isNull(0)) null else c.getDouble(0), if (c.isNull(1)) null else c.getDouble(1)) }
    @Synchronized fun snapshot(): Pair<Long, List<GeoPoint>> = epoch to points()
    @Synchronized fun saveObservation(station: Int, hour: String, rain: Double?, snow: Double?, expectedEpoch: Long) {
        if (epoch != expectedEpoch) return
        writableDatabase.insertWithOnConflict("observations", null, ContentValues().apply {
            put("station", station); put("hour", hour); put("rain", rain); put("snow", snow); put("checked", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun clearAll() {
        epoch++
        writableDatabase.beginTransaction()
        try { writableDatabase.delete("points", null, null); writableDatabase.delete("observations", null, null); writableDatabase.setTransactionSuccessful() }
        finally { writableDatabase.endTransaction() }
    }
}
