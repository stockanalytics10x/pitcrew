package com.example.washguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.*
import java.time.format.DateTimeFormatter

val KST: ZoneId = ZoneId.of("Asia/Seoul")
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")
data class ForecastBundle(val tomorrow: SeoulForecast?, val nearRain: String?, val nearStatus: String, val issued: String, val nearKnown: Boolean = false, val nearClearLabel: String = "확인 필요", val days: List<DayForecast> = emptyList(), val nowTempC: Double? = null)
data class HistorySummary(val text: String, val wetHours: Int = 0, val compared: Int = 0)

class KmaApi(context: Context, private val store: TravelStore) {
    private val stations = context.assets.open("asos-stations.json").bufferedReader().use { input ->
        val array = JSONArray(input.readText().removePrefix("\uFEFF"))
        (0 until array.length()).map { val s = array.getJSONObject(it); Station(s.getInt("id"), s.getString("name"), s.getDouble("lat"), s.getDouble("lon")) }
    }
    fun nearest(point: GeoPoint): Station? = stations.minByOrNull { distanceKm(point, GeoPoint(0, it.lat, it.lon)) }
        ?.takeIf { distanceKm(point, GeoPoint(0, it.lat, it.lon)) <= 30 }

    private fun items(path: String, params: Map<String, String>): List<JSONObject> {
        val requestParams = when {
            path.endsWith("getWthrDataList") -> mapOf("kind" to "asos", "date" to params.getValue("startDt"), "station" to params.getValue("stnIds"))
            else -> mapOf("kind" to if (path.endsWith("getUltraSrtFcst")) "ultra" else "village",
                "date" to params.getValue("base_date"), "time" to params.getValue("base_time"),
                "nx" to params.getValue("nx"), "ny" to params.getValue("ny"))
        }
        val query = requestParams.entries.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
        val connection = URL("https://rmdrlodpvgkwbjmijxjd.supabase.co/functions/v1/kma-weather?$query").openConnection() as HttpURLConnection
        // Public anon JWT for compatibility with the function gateway. This is not a KMA or service-role secret.
        connection.setRequestProperty("Authorization", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InJtZHJsb2Rwdmdrd2JqbWlqeGpkIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTI3MTIyODQsImV4cCI6MjEwODI4ODI4NH0.UMVCH7qaBDpiwZJrx15ye-3bXRaRDvK1Si8-P47kp-k")
        connection.connectTimeout = 12000; connection.readTimeout = 15000
        try {
            check(connection.responseCode == 200) { "기상청 서버 응답 오류" }
            val text = connection.inputStream.bufferedReader().use { it.readText().removePrefix("\uFEFF") }
            check(text.trimStart().startsWith("{")) { "기상청 인증키 또는 활용 승인 확인 필요" }
            val response = JSONObject(text).getJSONObject("response")
            val code = response.getJSONObject("header").getString("resultCode")
            if (code == "03") return emptyList()
            check(code == "00") { "기상청 응답 코드 $code · 인증키 및 서비스 활용 승인 확인" }
            val item = response.getJSONObject("body").optJSONObject("items")?.opt("item") ?: return emptyList()
            return when (item) { is JSONArray -> (0 until item.length()).map { item.getJSONObject(it) }; is JSONObject -> listOf(item); else -> emptyList() }
        } finally { connection.disconnect() }
    }

    fun forecast(point: GeoPoint, now: LocalDateTime): ForecastBundle {
        val grid = weatherGrid(point.lat, point.lon) ?: error("기상청 국내 예보 범위 밖의 위치")
        var base = villageBase(now)
        var village = emptyList<JSONObject>()
        repeat(2) {
            if (village.isEmpty()) {
                village = items("VilageFcstInfoService_2.0/getVilageFcst", mapOf("base_date" to base.format(DAY), "base_time" to base.format(CLOCK), "nx" to grid.x.toString(), "ny" to grid.y.toString()))
                if (village.isEmpty()) base = base.minusHours(3)
            }
        }
        val forecast = parseTomorrow(village, now.toLocalDate().plusDays(1))
        val days = parseDays(village, now)
        val ultraTime = ultraBase(now)
        val near = runCatching {
            val ultra = items("VilageFcstInfoService_2.0/getUltraSrtFcst", mapOf("base_date" to ultraTime.format(DAY), "base_time" to ultraTime.format(CLOCK), "nx" to grid.x.toString(), "ny" to grid.y.toString()))
            parseUltraForecast(ultra, ultraTime, now)
        }.getOrElse { UltraForecastSummary(status = "초단기 강수 예보 조회 실패") }
        check(forecast != null || near.firstRain != null || near.complete) { "기상청 예보 없음" }
        return ForecastBundle(forecast, near.firstRain, near.status, base.format(DateTimeFormatter.ofPattern("MM-dd HH:mm")), near.complete, near.clearLabel, days, parseTempNow(village, now))
    }

    fun history(now: LocalDateTime, wash: Long): HistorySummary {
        if (wash <= 0) return HistorySummary("세차 완료를 기록하면 그 이후의 위치 표본으로 비교합니다.")
        val (epoch, points) = store.snapshot()
        val plan = historyPlan(points, wash, now, ::nearest)
        if (plan.points == 0) return HistorySummary("마지막 세차 이후 위치 기록을 기다리고 있어요.")
        var requests = 0
        val hourFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00")
        for ((group, samples) in plan.targets.groupBy { it.station to it.hour.toLocalDate() }) {
            if (samples.all { store.observation(it.station, it.hour.format(hourFormat)) != null }) continue
            if (requests++ >= 6) break
            runCatching {
                items("AsosHourlyInfoService/getWthrDataList", mapOf("dataCd" to "ASOS", "dateCd" to "HR",
                    "startDt" to group.second.format(DAY), "endDt" to group.second.format(DAY),
                    "startHh" to "00", "endHh" to "23", "stnIds" to group.first.toString())).forEach { row ->
                    val hour = runCatching { LocalDateTime.parse(row.getString("tm"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrNull()
                    if (row.optString("stnId").toIntOrNull() == group.first && hour?.toLocalDate() == group.second) {
                        store.saveObservation(group.first, hour.format(hourFormat),
                            observedAmount(row, "rn", "rnQcflg"), observedAmount(row, "dsnw", "dsnwQcflg"), epoch)
                    }
                }
            }
        }
        val evidence = historyEvidence(plan.targets) { store.observation(it.station, it.hour.format(hourFormat)) }
        val since = java.time.Instant.ofEpochMilli(plan.since).atZone(KST).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        return HistorySummary("세차 이후 · 최근 7일 내 $since 이후 위치 표본 ${plan.points}개. " +
            "근처 관측소 강수·신적설 ${evidence.wet}개 시간대 / 확인 ${evidence.checked}개 시간대. " +
            "자료 대기 ${plan.pending}개 시간대, 결측·조회 미확인 ${evidence.missing}개 시간대, 관측소 30km 밖 표본 ${plan.outside}개. " +
            "세차 시각에 걸친 시간대 ${plan.boundary}개는 제외했습니다. ASOS는 전일 자료까지 제공해 오늘 기록은 다음 날 이후 비교합니다. 주차·실내 여부와 실제 차량 오염은 미확인입니다.",
            evidence.wet, evidence.checked)

    }
}

fun parseTomorrow(rows: List<JSONObject>, date: LocalDate): SeoulForecast? {
    val day = date.format(DateTimeFormatter.BASIC_ISO_DATE)
    val probabilities = rows.filter { it.optString("fcstDate") == day && it.optString("category") == "POP" }
        .associate { it.optString("fcstTime") to it.optString("fcstValue").toIntOrNull() }
    val precipitation = rows.filter { it.optString("fcstDate") == day && it.optString("category") == "PCP" }
        .associate { it.optString("fcstTime") to parsePrecipitation(it.optString("fcstValue")) }
    val expected = (0..23).map { "%02d00".format(it) }
    if (expected.any { (probabilities[it] ?: -1) !in 0..100 }) return null
    val total = if (expected.all { precipitation[it] != null }) expected.sumOf { precipitation[it]!! } else null
    return SeoulForecast(date.toString(), probabilities.values.filterNotNull().max(), total)
}
/** Per-day POP/PCP for today (remaining hours) and the next days the bulletin covers. */
fun parseDays(rows: List<JSONObject>, now: LocalDateTime, count: Int = 5): List<DayForecast> = (0 until count).map { offset ->
    val date = now.toLocalDate().plusDays(offset.toLong())
    val day = date.format(DateTimeFormatter.BASIC_ISO_DATE)
    val expected = ((if (offset == 0) now.hour else 0)..23).map { "%02d00".format(it) }
    val pop = rows.filter { it.optString("fcstDate") == day && it.optString("category") == "POP" }
        .associate { it.optString("fcstTime") to it.optString("fcstValue").toIntOrNull()?.takeIf { v -> v in 0..100 } }
    val pcp = rows.filter { it.optString("fcstDate") == day && it.optString("category") == "PCP" }
        .associate { it.optString("fcstTime") to parsePrecipitation(it.optString("fcstValue")) }
    val pops = expected.mapNotNull { pop[it] }
    val complete = expected.all { pop[it] != null }
    val rain = if (expected.all { pcp[it] != null }) expected.sumOf { pcp[it]!! } else null
    DayForecast(date, pops.maxOrNull(), rain, complete)
}
/** Forecast temperature (TMP, °C) for the current hour, or the nearest later hour of today. */
fun parseTempNow(rows: List<JSONObject>, now: LocalDateTime): Double? {
    val day = now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)
    return rows.filter { it.optString("fcstDate") == day && it.optString("category") == "TMP" }
        .mapNotNull { r -> r.optString("fcstTime").take(2).toIntOrNull()?.let { h -> h to r.optString("fcstValue").toDoubleOrNull() } }
        .filter { it.second != null && it.first >= now.hour }.minByOrNull { it.first }?.second
}
fun parsePrecipitation(text: String): Double? = if (text == "강수없음") 0.0 else text.removeSuffix("mm").trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
