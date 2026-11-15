package com.example.washguard

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * EV chargers from 한국환경공단_전기자동차 충전소 정보 (공공데이터포털 15076352, B552584/EvCharger).
 *
 * - getChargerInfo by 시군구 (zscode) around the car, found from a small first page of the 시도 (zcode).
 * - getChargerStatus (last 10 minutes) updates each charger's state.
 * The stations near the car are kept in a file for a day, so the list works offline after the first load.
 * The key comes from local.properties (EV_CHARGER_KEY) through BuildConfig; without it nothing is called.
 */
object EvCharger {
    const val SOURCE = "한국환경공단 전기차 충전소 정보"
    private const val BASE = "https://apis.data.go.kr/B552584/EvCharger"
    const val KEEP_KM = 30.0
    private const val DAY_MS = 86_400_000L

    data class Charger(val id: String, val type: String, val outputKw: Int?, var stat: String, var statAt: String)
    data class Station(val id: String, val name: String, val lat: Double, val lon: Double, val address: String,
                       val operator: String, val useTime: String, val limited: Boolean, val limitNote: String,
                       val parkingFree: Boolean?, val zscode: String, val chargers: MutableList<Charger>) {
        val fast: List<Charger> get() = chargers.filter { isFast(it.type) }
        val slow: List<Charger> get() = chargers.filter { !isFast(it.type) }
    }

    /** 01·03·04·05·06 are DC (fast); 02·07 are AC (slow). */
    fun isFast(type: String) = type in setOf("01", "03", "04", "05", "06")

    /** Charger state code in words. 2 = free, 3 = charging. */
    fun statWord(code: String) = when (code) {
        "2" -> "사용 가능"; "3" -> "충전 중"; "4" -> "운영 중지"; "5" -> "점검 중"; "1" -> "통신 이상"; else -> "상태 미확인"
    }

    /** 시도 codes with a rough centre; 강원·전북 have new codes (51·52) and old ones (42·45). */
    private val SIDO = listOf(
        Triple("11", 37.5665, 126.9780), Triple("26", 35.1796, 129.0756), Triple("27", 35.8714, 128.6014),
        Triple("28", 37.4563, 126.7052), Triple("29", 35.1595, 126.8526), Triple("30", 36.3504, 127.3845),
        Triple("31", 35.5384, 129.3114), Triple("36", 36.4800, 127.2890), Triple("41", 37.4138, 127.5183),
        Triple("51", 37.8228, 128.1555), Triple("43", 36.6357, 127.4917), Triple("44", 36.5184, 126.8000),
        Triple("52", 35.7175, 127.1530), Triple("46", 34.8161, 126.4629), Triple("47", 36.4919, 128.8889),
        Triple("48", 35.4606, 128.2132), Triple("50", 33.4890, 126.4983))
    private val OLD = mapOf("51" to "42", "52" to "45")

    data class Result(val stations: List<Station>, val status: String)

    private fun cacheFile(context: Context) = File(context.filesDir, "ev_chargers.json")

