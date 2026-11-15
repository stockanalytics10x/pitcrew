package com.example.washguard

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class UltraForecastSummary(
    val firstRain: String? = null,
    val complete: Boolean = false,
    val status: String = "가까운 시간의 강수 여부를 아직 확인하지 못했어요",
    val clearLabel: String = "확인 필요"
)

/** KMA returns six hourly forecast slots from the bulletin, not six future slots from each query. */
fun parseUltraForecast(rows: List<JSONObject>, requestedBase: LocalDateTime, now: LocalDateTime): UltraForecastSummary {
    val stamp = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
    val display = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    val clock = DateTimeFormatter.ofPattern("HH:mm")
    if (now < requestedBase || now >= requestedBase.plusHours(2)) return UltraForecastSummary()
    val hourBase = requestedBase.withMinute(0).withSecond(0).withNano(0)
    val expected = (1L..6L).map { hourBase.plusHours(it) }.filter { it > now }
    if (expected.isEmpty()) return UltraForecastSummary()
    val pty = rows.filter { it.optString("category") == "PTY" }
    // The live response labels baseTime as HH00 for an HH30 request; accept only that same bulletin hour.
    val matching = pty.filter {
        runCatching {
            val base = LocalDateTime.parse(it.getString("baseDate") + it.getString("baseTime"), stamp)
            base == hourBase || base == requestedBase
        }.getOrDefault(false)
    }
    val values = matching.mapNotNull {
        runCatching {
            LocalDateTime.parse(it.getString("fcstDate") + it.getString("fcstTime"), stamp) to it.optString("fcstValue").toIntOrNull()
        }.getOrNull()
    }.groupBy({ it.first }, { it.second })
    val validCodes = setOf(0, 1, 2, 3, 5, 6, 7)
    fun value(time: LocalDateTime): Int? = values[time]?.distinct()?.singleOrNull()?.takeIf { it in validCodes }
    val complete = expected.all { value(it) != null }
    val first = expected.firstOrNull { value(it)?.let { code -> code != 0 } == true }
    val window = "${expected.first().format(clock)}~${expected.last().format(clock)}"
    val actualBase = matching.firstOrNull()?.let {
        runCatching { LocalDateTime.parse(it.getString("baseDate") + it.getString("baseTime"), stamp).format(display) }.getOrNull()
    }
    val status = when {
        first != null -> "초단기 · ${actualBase ?: requestedBase.format(display)} 기준 · ${first.format(display)} 강수 예보"
        complete -> "초단기 · ${actualBase ?: requestedBase.format(display)} 기준 · 예보 시각 $window 강수 예보 없음"
        else -> "가까운 시간의 강수 여부를 아직 확인하지 못했어요"
    }
    return UltraForecastSummary(first?.format(display), complete, status,
        if (complete && first == null) "${expected.last().format(clock)}까지 강수 예보 없음" else "확인 필요")
}

