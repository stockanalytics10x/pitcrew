package com.example.washguard

import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId
import kotlin.math.*

data class GeoPoint(val time: Long, val lat: Double, val lon: Double)
data class WeatherGrid(val x: Int, val y: Int)
data class Station(val id: Int, val name: String, val lat: Double, val lon: Double)

fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
    val lat = Math.toRadians(b.lat - a.lat)
    val lon = Math.toRadians(b.lon - a.lon)
    val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(lon / 2).pow(2)
    return 6371.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** KMA Lambert conformal grid, 5 km; same constants as official short-range forecast guide. */
fun weatherGrid(lat: Double, lon: Double): WeatherGrid? {
    if (!lat.isFinite() || !lon.isFinite() || lat !in 33.0..39.0 || lon !in 124.0..132.0) return null
    val re = 6371.00877 / 5.0
    val rad = Math.PI / 180
    val slat1 = 30 * rad
    val slat2 = 60 * rad
    val sn = ln(cos(slat1) / cos(slat2)) / ln(tan(Math.PI / 4 + slat2 / 2) / tan(Math.PI / 4 + slat1 / 2))
    val sf = tan(Math.PI / 4 + slat1 / 2).pow(sn) * cos(slat1) / sn
    val ro = re * sf / tan(Math.PI / 4 + 38 * rad / 2).pow(sn)
    val ra = re * sf / tan(Math.PI / 4 + lat * rad / 2).pow(sn)
    val theta = (lon - 126) * rad * sn
    return WeatherGrid(floor(ra * sin(theta) + 43 + 0.5).toInt(), floor(ro - ra * cos(theta) + 136 + 0.5).toInt())
}

fun villageBase(now: LocalDateTime): LocalDateTime {
    val ready = now.minusMinutes(45)
    val hour = listOf(2, 5, 8, 11, 14, 17, 20, 23).lastOrNull { it <= ready.hour }
    return if (hour == null) ready.toLocalDate().minusDays(1).atTime(23, 0) else ready.toLocalDate().atTime(hour, 0)
}

fun ultraBase(now: LocalDateTime): LocalDateTime {
    val ready = now.minusMinutes(20)
    return if (ready.minute >= 30) ready.withMinute(30).withSecond(0).withNano(0)
        else ready.minusHours(1).withMinute(30).withSecond(0).withNano(0)
}

/** ASOS hourly precipitation is reported for the hour ending at the observation timestamp. */
fun observationHour(time: Long): LocalDateTime = Instant.ofEpochMilli(time).atZone(ZoneId.of("Asia/Seoul"))
    .toLocalDateTime().withMinute(0).withSecond(0).withNano(0).plusHours(1)

fun validFreshPoint(point: GeoPoint, now: Long): Boolean = point.lat.isFinite() && point.lon.isFinite() &&
    point.lat in -90.0..90.0 && point.lon in -180.0..180.0 && point.time in (now - 5 * 60_000)..(now + 60_000)

fun nextWashAdvice(nearRain: String?, probability: Int?, precipitation: Double?, history: String, nearKnown: Boolean = true, wetHours: Int = 0): CareAdvice = when {
    nearRain != null -> CareAdvice("곧 강수 예상 · 세차 일정 조정", "$nearRain 비·눈 예보가 있습니다. 세차는 강수가 지난 뒤 다시 검토하세요. $history")
    probability == null -> CareAdvice("예보 확인 필요", "날씨 조회가 완료되기 전에는 세차 시점을 판단하지 않습니다. $history")
    !nearKnown -> CareAdvice("가까운 시간의 예보 확인 필요", "가까운 시간의 강수 여부를 아직 확인하지 못했어요. 내일 예보만으로 지금 세차 여부를 판단하기는 어려워요. $history")
    probability >= 60 || (precipitation ?: 0.0) >= 1 -> CareAdvice("세차 일정 조정 고려", "내일 강수 예보가 있습니다. $history")
    wetHours > 0 -> CareAdvice("강수 기록이 있어 오염 상태를 확인해요", "세차 이후 근처 관측소에서 강수·신적설이 확인됐어요. 실제 오염을 보고 세차를 결정하세요. $history")
    else -> CareAdvice("세차 일정 검토 가능", "앞으로의 강수 예보와 실제 오염 상태를 함께 확인하세요. $history")
}
