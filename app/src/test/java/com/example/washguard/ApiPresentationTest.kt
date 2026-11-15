package com.example.washguard

import org.junit.Assert.*
import org.junit.Test

class ApiPresentationTest {
    @Test fun failedWeatherDoesNotLookLikeLoading() {
        assertEquals("날씨 조회 실패 · 재시도", weatherHeadline(WeatherState(status="기상청 조회 실패")))
        assertEquals("기상 정보 확인 중", weatherHeadline(WeatherState(loading=true)))
    }
    @Test fun weatherDistinguishesLocationAndMissingData() {
        assertEquals("새 차량 위치 필요", weatherHeadline(WeatherState(status="새 차량 위치 수신 필요")))
        assertEquals("날씨 미수신 · 설정 확인", weatherHeadline(WeatherState()))
    }
}
