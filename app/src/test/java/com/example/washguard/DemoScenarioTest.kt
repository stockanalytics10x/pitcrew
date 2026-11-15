package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoScenarioTest {
    private val s = DemoScenario.roadToDaegu
    private fun plan(i: Int, soc: Double = s[i].soc.toDouble()) = PitWallEngine.plan(DemoScenario.input(s[i], soc, WeatherState()))

    @Test fun storyVerdictsFollowTheScenes() {
        assertEquals(PitVerdict.FINISH_OK, plan(0).verdict)
        assertEquals(PitVerdict.PIT_REQUIRED, plan(1).verdict)
        assertEquals(80, plan(1).chargeTargetPercent.value!!.toInt()) // learned: cold rain costs more
        assertTrue(s[2].driving)
        val charging = s.indexOfFirst { it.charging }
        assertEquals(s[charging].socEnd, plan(charging).chargeTargetPercent.value!!.toInt()) // target equals where the charging scene stops
        assertEquals(PitVerdict.FINISH_OK, plan(s.lastIndex).verdict)
    }

    @Test fun everythingIsLabelledDemo() {
        val p = plan(1)
        listOf(p.soc, p.range, p.remaining, p.arrivalPercent).forEach { assertEquals(DataSource.DEMO, it.source) }
        assertEquals("시연", p.soc.label)
    }

    @Test fun chargingRisesAndStopsAtTarget() {
        val c = s.first { it.charging }
        assertEquals(c.soc, DemoScenario.chargingSoc(c, 0))
        assertEquals(c.soc + 10, DemoScenario.chargingSoc(c, 3_500))
        assertEquals(80, DemoScenario.chargingSoc(c, 60_000))
        assertEquals(80, DemoScenario.chargingSoc(s[0], 60_000)) // not a charging scene
    }

    @Test fun tyreWarningIsRadioedWhileDrivingFixedAtThePitAndClearedAfter() {
        val warn = s.indexOfFirst { DemoScenario.TPMS in it.faults }
        assertTrue(s[warn].driving && s[warn].radio != null)
        val pit = s.indexOfFirst { it.charging }
        assertTrue(DemoScenario.TPMS in s[pit].faults && s[pit].page == "PITSTOP")
        val cleared = s[pit + 1]
        assertTrue(cleared.faults.isEmpty() && cleared.page == "PITSTOP")
        val card = FaultTriage.check(HealthInput(s[pit].faults.associateWith { false })).cards.single()
        assertEquals(ServiceNeed.AIR, card.fix?.need)
        assertTrue(PitServiceRank.options(ServiceNeed.AIR, PitServiceRank.demoPlaces(GeoPoint(0, 37.5, 127.0)), emptyList(), GeoPoint(0, 37.5, 127.0), 0L).isNotEmpty())
    }
}
