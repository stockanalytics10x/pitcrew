package com.example.washguard

import android.content.Context
import java.util.zip.GZIPInputStream

/**
 * Car washes from the public dataset 행정안전부_세차장정보 (공공데이터포털 15075535, 지방행정 인허가 데이터).
 * Shipped in the app as assets/car_wash_kr.tsvz (packed with tools/pack_places.py, converted from the CSV: WGS84 coordinates, no owner names or phones).
 *
 * The dataset lists businesses registered for a car-wash facility, which includes fuel stations, repair shops and
 * charging stations that also wash. Those are marked "주유소 겸업" etc. so the driver sees what kind of place it is.
 */
/**
 * Opens a bundled ".tsv.gz" data file. The Android build un-gzips assets ending in .gz and stores them without the
 * extension (car_wash_kr.tsv.gz → car_wash_kr.tsv inside the APK), so the plain name is tried first.
 */
internal fun openDataAsset(context: Context, name: String): java.io.BufferedReader {
    // .tsvz is gzip that the build leaves as it is (it un-gzips .gz files and stores them larger).
    if (name.endsWith(".tsvz")) return GZIPInputStream(context.assets.open(name)).bufferedReader(Charsets.UTF_8)
    val plain = name.removeSuffix(".gz")
    return runCatching { context.assets.open(plain).bufferedReader(Charsets.UTF_8) }
        .getOrElse { GZIPInputStream(context.assets.open(name)).bufferedReader(Charsets.UTF_8) }
}

object OfficialWash {
    const val ASSET = "car_wash_kr.tsvz"
    const val SOURCE = "행정안전부 세차장정보"

    data class Row(val id: String, val name: String, val lat: Double, val lon: Double, val address: String,
                   /** 셀프 / 자동 / 손세차, or "" when the data does not say. */
                   val kind: String,
                   /** 주유소 / 정비소 / 충전소 when the wash is part of another business, else "". */
                   val business: String,
                   val hours: String)

    @Volatile private var cache: Pair<String, List<Row>>? = null

    /** Rows and the data date. Reads the asset once (about 16,000 rows); call off the main thread. */
    fun load(context: Context): Pair<String, List<Row>> = cache ?: synchronized(this) {
        cache ?: openDataAsset(context, ASSET).useLines { lines -> parse(lines) }.also { cache = it }
    }

    fun parse(lines: Sequence<String>): Pair<String, List<Row>> {
        var date = ""
        val rows = ArrayList<Row>(16_500)
        // "#!d5": coordinates are 1e-5° steps from the previous row (packed files); otherwise plain degrees.
        var delta = false; var la = 0L; var lo = 0L
        lines.forEach { line ->
            if (line.startsWith("#!d5")) { delta = true; return@forEach }
            if (line.startsWith("#")) { date = line.substringAfterLast(' ').trim(); return@forEach }
            val f = line.split('\t')
            if (f.size < 8) return@forEach
            val lat: Double; val lon: Double
            if (delta) {
                la += f[2].toLongOrNull() ?: return@forEach; lo += f[3].toLongOrNull() ?: return@forEach
                lat = la / 1e5; lon = lo / 1e5
            } else {
                lat = f[2].toDoubleOrNull() ?: return@forEach; lon = f[3].toDoubleOrNull() ?: return@forEach
            }
            // Packed repair rows carry no id: the row number is the id.
            rows += Row(f[0].ifBlank { rows.size.toString() }, f[1], lat, lon, f[4], f[5], f[6], f[7])
        }
        return date to rows
    }

    /** The washes within [km] of [here], nearest first, at most [max], as PIT SERVICE places. */
    fun near(data: Pair<String, List<Row>>, here: GeoPoint, km: Double = 40.0, max: Int = 400,
             toPlace: (Row, String) -> ServicePlace = ::place): List<ServicePlace> {
        val (date, rows) = data
        // Cheap box first (1° lat ≈ 111 km), then the real distance.
        val dLat = km / 111.0; val dLon = km / (111.0 * kotlin.math.cos(Math.toRadians(here.lat)).coerceAtLeast(0.2))
        return rows.asSequence()
            .filter { kotlin.math.abs(it.lat - here.lat) <= dLat && kotlin.math.abs(it.lon - here.lon) <= dLon }
            .map { it to distanceKm(here, GeoPoint(0, it.lat, it.lon)) }
            .filter { it.second <= km }
            .sortedBy { it.second }.take(max)
            .map { (r, _) -> toPlace(r, date) }.toList()
    }

