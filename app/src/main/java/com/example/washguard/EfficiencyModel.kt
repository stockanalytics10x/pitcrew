package com.example.washguard

import java.time.DayOfWeek
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * What this car taught the Pit Wall from its own drives. Pure Kotlin, unit tested.
 *
 * - Each finished drive gives a real km per % (odometer distance ÷ level used).
 * - Drives under conditions like now (temperature band, rain, day/night, weekday/weekend) and recent ones weigh more.
 * - The learned value is blended with the car's own range estimate: the more drives, the more it is trusted.
 * - Past predictions vs what actually happened give a bias to correct and an error band (±).
 *
 * Nothing here is an assumed rule such as "cold costs 20%": every effect comes from this car's records.
 * With no usable records it returns nothing and the Pit Wall works exactly as before.
 */
object EfficiencyModel {
    /** Drives worth learning from. */
    const val MIN_KM = 3.0
    const val MIN_USED_PERCENT = 1.0
    /** How many drives' worth of trust the car's own estimate starts with. */
    const val CAR_WEIGHT = 3.0
    /** Half-life of a record's weight. */
    const val HALF_LIFE_DAYS = 30.0
    /** Prediction errors needed before a ± band and a bias are used. */
    const val MIN_ERRORS = 3
    const val MIN_SPREAD = 2.0

    enum class TempBand(val label: String) { COLD("저온"), MILD("보통 기온"), HOT("고온") }

    fun tempBand(c: Double?): TempBand? = when {
        c == null -> null
        c < 5.0 -> TempBand.COLD
        c > 25.0 -> TempBand.HOT
        else -> TempBand.MILD
    }

    fun night(hour: Int): Boolean = hour >= 20 || hour < 6
    fun weekend(d: DayOfWeek): Boolean = d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY

    /** Conditions of the drive being planned, or of a recorded drive. */
    data class Conditions(val tempC: Double?, val rainy: Boolean?, val atMs: Long,
                          /** Highway-type drive (average 60 km/h or more, or a long route). Null = not known. */
                          val highway: Boolean? = null) {
        private val t = Instant.ofEpochMilli(atMs).atZone(KST_ZONE)
        val hour: Int get() = t.hour
        val day: DayOfWeek get() = t.dayOfWeek
    }

    /** One learnable drive. */
    data class Trip(val kmPerPercent: Double, val km: Double, val avgKmh: Double?, val conditions: Conditions,
                    /** Predicted minus actual arrival level, when the drive followed its route to the end. */
                    val arrivalError: Double?,
                    /** Level used, %. The car reports whole percents, so short drives carry a large rounding error. */
                    val usedPercent: Double = 0.0)

    fun trips(records: List<DriveRecord>): List<Trip> = records.mapNotNull { r ->
        val km = (r.endOdoKm ?: return@mapNotNull null) - (r.startOdoKm ?: return@mapNotNull null)
        val used = (r.startSoc ?: return@mapNotNull null) - (r.endSoc ?: return@mapNotNull null)
        if (km < MIN_KM || used < MIN_USED_PERCENT) return@mapNotNull null // too short, or charged on the way
        val hours = (r.endMs - r.startMs) / 3_600_000.0
        // The prediction counts only when the drive covered its route (within 15%), so a detour is not an "error".
        val err = if (r.predictedArrival != null && r.routeKm != null && r.routeKm > 0 && abs(km - r.routeKm) <= r.routeKm * 0.15)
            r.predictedArrival - r.endSoc else null
        val avg = if (hours > 0.05) km / hours else null
        Trip(km / used, km, avg, Conditions(r.tempC, r.rainy, r.startMs, avg?.let { it >= HIGHWAY_KMH } ?: (km >= HIGHWAY_KM)), err, used)
    }

    const val HIGHWAY_KMH = 60.0
    /** A planned trip at least this long is treated as highway-type when its speed is not known yet. */
    const val HIGHWAY_KM = 60.0

