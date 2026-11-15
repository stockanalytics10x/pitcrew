package com.example.washguard

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * PIT SERVICE: where to fix what the pit crew found, ranked from what drivers and cars have confirmed.
 *
 * Evidence is always shown with its source, never mixed:
 *  - USER     drivers said "I could do it here" (and the car's sensor recovered afterwards when [CrewReport.carConfirmed])
 *  - OFFICIAL a public dataset says the facility exists (e.g. expressway rest area air pump)
 *  - LIKELY   inferred, e.g. a fuel station with light repair probably has an air pump
 *  - DEMO     presentation data placed around the car, not a real place
 */
enum class ServiceNeed(val label: String, val verb: String) {
    AIR("공기압 보충", "공기 넣을 수 있어요"),
    REPAIR("정비 점검", "정비받을 수 있어요"),
    OIL("오일 보충·교환", "오일 넣을 수 있어요"),
    WASHER("워셔액 구매", "워셔액 살 수 있어요"),
    WASH("세차", "세차할 수 있어요"),
    CHARGE("전기차 충전", "충전할 수 있어요"),
    FUEL("주유", "주유할 수 있어요")
}

/** A place the driver added where they are standing (kept in the car's DB). */
data class UserPlace(val id: String, val name: String, val lat: Double, val lon: Double, val needs: Set<ServiceNeed>, val atMs: Long) {
    fun asPlace() = ServicePlace(id, name, lat, lon, "", needs.associateWith { Evidence.USER }, "내가 추가한 장소")
}

enum class Evidence(val label: String, val rank: Int) {
    USER("운전자 확인", 0), OFFICIAL("공식 데이터", 1), LIKELY("추정", 2), DEMO("시연 데이터", 3)
}

data class ServicePlace(
    val id: String, val name: String, val lat: Double, val lon: Double, val address: String,
    /** What the source data says this place offers, and how sure that is. */
    val offers: Map<ServiceNeed, Evidence>,
    /** Short source note, e.g. "한국도로공사 휴게소 편의시설". */
    val source: String,
    val hours: String? = null,
    /** What the source data says about the kind of place, e.g. "셀프세차", "주유소 겸업". */
    val labels: List<String> = emptyList()
)

/** A condition of use the driver can confirm with one tap after a visit. Asked one per visit, never typed. */
enum class PlaceFact(val question: String, val yesLabel: String, val noLabel: String, val yesTag: String, val noTag: String) {
    FREE("무료였나요?", "무료였어요", "유료였어요", "무료", "유료"),
    SELF("직접 하셨나요?", "직접 했어요", "직원이 해 줬어요", "셀프", "직원 서비스"),
    ALL_DAY("24시간 쓸 수 있는 곳인가요?", "24시간이에요", "영업시간에만", "24시간", "영업시간 중")
}

/** Why a place could not do it. */
enum class FailReason(val label: String) { BROKEN("고장"), NONE("그런 시설 없음"), CLOSED("문 닫음") }

/** One driver's report: "this place could / could not do this", plus what they added in one tap. */
data class CrewReport(val placeId: String, val need: ServiceNeed, val ok: Boolean, val atMs: Long,
                      /** The car's own reading recovered after the visit (e.g. tyre pressure back to normal). */
                      val carConfirmed: Boolean = false,
                      val facts: Map<PlaceFact, Boolean> = emptyMap(),
                      val fail: FailReason? = null)

/** The one extra question after a report: a condition of use ([fact]), or why it failed ([fact] == null). */
data class FollowUp(val placeId: String, val placeName: String, val need: ServiceNeed, val atMs: Long, val fact: PlaceFact?)

data class PlaceOption(val place: ServicePlace, val distanceKm: Double?, val evidence: Evidence,
                       val yes: Int, val no: Int, val carConfirmed: Int,
                       /** Conditions drivers confirmed, e.g. ["무료", "셀프", "24시간"]. */
                       val tags: List<String> = emptyList(),
                       /** Last time someone said it worked here. */
                       val lastOkMs: Long? = null) {
    /** Short line for the card, e.g. "✓ 운전자 3명 확인 (차량 센서 2)". */
    val badge: String get() = when {
        yes > 0 -> "✓ 운전자 ${yes}명 확인" + (if (carConfirmed > 0) " · 차량 센서 ${carConfirmed}건" else "")
        evidence == Evidence.OFFICIAL -> "공식 데이터 · ${place.source}"
        evidence == Evidence.LIKELY -> "추정 · ${place.source}"
        else -> "시연 데이터"
    }
}

/** Everything known about one service at one place, for the place information page. */
data class NeedInfo(val need: ServiceNeed,
                    /** What the source data says (null = the data does not list it; only drivers reported it). */
                    val listed: Evidence?,
                    val yes: Int, val no: Int, val carConfirmed: Int,
                    val lastOkMs: Long?, val lastNoMs: Long?, val lastFail: FailReason?,
                    val tags: List<String>) {
    /** One-line judgment from the evidence, strongest first. Says "unknown" rather than guessing. */
    val verdict: String get() = when {
        lastNoMs != null && (lastOkMs == null || lastNoMs > lastOkMs) -> "최근 '안 됨' 신고" + (lastFail?.let { " · ${it.label}" } ?: "")
        carConfirmed > 0 -> "차량 센서로 확인됨 (${carConfirmed}건)"
        yes > 0 -> "운전자 확인 (${yes}명)"
        listed == Evidence.OFFICIAL -> "공식 데이터에 있음 · 운전자 확인 전"
        listed == Evidence.LIKELY -> "있을 가능성 높음 (추정) · 확인 전"
        listed == Evidence.DEMO -> "시연 데이터 · 실제 확인 전"
        else -> "정보 없음"
    }
}

/** A trip to a place for a fault; when the fault clears afterwards, the driver is asked to confirm in one tap. */
data class PendingVisit(val placeId: String, val placeName: String, val need: ServiceNeed, val faultId: String, val atMs: Long)

object PitServiceRank {
    const val MAX_KM = 30.0
    private const val REPORT_DAYS = 365L

    /** Best places for [need]: driver-confirmed first, then official, likely, demo; nearer first within each. */
    fun options(need: ServiceNeed, places: List<ServicePlace>, reports: List<CrewReport>, here: GeoPoint?, nowMs: Long, limit: Int = 3): List<PlaceOption> {
        val fresh = reports.filter { it.need == need && nowMs - it.atMs <= REPORT_DAYS * 86_400_000L }
        return places.mapNotNull { p ->
            val mine = fresh.filter { it.placeId == p.id }
            val yes = mine.count { it.ok }; val no = mine.count { !it.ok }
            val base = p.offers[need]
            if (base == null && yes == 0) return@mapNotNull null
            // The latest report wins: a recent "not here" hides the place until someone confirms it again.
            val latest = mine.maxByOrNull { it.atMs }
            if (latest != null && !latest.ok) return@mapNotNull null
            val d = here?.let { distanceKm(it, GeoPoint(0, p.lat, p.lon)) }
            if (d != null && d > MAX_KM) return@mapNotNull null
            PlaceOption(p, d, if (yes > 0) Evidence.USER else base!!, yes, no, mine.count { it.ok && it.carConfirmed }, tags(mine),
                mine.filter { it.ok }.maxOfOrNull { it.atMs })
        }.sortedWith(compareBy<PlaceOption>({ it.evidence.rank }, { -it.yes }, { it.distanceKm ?: Double.MAX_VALUE })).take(limit)
    }

    /** What is known about each service at [place]: the source data and this car's reports, newest facts first. */
    fun detail(place: ServicePlace, reports: List<CrewReport>): List<NeedInfo> = ServiceNeed.values().mapNotNull { need ->
        val mine = reports.filter { it.placeId == place.id && it.need == need }
        val listed = place.offers[need]
        if (listed == null && mine.isEmpty()) return@mapNotNull null
        val lastNo = mine.filter { !it.ok }.maxByOrNull { it.atMs }
        NeedInfo(need, listed, mine.count { it.ok }, mine.count { !it.ok }, mine.count { it.ok && it.carConfirmed },
            mine.filter { it.ok }.maxOfOrNull { it.atMs }, lastNo?.atMs, lastNo?.fail, tags(mine))
    }

    /** What drivers said about using a place: for each fact, the answer most of them gave (a tie shows nothing). */
    fun tags(reports: List<CrewReport>): List<String> = PlaceFact.values().mapNotNull { f ->
        val answers = reports.mapNotNull { it.facts[f] }
        val yes = answers.count { it }; val no = answers.size - yes
        when { yes > no -> f.yesTag; no > yes -> f.noTag; else -> null }
    }

    /** The next thing worth asking about this place for [need]: the first fact nobody has answered yet. */
    fun nextFact(placeId: String, need: ServiceNeed, reports: List<CrewReport>): PlaceFact? {
        val known = reports.filter { it.placeId == placeId && it.need == need }.flatMap { it.facts.keys }.toSet()
        return PlaceFact.values().firstOrNull { it !in known }
    }

    /** The fault from [visit] has cleared, so ask whether it was fixed there. */
    fun shouldAsk(visit: PendingVisit?, openFaultIds: Set<String>, nowMs: Long): Boolean =
        visit != null && visit.faultId !in openFaultIds && nowMs - visit.atMs in 0..(12 * 3_600_000L)

    /**
     * Presentation places laid around the car (offsets in degrees), used until real data is loaded.
     * Names say plainly that they are not real places.
     */
    fun demoPlaces(here: GeoPoint): List<ServicePlace> {
        fun at(id: String, name: String, dLat: Double, dLon: Double, offers: Map<ServiceNeed, Evidence>, source: String, hours: String? = null) =
            ServicePlace("demo-$id", name, here.lat + dLat, here.lon + dLon, "시연용 위치", offers, source, hours)
        return listOf(
            at("rest", "시연 · 휴게소 주유소", 0.060, 0.020, mapOf(ServiceNeed.AIR to Evidence.DEMO, ServiceNeed.WASHER to Evidence.DEMO), "휴게소 편의시설(시연)"),
            at("gas", "시연 · 경정비 주유소", 0.012, -0.018, mapOf(ServiceNeed.AIR to Evidence.DEMO, ServiceNeed.WASHER to Evidence.DEMO), "경정비 주유소(시연)"),
            at("tire", "시연 · 타이어 전문점", -0.020, 0.025, mapOf(ServiceNeed.AIR to Evidence.DEMO), "정비업체(시연)", "09:00–19:00"),
            at("shop", "시연 · 종합정비", 0.030, 0.040, mapOf(ServiceNeed.REPAIR to Evidence.DEMO, ServiceNeed.OIL to Evidence.DEMO, ServiceNeed.AIR to Evidence.DEMO), "정비업체(시연)", "09:00–18:00"),
            at("mart", "시연 · 대형마트 자동차용품", -0.035, -0.030, mapOf(ServiceNeed.WASHER to Evidence.DEMO), "자동차용품(시연)")
        )
    }
}

/**
 * Keeps driver reports in the car's database ([PitWallDb], table place_reports) and the pending visit in settings.
 * Reports stay on this car; they are stored in the shape a sharing server would take later, which is not built yet.
 */
class PitServiceStore(context: Context) {
    private val prefs = context.getSharedPreferences("pit_service", Context.MODE_PRIVATE)
    private val db = PitWallDb.open(context)
    var reports by mutableStateOf(load())
        private set
    var pending by mutableStateOf(loadPending())
        private set
    var status by mutableStateOf("")
    /** The one extra question waiting for a tap (kept for this run only; skipping it is fine). */
    var followUp by mutableStateOf<FollowUp?>(null)
    /** Places the driver saved (star), newest first. */
    var saved by mutableStateOf(loadSaved())
        private set
    /** Places the driver added on the spot. */
    var userPlaces by mutableStateOf(loadUserPlaces())
        private set

    /** Adds the place the car is at now, and records each chosen service as "works here" (driver, no sensor). */
    fun addUserPlace(p: UserPlace) {
        userPlaces = userPlaces + p
        runCatching {
            db?.insert(PitWallDb.USER_PLACES, null, android.content.ContentValues().apply {
                put("id", p.id); put("name", p.name); put("lat", p.lat); put("lon", p.lon); put("needs", p.needs.joinToString(",") { it.name }); put("created_at", p.atMs)
            })
        }.onFailure { android.util.Log.w("PitServiceStore", "add place failed", it) }
        p.needs.forEach { report(CrewReport(p.id, it, ok = true, atMs = p.atMs)) }
    }

    private fun loadUserPlaces(): List<UserPlace> = runCatching {
        val out = mutableListOf<UserPlace>()
        db?.rawQuery("SELECT id,name,lat,lon,needs,created_at FROM ${PitWallDb.USER_PLACES} ORDER BY created_at", null)?.use { c ->
            while (c.moveToNext()) out += UserPlace(c.getString(0), c.getString(1), c.getDouble(2), c.getDouble(3),
                c.getString(4).split(",").mapNotNull { n -> runCatching { ServiceNeed.valueOf(n) }.getOrNull() }.toSet(), c.getLong(5))
        }
        out
    }.getOrDefault(emptyList())

    init { migrateFromPrefs() }

    fun toggleSaved(placeId: String) {
        val on = placeId !in saved
        saved = if (on) listOf(placeId) + saved else saved - placeId
        runCatching {
            if (on) db?.insert(PitWallDb.SAVED, null, android.content.ContentValues().apply { put("place_id", placeId); put("saved_at", System.currentTimeMillis()) })
            else db?.delete(PitWallDb.SAVED, "place_id = ?", arrayOf(placeId))
        }.onFailure { android.util.Log.w("PitServiceStore", "save failed", it) }
    }

    private fun loadSaved(): List<String> = runCatching {
        val out = mutableListOf<String>()
        db?.rawQuery("SELECT place_id FROM ${PitWallDb.SAVED} ORDER BY saved_at DESC", null)?.use { c -> while (c.moveToNext()) out += c.getString(0) }
        out.distinct()
    }.getOrDefault(emptyList())

    /** Adds the tapped answer to the report it belongs to. */
    fun amend(f: FollowUp, fact: Pair<PlaceFact, Boolean>? = null, fail: FailReason? = null) {
        reports = reports.map { r ->
            if (r.placeId == f.placeId && r.need == f.need && r.atMs == f.atMs)
                r.copy(facts = if (fact != null) r.facts + fact else r.facts, fail = fail ?: r.fail) else r
        }
        runCatching {
            val v = android.content.ContentValues().apply {
                fact?.let { (k, yes) -> put(column(k), if (yes) 1 else 0) }
                fail?.let { put("fail_reason", it.name) }
            }
            if (v.size() > 0) db?.update(PitWallDb.REPORTS, v, "place_id = ? AND need = ? AND at_ms = ?", arrayOf(f.placeId, f.need.name, f.atMs.toString()))
        }.onFailure { android.util.Log.w("PitServiceStore", "amend failed", it) }
    }

    private fun column(f: PlaceFact) = when (f) { PlaceFact.FREE -> "free"; PlaceFact.SELF -> "self_service"; PlaceFact.ALL_DAY -> "all_day" }

    fun report(r: CrewReport) {
        reports = (reports + r).takeLast(KEEP)
        runCatching { db?.insert(PitWallDb.REPORTS, null, values(r)) }.onFailure { android.util.Log.w("PitServiceStore", "insert failed", it) }
    }

    private fun values(r: CrewReport) = android.content.ContentValues().apply {
        put("place_id", r.placeId); put("need", r.need.name); put("ok", if (r.ok) 1 else 0)
        put("car_confirmed", if (r.carConfirmed) 1 else 0); put("at_ms", r.atMs)
        r.facts.forEach { (k, yes) -> put(column(k), if (yes) 1 else 0) }
        r.fail?.let { put("fail_reason", it.name) }
    }

    fun visit(v: PendingVisit?) {
        pending = v
        prefs.edit().apply { if (v == null) remove("pending") else putString("pending", JSONObject().put("p", v.placeId).put("name", v.placeName).put("n", v.need.name).put("f", v.faultId).put("t", v.atMs).toString()) }.apply()
    }

    fun clear() { reports = emptyList(); visit(null); runCatching { db?.delete(PitWallDb.REPORTS, null, null) } }

    private fun load(): List<CrewReport> = runCatching {
        val out = mutableListOf<CrewReport>()
        db?.rawQuery("SELECT place_id,need,ok,car_confirmed,at_ms,free,self_service,all_day,fail_reason FROM ${PitWallDb.REPORTS} ORDER BY at_ms DESC LIMIT $KEEP", null)?.use { c ->
            while (c.moveToNext()) runCatching {
                val facts = listOf(PlaceFact.FREE to 5, PlaceFact.SELF to 6, PlaceFact.ALL_DAY to 7).filter { !c.isNull(it.second) }.associate { it.first to (c.getInt(it.second) != 0) }
                val fail = if (c.isNull(8)) null else runCatching { FailReason.valueOf(c.getString(8)) }.getOrNull()
                out += CrewReport(c.getString(0), ServiceNeed.valueOf(c.getString(1)), c.getInt(2) != 0, c.getLong(4), c.getInt(3) != 0, facts, fail)
            }
        }
        out.reversed()
    }.getOrDefault(emptyList())

    /** Earlier versions kept reports in the settings file: move them into the database once. */
    private fun migrateFromPrefs() {
        val text = prefs.getString("reports", null) ?: return
        runCatching {
            val a = JSONArray(text)
            val old = (0 until a.length()).map { i -> a.getJSONObject(i).let { CrewReport(it.getString("p"), ServiceNeed.valueOf(it.getString("n")), it.getBoolean("ok"), it.getLong("t"), it.optBoolean("car")) } }
                .filter { o -> reports.none { it.placeId == o.placeId && it.atMs == o.atMs } }
            old.forEach { db?.insert(PitWallDb.REPORTS, null, values(it)) }
            reports = (reports + old).sortedBy { it.atMs }.takeLast(KEEP)
        }
        if (db != null) prefs.edit().remove("reports").apply()
    }

    private companion object { const val KEEP = 5_000 }

    private fun loadPending(): PendingVisit? = runCatching {
        prefs.getString("pending", null)?.let { JSONObject(it) }?.let { PendingVisit(it.getString("p"), it.getString("name"), ServiceNeed.valueOf(it.getString("n")), it.getString("f"), it.getLong("t")) }
    }.getOrNull()
}
