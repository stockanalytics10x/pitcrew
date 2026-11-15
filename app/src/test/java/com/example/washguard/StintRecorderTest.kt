package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StintRecorderTest {
    private fun box(at: Long, outcome: BoxOutcome = BoxOutcome.ACCEPTED) = BoxCallRecord(at, BoxLevel.REQUIRED, -5.0, outcome)
    private val tpms = FaultTriage.check(HealthInput(mapOf("TPMS" to false)))
    private val brake = FaultTriage.check(HealthInput(emptyMap(), brakeFluid = "LOW", driving = true))

    @Test fun eventsAreRecordedOncePerStintAndClearedWhenParked() {
        val r = StintRecorder()
        val old = listOf(box(1))
        r.update(false, 250.0, -5.0, old, null, 0)
        r.update(true, 250.0, -5.0, old, null, 10)             // earlier calls belong to no stint
        assertTrue(r.events.isEmpty())
        r.update(true, 250.0, -5.0, old + box(20), null, 20)
        r.update(true, 240.0, -6.0, old + box(20), tpms, 30)
        r.update(true, 240.0, -6.0, old + box(20), tpms, 40)    // same inputs again: nothing new
        assertEquals(listOf(TrackEventKind.BOX, TrackEventKind.FAULT), r.events.map { it.kind })
        assertEquals(250.0, r.events[0].remainingKm, 0.0)
        assertEquals("TPMS !", r.events[1].label)
        assertEquals(-5.0, r.startArrival!!, 0.0)               // the estimate when the stint began
        r.update(false, 240.0, -6.0, old + box(20), tpms, 50)
        assertTrue(r.events.isEmpty()); assertNull(r.startArrival)
    }

    @Test fun stopNowIsRadioedOnceWhileDrivingAndAgainAfterItClears() {
        val r = StintRecorder()
        assertTrue(r.update(false, null, null, emptyList(), brake, 0).isEmpty())   // parked: no radio
        assertEquals(1, r.update(true, null, null, emptyList(), brake, 1).size)
        assertTrue(r.update(true, null, null, emptyList(), brake, 2).isEmpty())
        r.update(true, null, null, emptyList(), FaultTriage.check(HealthInput(emptyMap())), 3)
        assertEquals(1, r.update(true, null, null, emptyList(), brake, 4).size)
    }

    @Test fun driveSpellsSaveRealDrivesLongerThanAMinute() {
        val s = DriveSpellRecorder()
        assertNull(s.update(DriveAccess.RESTRICTED, false, 80.0, 1000.0, 30.0, listOf(box(1)), 0))
        val rec = s.update(DriveAccess.PARKED, false, 70.0, 1040.0, 30.0, listOf(box(1), box(5), box(6, BoxOutcome.DECLINED)), 120_000)
        assertNotNull(rec)
        assertEquals(2, rec!!.boxCalls); assertEquals(1, rec.boxAccepted)
        assertEquals(70.0, rec.endSoc!!, 0.0)
        s.update(DriveAccess.RESTRICTED, false, 70.0, 1040.0, 30.0, emptyList(), 200_000)
        assertNull(s.update(DriveAccess.PARKED, false, 70.0, 1040.0, 30.0, emptyList(), 230_000))   // too short
        assertNull(s.update(DriveAccess.RESTRICTED, true, 70.0, 1040.0, 30.0, emptyList(), 300_000))
        assertNull(s.update(DriveAccess.PARKED, true, 70.0, 1040.0, 30.0, emptyList(), 999_000))    // demo never records
    }
}
