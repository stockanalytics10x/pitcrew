package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StintTrackViewMathTest {
    private val plan = PitWallEngine.plan(PitWallInput(27.0, 129.0, 250.0)) // demo-like: needs a pit

    @Test fun noLaneUntilAStopIsChosen() {
        val t = StintMath.track(plan)!!
        assertNull(t.pitKm)                    // the engine only assumed "charge now"
        assertEquals((27.0 - 10.0) * plan.kmPerPercent.value!!, t.chargeByKm!!, 0.01)
    }

    @Test fun pitSitsWhereTheChosenStopIs() {
        val plan = PitWallEngine.plan(PitWallInput(27.0, 129.0, 250.0, pitAtKm = 12.0))
        val t = StintMath.track(plan)!!
        val pit = t.pitKm!!
        assertEquals(12.0, pit, 0.01)
        val kpp = plan.kmPerPercent.value!!
        assertEquals(plan.soc.value!! - plan.socAtPitPercent.value!!, pit / kpp, 0.01)
        assertTrue(pit in 0.0..t.totalKm)
        assertNull(StintMath.track(PitWallEngine.plan(PitWallInput(80.0, 400.0, 30.0)))!!.pitKm)
    }

    @Test fun energyProfileGoesDownToThePitUpToTheTargetAndOnToTheFinish() {
        val e = StintMath.energyProfile(plan)
        assertEquals(4, e.size)
        assertEquals(27.0, e[0].second, 0.01)
        assertEquals(e[1].first, e[2].first, 0.001)
        assertEquals(plan.chargeTargetPercent.value!!, e[2].second, 0.01)
        assertEquals(250.0, e[3].first, 0.01)
        assertTrue(e[3].second >= PitWallEngine.ARRIVAL_COMFORT_PERCENT - 1)
        val noPit = StintMath.energyProfile(PitWallEngine.plan(PitWallInput(80.0, 400.0, 30.0)))
        assertEquals(2, noPit.size)
    }

    @Test fun eventsSitBehindTheCar() {
        assertEquals(10.0, StintMath.behindKm(250.0, 240.0)!!, 0.001)
        assertNull(StintMath.behindKm(240.0, 250.0))   // not yet reached
        assertNull(StintMath.behindKm(300.0, 240.0))   // far behind, out of view
    }
}
