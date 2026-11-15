package com.example.washguard

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray

/**
 * Keeps a small on-device log of battery %, odometer and range so the pit forecast can learn
 * how far the car is driven per day. Stays on the device; nothing is sent anywhere.
 * Kept for 30 days, at most one sample every 30 minutes unless the odometer or charge moved.
 */
class EnergyLog(context: Context) {
    private val prefs = context.getSharedPreferences("energy_log", Context.MODE_PRIVATE)
    var samples by mutableStateOf(load())
        private set

    fun record(socText: String?, odometerText: String?, rangeKm: Double?, now: Long = System.currentTimeMillis()) {
        val sample = EnergySample(now, PitWallInputs.socPercent(socText), parseOdometer(odometerText), rangeKm?.takeIf { it >= 0 })
        val next = EnergyLogRules.append(samples, sample, now)
        if (next === samples) return
        samples = next
        prefs.edit().putString(KEY, encode(next)).apply()
    }

    fun clear() { samples = emptyList(); prefs.edit().remove(KEY).apply() }

    private fun load(): List<EnergySample> = runCatching {
        val a = JSONArray(prefs.getString(KEY, "[]"))
        (0 until a.length()).map { i ->
            val r = a.getJSONArray(i)
            EnergySample(r.getLong(0), r.optNullable(1), r.optNullable(2), r.optNullable(3))
        }
    }.getOrDefault(emptyList())

    private fun encode(list: List<EnergySample>): String = JSONArray().apply {
        list.forEach { s -> put(JSONArray().put(s.time).put(s.soc ?: JSONArrayNull).put(s.odometerKm ?: JSONArrayNull).put(s.rangeKm ?: JSONArrayNull)) }
    }.toString()

    private fun JSONArray.optNullable(i: Int): Double? = if (isNull(i)) null else optDouble(i).takeIf { it.isFinite() }

    private companion object {
        const val KEY = "samples"
        val JSONArrayNull: Any = org.json.JSONObject.NULL
    }
}

/** Pure rules for the energy log, unit tested. */
object EnergyLogRules {
    const val KEEP_MS = 30L * 86_400_000L
    const val MIN_GAP_MS = 30L * 60_000L

    /** Returns the same list instance when the sample is not worth keeping. */
    fun append(list: List<EnergySample>, s: EnergySample, now: Long): List<EnergySample> {
        if (s.soc == null && s.odometerKm == null) return list
        val last = list.lastOrNull()
        if (last != null) {
            if (s.time <= last.time) return list
            val moved = s.odometerKm != null && last.odometerKm != null && kotlin.math.abs(s.odometerKm - last.odometerKm) >= 1.0
            val charged = s.soc != null && last.soc != null && kotlin.math.abs(s.soc - last.soc) >= 1.0
            val firstOdo = s.odometerKm != null && last.odometerKm == null
            // A range reading that appears or moves is what the pit wall learns km-per-% from; keep it.
            val rangeMoved = s.rangeKm != null && s.rangeKm > 0 && (last.rangeKm == null || kotlin.math.abs(s.rangeKm - last.rangeKm) >= 5.0)
            if (s.time - last.time < MIN_GAP_MS && !moved && !charged && !firstOdo && !rangeMoved) return list
        }
        return (list + s).filter { it.time >= now - KEEP_MS }.takeLast(1500)
    }
}

/** "1,234.5 km" → 1234.5 */
fun parseOdometer(text: String?): Double? =
    text?.replace(",", "")?.removeSuffix("km")?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
