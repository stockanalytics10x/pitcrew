package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PitWallInputsTest {
    private val now = 1_800_000_000_000L
    private fun navi(active: Boolean? = true, km: Double? = 120.0, age: Long = 10_000, connected: Boolean = true) =
        NaviSnapshot(connected = connected, active = active, remainKm = km, updated = now - age)

    @Test fun socTextIsParsedOrMissing() {
        assertEquals(52.0, PitWallInputs.socPercent("52%")!!, 0.0)
        assertEquals(52.5, PitWallInputs.socPercent(" 52.5 % ")!!, 0.0)
        assertNull(PitWallInputs.socPercent("—"))
        assertNull(PitWallInputs.socPercent("120%"))
        assertNull(PitWallInputs.socPercent(null))
    }

    @Test fun remainingDistanceOnlyWhileRouteIsActiveAndFresh() {
        assertEquals(120.0, PitWallInputs.remainingKm(navi(), now)!!, 0.0)
        assertNull(PitWallInputs.remainingKm(navi(active = false), now))
        assertNull(PitWallInputs.remainingKm(navi(active = null), now))
        assertNull(PitWallInputs.remainingKm(navi(connected = false), now))
        assertNull(PitWallInputs.remainingKm(navi(km = null), now))
        assertNull(PitWallInputs.remainingKm(navi(age = PitWallInputs.NAVI_FRESH_MS + 1), now))
        assertNull(PitWallInputs.remainingKm(null, now))
    }

    @Test fun endToEndVerdictFromReceivedState() {
        val input = PitWallInputs.of("52%", 260.0, navi(km = 230.0), WeatherState(), now)
        val plan = PitWallEngine.plan(input)
        assertEquals(PitVerdict.PIT_REQUIRED, plan.verdict)
        assertEquals(DataSource.MEASURED, plan.remaining.source)
    }

    @Test fun staleNaviFallsBackToNoDestinationNotAGuess() {
        val input = PitWallInputs.of("52%", 260.0, navi(age = 10 * 60_000), WeatherState(), now)
        assertEquals(PitVerdict.NO_DESTINATION, PitWallEngine.plan(input).verdict)
    }
}
