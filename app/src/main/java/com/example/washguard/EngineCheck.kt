package com.example.washguard

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Engine check: how well the Pit Wall would have predicted this car's own past drives.
 *
 * Walk-forward, so nothing is predicted from its own future: for each drive, learn only from the drives before it,
 * predict the battery it would use, and compare with what it really used. The same is done with one plain average
 * (what a fixed "this car does N km per %" would say), so the gain of the learning is measured, not claimed.
 */
object EngineCheck {
    /** Drives learned from before a drive is predicted. */
    const val WARMUP = 3

    data class Energy(
        /** Drives predicted. */
        val checked: Int,
        /** Mean miss of the battery used, % points: learning engine / one plain average / the earlier similar-day method. */
        val enginePp: Double,
        val averagePp: Double,
        val similarPp: Double,
        /** Share of drives the engine got within 2 % points. */
        val within2: Double,
        /** Same, last [RECENT] drives only: shows the learning getting better over time. */
        val recentPp: Double?,
        val firstPp: Double?,
        /** Mean signed miss, % points: negative = predicted less battery than was used (optimistic). */
        val engineBias: Double = 0.0
    ) {
        /** How much smaller the engine's miss is than the plain average, 0.25 = 25%. */
        val gain: Double get() = if (averagePp > 0) 1 - enginePp / averagePp else 0.0
    }

    const val RECENT = 10

    fun energy(records: List<DriveRecord>, powertrain: Powertrain = Powertrain.EV): Energy? {
        val sorted = records.sortedBy { it.startMs }
        val all = EfficiencyModel.trips(sorted)
        if (all.size < WARMUP + 3) return null
        val clean = EfficiencyModel.clean(all).toSet()
        val signed = mutableListOf<Double>()
        val eng = mutableListOf<Double>(); val avg = mutableListOf<Double>(); val sim = mutableListOf<Double>()
        for (t in all) {
            if (t !in clean) continue                       // an implausible drive is not a fair target either
            val before = sorted.filter { it.endMs <= t.conditions.atMs }
            val past = EfficiencyModel.clean(EfficiencyModel.trips(before))
            if (past.size < WARMUP) continue
            val learned = EfficiencyModel.learn(before, t.conditions, null, powertrain).learnedKmPerPercent ?: continue
            val plain = past.sumOf { it.km } / past.sumOf { it.usedPercent }
            val similar = EfficiencyModel.learnSimilar(before, t.conditions) ?: plain
            eng += abs(t.km / learned - t.usedPercent); signed += t.km / learned - t.usedPercent
            avg += abs(t.km / plain - t.usedPercent)
            sim += abs(t.km / similar - t.usedPercent)
        }
        if (eng.size < 3) return null
        return Energy(eng.size, eng.average(), avg.average(), sim.average(), eng.count { it <= 2.0 }.toDouble() / eng.size,
            eng.takeLast(RECENT).takeIf { eng.size >= 2 * RECENT }?.average(), eng.take(RECENT).takeIf { eng.size >= 2 * RECENT }?.average(), signed.average())
    }

    data class Daily(
        val days: Int,
        /** Mean miss of the km driven next day, km: weekday pattern vs one flat daily average. */
        val patternKm: Double,
        val flatKm: Double
    ) {
        val gain: Double get() = if (flatKm > 0) 1 - patternKm / flatKm else 0.0
    }

    /** Walk-forward check of the next-day distance the charge forecast plans with. */
    fun daily(records: List<DriveRecord>, zone: ZoneId = KST_ZONE): Daily? {
        val rs = records.filter { it.startOdoKm != null && it.endOdoKm != null && it.endOdoKm >= it.startOdoKm }.sortedBy { it.startMs }
        if (rs.isEmpty()) return null
        fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val perDay = rs.groupBy { day(it.startMs) }.mapValues { (_, v) -> v.sumOf { it.endOdoKm!! - it.startOdoKm!! } }
        val first = day(rs.first().startMs); val last = day(rs.last().startMs)
        val pat = mutableListOf<Double>(); val flat = mutableListOf<Double>()
        var d = first.plusDays(PitForecastEngine.PATTERN_MIN_WEEKS * 7L)
        while (!d.isAfter(last)) {
            val cut = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val before = rs.filter { it.endMs <= cut }
            val pattern = PitForecastEngine.weekdayKm(before, cut - 1, zone)
            val recentDays = (1..PitForecastEngine.HISTORY_WINDOW_DAYS).map { perDay[d.minusDays(it)] ?: 0.0 }
            val actual = perDay[d] ?: 0.0
            if (pattern != null) { pat += abs(pattern[d.dayOfWeek]!! - actual); flat += abs(recentDays.average() - actual) }
            d = d.plusDays(1)
        }
        if (pat.size < 5) return null
        return Daily(pat.size, pat.average(), flat.average())
    }
}
