package com.example.washguard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class PitForecastEngineTest {
    private val day = 86_400_000L
    // 2026-10-28 (Wed) 09:00 KST
    private val now = LocalDateTime.of(2026, 10, 28, 9, 0).atZone(KST_ZONE).toInstant().toEpochMilli()
    private val today = LocalDate.of(2026, 10, 28)

    private fun dry(offset: Long, pop: Int = 10) = DayForecast(today.plusDays(offset), pop, 0.0, true)
    private fun wet(offset: Long) = DayForecast(today.plusDays(offset), 80, 5.0, true)

    /** Odometer log: [days] days of history, [kmPerDay] each day. */
    private fun log(days: Int, kmPerDay: Double) = (0..days).map { d ->
        EnergySample(now - (days - d) * day, null, 1000.0 + d * kmPerDay, null)
    }

    private fun input(
        soc: Double? = 60.0, range: Double? = 300.0, remaining: Double? = null,
        samples: List<EnergySample> = log(7, 50.0), days: List<DayForecast> = (0L..4L).map { dry(it) },
        nearRain: String? = null, lastWash: Long = now - 20 * day, wet: Int = 0
    ) = PitForecastInput(now, soc, range, remaining, samples, days, nearRain, lastWash, wet)

    @Test fun dailyKmNeedsThreeDaysAndIgnoresResets() {
        assertNull(PitForecastEngine.dailyKm(log(2, 50.0), now).first)
        assertEquals(50.0, PitForecastEngine.dailyKm(log(7, 50.0), now).first!!, 0.001)
        val reset = listOf(EnergySample(now - 5 * day, null, 5000.0, null), EnergySample(now, null, 10.0, null))
        assertNull(PitForecastEngine.dailyKm(reset, now).first)
        // older than 14 days is not used
        val old = listOf(EnergySample(now - 20 * day, null, 0.0, null), EnergySample(now - 16 * day, null, 400.0, null))
        assertNull(PitForecastEngine.dailyKm(old, now).first)
    }

    @Test fun dailyKmRejectsAnImpossibleDailyAverage() {
        // 700 km every day for a week is not believed
        assertNull(PitForecastEngine.dailyKm(log(7, 700.0), now).first)
    }

    @Test fun dailyKmSkipsAnOdometerSetByHand() {
        // a week at 50 km a day, then the odometer jumps by 12,000 km within an hour (emulator, new head unit)
        val jump = log(7, 50.0) + EnergySample(now + 3_600_000, null, 1000.0 + 7 * 50.0 + 12_000.0, null)
        assertEquals(50.0, PitForecastEngine.dailyKm(jump, now + 3_600_000).first!!, 1.0)
        // and a car that sends 0 for no reading
        val zero = listOf(EnergySample(now - 5 * day, null, 0.0, null)) + log(4, 30.0)
        assertEquals(30.0, PitForecastEngine.dailyKm(zero, now).first!!, 0.5)
    }

    @Test fun chargeDateFromDailyUse() {
        // 5 km per %, 50 km/day → 10 %/day. 60% → mornings 60, 50, 40, 30; drops below 20 during day 4
        val f = PitForecastEngine.forecast(input())
        assertEquals(10.0, f.charge.dailyUsePercent.value!!, 0.001)
        assertEquals(listOf(60.0, 50.0, 40.0, 30.0), f.charge.morningSoc)
        assertEquals(4, f.charge.chargeByIndex)
        assertEquals(ChargeStatus.LATER, f.charge.status)
        // 40% → by day 2
        val g = PitForecastEngine.forecast(input(soc = 40.0, range = 200.0))
        assertEquals(2, g.charge.chargeByIndex)
        assertEquals(ChargeStatus.BY_DATE, g.charge.status)
        assertEquals(today.plusDays(2), g.charge.chargeByDate)
    }

    @Test fun todaysRouteIsSubtractedFirst() {
        // 60% − 150 km / 5 = 30% start
        val f = PitForecastEngine.forecast(input(remaining = 150.0))
        assertEquals(30.0, f.charge.morningSoc[0]!!, 0.001)
        assertEquals(1, f.charge.chargeByIndex)
        val now2 = PitForecastEngine.forecast(input(remaining = 220.0))
        assertEquals(ChargeStatus.NOW, now2.charge.status)
        assertEquals(0, now2.charge.chargeByIndex)
    }

    @Test fun noHistoryGivesDistanceNotDate() {
        val f = PitForecastEngine.forecast(input(samples = emptyList()))
        assertEquals(ChargeStatus.NO_HISTORY, f.charge.status)
        assertNull(f.charge.chargeByIndex)
        assertEquals(200.0, f.charge.kmTo20.value!!, 0.001) // (60-20)*5
        assertTrue(f.charge.reason.contains("200 km"))
        val unknown = PitForecastEngine.forecast(input(soc = null))
        assertEquals(ChargeStatus.UNKNOWN, unknown.charge.status)
        assertTrue(unknown.charge.morningSoc.all { it == null })
    }

    @Test fun washDayNeedsTheNextDayDryToo() {
        // dry, wet, dry, dry, dry → today BAD (rain tomorrow), tomorrow BAD, day2 GOOD
        val f = PitForecastEngine.forecast(input(days = listOf(dry(0), wet(1), dry(2), dry(3), dry(4))))
        assertEquals(listOf(WashRating.BAD, WashRating.BAD, WashRating.GOOD, WashRating.GOOD), f.wash.days.map { it.rating })
        assertEquals(2, f.wash.recommendedIndex)
    }

    @Test fun nearRainMakesTodayWetAndMissingForecastIsUnknown() {
        val f = PitForecastEngine.forecast(input(nearRain = "10-28 11:00"))
        assertEquals(DayWeather.WET, f.wash.days[0].weather)
        val none = PitForecastEngine.forecast(input(days = emptyList()))
        assertTrue(none.wash.days.all { it.rating == WashRating.UNKNOWN })
        assertNull(none.wash.recommendedIndex)
        assertTrue(none.wash.reason.contains("예보를 받지 못해"))
        // incomplete last day → the day before is only OK
        val partial = PitForecastEngine.forecast(input(days = listOf(wet(0), wet(1), wet(2), dry(3))))
        assertEquals(WashRating.OK, partial.wash.days[3].rating)
        assertEquals(3, partial.wash.recommendedIndex)
    }

    @Test fun washNeedReasons() {
        assertEquals(WashNeed.NO_RECORD, PitForecastEngine.forecast(input(lastWash = 0)).wash.need)
        assertEquals(WashNeed.EXPOSED, PitForecastEngine.forecast(input(lastWash = now - 3 * day, wet = 4)).wash.need)
        assertEquals(WashNeed.DUE, PitForecastEngine.forecast(input(lastWash = now - 15 * day)).wash.need)
        assertEquals(WashNeed.RECENT, PitForecastEngine.forecast(input(lastWash = now - 2 * day)).wash.need)
    }

    @Test fun chargeAndWashCombineOnTheSameDay() {
        val f = PitForecastEngine.forecast(input(soc = 40.0, range = 200.0, days = listOf(wet(0), dry(1), dry(2), dry(3), dry(4))))
        assertEquals(2, f.charge.chargeByIndex)
        assertEquals(1, f.wash.recommendedIndex)
        assertEquals(1, f.combinedIndex)
        assertTrue(f.headline.startsWith("내일"))
        assertTrue(f.headline.contains("충전하면서 세차"))
    }

    @Test fun derivationShowsTheNumbersAndNoMoneyWords() {
        val f = PitForecastEngine.forecast(input())
        assertTrue(f.derivation.first().contains("300 km ÷ 잔량 60%"))
        assertTrue(f.derivation.any { it.contains("하루 평균 50 km") })
        val all = (f.derivation + f.headline + f.charge.reason + f.wash.reason).joinToString(" ")
        listOf("원", "절감", "저렴", "최저", "AI").forEach { assertFalse(it, all.contains(it)) }
    }

    @Test fun parseDaysUsesRemainingHoursToday() {
        val rows = mutableListOf<JSONObject>()
        fun add(date: String, hour: Int, pop: Int, pcp: String) {
            rows += JSONObject(mapOf("fcstDate" to date, "fcstTime" to "%02d00".format(hour), "category" to "POP", "fcstValue" to pop.toString()))
            rows += JSONObject(mapOf("fcstDate" to date, "fcstTime" to "%02d00".format(hour), "category" to "PCP", "fcstValue" to pcp))
        }
        (21..23).forEach { add("20261028", it, 30, "강수없음") }
        (0..23).forEach { add("20261029", it, if (it == 15) 70 else 20, if (it == 15) "2.0mm" else "강수없음") }
        (0..11).forEach { add("20261030", it, 10, "강수없음") }
        val days = parseDays(rows, LocalDateTime.of(2026, 10, 28, 21, 10), 3)
        assertEquals(DayForecast(today, 30, 0.0, true), days[0])
        assertEquals(DayForecast(today.plusDays(1), 70, 2.0, true), days[1])
        assertFalse(days[2].complete)
        assertEquals(DayWeather.WET, PitForecastEngine.dayWeather(days[1]))
        assertEquals(DayWeather.UNKNOWN, PitForecastEngine.dayWeather(days[2]))
    }

    @Test fun energyLogKeepsUsefulSamplesOnly() {
        val a = EnergySample(now, 60.0, 1000.0, 300.0)
        val l1 = EnergyLogRules.append(emptyList(), a, now)
        assertEquals(1, l1.size)
        // 10 minutes later, nothing changed → skipped
        assertTrue(EnergyLogRules.append(l1, a.copy(time = now + 600_000), now) === l1)
        // moved 2 km → kept
        assertEquals(2, EnergyLogRules.append(l1, a.copy(time = now + 600_000, odometerKm = 1002.0), now).size)
        // 31 minutes later → kept
        assertEquals(2, EnergyLogRules.append(l1, a.copy(time = now + 31 * 60_000), now).size)
        // nothing usable → skipped
        assertTrue(EnergyLogRules.append(l1, EnergySample(now + day, null, null, 300.0), now) === l1)
        // range arriving later (permission granted after start) is kept even within 30 minutes
        val noRange = EnergyLogRules.append(emptyList(), a.copy(rangeKm = null), now)
        assertEquals(2, EnergyLogRules.append(noRange, a.copy(time = now + 60_000), now).size)
        assertEquals(1234.5, parseOdometer("1,234.5 km")!!, 0.001)
        assertNull(parseOdometer("—"))
    }

    @Test fun fuelCarsHearRefuelWords() {
        val ev = PitForecastEngine.forecast(input(soc = 15.0, days = (0L..4L).map { wet(it) }))
        val ice = PitForecastEngine.forecast(input(soc = 15.0, days = (0L..4L).map { wet(it) }).copy(powertrain = Powertrain.ICE))
        assertEquals("오늘 충전이 필요해요", ev.headline)
        assertEquals("오늘 주유가 필요해요", ice.headline)
        assertEquals(Powertrain.ICE, ice.powertrain)
        assertFalse(ice.derivation.any { it.contains("충전") })
    }

    @Test fun weekdayPatternDecidesTheChargeDay() {
        // Three weeks: 40 km every weekday, nothing at weekends. Today is Wednesday 2026-10-28.
        val recs = (1..21).mapNotNull { back ->
            val d = today.minusDays(back.toLong())
            if (d.dayOfWeek.value >= 6) null
            else { val t = d.atTime(8, 0).atZone(KST_ZONE).toInstant().toEpochMilli(); DriveRecord(t, t + 3_600_000L, 80.0, 72.0, 1000.0, 1040.0, null, 0, 0) }
        }
        val p = PitForecastEngine.weekdayKm(recs, now)!!
        // Weekend: no drives, pulled a little toward the overall average (few samples per weekday).
        assertTrue(p[java.time.DayOfWeek.SATURDAY]!! < 10.0)
        assertTrue(p[java.time.DayOfWeek.MONDAY]!! > 30.0)
        // 5 km per %, 40 km = 8 %/weekday. 38% → Wed 30, Thu 22, Fri drops below 20 during the day.
        val f = PitForecastEngine.forecast(input(soc = 38.0, range = 190.0, samples = emptyList()).copy(records = recs))
        assertEquals(2, f.charge.chargeByIndex)
        assertTrue(f.charge.reason.contains("평일"))
    }

    @Test fun learnedKmPerPercentWithoutRangeReadingDoesNotCrash() {
        // Emulator case: the car sends a charge level but no range yet, while past drives already taught km per %.
        val f = PitForecastEngine.forecast(input(range = null).copy(learnedKmPerPercent = 5.0))
        assertTrue(f.derivation.any { it.contains("학습") })
    }
}
