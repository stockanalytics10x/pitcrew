package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StintMathTest {
    @Test fun windowAndEmptyPointAlongTheRoute() {
        // 50% · 250 km range → 5 km per %; 300 km to go
        val t = StintMath.track(PitWallEngine.plan(PitWallInput(50.0, 250.0, 300.0)))!!
        assertEquals(100.0, t.windowStartKm, 0.001) // 50→30%
        assertEquals(200.0, t.windowEndKm, 0.001)   // 50→10%
        assertEquals(150.0, t.reserveKm, 0.001)     // 50→20%
        assertEquals(250.0, t.emptyKm, 0.001)
        assertFalse(t.reachesFinish)
    }

    @Test fun belowWindowStartsNowAndShortTripsReachTheFinish() {
        val low = StintMath.track(PitWallEngine.plan(PitWallInput(25.0, 125.0, 300.0)))!!
        assertEquals(0.0, low.windowStartKm, 0.001)
        val short = StintMath.track(PitWallEngine.plan(PitWallInput(80.0, 400.0, 30.0)))!!
        assertTrue(short.reachesFinish)
        assertEquals(30.0, short.windowEndKm, 0.001) // clamped to the finish
    }

    @Test fun noRouteNoTrackAndDelta() {
        assertNull(StintMath.track(PitWallEngine.plan(PitWallInput(50.0, 250.0, null))))
        assertEquals(-3.0, StintMath.delta(20.0, 17.0)!!, 0.001)
        assertNull(StintMath.delta(null, 17.0))
    }
}
