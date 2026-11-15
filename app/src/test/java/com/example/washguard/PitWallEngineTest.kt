package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PitWallEngineTest {
    private fun plan(soc: Double?, range: Double?, remaining: Double?, pitAt: Double? = null, demo: Boolean = false) =
        PitWallEngine.plan(PitWallInput(soc, range, remaining, pitAt, demo = demo))

    // 52% with 260 km range => 5 km per percent.
    @Test fun kmPerPercentComesFromVehicleReadings() {
        val p = plan(52.0, 260.0, 100.0)
        assertEquals(5.0, p.kmPerPercent.value!!, 1e-9)
        assertEquals(DataSource.ESTIMATED, p.kmPerPercent.source)
        assertEquals(DataSource.MEASURED, p.soc.source)
    }

    @Test fun finishWhenArrivalAtLeastTwentyPercent() {
        val p = plan(52.0, 260.0, 160.0) // 52 - 32 = 20
        assertEquals(PitVerdict.FINISH_OK, p.verdict)
        assertEquals(20.0, p.arrivalPercent.value!!, 1e-9)
        assertFalse(p.needsPit)
        assertNull(p.chargeTargetPercent.value)
    }

    @Test fun boundaryJustBelowTwentyAdvisesPit() {
        val p = plan(52.0, 260.0, 160.5) // 19.9
        assertEquals(PitVerdict.PIT_ADVISED, p.verdict)
    }

    @Test fun belowTenPercentRequiresPit() {
        val p = plan(52.0, 260.0, 215.0) // 52 - 43 = 9
        assertEquals(PitVerdict.PIT_REQUIRED, p.verdict)
        assertTrue(p.needsPit)
    }

    @Test fun chargeTargetCoversDistanceAfterPitPlusMargin() {
        // Seoul -> Gangneung demo: 52%, 5 km/%, 230 km left, pit after 110 km.
        val p = plan(52.0, 260.0, 230.0, pitAt = 110.0)
        assertEquals(PitVerdict.PIT_REQUIRED, p.verdict) // 52 - 46 = 6
        assertEquals(30.0, p.socAtPitPercent.value!!, 1e-9) // 52 - 22
        // 120 km after pit = 24% + 20% margin = 44 -> rounded up to 45
        assertEquals(45.0, p.chargeTargetPercent.value!!, 1e-9)
        assertFalse(p.needsSecondStop)
    }

    @Test fun withoutPitPositionPlanAsIfChargingNow() {
        val p = plan(30.0, 150.0, 120.0) // 30 - 24 = 6
        // 120 km = 24% + 20% = 44 -> 45
        assertEquals(45.0, p.chargeTargetPercent.value!!, 1e-9)
        assertEquals(30.0, p.socAtPitPercent.value!!, 1e-9)
    }

    @Test fun targetIsCappedAndFlagsSecondStop() {
        val p = plan(20.0, 80.0, 400.0) // 4 km/%, 400 km = 100% + 20 > 80
        assertEquals(80.0, p.chargeTargetPercent.value!!, 1e-9)
        assertTrue(p.needsSecondStop)
        assertTrue(p.reason.contains("한 번 더"))
        assertTrue(p.reason.contains("바닥"))
        assertFalse(p.reason.contains("-"))
    }

    @Test fun pitBeyondReachIsReported() {
        val p = plan(10.0, 50.0, 300.0, pitAt = 80.0) // 5 km/%, pit needs 16%
        assertEquals(PitVerdict.PIT_REQUIRED, p.verdict)
        assertNull(p.chargeTargetPercent.value)
        assertTrue(p.reason.contains("가까운 곳"))
    }

    @Test fun missingInputsNeverFallBackToDefaults() {
        assertEquals(PitVerdict.UNKNOWN, plan(null, 300.0, 100.0).verdict)
        assertEquals(PitVerdict.UNKNOWN, plan(50.0, null, 100.0).verdict)
        assertEquals(PitVerdict.UNKNOWN, plan(0.0, 0.0, 100.0).verdict)
        val noRoute = plan(50.0, 250.0, null)
        assertEquals(PitVerdict.NO_DESTINATION, noRoute.verdict)
        assertEquals(DataSource.MISSING, noRoute.remaining.source)
        assertNull(noRoute.arrivalPercent.value)
    }

    @Test fun invalidReadingsAreTreatedAsMissing() {
        assertEquals(PitVerdict.UNKNOWN, plan(150.0, 300.0, 100.0).verdict)
        assertEquals(PitVerdict.UNKNOWN, plan(50.0, -1.0, 100.0).verdict)
    }

    @Test fun demoInputsAreLabelledDemo() {
        val p = plan(52.0, 260.0, 230.0, demo = true)
        assertEquals(DataSource.DEMO, p.soc.source)
        assertEquals(DataSource.DEMO, p.arrivalPercent.source)
        assertEquals("시연", p.arrivalPercent.label)
    }

    @Test fun noPriceOrSavingsWords() {
        listOf(plan(52.0, 260.0, 230.0, 110.0), plan(80.0, 400.0, 50.0), plan(20.0, 80.0, 400.0)).forEach {
            listOf("원", "절감", "저렴", "최저", "최적").forEach { w -> assertFalse(it.reason.contains(w)) }
        }
    }

    @Test fun washVerdictUsesForecastOnly() {
        assertEquals(WashVerdict.UNKNOWN, PitWallEngine.washVerdict(WeatherState()).verdict)
        assertEquals(WashVerdict.UNKNOWN, PitWallEngine.washVerdict(WeatherState(forecast = SeoulForecast("20261028", 0, 0.0), nearKnown = false)).verdict)
        assertEquals(WashVerdict.HOLD, PitWallEngine.washVerdict(WeatherState(nearRain = "오늘 오후")).verdict)
        assertEquals(WashVerdict.HOLD, PitWallEngine.washVerdict(WeatherState(forecast = SeoulForecast("20261028", 60, 0.0), nearKnown = true)).verdict)
        assertEquals(WashVerdict.HOLD, PitWallEngine.washVerdict(WeatherState(forecast = SeoulForecast("20261028", 10, 1.0), nearKnown = true)).verdict)
        val ok = PitWallEngine.washVerdict(WeatherState(forecast = SeoulForecast("20261028", 59, 0.0), nearKnown = true))
        assertEquals(WashVerdict.OK, ok.verdict)
        assertTrue(ok.reason.contains("직접 확인"))
    }

    @Test fun zeroRangeWithChargeUsesThisCarsLoggedKmPerPercent() {
        // emulator: charge set to 30%, range stuck at 0 km; the log has 500 km at 100% → 5 km per %
        val log = listOf(EnergySample(1L, 100.0, null, 500.0), EnergySample(2L, 30.0, null, 0.0))
        val p = PitWallEngine.plan(PitWallInputs.of("30%", 0.0, null, WeatherState(), 0L, energySamples = log))
        assertTrue(p.rangeFromLog)
        assertEquals(150.0, p.range.value!!, 0.001)
        assertEquals(DataSource.ESTIMATED, p.range.source)
        assertEquals(5.0, p.kmPerPercent.value!!, 0.001)
        // engine alone never guesses: no fallback given → unknown
        val none = PitWallEngine.plan(PitWallInput(30.0, 0.0, 391.0))
        assertEquals(PitVerdict.UNKNOWN, none.verdict)
        // the app layer supplies the labelled spec value when nothing is logged
        val spec = PitWallEngine.plan(PitWallInputs.of("27%", 0.0, null, WeatherState(), 0L))
        assertEquals(PitWallInputs.SPEC_NOTE, spec.rangeNote)
        assertEquals(27 * 4.78, spec.range.value!!, 0.001)
        // a real 0 km at 0% is kept as is
        assertFalse(PitWallEngine.plan(PitWallInput(0.0, 0.0, 10.0, kmPerPercentFallback = 5.0)).rangeFromLog)
        // 391 km at 30% with 5 km/% → pit required
        val trip = PitWallEngine.plan(PitWallInput(30.0, 0.0, 391.0, kmPerPercentFallback = 5.0))
        assertEquals(PitVerdict.PIT_REQUIRED, trip.verdict)
    }
}
