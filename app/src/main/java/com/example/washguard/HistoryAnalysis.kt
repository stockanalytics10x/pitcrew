package com.example.washguard

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONObject

data class HistoryTarget(val station: Int, val hour: LocalDateTime)
data class HistoryPlan(val targets: List<HistoryTarget>, val points: Int, val pending: Int, val outside: Int, val since: Long, val boundary: Int = 0)
data class HistoryEvidence(val wet: Int, val checked: Int, val missing: Int)

/** ASOS hourly API publishes through yesterday. Today's samples wait, never become dry samples. */
fun historyPlan(points: List<GeoPoint>, wash: Long, now: LocalDateTime, nearest: (GeoPoint) -> Station?): HistoryPlan {
    val nowMillis = now.atZone(KST).toInstant().toEpochMilli()
    val since = maxOf(wash, nowMillis - 7L * 86400000)
    if (wash <= 0) return HistoryPlan(emptyList(), 0, 0, 0, since)
    val recent = points.filter { it.time > wash && it.time >= since && it.time <= nowMillis && it.lat.isFinite() && it.lon.isFinite() }
    var outside = 0
    val mapped = recent.mapNotNull { p ->
        val station = nearest(p)
        if (station == null) { outside++; null } else HistoryTarget(station.id, observationHour(p.time))
    }.distinct()
    val boundary = mapped.count { it.hour.minusHours(1).atZone(KST).toInstant().toEpochMilli() < wash }
    val scoped = mapped.filter { it.hour.minusHours(1).atZone(KST).toInstant().toEpochMilli() >= wash }
    val pending = scoped.count { it.hour.toLocalDate() >= now.toLocalDate() }
    return HistoryPlan(scoped.filter { it.hour.toLocalDate() < now.toLocalDate() }, recent.size, pending, outside, since, boundary)
}
fun historyEvidence(targets: List<HistoryTarget>, observation: (HistoryTarget) -> Pair<Double?, Double?>?): HistoryEvidence {
    var wet = 0; var checked = 0
    targets.distinct().forEach {
        val row = observation(it)
        val rain = row?.first?.takeIf { value -> value.isFinite() && value >= 0 }
        val snow = row?.second?.takeIf { value -> value.isFinite() && value >= 0 }
        val positive = (rain ?: 0.0) > 0 || (snow ?: 0.0) > 0
        if (positive || (rain != null && snow != null)) { checked++; if (positive) wet++ }
    }
    return HistoryEvidence(wet, checked, targets.distinct().size - checked)
}
fun observedAmount(row: JSONObject, field: String, flag: String): Double? {
    if (row.optString(flag) !in listOf("", "0")) return null
    return row.optString(field).toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
}

