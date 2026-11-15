package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class EfficiencyModelTest {
    private val day = 86_400_000L
    private fun at(y: Int, m: Int, d: Int, h: Int) = LocalDateTime.of(y, m, d, h, 0).atZone(KST_ZONE).toInstant().toEpochMilli()
    private val now = at(2026, 11, 12, 9) // Thursday morning

    /** A drive of [km] that used [used] %, [daysAgo] before now at the same hour. */
    private fun rec(km: Double, used: Double, daysAgo: Int = 1, temp: Double? = 15.0, rain: Boolean? = false,
                    predicted: Double? = null, route: Double? = null, startSoc: Double = 80.0) =
        DriveRecord(now - daysAgo * day, now - daysAgo * day + 3_600_000L, startSoc, startSoc - used, 1000.0, 1000.0 + km,
            predicted, 0, 0, route, temp, rain)

    @Test fun noUsableRecordsLearnsNothingAndThePlanIsUnchanged() {
        val l = EfficiencyModel.learn(listOf(rec(2.0, 1.0), rec(30.0, -5.0)), EfficiencyModel.Conditions(15.0, false, now), 5.0, Powertrain.EV)
        assertNull(l.kmPerPercent)
        val input = PitWallInput(50.0, 250.0, 100.0)
        assertEquals(PitWallEngine.plan(input), PitWallEngine.plan(input.copy(learned = l)))
    }

    @Test fun realEfficiencyComesFromOdometerAndLevel() {
        val t = EfficiencyModel.trips(listOf(rec(40.0, 10.0)))
        assertEquals(4.0, t.single().kmPerPercent, 1e-9)
    }

    @Test fun moreDrivesMeanMoreTrustAwayFromTheCarsEstimate() {
        val c = EfficiencyModel.Conditions(15.0, false, now)
        val few = EfficiencyModel.learn(listOf(rec(40.0, 10.0)), c, 5.0, Powertrain.EV)
        val many = EfficiencyModel.learn((1..12).map { rec(40.0, 10.0, it) }, c, 5.0, Powertrain.EV)
        assertTrue(few.kmPerPercent!! > many.kmPerPercent!!) // both between 4 (learned) and 5 (car)
        assertTrue(many.kmPerPercent!! < 4.4 && few.kmPerPercent!! > 4.4)
    }

    @Test fun drivesUnderLikeConditionsCountMore() {
        // Rainy cold drives were less efficient; planning a rainy cold drive should lean to them.
        val recs = (1..6).map { rec(40.0, 8.0, it) } + (7..12).map { rec(40.0, 12.0, it, temp = 0.0, rain = true) }
        val dry = EfficiencyModel.learn(recs, EfficiencyModel.Conditions(15.0, false, now), null, Powertrain.EV)
        val wetCold = EfficiencyModel.learn(recs, EfficiencyModel.Conditions(1.0, true, now), null, Powertrain.EV)
        assertTrue(wetCold.learnedKmPerPercent!! < dry.learnedKmPerPercent!!)
        assertTrue(wetCold.conditionEffect!! < -0.05)
        assertTrue(wetCold.note!!.contains("저온·비"))
    }

    @Test fun optimisticPastPredictionsAreCorrectedWithABand() {
        // Predicted 4 points too high each time (route followed to the end).
        val recs = (1..5).map { rec(40.0, 10.0, it, predicted = 74.0, route = 40.0) }
        val l = EfficiencyModel.learn(recs, EfficiencyModel.Conditions(15.0, false, now), 4.0, Powertrain.EV)
        assertEquals(4.0 * 5 / 7.0, l.bias, 1e-9)
        assertEquals(EfficiencyModel.MIN_SPREAD, l.spread!!, 1e-9)
        val base = PitWallEngine.plan(PitWallInput(50.0, 200.0, 100.0))
        val corrected = PitWallEngine.plan(PitWallInput(50.0, 200.0, 100.0, learned = l))
        assertTrue(corrected.arrivalPercent.value!! < base.arrivalPercent.value!!)
        assertNotNull(corrected.arrivalSpread)
    }

    @Test fun aDetourIsNotCountedAsAPredictionError() {
        val l = EfficiencyModel.trips(listOf(rec(80.0, 20.0, predicted = 70.0, route = 40.0)))
        assertNull(l.single().arrivalError)
    }

    @Test fun theBandMakesTheCallEarlier() {
        // Arrival 21% is a finish without a band, but its low end (21 - 3) asks for a pit.
        val input = PitWallInput(60.0, 300.0, 195.0)
        assertEquals(PitVerdict.FINISH_OK, PitWallEngine.plan(input).verdict)
        val banded = input.copy(learned = EfficiencyModel.NONE.copy(kmPerPercent = 5.0, learnedKmPerPercent = 5.0, spread = 3.0))
        assertEquals(PitVerdict.PIT_ADVISED, PitWallEngine.plan(banded).verdict)
    }

    @Test fun fuelCarsDoNotFallBackToTheEvSpec() {
        val i = PitWallInputs.of("40%", null, null, WeatherState(), now, powertrain = Powertrain.ICE)
        assertNull(i.kmPerPercentFallback)
    }

    @Test fun demoHistoryTeachesThatColdRainCostsMore() {
        val l = DemoHistory.learned(DemoScenario.KM_PER_PERCENT, Powertrain.EV)
        assertTrue(l.trips >= 15)
        assertTrue(l.conditionEffect!! < 0)
        assertNotNull(l.spread)
        assertTrue(l.note!!.startsWith("시연 기록"))
    }

    @Test fun reportGroupsAndAccuracyComeFromTheRecords() {
        val recs = (1..6).map { rec(40.0, 8.0, it) } + (7..10).map { rec(40.0, 10.0, it, temp = 0.0, rain = true, predicted = 72.0, route = 40.0) }
        val g = EfficiencyModel.groups(recs)
        val rain = g.first { it.label == "비 오는 날" }
        assertEquals(4, rain.trips)
        assertTrue(rain.vsAll < 0)
        assertTrue(g.first { it.label == "맑고 보통 기온" }.vsAll > 0)
        val a = EfficiencyModel.accuracy(recs)!!
        assertEquals(4, a.checked)
        assertEquals(2.0, a.meanError, 1e-9) // predicted 72, ended at 70
        assertNull(EfficiencyModel.accuracy(recs.take(6)))
        assertTrue(EfficiencyModel.groups(recs.take(3)).isEmpty())
    }
}
