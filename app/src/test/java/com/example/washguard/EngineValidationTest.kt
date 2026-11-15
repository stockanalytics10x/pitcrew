package com.example.washguard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.random.Random

/**
 * Validation of the learning and the pit forecast on simulated cars whose true behaviour is known.
 *
 * Each simulated car has its own base efficiency and its own (hidden) condition effects, its driver has a weekly
 * routine with random extra trips, the battery is reported in whole percents like a real car, and a few records
 * are broken (odometer glitch). The engine never sees the true values; it is checked walk-forward.
 */
class EngineValidationTest {

    data class Truth(val base: Double, val rain: Double, val cold: Double, val hot: Double, val highway: Double, val night: Double)

    enum class Driver { COMMUTER, WEEKEND_TRIPPER, IRREGULAR }

    class SimCar(seed: Int, val driver: Driver, val days: Int = 90, val glitches: Boolean = true) {
        private val rnd = Random(seed)
        val truth = Truth(
            base = 4.6 + rnd.nextDouble() * 1.4,
            rain = -0.04 - rnd.nextDouble() * 0.08,
            cold = -0.08 - rnd.nextDouble() * 0.14,
            hot = -0.02 - rnd.nextDouble() * 0.06,
            highway = -0.05 - rnd.nextDouble() * 0.10,
            night = -0.01 - rnd.nextDouble() * 0.03
        )
        val now: Long = LocalDateTime.of(2026, 11, 13, 18, 0).atZone(KST_ZONE).toInstant().toEpochMilli()
        /** Midnight that starts simulated day [d]. */
        fun dayStart(d: Int): Long = LocalDateTime.of(2026, 11, 13, 0, 0).atZone(KST_ZONE).toInstant().toEpochMilli() - (days - d) * 86_400_000L
        val records = mutableListOf<DriveRecord>()
        /** km actually driven per day index (0 = first day). */
        val kmByDay = DoubleArray(days)
        /** Battery % actually used per day (true efficiency of each drive, before rounding). */
        val usedByDay = DoubleArray(days)
        val rainyDay = BooleanArray(days)
        val tempByDay = DoubleArray(days)

        fun trueKpp(rain: Boolean, temp: Double, highway: Boolean, night: Boolean): Double =
            truth.base * (if (rain) 1 + truth.rain else 1.0) * (if (temp < 5) 1 + truth.cold else 1.0) * (if (temp > 25) 1 + truth.hot else 1.0) *
                (if (highway) 1 + truth.highway else 1.0) * (if (night) 1 + truth.night else 1.0)

