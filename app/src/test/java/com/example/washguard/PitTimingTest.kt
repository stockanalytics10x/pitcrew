package com.example.washguard

import org.junit.Assert.*
import org.junit.Test

class PitTimingTest {
    @Test fun lowBatteryTakesPriorityEvenWithRain() {
        assertEquals("CHARGE",pitTiming(20,WeatherState(nearRain="오늘 오후"),0).kind)
        assertEquals("WASH",pitTiming(21,WeatherState(nearRain="오늘 오후"),0).kind)
    }
    @Test fun missingNearForecastDoesNotRecommendWashingNow() {
        val result=pitWashTiming(WeatherState(forecast=SeoulForecast("20261025",0,0.0),nearKnown=false),0)
        assertTrue(result.title.contains("확인 필요"))
        assertTrue(result.reason.contains("기록 없음"))
    }
    @Test fun rainDefersWashingWithoutInventingAnExactDay() {
        assertEquals("강수가 지난 뒤 세차 검토",pitWashTiming(WeatherState(nearRain="오늘 오후"),0).title)
        assertEquals("내일 강수 후 다시 확인",pitWashTiming(WeatherState(forecast=SeoulForecast("20261025",70,2.0),nearKnown=true),0).title)
    }
    @Test fun invalidBatteryDoesNotProduceAnUrgentChargeRecommendation() {
        assertEquals("충전 시점 확인 필요",pitChargeTiming(101).title)
        assertEquals("충전 시점 확인 필요",pitChargeTiming(null).title)
    }
}
