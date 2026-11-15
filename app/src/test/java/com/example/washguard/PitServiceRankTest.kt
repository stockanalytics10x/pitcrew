package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PitServiceRankTest {
    private val here = GeoPoint(0, 37.50, 127.00)
    private val now = 1_800_000_000_000L
    private fun place(id: String, dLat: Double, e: Evidence?) =
        ServicePlace(id, id, here.lat + dLat, here.lon, "", if (e == null) emptyMap() else mapOf(ServiceNeed.AIR to e), "test")

    @Test fun driverConfirmedComesFirstThenOfficialThenLikelyNearestFirst() {
        val places = listOf(place("likelyNear", 0.005, Evidence.LIKELY), place("official", 0.05, Evidence.OFFICIAL),
            place("confirmedFar", 0.1, Evidence.LIKELY), place("likelyFar", 0.02, Evidence.LIKELY))
        val reports = listOf(CrewReport("confirmedFar", ServiceNeed.AIR, true, now - 1000, carConfirmed = true))
        val o = PitServiceRank.options(ServiceNeed.AIR, places, reports, here, now)
        assertEquals(listOf("confirmedFar", "official", "likelyNear"), o.map { it.place.id })
        assertTrue(o.first().badge.contains("운전자 1명") && o.first().badge.contains("차량 센서 1건"))
    }

    @Test fun latestNotHereHidesUntilConfirmedAgain() {
        val p = listOf(place("a", 0.01, Evidence.LIKELY))
        val no = CrewReport("a", ServiceNeed.AIR, false, now - 1000)
        assertTrue(PitServiceRank.options(ServiceNeed.AIR, p, listOf(no), here, now).isEmpty())
        val yesLater = CrewReport("a", ServiceNeed.AIR, true, now - 10)
        assertEquals(1, PitServiceRank.options(ServiceNeed.AIR, p, listOf(no, yesLater), here, now).size)
    }

    @Test fun driverReportAddsAPlaceTheDataDidNotList() {
        val p = listOf(place("cafe", 0.01, null))
        assertTrue(PitServiceRank.options(ServiceNeed.AIR, p, emptyList(), here, now).isEmpty())
        val o = PitServiceRank.options(ServiceNeed.AIR, p, listOf(CrewReport("cafe", ServiceNeed.AIR, true, now)), here, now)
        assertEquals(Evidence.USER, o.single().evidence)
    }

    @Test fun farPlacesAndOtherNeedsAreLeftOut() {
        val p = listOf(place("far", 1.0, Evidence.OFFICIAL))
        assertTrue(PitServiceRank.options(ServiceNeed.AIR, p, emptyList(), here, now).isEmpty())
        assertTrue(PitServiceRank.options(ServiceNeed.OIL, listOf(place("near", 0.01, Evidence.OFFICIAL)), emptyList(), here, now).isEmpty())
    }

    @Test fun askOnlyAfterTheFaultClearsWithinHalfADay() {
        val v = PendingVisit("a", "A", ServiceNeed.AIR, "tpms", now - 3_600_000L)
        assertFalse(PitServiceRank.shouldAsk(v, setOf("tpms"), now))
        assertTrue(PitServiceRank.shouldAsk(v, emptySet(), now))
        assertFalse(PitServiceRank.shouldAsk(v.copy(atMs = now - 13 * 3_600_000L), emptySet(), now))
        assertFalse(PitServiceRank.shouldAsk(null, emptySet(), now))
    }

    @Test fun demoPlacesSayTheyAreDemoAndTyreCardAsksForAir() {
        assertTrue(PitServiceRank.demoPlaces(here).all { it.name.startsWith("시연") && it.offers.values.all { e -> e == Evidence.DEMO } })
        assertEquals(ServiceNeed.AIR, FaultTriage.check(HealthInput(mapOf("TPMS" to false))).cards.first().fix?.need)
    }

    @Test fun oneTapFactsBecomeTagsAndTheNextQuestionMovesOn() {
        val r1 = CrewReport("p", ServiceNeed.AIR, true, 1L, facts = mapOf(PlaceFact.FREE to true))
        val r2 = CrewReport("p", ServiceNeed.AIR, true, 2L, facts = mapOf(PlaceFact.SELF to true))
        val r3 = CrewReport("p", ServiceNeed.AIR, true, 3L, facts = mapOf(PlaceFact.FREE to true))
        val r4 = CrewReport("p", ServiceNeed.AIR, true, 4L, facts = mapOf(PlaceFact.FREE to false))
        assertEquals(listOf("무료", "셀프"), PitServiceRank.tags(listOf(r1, r2, r3, r4))) // 2 free vs 1 paid
        assertEquals(emptyList<String>(), PitServiceRank.tags(listOf(r1, r4)))            // a tie shows nothing
        assertEquals(PlaceFact.FREE, PitServiceRank.nextFact("p", ServiceNeed.AIR, emptyList()))
        assertEquals(PlaceFact.ALL_DAY, PitServiceRank.nextFact("p", ServiceNeed.AIR, listOf(r1, r2)))
        assertEquals(null, PitServiceRank.nextFact("p", ServiceNeed.AIR, listOf(r1, r2, CrewReport("p", ServiceNeed.AIR, true, 5L, facts = mapOf(PlaceFact.ALL_DAY to false)))))
        assertEquals(PlaceFact.FREE, PitServiceRank.nextFact("p", ServiceNeed.OIL, listOf(r1, r2))) // asked per kind of service
    }

    @Test fun placeDetailSaysWhatIsKnownAndNeverGuesses() {
        val p = ServicePlace("p", "곳", 37.5, 127.0, "", mapOf(ServiceNeed.AIR to Evidence.OFFICIAL, ServiceNeed.WASHER to Evidence.DEMO), "출처")
        val none = PitServiceRank.detail(p, emptyList())
        assertEquals(listOf(ServiceNeed.AIR, ServiceNeed.WASHER), none.map { it.need })
        assertEquals("공식 데이터에 있음 · 운전자 확인 전", none[0].verdict)
        val ok = CrewReport("p", ServiceNeed.AIR, true, 10L, carConfirmed = true)
        assertEquals("차량 센서로 확인됨 (1건)", PitServiceRank.detail(p, listOf(ok))[0].verdict)
        val broken = CrewReport("p", ServiceNeed.AIR, false, 20L, fail = FailReason.BROKEN)
        assertEquals("최근 '안 됨' 신고 · 고장", PitServiceRank.detail(p, listOf(ok, broken))[0].verdict)
        // A service the data does not list appears once a driver reports it.
        assertTrue(PitServiceRank.detail(p, listOf(CrewReport("p", ServiceNeed.OIL, true, 5L))).any { it.need == ServiceNeed.OIL && it.listed == null })
    }
}