        init {
            var odo = 20_000.0
            var soc = 80
            val day = 86_400_000L
            for (d in 0 until days) {
                val startDay = dayStart(d)
                val dow = java.time.Instant.ofEpochMilli(startDay).atZone(KST_ZONE).dayOfWeek
                val weekend = EfficiencyModel.weekend(dow)
                // Seasons: the first month mild, then colder.
                val temp = (if (d < days / 3) 16.0 else if (d < 2 * days / 3) 8.0 else 1.0) + rnd.nextDouble(-6.0, 6.0)
                val rain = rnd.nextDouble() < 0.22
                rainyDay[d] = rain
                tempByDay[d] = temp
                val trips = mutableListOf<Pair<Double, Int>>() // km, start hour
                when (driver) {
                    Driver.COMMUTER -> if (!weekend) { trips += 22.0 + rnd.nextDouble(-3.0, 3.0) to 8; trips += 22.0 + rnd.nextDouble(-3.0, 3.0) to 18 }
                        else if (rnd.nextDouble() < 0.4) trips += 40.0 + rnd.nextDouble(0.0, 60.0) to 11
                    Driver.WEEKEND_TRIPPER -> if (!weekend) { if (rnd.nextDouble() < 0.7) trips += 12.0 + rnd.nextDouble(0.0, 8.0) to 9 }
                        else if (dow == java.time.DayOfWeek.SATURDAY) trips += 160.0 + rnd.nextDouble(-40.0, 60.0) to 9
                        else trips += 150.0 + rnd.nextDouble(-40.0, 40.0) to 17
                    Driver.IRREGULAR -> repeat(rnd.nextInt(0, 3)) { trips += (if (rnd.nextDouble() < 0.15) 120.0 else 15.0) + rnd.nextDouble(0.0, 30.0) to rnd.nextInt(6, 23) }
                }
                for ((km, hour) in trips) {
                    val highway = km >= 60
                    val night = hour >= 20 || hour < 6
                    val kpp = trueKpp(rain, temp, highway, night) * (1 + rnd.nextDouble(-0.05, 0.05))
                    if (soc - km / kpp < 15) soc = 80 + rnd.nextInt(0, 15)   // charged overnight
                    val startMs = startDay + hour * 3_600_000L
                    val exactEnd = soc - km / kpp
                    var endSoc = kotlin.math.round(exactEnd).toInt()
                    var endOdo = odo + km
                    // ~3% of records are broken: odometer glitch.
                    if (glitches && rnd.nextDouble() < 0.03) endOdo = odo + km * 3
                    // A third of drives had a navi route; the prediction made then is filled in by the walk-forward check.
                    val routed = rnd.nextDouble() < 0.35
                    records += DriveRecord(startMs, startMs + (km / (if (highway) 85.0 else 32.0) * 3_600_000).toLong(),
                        soc.toDouble(), endSoc.toDouble(), odo, endOdo, null, 0, 0, if (routed) km else null, temp, rain)
                    kmByDay[d] += km
                    usedByDay[d] += km / kpp
                    odo += km
                    soc = endSoc
                }
            }
        }
    }

    private fun carsOf(n: Int) = (1..n).flatMap { s -> Driver.values().map { SimCar(s * 31 + it.ordinal, it) } }

    @Test fun learningBeatsPlainAverageOnSimulatedCars() {
        val cars = carsOf(10)
        val results = cars.mapNotNull { EngineCheck.energy(it.records) }
        val engine = results.map { it.enginePp }.average()
        val plain = results.map { it.averagePp }.average()
        val similar = results.map { it.similarPp }.average()
        val within2 = results.map { it.within2 }.average()
        println("ENERGY bias=%.2f%%p per drive".format(results.map { it.engineBias }.average()))
        println("ENERGY  cars=${results.size} drives=${results.sumOf { it.checked }}  engine=%.2f%%p  similar=%.2f%%p  plain=%.2f%%p  gain=%.0f%%  within2=%.0f%%"
            .format(engine, similar, plain, (1 - engine / plain) * 100, within2 * 100))
        val firsts = results.mapNotNull { it.firstPp }; val lasts = results.mapNotNull { it.recentPp }
        if (firsts.isNotEmpty()) println("LEARNING CURVE first10=%.2f%%p last10=%.2f%%p".format(firsts.average(), lasts.average()))
        for (d in Driver.values()) {
            val r = cars.filter { it.driver == d }.mapNotNull { EngineCheck.energy(it.records) }
            println("  $d engine=%.2f similar=%.2f plain=%.2f".format(r.map { it.enginePp }.average(), r.map { it.similarPp }.average(), r.map { it.averagePp }.average()))
        }
        assertTrue("engine $engine vs plain $plain", engine < plain)
        assertTrue("engine $engine vs similar $similar", engine <= similar * 1.02)
    }

    @Test fun learnedEffectsMatchTheHiddenTruth() {
        var hits = 0; var total = 0
        val errs = mutableMapOf<EfficiencyModel.Factor, MutableList<Double>>()
        for (car in carsOf(10)) {
            val l = EfficiencyModel.learn(car.records, EfficiencyModel.Conditions(10.0, false, car.now), null, Powertrain.EV)
            val truth = mapOf(EfficiencyModel.Factor.RAIN to car.truth.rain, EfficiencyModel.Factor.COLD to car.truth.cold,
                EfficiencyModel.Factor.HIGHWAY to car.truth.highway)
            for (f in l.factors) {
                val t = truth[f.factor] ?: continue
                errs.getOrPut(f.factor) { mutableListOf() } += abs(f.effect - t)
                total++; if ((f.effect < 0) == (t < 0)) hits++
            }
        }
        errs.forEach { (f, e) -> println("FACTOR $f mean |learned − true| = %.1f%%p".format(e.average() * 100)) }
        println("FACTOR sign right $hits / $total")
        assertTrue(hits >= total * 0.85)
        assertTrue(errs.values.all { it.average() < 0.05 })
    }

