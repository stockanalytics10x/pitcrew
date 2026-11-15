package com.example.washguard

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SeoulRoadGeometryTest {
    private fun square()=SeoulRoadGeometry(listOf(floatArrayOf(-1000f,-1000f,1000f,-1000f,1000f,1000f,-1000f,1000f,-1000f,-1000f)),
        listOf(OfflineRoad(1,floatArrayOf(-500f,0f,500f,0f)),OfflineRoad(4,floatArrayOf(5000f,5000f,5100f,5100f))))
    @Test fun projectionKeepsEastAndNorthInCorrectDirection() {
        val origin=roadProject(37.56,127.0)
        assertEquals(0f,origin.x,0.001f);assertEquals(0f,origin.y,0.001f)
        assertTrue(roadProject(37.57,127.01).x>0);assertTrue(roadProject(37.57,127.01).y>0)
    }
    @Test fun boundaryRejectsUnsupportedLocations() {
        assertTrue(square().inside(RoadXY(0f,0f)))
        assertFalse(square().inside(RoadXY(1500f,0f)))
        assertFalse(square().inside(RoadXY(0f,-1500f)))
    }
    @Test fun spatialIndexReturnsOnlyNearbyRoads() {
        val rows=square().nearby(RoadXY(0f,0f),800f)
        assertEquals(1,rows.size);assertEquals(1,rows.single().kind)
        assertTrue(square().nearby(RoadXY(15000f,15000f)).isEmpty())
    }
    @Test fun shippedSeoulDatabaseLoadsAndContainsRealCentralRoads() {
        val file=listOf(File("src/main/assets/seoul_roads.pfrgz"),File("app/src/main/assets/seoul_roads.pfrgz")).first {it.exists()}
        val map=file.inputStream().use {SeoulRoadGeometry.read(it)}
        assertTrue(map.roads.size>70000)
        val cityHall=roadProject(37.5665,126.9780)
        assertTrue(map.inside(cityHall))
        assertTrue(map.inside(roadProject(37.4979,127.0276)))
        assertFalse(map.inside(roadProject(37.3948,127.1112)))
        assertTrue(map.nearby(cityHall).size>100)
        assertTrue(map.nearby(cityHall).size<map.roads.size/2)
    }
}