    /**
     * Drives whose km per % is far from this car's usual (odometer glitch, charged or reset on the way) are left out.
     * Median ± max(3·MAD, 25%) — only once there are enough drives to know what "usual" is.
     */
    fun clean(trips: List<Trip>): List<Trip> {
        if (trips.size < 5) return trips
        val v = trips.map { it.kmPerPercent }.sorted()
        val med = v[v.size / 2]
        val mad = trips.map { abs(it.kmPerPercent - med) }.sorted()[trips.size / 2] * 1.4826
        val lim = maxOf(3 * mad, 0.25 * med)
        return trips.filter { abs(it.kmPerPercent - med) <= lim }
    }

    /** How alike a past drive is to now: 1 = same conditions. Unknown on either side counts as half-alike. */
    fun similarity(past: Conditions, now: Conditions): Double {
        var w = 1.0
        val a = tempBand(past.tempC); val b = tempBand(now.tempC)
        w *= if (a == null || b == null) 0.7 else if (a == b) 1.0 else 0.35
        w *= if (past.rainy == null || now.rainy == null) 0.85 else if (past.rainy == now.rainy) 1.0 else 0.6
        w *= if (night(past.hour) == night(now.hour)) 1.0 else 0.75
        w *= if (weekend(past.day) == weekend(now.day)) 1.0 else 0.8
        return w
    }

    fun recency(past: Long, now: Long): Double = 0.5.pow(((now - past).coerceAtLeast(0) / 86_400_000.0) / HALF_LIFE_DAYS)

    data class Learned(
        /** km per % to plan with (blended), or null when nothing could be learned. */
        val kmPerPercent: Double?,
        /** This car's learned km per % under conditions like now, before blending. */
        val learnedKmPerPercent: Double?,
        /** Drives used, and their total weight (how much the learning counts). */
        val trips: Int,
        val weight: Double,
        /** Learned efficiency now vs this car's overall average, e.g. -0.07 = 7% fewer km per %. */
        val conditionEffect: Double?,
        /** Subtract from the arrival estimate (positive = past predictions were too optimistic). */
        val bias: Double,
        /** ± band of the arrival estimate, % points; null until enough predictions were checked. */
        val spread: Double?,
        val checked: Int,
        val note: String?,
        /** What each condition does to this car's efficiency, learned from its drives (shown when seen at least twice). */
        val factors: List<FactorEffect> = emptyList(),
        /** Drives left out as implausible. */
        val dropped: Int = 0
    ) {
        /** How far the learning has come, in words. */
        val stage: String get() = when {
            trips == 0 -> "학습 전 · 차량 추정치 사용"
            trips < 5 -> "학습 초기 · 차량 추정치 위주"
            trips < 15 -> "학습 중 · 이 차 기록 반영"
            else -> "학습 안정 · 이 차 기록 위주"
        }
    }

    val NONE = Learned(null, null, 0, 0.0, null, 0.0, null, 0, null)

    enum class Factor(val label: String) { RAIN("비"), COLD("저온 5℃ 미만"), HOT("고온 25℃ 초과"), HIGHWAY("고속 주행"), NIGHT("야간") }
    data class FactorEffect(val factor: Factor, val effect: Double, val trips: Int)

    /** 1 / 0 for a known condition, null when not known. */
    fun features(c: Conditions): List<Double?> {
        val band = tempBand(c.tempC)
        return listOf(
            c.rainy?.let { if (it) 1.0 else 0.0 },
            band?.let { if (it == TempBand.COLD) 1.0 else 0.0 },
            band?.let { if (it == TempBand.HOT) 1.0 else 0.0 },
            c.highway?.let { if (it) 1.0 else 0.0 },
            if (night(c.hour)) 1.0 else 0.0
        )
    }

    /** Shrinks each condition effect toward "no effect" while few drives show it. In units of drive weight. */
    const val RIDGE = 1.0

