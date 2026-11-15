package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserPlaceTest {
    @Test fun addedPlaceCarriesDriverEvidenceForEachChosenService() {
        val p = UserPlace("u1", "동네 세차장", 37.5, 127.0, setOf(ServiceNeed.WASH, ServiceNeed.AIR), 0L).asPlace()
        assertEquals(Evidence.USER, p.offers[ServiceNeed.WASH])
        assertEquals(Evidence.USER, p.offers[ServiceNeed.AIR])
        assertTrue(ServiceNeed.OIL !in p.offers)
        assertEquals("내가 추가한 장소", p.source)
    }

    @Test fun aGarageMarkedAsAlsoWashingAppearsInTheWashList() {
        val garage = ServicePlace("g1", "정비소", 37.5, 127.0, "", mapOf(ServiceNeed.REPAIR to Evidence.OFFICIAL), "공식")
        assertTrue(PitServiceRank.options(ServiceNeed.WASH, listOf(garage), emptyList(), null, 1_000L).isEmpty())
        val marked = listOf(CrewReport("g1", ServiceNeed.WASH, ok = true, atMs = 1_000L))
        val wash = PitServiceRank.options(ServiceNeed.WASH, listOf(garage), marked, null, 1_000L)
        assertEquals(listOf("g1"), wash.map { it.place.id })
        assertEquals(Evidence.USER, wash.first().evidence)
    }
}
