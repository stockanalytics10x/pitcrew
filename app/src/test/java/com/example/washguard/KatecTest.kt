package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KatecTest {
    @Test fun seoulCityHallLandsWhereKatecPutsSeoul() {
        val (x, y) = Katec.fromWgs84(37.5665, 126.9780)
        println("KATEC city hall x=$x y=$y")
        assertTrue(x in 300_000.0..320_000.0)
        assertTrue(y in 545_000.0..560_000.0)
    }

    @Test fun roundTripWithinAFewMetres() {
        for ((lat, lon) in listOf(37.5665 to 126.9780, 35.1796 to 129.0756, 33.4890 to 126.4983, 37.75 to 128.9)) {
            val (x, y) = Katec.fromWgs84(lat, lon)
            val (la, lo) = Katec.toWgs84(x, y)
            assertTrue(distanceKm(GeoPoint(0, lat, lon), GeoPoint(0, la, lo)) < 0.005)
        }
    }

    @Test fun originIsFalseEastingNorthing() {
        // The grid origin (38N 128E on Bessel) sits near 400000/600000; the WGS84→Bessel shift moves it by a few hundred metres.
        val (x, y) = Katec.fromWgs84(38.0, 128.0)
        assertEquals(400_000.0, x, 1_000.0); assertEquals(600_000.0, y, 1_000.0)
    }
}
