package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowertrainTest {
    @Test fun detectedFromWhatTheCarSent() {
        assertEquals(Powertrain.EV, Powertrain.detect(evSeen = true, fuelSeen = false))
        assertEquals(Powertrain.ICE, Powertrain.detect(evSeen = false, fuelSeen = true))
        assertEquals(Powertrain.HYBRID, Powertrain.detect(evSeen = true, fuelSeen = true))
        assertEquals(Powertrain.UNKNOWN, Powertrain.detect(evSeen = false, fuelSeen = false))
    }

    @Test fun vehicleStatePlansWithFuelForCombustionCars() {
        val v = VehicleDataState(soc = "—", fuel = "18%", fuelSeen = true)
        assertEquals(Powertrain.ICE, v.powertrain)
        assertEquals("18%", v.energy(v.powertrain))
        assertEquals("—", v.energy(Powertrain.EV))
    }

    @Test fun combustionPlanSpeaksFuelAndFillsToFull() {
        // 18% fuel, 120 km range, 300 km to go: 6.67 km per %, needs (300/6.67)+20 = 65% → refuel
        val ice = PitWallEngine.plan(PitWallInput(18.0, 120.0, 300.0, powertrain = Powertrain.ICE))
        assertEquals(Powertrain.ICE, ice.powertrain)
        assertTrue(ice.needsPit)
        assertTrue(ice.reason.contains("주유") && !ice.reason.contains("충전"))
        assertTrue(BoxCallPolicy.message(ice).contains("주유소") && !BoxCallPolicy.message(ice).contains("충전"))
        // A long trip that needs more than 80%: an EV stops twice, a fuel car fills up once.
        val far = PitWallInput(10.0, 50.0, 350.0)  // needs 90%
        assertTrue(PitWallEngine.plan(far).needsSecondStop)
        assertFalse(PitWallEngine.plan(far.copy(powertrain = Powertrain.ICE)).needsSecondStop)
    }

    @Test fun demoTextFollowsThePowertrain() {
        val t = "대구 250km · {E} 27% → {R}이 필요해요 · {P}"
        assertEquals("대구 250km · 배터리 27% → 충전이 필요해요 · 충전소", DemoScenario.words(t, Powertrain.EV))
        assertEquals("대구 250km · 연료 27% → 주유가 필요해요 · 주유소", DemoScenario.words(t, Powertrain.ICE))
    }
}