    /** Stations near [here], from today's cache when the car has not moved far, otherwise from the API. */
    fun near(context: Context, key: String, here: GeoPoint, nowMs: Long = System.currentTimeMillis()): Result {
        if (key.isBlank()) return Result(emptyList(), "충전소 키 없음 · 기본 내비 검색 사용")
        readCache(context)?.let { (at, atMs, list) ->
            if (nowMs - atMs < DAY_MS && distanceKm(at, here) < 10.0) {
                val upd = runCatching { updateStatus(key, list) }.getOrDefault(0)
                return Result(list, "충전소 ${list.size}곳 · 상태 ${if (upd >= 0) "갱신" else "갱신 실패"} (오늘 받은 목록)")
            }
        }
        // Which 시도 to look in: the nearest centre, plus the next one when it is about as close (borders, Seoul↔Gyeonggi).
        val bySido = SIDO.sortedBy { distanceKm(here, GeoPoint(0, it.second, it.third)) }
        val d0 = distanceKm(here, GeoPoint(0, bySido[0].second, bySido[0].third))
        val sidos = bySido.take(3).filter { distanceKm(here, GeoPoint(0, it.second, it.third)) <= d0 + 35.0 }.map { it.first }

        // 1) A small page per 시도 shows which 시군구 (zscode) lie around the car.
        val zs = mutableMapOf<String, MutableList<GeoPoint>>()
        for (z in sidos) {
            val items = runCatching { page(key, "getChargerInfo", "zcode=$z", 1, 2000).second }
                .recoverCatching { page(key, "getChargerInfo", "zcode=${OLD[z] ?: z}", 1, 2000).second }.getOrThrow()
            for (o in items) {
                val p = latLon(o) ?: continue
                zs.getOrPut(o.optString("zscode")) { mutableListOf() } += p
            }
        }
        val near = zs.mapNotNull { (code, ps) ->
            val c = GeoPoint(0, ps.map { it.lat }.average(), ps.map { it.lon }.average())
            (code to distanceKm(here, c)).takeIf { code.isNotBlank() && it.second <= KEEP_KM - 5 }
        }.sortedBy { it.second }.take(6).map { it.first }.ifEmpty { zs.keys.take(1) }

        // 2) Every charger in those 시군구.
        val stations = linkedMapOf<String, Station>()
        for (zc in near) {
            var pageNo = 1
            while (pageNo <= 10) {
                val (total, items) = page(key, "getChargerInfo", "zscode=$zc", pageNo, 9999)
                for (o in items) add(stations, o)
                if (pageNo * 9999 >= total || items.isEmpty()) break
                pageNo++
            }
        }
        val list = stations.values.filter { distanceKm(here, GeoPoint(0, it.lat, it.lon)) <= KEEP_KM }
            .sortedBy { distanceKm(here, GeoPoint(0, it.lat, it.lon)) }.take(400)
        writeCache(context, here, nowMs, list)
        val free = list.sumOf { s -> s.fast.count { it.stat == "2" } }
        return Result(list, "충전소 ${list.size}곳 · 지금 쓸 수 있는 급속 ${free}대")
    }

    private fun latLon(o: JSONObject): GeoPoint? {
        val lat = o.optString("lat").toDoubleOrNull() ?: return null
        val lon = o.optString("lng").toDoubleOrNull() ?: return null
        return if (lat in 33.0..39.0 && lon in 124.0..132.0) GeoPoint(0, lat, lon) else null
    }

    private fun add(map: MutableMap<String, Station>, o: JSONObject) {
        if (o.optString("delYn") == "Y") return
        val p = latLon(o) ?: return
        val id = o.optString("statId").ifBlank { return }
        val st = map.getOrPut(id) {
            Station(id, o.optString("statNm"), p.lat, p.lon, o.optString("addr"), o.optString("busiNm").ifBlank { o.optString("bnm") },
                o.optString("useTime"), o.optString("limitYn") == "Y", o.optString("limitDetail"),
                o.optString("parkingFree").let { if (it == "Y") true else if (it == "N") false else null }, o.optString("zscode"), mutableListOf())
        }
        val cid = o.optString("chgerId")
        if (st.chargers.none { it.id == cid })
            st.chargers += Charger(cid, o.optString("chgerType"), o.optString("output").toIntOrNull(), o.optString("stat"), o.optString("statUpdDt"))
    }

    /** Applies the last 10 minutes of state changes for the 시도 of the listed stations. Returns how many changed. */
    fun updateStatus(key: String, list: List<Station>): Int {
        if (list.isEmpty()) return 0
        val byId = list.associateBy { it.id }
        var n = 0
        for (zcode in list.map { it.zscode.take(2) }.filter { it.length == 2 }.distinct()) {
            var pageNo = 1
            while (pageNo <= 5) {
                val (total, items) = page(key, "getChargerStatus", "zcode=$zcode&period=10", pageNo, 9999)
                for (o in items) {
                    val c = byId[o.optString("statId")]?.chargers?.firstOrNull { it.id == o.optString("chgerId") } ?: continue
                    c.stat = o.optString("stat"); c.statAt = o.optString("statUpdDt"); n++
                }
                if (pageNo * 9999 >= total || items.isEmpty()) break
                pageNo++
            }
        }
        return n
    }