    /**
     * km per % for conditions like [now], from this car's drives.
     *
     * A weighted log-linear fit: log(km per %) = base + Σ effect × condition (rain, cold, heat, highway, night).
     * Recent drives and drives that used more battery (less rounding error) weigh more, and every effect is pulled
     * toward 0 by [RIDGE], so one rainy drive cannot claim "rain costs 30%". Conditions that are not known count at
     * their usual share. Implausible drives are left out first ([clean]).
     */
    fun learn(records: List<DriveRecord>, now: Conditions, carKmPerPercent: Double?, powertrain: Powertrain): Learned {
        val all = trips(records)
        val trips = clean(all)
        if (trips.isEmpty()) return NONE
        val ws = trips.map { recency(it.conditions.atMs, now.atMs) * (it.usedPercent / (it.usedPercent + 4.0)).coerceAtLeast(0.05) }
        val wSum = ws.sum()
        val fx = trips.map { features(it.conditions) }
        val k = Factor.values().size
        // Unknown values count at the weighted share of drives that had the condition.
        val share = DoubleArray(k) { j ->
            val known = trips.indices.filter { fx[it][j] != null }
            if (known.isEmpty()) 0.0 else known.sumOf { ws[it] * fx[it][j]!! } / known.sumOf { ws[it] }
        }
        val x = trips.indices.map { i -> DoubleArray(k + 1) { j -> if (j == 0) 1.0 else fx[i][j - 1] ?: share[j - 1] } }
        val y = trips.map { kotlin.math.ln(it.kmPerPercent) }
        val a = Array(k + 1) { r -> DoubleArray(k + 1) { c -> trips.indices.sumOf { ws[it] * x[it][r] * x[it][c] } + if (r == c && r > 0) RIDGE else 0.0 } }
        val b = DoubleArray(k + 1) { r -> trips.indices.sumOf { ws[it] * x[it][r] * y[it] } }
        val beta = solve(a, b) ?: return NONE
        fun predict(f: List<Double?>) = kotlin.math.exp(beta[0] + (0 until k).sumOf { j -> beta[j + 1] * (f[j] ?: share[j]) })
        val learned = predict(features(now))
        val typical = kotlin.math.exp(beta[0] + (0 until k).sumOf { j -> beta[j + 1] * share[j] })
        val blended = if (carKmPerPercent != null && carKmPerPercent > 0) (carKmPerPercent * CAR_WEIGHT + learned * wSum) / (CAR_WEIGHT + wSum) else learned
        val effect = if (trips.size >= 4) learned / typical - 1.0 else null
        val factors = Factor.values().mapIndexedNotNull { j, f ->
            val n = fx.count { it[j] == 1.0 }
            if (n >= 2 && n <= trips.size - 2) FactorEffect(f, kotlin.math.exp(beta[j + 1]) - 1.0, n) else null
        }

        // Past arrival predictions: recent ones weigh more. The band is the 80th percentile of the miss, not ±1 sd.
        val ev = trips.filter { it.arrivalError != null }
        val (bias, spread) = if (ev.size >= MIN_ERRORS) {
            val rw = ev.map { recency(it.conditions.atMs, now.atMs) }
            val mean = ev.indices.sumOf { ev[it].arrivalError!! * rw[it] } / rw.sum()
            val shrunk = mean * ev.size / (ev.size + 2.0)
            val misses = ev.map { abs(it.arrivalError!! - shrunk) }.sorted()
            val p80 = misses[((misses.size - 1) * 0.8).roundToIntSafe()]
            shrunk to maxOf(p80, MIN_SPREAD)
        } else 0.0 to null

        val parts = mutableListOf("이 차 기록 ${trips.size}회 학습")
        val label = listOfNotNull(tempBand(now.tempC)?.label, now.rainy?.let { if (it) "비" else null }, if (night(now.hour)) "야간" else null,
            if (now.highway == true) "고속" else null).joinToString("·")
        if (effect != null && abs(effect) >= 0.03) parts += "${label.ifEmpty { "지금 조건" }}엔 평소보다 ${if (powertrain.usesFuel) "연비" else "전비"} ${if (effect < 0) "−" else "+"}${pct(abs(effect))}%"
        if (ev.size >= MIN_ERRORS) parts += "예측 ${ev.size}회 검증 · 오차 ±${spread!!.toInt()}%p" + if (abs(bias) >= 1.0) " · ${if (bias > 0) "낙관" else "비관"} ${fmt(abs(bias))}%p 보정" else ""
        return Learned(blended, learned, trips.size, wSum, effect, bias, spread, ev.size, parts.joinToString(" · "), factors, all.size - trips.size)
    }

