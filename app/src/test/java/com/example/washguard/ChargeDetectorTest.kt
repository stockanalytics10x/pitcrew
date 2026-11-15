package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeDetectorTest {
    private val min = 60_000L

    private fun run(vararg steps: Triple<Long, Int?, Boolean?>, parked: Boolean = true, switch: Boolean? = null): ChargeSession =
        steps.fold(ChargeSession()) { s, (t, soc, port) -> ChargeDetector.step(s, parked, soc, port, switch, t) }

    @Test fun portOpenThenRiseIsCharging() {
        val s = run(Triple(0, 40, true), Triple(1 * min, 40, true), Triple(2 * min, 41, true))
        assertEquals(ChargePhase.CHARGING, s.phase)
        assertEquals(40, s.startSoc)
    }

    @Test fun portOpenWithoutRiseIsOnlyReady() {
        val s = run(Triple(0, 40, true), Triple(10 * min, 40, true))
        assertEquals(ChargePhase.READY, s.phase)
    }

    @Test fun noSignalNeedsTwoPercent() {
        assertEquals(ChargePhase.NONE, run(Triple(0, 40, null), Triple(min, 41, null)).phase)
        assertEquals(ChargePhase.CHARGING, run(Triple(0, 40, null), Triple(min, 41, null), Triple(2 * min, 42, null)).phase)
    }

    @Test fun drivingNeverCharges() {
        val s = run(Triple(0, 40, true), Triple(min, 45, true), parked = false)
        assertEquals(ChargePhase.NONE, s.phase)
    }

    @Test fun portClosedEnds() {
        val s = run(Triple(0, 40, true), Triple(min, 41, true), Triple(2 * min, 42, false))
        assertEquals(ChargePhase.DONE, s.phase)
    }

    @Test fun stallEnds() {
        val s = run(Triple(0, 40, true), Triple(min, 41, true), Triple(7 * min, 41, true))
        assertEquals(ChargePhase.DONE, s.phase)
    }

    @Test fun rateAndEta() {
        val s = run(Triple(0, 40, true), Triple(min, 41, true), Triple(3 * min, 45, true))
        assertEquals(2.0, s.ratePerMin!!, 0.001)
        assertEquals(10, s.minutesTo(65))
    }

    @Test fun noRateFromOneRise() {
        assertNull(run(Triple(0, 40, true), Triple(min, 41, true)).ratePerMin)
    }
}