    fun place(r: Row, date: String): ServicePlace {
        val offers = buildMap {
            put(ServiceNeed.WASH, Evidence.OFFICIAL)
            // A registered repair shop that also washes: repair is its main business, so it is listed for repair too (inferred).
            if (r.business == "정비소") put(ServiceNeed.REPAIR, Evidence.LIKELY)
        }
        val labels = listOfNotNull(r.kind.ifBlank { null }?.let { if (it == "자동") "자동세차" else if (it == "셀프") "셀프세차" else it },
            r.business.ifBlank { null }?.let { "$it 겸업" })
        return ServicePlace("wash-${r.id}", r.name, r.lat, r.lon, r.address, offers,
            "$SOURCE" + if (date.isNotBlank()) " ($date 갱신)" else "", r.hours.ifBlank { null }, labels)
    }
}

/**
 * Repair shops from 전국자동차정비업체표준데이터 (공공데이터포털 15028204), open businesses only,
 * shipped as assets/repair_kr.tsvz (same columns as the wash file: kind = 종합/소형/부분/원동기 정비, business = "타이어" when the name says so).
 */
object OfficialRepair {
    const val ASSET = "repair_kr.tsvz"
    const val SOURCE = "전국자동차정비업체표준데이터"

    @Volatile private var cache: Pair<String, List<OfficialWash.Row>>? = null

    fun load(context: Context): Pair<String, List<OfficialWash.Row>> = cache ?: synchronized(this) {
        cache ?: openDataAsset(context, ASSET).useLines { OfficialWash.parse(it) }.also { cache = it }
    }

    fun near(data: Pair<String, List<OfficialWash.Row>>, here: GeoPoint, km: Double = 40.0, max: Int = 400) =
        OfficialWash.near(data, here, km, max, ::place)

    /**
     * Repair is what the registry says (official). Oil change and tyre air are what such shops usually do, so they
     * are listed as inferred until a driver confirms them: oil at 종합·소형·부분정비, air at 부분정비 and tyre shops.
     */
    fun place(r: OfficialWash.Row, date: String): ServicePlace {
        val tire = r.business == "타이어"
        val offers = buildMap {
            put(ServiceNeed.REPAIR, Evidence.OFFICIAL)
            if (r.kind != "원동기정비") put(ServiceNeed.OIL, Evidence.LIKELY)
            if (tire || r.kind == "부분정비") put(ServiceNeed.AIR, Evidence.LIKELY)
        }
        return ServicePlace("repair-${r.id}", r.name, r.lat, r.lon, r.address, offers,
            SOURCE + if (date.isNotBlank()) " ($date 기준)" else "", r.hours.ifBlank { null },
            listOfNotNull(r.kind.ifBlank { null }?.let { if (it == "부분정비") "부분정비(카센터)" else it }, if (tire) "타이어 전문" else null))
    }
}

/**
 * Expressway rest areas from 한국도로공사 공공데이터(data.ex.co.kr): 휴게소 현황(serviceAreaRoute, 경정비 여부) joined with
 * 휴게소 위치(locationinfoRest). Shipped as assets/rest_area_kr.tsv.gz (address column holds the route name).
 */
object OfficialRestArea {
    const val ASSET = "rest_area_kr.tsv.gz"
    const val SOURCE = "한국도로공사 휴게소 정보"

    @Volatile private var cache: Pair<String, List<OfficialWash.Row>>? = null

    fun load(context: Context): Pair<String, List<OfficialWash.Row>> = cache ?: synchronized(this) {
        cache ?: openDataAsset(context, ASSET).useLines { OfficialWash.parse(it) }.also { cache = it }
    }

    fun near(data: Pair<String, List<OfficialWash.Row>>, here: GeoPoint, km: Double = 60.0, max: Int = 40) =
        OfficialWash.near(data, here, km, max, ::place)

    /**
     * 경정비 is in the data (official repair). Tyre air and oil at a 경정비 rest area, and washer fluid at any rest-area
     * shop, are what such places usually have, so they are listed as inferred until a driver confirms them.
     */
    fun place(r: OfficialWash.Row, date: String): ServicePlace {
        val shop = r.business == "경정비"
        val offers = buildMap {
            put(ServiceNeed.WASHER, Evidence.LIKELY)
            if (shop) { put(ServiceNeed.REPAIR, Evidence.OFFICIAL); put(ServiceNeed.AIR, Evidence.LIKELY); put(ServiceNeed.OIL, Evidence.LIKELY) }
        }
        return ServicePlace("rest-${r.id}", r.name, r.lat, r.lon, "${r.address} 고속도로", offers, SOURCE, null,
            listOfNotNull("고속도로 휴게소", r.address.ifBlank { null }, if (shop) "경정비" else null))
    }
}
