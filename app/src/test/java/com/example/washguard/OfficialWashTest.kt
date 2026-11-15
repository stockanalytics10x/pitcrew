package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class OfficialWashTest {
    private val data by lazy {
        val f = listOf("src/main/assets/${OfficialWash.ASSET}", "app/src/main/assets/${OfficialWash.ASSET}").map(::File).first { it.exists() }
        GZIPInputStream(f.inputStream()).bufferedReader().useLines { OfficialWash.parse(it) }
    }

    @Test fun assetLoadsTheWholeCountry() {
        assertTrue(data.second.size > 15_000)
        assertTrue(data.first.startsWith("2026"))
    }

    @Test fun nearestAroundCityHallAreRealAndSorted() {
        val here = GeoPoint(0, 37.5665, 126.9780)
        val near = OfficialWash.near(data, here, km = 5.0)
        assertTrue(near.size >= 10)
        val d = near.map { distanceKm(here, GeoPoint(0, it.lat, it.lon)) }
        assertEquals(d.sorted(), d)
        assertTrue(d.all { it <= 5.0 })
        assertTrue(near.all { it.offers[ServiceNeed.WASH] == Evidence.OFFICIAL })
    }

    @Test fun sideBusinessesAreMarked() {
        val all = data.second
        assertTrue(all.any { it.business == "주유소" })
        val shop = all.first { it.business == "정비소" }
        val p = OfficialWash.place(shop, data.first)
        assertTrue("정비소 겸업" in p.labels)
        assertEquals(Evidence.LIKELY, p.offers[ServiceNeed.REPAIR])
    }

    @Test fun rankingKeepsOfficialWashesWithin30Km() {
        val here = GeoPoint(0, 37.5665, 126.9780)
        val opts = PitServiceRank.options(ServiceNeed.WASH, OfficialWash.near(data, here), emptyList(), here, 0L, limit = 10)
        assertEquals(10, opts.size)
        assertTrue(opts.all { it.evidence == Evidence.OFFICIAL && (it.distanceKm ?: 99.0) <= PitServiceRank.MAX_KM })
    }
}

class OfficialRepairTest {
    private val data by lazy {
        val f = listOf("src/main/assets/${OfficialRepair.ASSET}", "app/src/main/assets/${OfficialRepair.ASSET}").map(::File).first { it.exists() }
        GZIPInputStream(f.inputStream()).bufferedReader().useLines { OfficialWash.parse(it) }
    }

    @Test fun openShopsOnly() { assertTrue(data.second.size > 30_000) }

    @Test fun repairIsOfficialOilAndAirAreInferred() {
        val here = GeoPoint(0, 37.5665, 126.9780)
        val near = OfficialRepair.near(data, here, km = 5.0)
        assertTrue(near.size >= 20)
        assertTrue(near.all { it.offers[ServiceNeed.REPAIR] == Evidence.OFFICIAL })
        assertTrue(near.filter { ServiceNeed.AIR in it.offers }.all { it.offers[ServiceNeed.AIR] == Evidence.LIKELY })
        val opts = PitServiceRank.options(ServiceNeed.REPAIR, near, emptyList(), here, 0L, limit = 5)
        assertEquals(5, opts.size)
    }
}

class OfficialRestAreaTest {
    private val data by lazy {
        val f = listOf("src/main/assets/${OfficialRestArea.ASSET}", "app/src/main/assets/${OfficialRestArea.ASSET}").map(::File).first { it.exists() }
        GZIPInputStream(f.inputStream()).bufferedReader().useLines { OfficialWash.parse(it) }
    }

    @Test fun restAreasWithShops() {
        assertEquals(171, data.second.size)
        val near = OfficialRestArea.near(data, GeoPoint(0, 36.65, 127.37), km = 20.0)   // 옥산 부근
        val oksan = near.first { it.name.startsWith("옥산") }
        assertEquals(Evidence.OFFICIAL, oksan.offers[ServiceNeed.REPAIR])
        assertEquals(Evidence.LIKELY, oksan.offers[ServiceNeed.AIR])
        assertTrue("경정비" in oksan.labels)
    }
}
