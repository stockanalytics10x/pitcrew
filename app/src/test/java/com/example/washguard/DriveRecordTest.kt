package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveRecordTest {
    private val r = DriveRecord(0, 72 * 60_000L, 52.0, 31.4, 1000.0, 1084.2, 30.2, 2, 1)

    @Test fun summaryUsesOnlyRecordedValues() {
        assertEquals("84 km", DriveRecordText.distance(r))
        assertEquals("1시간 12분", DriveRecordText.duration(r))
        assertEquals("52% → 31%", DriveRecordText.soc(r))
        assertEquals("예측 30% · 실제 31% · 오차 +1%p", DriveRecordText.prediction(r))
        assertEquals("Box 콜 2회 · 수락 1회", DriveRecordText.radio(r))
    }

    @Test fun missingStaysMissing() {
        val m = DriveRecord(0, 5 * 60_000L, null, 40.0, 0.0, 0.0, null, 0, 0)
        assertNull(DriveRecordText.distance(m)) // emulator odometer that never moved
        assertNull(DriveRecordText.prediction(m))
        assertEquals("— → 40%", DriveRecordText.soc(m))
        assertEquals("5분", DriveRecordText.duration(m))
        assertEquals("Box 콜 없음", DriveRecordText.radio(m))
        assertEquals("예측 부족 · 실제 3% · 오차 +8%p", DriveRecordText.prediction(m.copy(predictedArrival = -5.0, endSoc = 3.0)))
    }
}
