package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DarkRouteTest {
    @Test fun oldLightPagesMapToTheDarkStrategyPage() {
        assertEquals("STRATEGY_WASH", darkRoute("CARE_DETAILS"))
        assertEquals("STRATEGY", darkRoute("MYCAR"))
        assertEquals("STRATEGY", darkRoute("ENERGY"))
        assertEquals("NAVI_CHARGE", darkRoute("NAVI_CHARGE"))
        listOf("CARE_DETAILS", "MYCAR", "ENERGY", "GARAGE", "PITSTOP", "STRATEGY").forEach {
            assertTrue(it, darkRoute(it) in referenceRoutes)
        }
    }
}