    private fun Double.roundToIntSafe(): Int = kotlin.math.round(this).toInt()

    /** Gaussian elimination with partial pivoting; null when the system is singular. */
    private fun solve(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
        val n = b0.size; val a = Array(n) { a0[it].copyOf() }; val b = b0.copyOf()
        for (c in 0 until n) {
            val p = (c until n).maxByOrNull { abs(a[it][c]) }!!
            if (abs(a[p][c]) < 1e-12) return null
            val t = a[p]; a[p] = a[c]; a[c] = t; val tb = b[p]; b[p] = b[c]; b[c] = tb
            for (r in c + 1 until n) { val f = a[r][c] / a[c][c]; for (j in c until n) a[r][j] -= f * a[c][j]; b[r] -= f * b[c] }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) x[r] = (b[r] - (r + 1 until n).sumOf { a[r][it] * x[it] }) / a[r][r]
        return x
    }

    /** The earlier method (similar-condition weighted average), kept for comparison in the engine check. */
    fun learnSimilar(records: List<DriveRecord>, now: Conditions): Double? {
        val trips = trips(records)
        if (trips.isEmpty()) return null
        val ws = trips.map { similarity(it.conditions, now) * recency(it.conditions.atMs, now.atMs) * (it.km / (it.km + 20.0)) }
        return trips.indices.sumOf { trips[it].kmPerPercent * ws[it] } / ws.sum()
    }

    /** Efficiency of this car under one kind of condition, compared with all its drives. */
    data class Group(val label: String, val kmPerPercent: Double, val trips: Int, val vsAll: Double)

    /** This car's efficiency by condition, from its own drives (groups with at least two drives). */
    fun groups(records: List<DriveRecord>): List<Group> {
        val t = trips(records)
        if (t.size < 4) return emptyList()
        val all = t.map { it.kmPerPercent }.average()
        fun g(label: String, pick: (Trip) -> Boolean) = t.filter(pick).takeIf { it.size >= 2 }?.let { ts ->
            val k = ts.map { it.kmPerPercent }.average(); Group(label, k, ts.size, k / all - 1.0)
        }
        return listOfNotNull(
            g("맑고 보통 기온") { it.conditions.rainy == false && tempBand(it.conditions.tempC) == TempBand.MILD },
            g("비 오는 날") { it.conditions.rainy == true },
            g("저온 (5℃ 미만)") { tempBand(it.conditions.tempC) == TempBand.COLD },
            g("고온 (25℃ 초과)") { tempBand(it.conditions.tempC) == TempBand.HOT },
            g("야간") { night(it.conditions.hour) },
            g("주말") { weekend(it.conditions.day) },
            g("평균 시속 60km 이상") { (it.avgKmh ?: 0.0) >= 60.0 }
        )
    }

    /** How right past arrival predictions were: predicted − actual, % points (positive = too optimistic). */
    data class Accuracy(val checked: Int, val meanError: Double, val meanAbsError: Double, val recent: List<Double>)

    fun accuracy(records: List<DriveRecord>): Accuracy? {
        val errs = trips(records).filter { it.arrivalError != null }.sortedBy { it.conditions.atMs }.map { it.arrivalError!! }
        if (errs.isEmpty()) return null
        return Accuracy(errs.size, errs.average(), errs.map { abs(it) }.average(), errs.takeLast(12))
    }

    private fun pct(v: Double) = (v * 100).toInt()
    private fun fmt(v: Double) = String.format(Locale.US, "%.0f", v)
}
