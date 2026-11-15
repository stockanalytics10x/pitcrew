package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaviActiveTest {
    @Test fun routeCountsWhenRoutingOrDistanceLeft() {
        assertTrue(naviActive(true, null))
        assertTrue(naviActive(false, 378.0))
        assertTrue(naviActive(null, 12.5))
        assertFalse(naviActive(false, null))
        assertFalse(naviActive(null, 0.0))
        assertFalse(naviActive(null, null))
    }

    @Test fun naviArrivalShownAsKwh() {
        assertEquals("77.1kWh", naviArrivalText(77105))
        assertEquals("—", naviArrivalText(null))
    }
}
