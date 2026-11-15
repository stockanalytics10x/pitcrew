package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceBriefingTest {
    private fun plan(soc: Double?, range: Double?, remaining: Double?) =
        PitWallEngine.plan(PitWallInput(soc, range, remaining))

    @Test fun headlineFollowsVerdict() {
        assertEquals("이번 주행, 피트인 없이 완주 가능", raceBriefingHeadline(plan(80.0, 400.0, 100.0)))
        assertEquals("중간에 한 번 피트인을 권장해요", raceBriefingHeadline(plan(52.0, 260.0, 170.0)))
        assertEquals("이번 주행은 피트인이 필요해요", raceBriefingHeadline(plan(52.0, 260.0, 230.0)))
        assertEquals("피트인이 두 번 필요해요", raceBriefingHeadline(plan(20.0, 80.0, 400.0)))
        assertTrue(raceBriefingHeadline(plan(50.0, 250.0, null)).contains("목적지"))
        assertTrue(raceBriefingHeadline(plan(null, null, 100.0)).contains("보류"))
    }
}
