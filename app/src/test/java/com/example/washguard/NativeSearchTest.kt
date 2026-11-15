package com.example.washguard

import ai.pleos.playground.navi.data.NavigationScreenRequest
import org.junit.Assert.*
import org.junit.Test

class NativeSearchTest {
    @Test fun washAndFuelUseOemKeywordSearch() {
        assertEquals("세차장",(nativeSearchRequest(NativeSearchKind.WASH) as NavigationScreenRequest.SearchPlace).keyword)
        assertEquals("주유소",(nativeSearchRequest(NativeSearchKind.FUEL) as NavigationScreenRequest.SearchPlace).keyword)
    }
    @Test fun chargingUsesDedicatedNearbyScreen() {
        assertEquals(NavigationScreenRequest.NearbyChargingStations,nativeSearchRequest(NativeSearchKind.CHARGE))
    }
}
