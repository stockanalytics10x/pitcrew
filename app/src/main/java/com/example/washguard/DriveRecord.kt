package com.example.washguard

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/** One driving spell (gear out of P until back in P), from the car's own readings. */
data class DriveRecord(
    val startMs: Long,
    val endMs: Long,
    val startSoc: Double?,
    val endSoc: Double?,
    val startOdoKm: Double?,
    val endOdoKm: Double?,
    /** Pit Wall's arrival estimate when the drive began (only with a navi route). */
    val predictedArrival: Double?,
    val boxCalls: Int,
    val boxAccepted: Int,
    /** Navi distance to the destination when the drive began, km (null = no route). */
    val routeKm: Double? = null,
    /** Conditions at the start, for learning: forecast temperature now (°C) and whether it was raining/likely to. */
    val tempC: Double? = null,
    val rainy: Boolean? = null
)

/** Text for the LAST DRIVE card. Pure, unit tested. Missing readings stay "—", never guessed. */
object DriveRecordText {
    fun distance(r: DriveRecord): String? {
        val a = r.startOdoKm ?: return null; val b = r.endOdoKm ?: return null
        val d = b - a
        return if (d < 0.05) null else if (d < 10) String.format(Locale.US, "%.1f km", d) else "${d.roundToInt()} km"
    }

    fun duration(r: DriveRecord): String {
        val min = ((r.endMs - r.startMs) / 60_000L).coerceAtLeast(0)
        return if (min < 60) "${min}분" else "${min / 60}시간 ${min % 60}분"
    }

    fun soc(r: DriveRecord): String = "${r.startSoc?.roundToInt()?.let { "$it%" } ?: "—"} → ${r.endSoc?.roundToInt()?.let { "$it%" } ?: "—"}"

    /** "예측 30% · 실제 31% · 오차 +1%p" when a prediction exists. */
    fun prediction(r: DriveRecord): String? {
        val p = r.predictedArrival ?: return null
        val a = r.endSoc ?: return null
        val diff = (a - p).roundToInt()
        val pText = if (p < 0) "부족" else "${p.roundToInt()}%"
        return "예측 $pText · 실제 ${a.roundToInt()}% · 오차 ${if (diff >= 0) "+" else ""}${diff}%p"
    }

    fun radio(r: DriveRecord): String = if (r.boxCalls == 0) "Box 콜 없음" else "Box 콜 ${r.boxCalls}회 · 수락 ${r.boxAccepted}회"
}

/**
 * The car's own drive database (SQLite, on the device only; nothing is sent anywhere).
 *
 * - One row per finished drive, kept per driver profile (the Android user the app runs as: AAOS gives each
 *   driver profile its own user, so each driver's records and learning stay separate).
 * - Keeps up to [KEEP] drives per profile in the database (ten years or more of daily driving, about 2 MB).
 *   The latest [IN_MEMORY] (about three years) are loaded for learning and the weekday pattern.
 * - Records saved by earlier versions (app settings file) are moved in once.
 * - If the database cannot be opened, records still work in memory for this run.
 */
class DriveRecordStore(context: Context) {
    private val app = context.applicationContext
    /** Driver profile key. */
    val profile: Int = PitWallDb.profile
    private val db: android.database.sqlite.SQLiteDatabase? = PitWallDb.open(app)
    var records by mutableStateOf(load())
        private set

    init { migrateFromPrefs() }

    fun add(r: DriveRecord) {
        records = (records + r).takeLast(IN_MEMORY)
        runCatching {
            db?.insert(TABLE, null, values(r))
            db?.execSQL("DELETE FROM $TABLE WHERE profile = ? AND id NOT IN (SELECT id FROM $TABLE WHERE profile = ? ORDER BY start_ms DESC LIMIT $KEEP)",
                arrayOf<Any>(profile, profile))
        }.onFailure { android.util.Log.w(TAG, "drive db insert failed", it) }
    }

    /** Clears this driver's records only. */
    fun clear() {
        records = emptyList()
        runCatching { db?.delete(TABLE, "profile = ?", arrayOf(profile.toString())) }
    }

    private fun values(r: DriveRecord) = android.content.ContentValues().apply {
        put("profile", profile); put("start_ms", r.startMs); put("end_ms", r.endMs)
        put("start_soc", r.startSoc); put("end_soc", r.endSoc); put("start_odo", r.startOdoKm); put("end_odo", r.endOdoKm)
        put("predicted", r.predictedArrival); put("box_calls", r.boxCalls); put("box_accepted", r.boxAccepted)
        put("route_km", r.routeKm); put("temp_c", r.tempC); put("rainy", r.rainy?.let { if (it) 1 else 0 })
    }

    private fun load(): List<DriveRecord> = runCatching {
        val out = mutableListOf<DriveRecord>()
        db?.rawQuery("SELECT start_ms,end_ms,start_soc,end_soc,start_odo,end_odo,predicted,box_calls,box_accepted,route_km,temp_c,rainy " +
            "FROM $TABLE WHERE profile = ? ORDER BY start_ms DESC LIMIT $IN_MEMORY", arrayOf(profile.toString()))?.use { c ->
            fun d(i: Int) = if (c.isNull(i)) null else c.getDouble(i)
            while (c.moveToNext()) out += DriveRecord(c.getLong(0), c.getLong(1), d(2), d(3), d(4), d(5), d(6), c.getInt(7), c.getInt(8),
                d(9), d(10), if (c.isNull(11)) null else c.getInt(11) != 0)
        }
        out.reversed()
    }.onFailure { android.util.Log.w(TAG, "drive db load failed", it) }.getOrDefault(emptyList())

    /** Earlier versions kept the last 20 drives in a settings file: move them into the database once. */
    private fun migrateFromPrefs() {
        val prefs = app.getSharedPreferences("drive_records", Context.MODE_PRIVATE)
        val text = prefs.getString(KEY, null) ?: return
        runCatching {
            val a = JSONArray(text)
            val old = (0 until a.length()).map { decode(a.getJSONObject(it)) }.filter { o -> records.none { it.startMs == o.startMs } }
            old.forEach { db?.insert(TABLE, null, values(it)) }
            records = (records + old).sortedBy { it.startMs }.takeLast(IN_MEMORY)
        }
        if (db != null) prefs.edit().remove(KEY).apply()
    }

    private fun decode(o: JSONObject) = DriveRecord(
        o.getLong("s"), o.getLong("e"), o.optD("ss"), o.optD("es"), o.optD("so"), o.optD("eo"), o.optD("pa"),
        o.optInt("bc"), o.optInt("ba"), o.optD("rk"), o.optD("tc"), if (o.has("rn")) o.optBoolean("rn") else null
    )

    private fun JSONObject.optD(k: String): Double? = if (has(k)) optDouble(k).takeIf { it.isFinite() } else null

    companion object {
        const val KEEP = 20_000
        const val IN_MEMORY = 3_000
        private const val KEY = "records"
        private const val TABLE = PitWallDb.DRIVES
        private const val TAG = "DriveRecordStore"
    }
}