    @Test fun glitchedRecordsAreLeftOut() {
        val car = SimCar(5, Driver.COMMUTER)
        val l = EfficiencyModel.learn(car.records, EfficiencyModel.Conditions(10.0, false, car.now), null, Powertrain.EV)
        println("GLITCH dropped=${l.dropped} of ${EfficiencyModel.trips(car.records).size}  learned=%.2f true≈%.2f".format(l.learnedKmPerPercent, car.truth.base))
        assertTrue(l.dropped >= 1)
        val clean = EfficiencyModel.learn(SimCar(5, Driver.COMMUTER, glitches = false).records, EfficiencyModel.Conditions(10.0, false, car.now), null, Powertrain.EV)
        assertTrue(abs(l.learnedKmPerPercent!! - clean.learnedKmPerPercent!!) / clean.learnedKmPerPercent!! < 0.03)
    }

    @Test fun weekdayPatternBeatsFlatAverage() {
        val r = carsOf(10).mapNotNull { c -> EngineCheck.daily(c.records)?.let { c.driver to it } }
        val p = r.map { it.second.patternKm }.average(); val f = r.map { it.second.flatKm }.average()
        println("DAILY km miss pattern=%.1f flat=%.1f gain=%.0f%%".format(p, f, (1 - p / f) * 100))
        for (d in Driver.values()) r.filter { it.first == d }.let { x ->
            println("  $d pattern=%.1f flat=%.1f".format(x.map { it.second.patternKm }.average(), x.map { it.second.flatKm }.average())) }
        assertTrue(p < f)
    }

