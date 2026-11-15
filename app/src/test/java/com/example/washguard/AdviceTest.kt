package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Test

class AdviceTest {
    @Test fun chargingThresholdsAndMissingData() {
        assertEquals("배터리 확인 필요", chargingAdvice(null).title)
        assertEquals("배터리 확인 필요", chargingAdvice(101).title)
        assertEquals("충전소 확인 권장", chargingAdvice(20).title)
        assertEquals("충전 계획 확인", chargingAdvice(21).title)
        assertEquals("충전 계획 확인", chargingAdvice(50).title)
        assertEquals("잔량 51% · 경로 확인", chargingAdvice(51).title)
    }
}
