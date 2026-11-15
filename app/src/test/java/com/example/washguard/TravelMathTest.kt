package com.example.washguard

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

class TravelMathTest {
    @Test fun seoulGridAndInvalidCoordinates() {
        assertEquals(WeatherGrid(60, 127), weatherGrid(37.5665, 126.978))
        assertNull(weatherGrid(Double.NaN, 127.0))
        assertNull(weatherGrid(0.0, 0.0))
    }
    @Test fun baseTimeWaitsForPublicationAndCrossesMidnight() {
        assertEquals(LocalDateTime.of(2026, 10, 22, 23, 0), villageBase(LocalDateTime.of(2026, 10, 23, 0, 20)))
        assertEquals(LocalDateTime.of(2026, 10, 23, 2, 0), villageBase(LocalDateTime.of(2026, 10, 23, 5, 44)))
        assertEquals(LocalDateTime.of(2026, 10, 23, 5, 0), villageBase(LocalDateTime.of(2026, 10, 23, 5, 45)))
    }
    @Test fun stalePositionsAreNotCurrent() {
        val now = 1_700_000_000_000L
        assertFalse(validFreshPoint(GeoPoint(now - 360_000, 37.5, 127.0), now))
        assertFalse(validFreshPoint(GeoPoint(now + 120_000, 37.5, 127.0), now))
        assertTrue(validFreshPoint(GeoPoint(now, 37.5, 127.0), now))
    }
    @Test fun recordedTimeMatchesEndOfObservationHour() {
        val time = java.time.ZonedDateTime.of(2026, 10, 23, 13, 45, 0, 0, java.time.ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
        assertEquals(LocalDateTime.of(2026, 10, 23, 14, 0), observationHour(time))
    }
    @Test fun incompleteForecastAndCategoricalAmountsStayUnknown() {
        val date = LocalDate.of(2026, 10, 24)
        val rows = (0..23).flatMap { hour -> listOf(
            JSONObject().put("fcstDate", "20261024").put("fcstTime", "%02d00".format(hour)).put("category", "POP").put("fcstValue", if (hour == 12) "80" else "10"),
            JSONObject().put("fcstDate", "20261024").put("fcstTime", "%02d00".format(hour)).put("category", "PCP").put("fcstValue", "강수없음")
        ) }
        assertEquals(80, parseTomorrow(rows, date)!!.probability)
        assertEquals(0.0, parseTomorrow(rows, date)!!.precipitationMm!!, 0.0)
        assertNull(parseTomorrow(rows.drop(2), date))
        assertNull(parsePrecipitation("1.0mm 미만"))
        assertNull(parsePrecipitation("30.0~50.0mm"))
        assertNull(parsePrecipitation(""))
        assertEquals(1.5, parsePrecipitation("1.5mm")!!, 0.0)
    }
    @Test fun imminentRainOverridesTomorrowAndUnknownNeverMeansSafe() {
        assertEquals("곧 강수 예상 · 세차 일정 조정", nextWashAdvice("10-23 14:00", 0, 0.0, "").title)
        assertEquals("예보 확인 필요", nextWashAdvice(null, null, null, "").title)
        assertEquals("가까운 시간의 예보 확인 필요", nextWashAdvice(null, 0, 0.0, "", false).title)
    }
}