    /**
     * Charge timing: each morning from day 35, forecast the day the battery falls below 20% (no charging) and compare
     * with what the simulated car really did. "Late" (told a later day than reality) is the dangerous miss.
     */
    @Test fun chargeDayForecast() {
        var exact = 0; var withinOne = 0; var late = 0; var n = 0; var early2 = 0
        val calN = IntArray(5); val calP = DoubleArray(5); val calHit = IntArray(5)
        var under10 = 0; var flatUnder10 = 0; var lowSum = 0.0; var flatLowSum = 0.0
        var flatExact = 0; var flatLate = 0
        for (car in carsOf(6)) {
            val day = 86_400_000L
            for (d in 35 until car.days - 8) {
                val nowMs = car.dayStart(d) + 6 * 3_600_000L   // 06:00, before the day's drives
                val past = car.records.filter { it.endMs <= nowMs }
                if (past.isEmpty()) continue
                val soc = 30.0 + (d % 5) * 8.0
                // As in the app: today's temperature (forecast) and rain, and the KMA rain forecast for the next days.
                val l = EfficiencyModel.learn(past, EfficiencyModel.Conditions(car.tempByDay[d], false, nowMs), null, Powertrain.EV)
                val today = java.time.Instant.ofEpochMilli(nowMs).atZone(KST_ZONE).toLocalDate()
                val fc = (0..PitForecastEngine.FORECAST_DAYS).map { DayForecast(today.plusDays(it.toLong()), if (car.rainyDay[d + it]) 80 else 10, if (car.rainyDay[d + it]) 5.0 else 0.0, true) }
                val rainEffect = l.factors.firstOrNull { it.factor == EfficiencyModel.Factor.RAIN }?.effect
                val kpp = l.learnedKmPerPercent ?: continue
                // Real day: drive the true kms with the true-ish efficiency until under 20%.
                var lvl = soc; var actual = -1
                for (i in 0 until 11) { lvl -= car.usedByDay[d + i]; if (lvl < PitForecastEngine.SAFE_SOC) { actual = i; break } }
                if (actual < 0) actual = 99   // did not fall under 20% within 11 days: kept (dropping these would bias every number)
                val samples = past.flatMap { listOf(EnergySample(it.startMs, it.startSoc, it.startOdoKm, null), EnergySample(it.endMs, it.endSoc, it.endOdoKm, null)) }
                val inp = PitForecastInput(nowMs, soc, soc * kpp, null, samples, fc, null, 0, 0, records = past, learnedKmPerPercent = kpp, rainEffect = rainEffect)
                val fcRes = PitForecastEngine.forecast(inp).charge
                fcRes.risk.forEachIndexed { i, p -> val b = (p * 5).toInt().coerceAtMost(4); calN[b]++; calP[b] += p; if (actual <= i) calHit[b]++ }
                val f = fcRes.chargeByIndex ?: PitForecastEngine.FORECAST_DAYS + 7
                val flat = PitForecastEngine.forecast(inp.copy(records = emptyList())).charge.chargeByIndex ?: PitForecastEngine.FORECAST_DAYS + 7
                n++
                // Following the advice = charging the morning of the day it names. How low does the battery get first?
                fun lowest(by: Int) = soc - (0 until minOf(by, 11)).sumOf { car.usedByDay[d + it] }
                val lo = lowest(f); val flo = lowest(flat)
                lowSum += lo; flatLowSum += flo; if (lo < 10) under10++; if (flo < 10) flatUnder10++
                val fa = minOf(f, 11); val aa = minOf(actual, 11); if (fa == aa) exact++; if (abs(fa - aa) <= 1) withinOne++; if (f > actual) late++; if (f <= actual - 2) early2++
                if (minOf(flat, 11) == minOf(actual, 11)) flatExact++; if (flat > actual) flatLate++
            }
        }
        println("CHARGE DAY (all mornings, start 30–62%%) n=$n exact=%.0f%% ±1day=%.0f%% late=%.0f%% early≥2d=%.0f%%  | flat exact=%.0f%% late=%.0f%%"
            .format(exact * 100.0 / n, withinOne * 100.0 / n, late * 100.0 / n, early2 * 100.0 / n, flatExact * 100.0 / n, flatLate * 100.0 / n))
        println("FOLLOW ADVICE lowest level avg engine=%.1f%% flat=%.1f%%  under 10%%: engine=%.1f%% flat=%.1f%%"
            .format(lowSum / n, flatLowSum / n, under10 * 100.0 / n, flatUnder10 * 100.0 / n))
        println("CALIBRATION " + (0 until 5).filter { calN[it] > 0 }.joinToString { "said %.0f%% → happened %.0f%% (n=${calN[it]})".format(calP[it] / calN[it] * 100, calHit[it] * 100.0 / calN[it]) })
        assertTrue(n > 20)
        // Following the engine's day must leave the battery under 10% far less often than a flat daily average.
        assertTrue(under10 < flatUnder10 / 2)
        assertTrue(late < flatLate)
        assertTrue(exact > flatExact)
    }

    @Test fun exampleHistoryCheck() {
        val e = EngineCheck.energy(DemoHistory.records(DemoHistory.NOW_MS))!!
        val d = EngineCheck.daily(DemoHistory.records(DemoHistory.NOW_MS))
        val l = DemoHistory.learned(5.0, Powertrain.EV)
        println("EXAMPLE energy engine=%.2f plain=%.2f similar=%.2f within2=%.0f%% checked=${e.checked}".format(e.enginePp, e.averagePp, e.similarPp, e.within2 * 100))
        println("EXAMPLE daily ${d?.let { "pattern=%.1f flat=%.1f".format(it.patternKm, it.flatKm) }}")
        println("EXAMPLE learned ${l.stage} kpp=" + "%.2f".format(l.kmPerPercent) + " factors=" + l.factors.map { it.factor.label + " " + "%+.0f".format(it.effect * 100) + "% (" + it.trips + ")" } + " note=" + l.note)
        assertTrue(e.enginePp < e.averagePp)
    }
}