    /** One page: (totalCount, items). Throws with the API's message when it answers with an error. */
    private fun page(key: String, op: String, query: String, pageNo: Int, rows: Int): Pair<Int, List<JSONObject>> {
        val url = "$BASE/$op?serviceKey=${URLEncoder.encode(key, "UTF-8")}&pageNo=$pageNo&numOfRows=$rows&dataType=JSON&$query"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 60_000
        try {
            val body = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (!body.trimStart().startsWith("{")) throw IllegalStateException(errorText(body).ifBlank { "HTTP ${c.responseCode}" })
            val j = JSONObject(body)
            val code = j.optString("resultCode")
            if (code.isNotBlank() && code != "00") throw IllegalStateException(j.optString("resultMsg").ifBlank { "오류 $code" })
            val arr: JSONArray = j.optJSONObject("items")?.optJSONArray("item") ?: JSONArray()
            return j.optInt("totalCount", arr.length()) to (0 until arr.length()).map { arr.getJSONObject(it) }
        } finally { c.disconnect() }
    }

    /** The data.go.kr gateway answers key problems in XML. */
    private fun errorText(body: String): String =
        Regex("<returnAuthMsg>([^<]*)</returnAuthMsg>|<resultMsg>([^<]*)</resultMsg>|<errMsg>([^<]*)</errMsg>").find(body)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() } ?: body.take(80)

    private fun writeCache(context: Context, at: GeoPoint, atMs: Long, list: List<Station>) = runCatching {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("name", s.name).put("lat", s.lat).put("lon", s.lon).put("addr", s.address)
                .put("op", s.operator).put("use", s.useTime).put("lim", s.limited).put("limN", s.limitNote)
                .put("park", s.parkingFree ?: JSONObject.NULL).put("zs", s.zscode)
                .put("ch", JSONArray().apply { s.chargers.forEach { c -> put(JSONObject().put("id", c.id).put("t", c.type).put("kw", c.outputKw ?: JSONObject.NULL).put("s", c.stat).put("at", c.statAt)) } }))
        }
        cacheFile(context).writeText(JSONObject().put("lat", at.lat).put("lon", at.lon).put("ms", atMs).put("list", arr).toString())
    }.onFailure { Log.w("PitstopData", "EV cache write", it) }

    private fun readCache(context: Context): Triple<GeoPoint, Long, List<Station>>? = runCatching {
        val f = cacheFile(context); if (!f.exists()) return null
        val j = JSONObject(f.readText()); val arr = j.getJSONArray("list")
        Triple(GeoPoint(0, j.getDouble("lat"), j.getDouble("lon")), j.getLong("ms"), (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); val ch = o.getJSONArray("ch")
            Station(o.getString("id"), o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), o.optString("addr"), o.optString("op"),
                o.optString("use"), o.optBoolean("lim"), o.optString("limN"), if (o.isNull("park")) null else o.optBoolean("park"), o.optString("zs"),
                (0 until ch.length()).map { k -> val c = ch.getJSONObject(k)
                    Charger(c.getString("id"), c.optString("t"), if (c.isNull("kw")) null else c.optInt("kw"), c.optString("s"), c.optString("at")) }.toMutableList())
        })
    }.getOrNull()

    /** A station as a PIT SERVICE place: official for charging, with what is free right now in its labels. */
    fun place(s: Station): ServicePlace {
        val fast = s.fast; val slow = s.slow
        val labels = buildList {
            if (fast.isNotEmpty()) add("급속 ${fast.count { it.stat == "2" }}/${fast.size} 사용 가능")
            if (slow.isNotEmpty()) add("완속 ${slow.count { it.stat == "2" }}/${slow.size} 사용 가능")
            fast.mapNotNull { it.outputKw }.maxOrNull()?.let { add("최대 ${it}kW") }
            if (s.limited) add("이용 제한") else add("누구나 이용")
            if (s.parkingFree == true) add("주차 무료")
            if (s.operator.isNotBlank()) add(s.operator)
        }
        return ServicePlace("ev-${s.id}", s.name, s.lat, s.lon, s.address, mapOf(ServiceNeed.CHARGE to Evidence.OFFICIAL), SOURCE,
            s.useTime.ifBlank { null }, labels)
    }
}
