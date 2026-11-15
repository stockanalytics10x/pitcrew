package com.example.washguard

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

/**
 * KATEC (the TM grid Opinet uses): Bessel ellipsoid, origin 38°N 128°E, k 0.9999, false east 400 km / north 600 km.
 * WGS84 ↔ Bessel by a 3-parameter shift, good to a few tens of metres, which is enough to find and guide to a station.
 */
object Katec {
    private const val A_W = 6378137.0; private const val F_W = 1 / 298.257223563
    private const val A_B = 6377397.155; private const val F_B = 1 / 299.1528128
    private const val DX = -146.43; private const val DY = 507.89; private const val DZ = 681.46
    private const val LAT0 = 38.0; private const val LON0 = 128.0; private const val K0 = 0.9999
    private const val X0 = 400000.0; private const val Y0 = 600000.0

    private fun toEcef(lat: Double, lon: Double, a: Double, f: Double): DoubleArray {
        val e2 = f * (2 - f); val p = Math.toRadians(lat); val l = Math.toRadians(lon)
        val n = a / sqrt(1 - e2 * sin(p).pow(2))
        return doubleArrayOf(n * cos(p) * cos(l), n * cos(p) * sin(l), n * (1 - e2) * sin(p))
    }
    private fun fromEcef(x: Double, y: Double, z: Double, a: Double, f: Double): Pair<Double, Double> {
        val e2 = f * (2 - f); val lon = atan2(y, x); val p = sqrt(x * x + y * y)
        var lat = atan2(z, p * (1 - e2))
        repeat(6) { val n = a / sqrt(1 - e2 * sin(lat).pow(2)); lat = atan2(z + e2 * n * sin(lat), p) }
        return Math.toDegrees(lat) to Math.toDegrees(lon)
    }
    private fun m(phi: Double, a: Double, e2: Double): Double {
        val e4 = e2 * e2; val e6 = e4 * e2
        return a * ((1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi - (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * phi) +
            (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * phi) - (35 * e6 / 3072) * sin(6 * phi))
    }

    /** WGS84 lat/lon → KATEC x (east), y (north) in metres. */
    fun fromWgs84(lat: Double, lon: Double): Pair<Double, Double> {
        val w = toEcef(lat, lon, A_W, F_W)
        val (bl, bn) = fromEcef(w[0] - DX, w[1] - DY, w[2] - DZ, A_B, F_B)
        val e2 = F_B * (2 - F_B); val ep2 = e2 / (1 - e2)
        val phi = Math.toRadians(bl); val lam = Math.toRadians(bn - LON0)
        val n = A_B / sqrt(1 - e2 * sin(phi).pow(2)); val t = tan(phi).pow(2); val c = ep2 * cos(phi).pow(2); val aa = lam * cos(phi)
        val x = K0 * n * (aa + (1 - t + c) * aa.pow(3) / 6 + (5 - 18 * t + t * t + 72 * c - 58 * ep2) * aa.pow(5) / 120)
        val y = K0 * (m(phi, A_B, e2) - m(Math.toRadians(LAT0), A_B, e2) + n * tan(phi) * (aa * aa / 2 + (5 - t + 9 * c + 4 * c * c) * aa.pow(4) / 24 +
            (61 - 58 * t + t * t + 600 * c - 330 * ep2) * aa.pow(6) / 720))
        return (x + X0) to (y + Y0)
    }

    /** KATEC x, y → WGS84 lat/lon. */
    fun toWgs84(x: Double, y: Double): Pair<Double, Double> {
        val e2 = F_B * (2 - F_B); val ep2 = e2 / (1 - e2); val e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        val mm = m(Math.toRadians(LAT0), A_B, e2) + (y - Y0) / K0
        val mu = mm / (A_B * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2.pow(3) / 256))
        val phi1 = mu + (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) + (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) + (1097 * e1.pow(4) / 512) * sin(8 * mu)
        val n1 = A_B / sqrt(1 - e2 * sin(phi1).pow(2)); val t1 = tan(phi1).pow(2); val c1 = ep2 * cos(phi1).pow(2)
        val r1 = A_B * (1 - e2) / (1 - e2 * sin(phi1).pow(2)).pow(1.5); val d = (x - X0) / (n1 * K0)
        val lat = phi1 - (n1 * tan(phi1) / r1) * (d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * d.pow(4) / 24 +
            (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * d.pow(6) / 720)
        val lon = Math.toRadians(LON0) + (d - (1 + 2 * t1 + c1) * d.pow(3) / 6 + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * d.pow(5) / 120) / cos(phi1)
        val b = toEcef(Math.toDegrees(lat), Math.toDegrees(lon), A_B, F_B)
        return fromEcef(b[0] + DX, b[1] + DY, b[2] + DZ, A_W, F_W)
    }
}

/**
 * Fuel stations and today's prices from 오피넷 (한국석유공사) 유가정보 API, aroundAll (5 km around the car).
 * The key comes from local.properties (OPINET_KEY) through BuildConfig; without it nothing is called.
 */
object Opinet {
    const val SOURCE = "오피넷 유가정보"
    const val RADIUS_M = 5000

