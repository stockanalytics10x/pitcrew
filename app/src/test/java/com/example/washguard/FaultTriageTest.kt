package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultTriageTest {
    @Test fun nothingReceivedIsUnknownNotFine() {
        val c = FaultTriage.check(HealthInput(telltales = null))
        assertTrue(c.allClear)
        assertTrue(c.spots.values.all { it == SpotStatus.UNKNOWN })
    }

    @Test fun noLightsIsAllClear() {
        val c = FaultTriage.check(HealthInput(telltales = emptyMap()))
        assertTrue(c.allClear)
        assertTrue(c.spots.values.all { it == SpotStatus.OK })
    }

    @Test fun parkingBrakeIsInfoWhenParkedButStopWhenDriving() {
        assertEquals(FaultLevel.INFO, FaultTriage.check(HealthInput(mapOf("PARKING_BRAKE" to false))).worst)
        val d = FaultTriage.check(HealthInput(mapOf("PARKING_BRAKE" to false), driving = true))
        assertEquals(FaultLevel.STOP_NOW, d.worst)
        assertTrue(d.cards.first().now.contains("서서히 감속"))
    }

    @Test fun brakeFluidLowIsStopNowEvenWithoutLight() {
        assertEquals(FaultLevel.STOP_NOW, FaultTriage.check(HealthInput(emptyMap(), brakeFluid = "LOW")).worst)
    }

    @Test fun manualLevels() {
        fun level(light: String) = FaultTriage.check(HealthInput(mapOf(light to false))).cards.first().level
        assertEquals(FaultLevel.SERVICE, level("LOW_VOLT_BATTERY"))
        assertEquals(FaultLevel.SERVICE, level("RBS"))
        assertEquals(FaultLevel.SERVICE, level("AIRBAG"))
        assertEquals(FaultLevel.SELF_FIX, level("ABS"))
        assertEquals(FaultLevel.SELF_FIX, level("TPMS"))
        assertEquals(FaultLevel.CHARGE, level("POWER_DOWN"))
        assertEquals(FaultLevel.CHARGE, level("LOW_EV_BATTERY"))
        assertEquals(FaultLevel.INFO, level("SEAT_BELT"))
    }

    @Test fun tpmsBlinkingMeansTheSensorNotThePressure() {
        val c = FaultTriage.check(HealthInput(mapOf("TPMS" to true))).cards.first()
        assertEquals(FaultLevel.SERVICE, c.level)
        assertTrue(c.title.contains("감지 장치"))
    }

    @Test fun lowWheelIsNamedAndMarkedOnItsCorner() {
        val c = FaultTriage.check(HealthInput(emptyMap(),
            tirePressure = mapOf(CrewSpot.TIRE_FL to 22f, CrewSpot.TIRE_FR to 36f),
            tireLowThreshold = mapOf(CrewSpot.TIRE_FL to 28f, CrewSpot.TIRE_FR to 28f)))
        val card = c.cards.single()
        assertTrue(card.what.contains("앞 왼쪽"))
        assertEquals(SpotStatus.WARN, c.spots[CrewSpot.TIRE_FL])
        assertEquals(SpotStatus.OK, c.spots[CrewSpot.TIRE_FR])
        assertTrue(card.plan.contains("다음 피트"))
    }

    @Test fun serviceWithPowerDownStaysServiceAndSaysCharge() {
        val c = FaultTriage.check(HealthInput(mapOf("EV_SYSTEM" to false, "POWER_DOWN" to false)))
        assertEquals(1, c.cards.size)
        assertEquals(FaultLevel.SERVICE, c.cards.first().level)
        assertTrue(c.cards.first().drive.contains("충전"))
    }

    @Test fun uncoveredLightsPointToTheManualAndSortBySeverity() {
        val c = FaultTriage.check(HealthInput(mapOf("ESC" to false, "SEAT_BELT" to false), washerLow = true, driving = true, brakeFluid = "LOW"))
        assertEquals(listOf(FaultLevel.STOP_NOW, FaultLevel.SERVICE, FaultLevel.SELF_FIX, FaultLevel.INFO), c.cards.map { it.level })
        assertTrue(c.cards.first { it.id == "esc" }.source.contains("확인 필요"))
        assertFalse(FaultTriage.radioLine(c.cards.first()).contains(".."))
    }

    @Test fun eachProblemPointsToAPlaceThatFixesIt() {
        fun fix(input: HealthInput) = FaultTriage.check(input).cards.first().fix?.kind
        assertEquals(NativeSearchKind.TIRE, fix(HealthInput(mapOf("TPMS" to false))))
        assertEquals(NativeSearchKind.SERVICE, fix(HealthInput(mapOf("TPMS" to true))))
        assertEquals(NativeSearchKind.OIL, fix(HealthInput(emptyMap(), engineOil = "LOW")))
        assertEquals(NativeSearchKind.FUEL, fix(HealthInput(emptyMap(), washerLow = true)))
        assertEquals(NativeSearchKind.CHARGE, fix(HealthInput(mapOf("LOW_EV_BATTERY" to false))))
        assertEquals(NativeSearchKind.SERVICE, fix(HealthInput(mapOf("LOW_VOLT_BATTERY" to false))))
        assertEquals(NativeSearchKind.SERVICE, fix(HealthInput(mapOf("ESC" to false))))
        assertEquals(null, fix(HealthInput(mapOf("SEAT_BELT" to false))))
    }

    @Test fun whereLinesFollowTheCarType() {
        fun plan(pt: Powertrain, input: HealthInput) = FaultTriage.check(input.copy(powertrain = pt)).cards.first().plan
        assertEquals("다음 피트(주유소·휴게소 공기 주입기)에서 보충", plan(Powertrain.ICE, HealthInput(mapOf("TPMS" to false))))
        assertEquals("다음 피트(충전소·휴게소 공기 주입기)에서 보충", plan(Powertrain.EV, HealthInput(mapOf("TPMS" to false))))
        assertEquals("다음 피트(주유하는 동안)", plan(Powertrain.ICE, HealthInput(emptyMap(), washerLow = true)))
    }
}
