package com.example.washguard

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class UltraForecastTest {
    private val base = LocalDateTime.of(2026, 10, 23, 14, 30)
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd")
    private fun rows(base: LocalDateTime = this.base): List<JSONObject> = (1L..6L).map {
        val at = base.withMinute(0).plusHours(it)
        JSONObject().put("category", "PTY").put("baseDate", base.format(stamp))
            .put("baseTime", base.withMinute(0).format(DateTimeFormatter.ofPattern("HHmm")))
            .put("fcstDate", at.format(stamp)).put("fcstTime", at.format(DateTimeFormatter.ofPattern("HHmm"))).put("fcstValue", "0")
    }
    private fun actual(name: String): List<JSONObject> {
        val text = javaClass.getResourceAsStream("/ultra/$name")!!.bufferedReader().use { it.readText() }
        val array = JSONObject(text).getJSONObject("response").getJSONObject("body").getJSONObject("items").getJSONArray("item")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    @Test fun fiveFutureSlotsAreNormalAfterHourChanges() {
        val result = parseUltraForecast(rows(), base, LocalDateTime.of(2026,10,23,15,29))
        assertTrue(result.complete)
        assertNull(result.firstRain)
        assertEquals("20:00까지 강수 예보 없음", result.clearLabel)
        assertTrue(result.status.contains("16:00~20:00"))
    }
    @Test fun sixFutureSlotsBeforeNextHourAreComplete() {
        assertTrue(parseUltraForecast(rows(), base, base.plusMinutes(25)).complete)
    }
    @Test fun missingFutureSlotRemainsUnknown() {
        assertFalse(parseUltraForecast(rows().filterIndexed { i, _ -> i != 3 }, base, base.plusMinutes(25)).complete)
    }
    @Test fun duplicateRowsCannotHideMissingHour() {
        val data = rows().toMutableList();data[3] = data[2]
        assertFalse(parseUltraForecast(data, base, base.plusMinutes(25)).complete)
    }
    @Test fun unknownPtyIsNotDry() {
        val data = rows();data[2].put("fcstValue", "99")
        assertFalse(parseUltraForecast(data, base, base.plusMinutes(25)).complete)
    }
    @Test fun conflictingDuplicateIsUnknown() {
        val data=rows()
        val conflict=JSONObject(data[2].toString()).put("fcstValue","1")
        assertFalse(parseUltraForecast(data + conflict, base, base.plusMinutes(25)).complete)
    }
    @Test fun wrongBulletinCannotBeMistakenForRequestedOne() {
        assertFalse(parseUltraForecast(rows(base.minusHours(1)), base, base.plusMinutes(25)).complete)
    }
    @Test fun rainInElapsedSlotDoesNotBecomeFutureRain() {
        val data=rows();data[0].put("fcstValue","1")
        val result=parseUltraForecast(data,base,base.plusHours(1))
        assertTrue(result.complete);assertNull(result.firstRain)
    }
    @Test fun rainStillWarnsWhenOtherSlotIsUnknown() {
        val data=rows();data[1].put("fcstValue","1");data[3].put("fcstValue","")
        val result=parseUltraForecast(data,base,base.plusMinutes(25))
        assertFalse(result.complete);assertEquals("10-23 16:00",result.firstRain)
    }
    @Test fun crossesMidnightAndRejectsStaleBulletin() {
        val b=LocalDateTime.of(2026,10,23,23,30)
        val result=parseUltraForecast(rows(b),b,b.plusMinutes(45))
        assertTrue(result.complete);assertEquals("05:00까지 강수 예보 없음", result.clearLabel)
        assertFalse(parseUltraForecast(rows(b),b,b.plusHours(2)).complete)
    }
    @Test fun publicationBufferCrossesMidnightAndRollsAtFifty() {
        assertEquals(LocalDateTime.of(2026,10,22,23,30),ultraBase(LocalDateTime.of(2026,10,23,0,49)))
        assertEquals(LocalDateTime.of(2026,10,23,0,30),ultraBase(LocalDateTime.of(2026,10,23,0,50)))
    }
    @Test fun recordedSeoulResponseHasFiveFutureSlotsAndIsComplete() {
        val b=LocalDateTime.of(2026,10,23,15,30)
        val result=parseUltraForecast(actual("seoul-20261023-1530.json"),b,LocalDateTime.of(2026,10,23,16,6))
        assertTrue(result.complete);assertNull(result.firstRain)
        assertTrue(result.status.contains("15:00 기준"))
        assertEquals("21:00까지 강수 예보 없음",result.clearLabel)
    }
    @Test fun recordedBusanResponseDetectsFirstRain() {
        val b=LocalDateTime.of(2026,10,23,15,30)
        val result=parseUltraForecast(actual("busan-20261023-1530.json"),b,LocalDateTime.of(2026,10,23,16,6))
        assertTrue(result.complete);assertEquals("10-23 17:00",result.firstRain)
    }
}