    data class Station(val id: String, val name: String, val brand: String, val lat: Double, val lon: Double,
                       val gasoline: Int?, val diesel: Int?, val distanceM: Double?)
    data class Result(val stations: List<Station>, val status: String)

    private val BRAND = mapOf("SKE" to "SK에너지", "GSC" to "GS칼텍스", "HDO" to "HD현대오일뱅크", "SOL" to "S-OIL",
        "RTE" to "자영 알뜰", "RTX" to "고속도로 알뜰", "NHO" to "농협 알뜰", "ETC" to "자가상표", "E1G" to "E1", "SKG" to "SK가스")

    fun near(key: String, here: GeoPoint): Result {
        if (key.isBlank()) return Result(emptyList(), "주유소 키 없음 · 기본 내비 검색 사용")
        val (x, y) = Katec.fromWgs84(here.lat, here.lon)
        val g = around(key, x, y, "B027")   // 휘발유
        val d = runCatching { around(key, x, y, "D047") }.getOrDefault(emptyList())   // 경유
        val dieselById = d.associate { it.id to it.price }
        val out = g.map { it.copy(diesel = dieselById[it.id]) } +
            d.filter { s -> g.none { it.id == s.id } }.map { it.copy(diesel = it.price, price = null) }
        val list = out.map { Station(it.id, it.name, it.brand, it.lat, it.lon, it.price, it.diesel, it.dist) }.sortedBy { it.distanceM ?: 1e9 }
        val cheapest = list.mapNotNull { it.gasoline }.minOrNull()
        return Result(list, "주유소 ${list.size}곳(5km)" + (cheapest?.let { " · 휘발유 최저 ${"%,d".format(it)}원" } ?: ""))
    }

    private data class Row(val id: String, val name: String, val brand: String, val lat: Double, val lon: Double,
                           val price: Int?, val dist: Double?, val diesel: Int? = null)

    private fun around(key: String, x: Double, y: Double, prod: String): List<Row> {
        val k = URLEncoder.encode(key, "UTF-8")
        val url = "https://www.opinet.co.kr/api/aroundAll.do?code=$k&certkey=$k&out=json&x=${"%.1f".format(java.util.Locale.US, x)}" +
            "&y=${"%.1f".format(java.util.Locale.US, y)}&radius=$RADIUS_M&sort=2&prodcd=$prod"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        try {
            val body = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (!body.trimStart().startsWith("{")) throw IllegalStateException(body.replace(Regex("<[^>]+>"), " ").trim().take(80).ifBlank { "HTTP ${c.responseCode}" })
            val arr = JSONObject(body).optJSONObject("RESULT")?.optJSONArray("OIL") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val gx = o.optString("GIS_X_COOR").toDoubleOrNull() ?: return@mapNotNull null
                val gy = o.optString("GIS_Y_COOR").toDoubleOrNull() ?: return@mapNotNull null
                val (lat, lon) = Katec.toWgs84(gx, gy)
                Row(o.optString("UNI_ID"), o.optString("OS_NM"), BRAND[o.optString("POLL_DIV_CD")] ?: o.optString("POLL_DIV_CD"),
                    lat, lon, o.optString("PRICE").toDoubleOrNull()?.roundToInt(), o.optString("DISTANCE").toDoubleOrNull())
            }
        } finally { c.disconnect() }
    }

    fun place(s: Station): ServicePlace = ServicePlace("oil-${s.id}", s.name, s.lat, s.lon, "", mapOf(ServiceNeed.FUEL to Evidence.OFFICIAL),
        "$SOURCE (오늘 가격)", null,
        listOfNotNull(s.gasoline?.let { "휘발유 ${"%,d".format(it)}원" }, s.diesel?.let { "경유 ${"%,d".format(it)}원" }, s.brand.ifBlank { null }))
}
