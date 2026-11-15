package com.example.washguard
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.time.LocalDateTime

class HistoryChargingTest {
    private val now=LocalDateTime.of(2026,10,23,16,0)
    private fun time(day:Int,hour:Int,minute:Int=0)=LocalDateTime.of(2026,10,day,hour,minute).atZone(KST).toInstant().toEpochMilli()
    private val station=Station(108,"서울",37.57,126.97)
    @Test fun filtersBeforeWashFutureAndDuplicatesAndWaitsForToday(){
        val points=listOf(GeoPoint(time(21,9),37.5,127.0),GeoPoint(time(22,12,15),37.5,127.0),
            GeoPoint(time(22,12,45),37.5,127.0),GeoPoint(time(23,10),37.5,127.0),GeoPoint(time(24,10),37.5,127.0))
        val plan=historyPlan(points,time(21,10),now){station}
        assertEquals(3,plan.points);assertEquals(1,plan.targets.size);assertEquals(1,plan.pending)
        assertEquals(LocalDateTime.of(2026,10,22,13,0),plan.targets.single().hour)
    }
    @Test fun noWashDoesNotClaimSinceWashEvidence(){
        assertTrue(historyPlan(listOf(GeoPoint(time(22,12),37.5,127.0)),0,now){station}.targets.isEmpty())
    }
    @Test fun observationHourStraddlingWashIsExcluded(){
        val plan=historyPlan(listOf(GeoPoint(time(22,12,45),37.5,127.0),GeoPoint(time(22,13,15),37.5,127.0)),time(22,12,30),now){station}
        assertEquals(1,plan.boundary);assertEquals(1,plan.targets.size)
        assertEquals(LocalDateTime.of(2026,10,22,14,0),plan.targets.single().hour)
    }
    @Test fun recordsBeyondSevenDaysAreExcluded(){
        val old=now.minusDays(8).atZone(KST).toInstant().toEpochMilli()
        val plan=historyPlan(listOf(GeoPoint(old,37.5,127.0)),old-1000,now){station}
        assertEquals(0,plan.points)
    }
    @Test fun newWashResetsEvidenceWithoutDeletingLocationHistory(){
        val points=listOf(GeoPoint(time(22,12),37.5,127.0))
        assertEquals(1,historyPlan(points,time(21,10),now){station}.targets.size)
        assertEquals(0,historyPlan(points,time(23,10),now){station}.targets.size)
    }
    @Test fun midnightObservationWaitsUntilFollowingDay(){
        val plan=historyPlan(listOf(GeoPoint(time(22,23,45),37.5,127.0)),time(21,10),now){station}
        assertEquals(1,plan.pending);assertTrue(plan.targets.isEmpty())
    }
    @Test fun outsideStationRangeIsNotDry(){
        val plan=historyPlan(listOf(GeoPoint(time(22,10),37.5,127.0)),time(21,10),now){null}
        assertEquals(1,plan.outside);assertTrue(plan.targets.isEmpty())
    }
    @Test fun deduplicatesObservedHoursAndDoesNotTreatUnknownAsZero(){
        val t=HistoryTarget(108,now.minusDays(1))
        val wet=historyEvidence(listOf(t,t)){0.2 to null}
        assertEquals(1,wet.wet);assertEquals(1,wet.checked)
        assertEquals(1,historyEvidence(listOf(t)){null}.missing)
        assertEquals(1,historyEvidence(listOf(t)){0.0 to null}.missing)
        assertEquals(1,historyEvidence(listOf(t)){0.0 to 0.0}.checked)
    }
    @Test fun qcAndMissingValuesAreNotInvented(){
        assertNull(observedAmount(JSONObject().put("rn","0").put("rnQcflg","9"),"rn","rnQcflg"))
        assertNull(observedAmount(JSONObject().put("rn",""),"rn","rnQcflg"))
        assertNull(observedAmount(JSONObject().put("rn","-1"),"rn","rnQcflg"))
        assertEquals(1.5,observedAmount(JSONObject().put("rn","1.5").put("rnQcflg","0"),"rn","rnQcflg")!!,0.0)
    }
    @Test fun rainHistoryAffectsAdviceButUpcomingRainHasPriority(){
        assertEquals("강수 기록이 있어 오염 상태를 확인해요",nextWashAdvice(null,10,0.0,"",true,1).title)
        assertEquals("곧 강수 예상 · 세차 일정 조정",nextWashAdvice("10-23 17:00",10,0.0,"",true,1).title)
    }
    @Test fun nativeDestinationComparisonProtectsExistingRoute(){
        val a=TripDestination("수원역",37.2657,127.0017,"41110")
        assertTrue(sameDestination(a,a.copy(name="이름 변경")))
        assertFalse(sameDestination(a,a.copy(lat=37.5547,lon=126.9706)))
        assertFalse(sameDestination(null,a))
        assertEquals("11",provinceCode("서울특별시 중구 세종대로"))
        assertEquals("41",provinceCode("경기도 수원시 권선구"))
        assertEquals("",provinceCode(""))
    }

}
