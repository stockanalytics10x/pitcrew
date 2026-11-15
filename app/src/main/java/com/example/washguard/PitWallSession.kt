package com.example.washguard

import android.content.Context
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import android.os.Handler
import android.os.Looper

/**
 * Everything the Pit Wall knows and decides, kept outside the screens.
 *
 * - One place picks the input: the car's readings, or the demo scene's values (labelled 시연) while demo mode is on.
 * - The engines (PitWallEngine, FaultTriage, PitServiceRank) are called from here; screens only read the results.
 * - Values are Compose state, so screens that read them update by themselves.
 *
 * - It also reacts by itself (records, radio, stint events), so nothing depends on a screen being drawn.
 *
 * The screens still own navigation between pages.
 */
class PitWallSession(
    context: Context,
    private val vehicleState: () -> VehicleDataState,
    private val rangeState: () -> RangeState,
    private val weatherState: () -> WeatherState,
    val navi: NaviClient?,
    val energyLog: EnergyLog?,
    val demo: DemoController?,
    val health: VehicleHealthClient?,
    val boxCall: BoxCallController?,
    val driveRecords: DriveRecordStore?,
    val serviceStore: PitServiceStore = PitServiceStore(context.applicationContext),
    val powertrainSetting: PowertrainSetting = PowertrainSetting(context.applicationContext)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Drive/park from the car (CarUxRestrictions + gear). Set by whoever watches the car. */
    var realAccess by mutableStateOf(DriveAccess.UNKNOWN)

    /** Coarse clock (15 s) so time-based judgments refresh without a screen driving them. */
    var now by mutableLongStateOf(System.currentTimeMillis())
        private set

    /** This stint's events on the STINT TRACK and the arrival estimate when it began. */
    private val stint = StintRecorder()
    var trackEvents by mutableStateOf<List<TrackEvent>>(emptyList())
        private set
    var stintStartArrival by mutableStateOf<Double?>(null)
        private set
    private val spell = DriveSpellRecorder()

    /**
     * State changes made outside a screen (e.g. from a service with no UI) only reach snapshotFlow once the global
     * snapshot is applied. Compose does this for screens; this does it for the session too.
     */
    private fun sendSnapshotNotifications() {
        val main = Handler(Looper.getMainLooper())
        var pending = false
        val handle = Snapshot.registerGlobalWriteObserver {
            if (!pending) { pending = true; main.post { pending = false; Snapshot.sendApplyNotifications() } }
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { handle.dispose() }
    }

    // ── input: the car, or the demo scene ────────────────────────────────────────────────────────

    val demoOn: Boolean get() = demo?.active == true
    val demoStep: DemoStep? get() = if (demoOn) demo?.step else null

    val access: DriveAccess get() = demoStep?.let { if (it.driving) DriveAccess.RESTRICTED else DriveAccess.PARKED } ?: realAccess
    val parked: Boolean get() = access == DriveAccess.PARKED
    val driving: Boolean get() = access == DriveAccess.RESTRICTED

    val realVehicle: VehicleDataState get() = vehicleState()
    val realRange: RangeState get() = rangeState()
    val weather: WeatherState get() = weatherState()

    /**
     * EV, combustion or hybrid: what the car sent (battery level, fuel level, or both), unless SETTINGS fixes it.
     * Demo scenes follow the SETTINGS choice (EV when left on auto), so the same story can be shown for either car.
     */
    val detectedPowertrain: Powertrain get() = realVehicle.powertrain
    val powertrain: Powertrain get() =
        if (demoOn) powertrainSetting.resolve(Powertrain.EV) else powertrainSetting.resolve(detectedPowertrain)

    /** The car's state with [VehicleDataState.soc] holding the level the Pit Wall plans with (battery or fuel). */
    val vehicle: VehicleDataState get() {
        val d = demo
        val pt = powertrain
        return if (demoOn && d != null) realVehicle.copy(soc = "${d.soc}%", socStatus = "시연")
        else if (pt.usesFuel) realVehicle.copy(soc = realVehicle.fuel, socStatus = realVehicle.fuelStatus) else realVehicle
    }

    val range: RangeState get() {
        val d = demo
        return if (demoOn && d != null) RangeState(DemoScenario.rangeKm(d.soc.toDouble()), "시연") else realRange
    }

    val naviState: NaviSnapshot? get() {
        val step = demoStep
        return if (step != null) NaviSnapshot(connected = true, status = "시연 목적지", remainKm = step.remainingKm, active = true, updated = now,
            routing = true, diag = "시연 경로 ${step.remainingKm.toInt()}km")
        else navi?.state
    }

    val energySamples: List<EnergySample> get() = energyLog?.samples ?: emptyList()

    /** Speed for the moving track: the demo scene's speed while it drives; otherwise null (the screen uses the car's). */
    val demoSpeedKmh: Double? get() = demoStep?.takeIf { it.driving }?.speedKmh?.toDouble()

    // ── decisions ────────────────────────────────────────────────────────────────────────────────

    /** Conditions of a drive starting now: forecast temperature, rain now or likely today, and the clock. */
    val conditionsNow: EfficiencyModel.Conditions get() {
        val w = weather
        val rainy = if (w.forecast == null && w.days.isEmpty() && !w.nearKnown) null
            else w.nearRain != null || (w.days.firstOrNull()?.maxPop ?: 0) >= PitWallEngine.RAIN_PROBABILITY
        // A long route ahead is planned as a highway-type drive; with no route it is left unknown.
        val left = PitWallInputs.remainingKm(naviState, now)
        return EfficiencyModel.Conditions(w.nowTempC, rainy, now, left?.let { it >= EfficiencyModel.HIGHWAY_KM })
    }

    /** What this car's own drives taught, for conditions like now. */
    val learned: EfficiencyModel.Learned by derivedStateOf {
        val soc = PitWallInputs.socPercent(vehicle.soc); val km = realRange.kilometers
        val car = if (soc != null && km != null && soc > 0 && km > 0) km / soc else null
        EfficiencyModel.learn(driveRecords?.records ?: emptyList(), conditionsNow, car, powertrain)
    }

    val pitPlan: PitPlan by derivedStateOf {
        val d = demo
        if (demoOn && d != null) PitWallEngine.plan(DemoScenario.input(d.step, d.soc.toDouble(), weather, powertrain))
        else PitWallEngine.plan(PitWallInputs.of(vehicle.soc, range.kilometers, naviState, weather, now, energySamples = energySamples,
            powertrain = powertrain, learned = learned))
    }

    /** In demo mode the crew checks the scene's warning lights, not the emulator's. */
    val healthInput: HealthInput? get() {
        val step = demoStep
        return if (step != null) HealthInput(telltales = step.faults.associateWith { false }) else health?.input
    }

    val crewCheck: CrewCheck? by derivedStateOf { healthInput?.let { FaultTriage.check(it.copy(driving = driving, powertrain = powertrain)) } }

    val crewStatus: String get() = if (demoOn) "시연 경고등 · ${demoStep?.title}" else health?.status ?: ""

    // ── PIT SERVICE: places that fix what the crew found ─────────────────────────────────────────

    val carHere: GeoPoint? get() = navi?.state?.here

    /** Demo places are laid around the first known car position (moved only after a long drive). */
    var serviceAnchor by mutableStateOf<GeoPoint?>(null)
        internal set

    /** Real car washes (public data) around the anchor; loaded off the main thread. */
    var officialPlaces by mutableStateOf<List<ServicePlace>>(emptyList())
        private set
    private val appContext = context.applicationContext
    /** What the public-data load did, in words (shown in PIT FINDER and SETTINGS). */
    var officialStatus by mutableStateOf("공공데이터 불러오는 중")
        private set
    /** EV chargers near the car (한국환경공단, live state) and what the last load or refresh did. */
    var evStations by mutableStateOf<List<EvCharger.Station>>(emptyList())
        private set
    var evStatus by mutableStateOf("충전소 불러오는 중")
        private set
    private var oilAt: GeoPoint? = null
    private var oilMs = 0L
    /** Fuel stations within 5 km and today's prices (오피넷). */
    var oilStations by mutableStateOf<List<Opinet.Station>>(emptyList())
        private set
    var oilStatus by mutableStateOf("주유소 불러오는 중")
        private set

    val servicePlaces: List<ServicePlace> by derivedStateOf {
        PitServiceRank.demoPlaces(serviceAnchor ?: GeoPoint(0, 37.5665, 126.9780)) + officialPlaces + evStations.map { EvCharger.place(it) } + oilStations.map { Opinet.place(it) } +
            serviceStore.userPlaces.map { it.asPlace() }
    }

    /** Adds the place the car is at now. Returns what happened, for the screen. */
    fun addPlaceHere(name: String, needs: Set<ServiceNeed>): String {
        val here = carHere ?: return "현재 위치를 받지 못했어요. 내비가 연결된 뒤 다시 해 주세요."
        if (needs.isEmpty()) return "여기서 할 수 있는 것을 하나 이상 골라 주세요."
        val at = System.currentTimeMillis()
        val label = name.trim().ifEmpty { "내가 추가한 곳 · " + java.time.Instant.ofEpochMilli(at).atZone(KST_ZONE).format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm")) }
        serviceStore.addUserPlace(UserPlace("user-$at", label, here.lat, here.lon, needs, at))
        serviceStore.status = "$label 을(를) 추가했어요. 이 차의 장소 목록과 방문 기록에 남아요."
        return serviceStore.status
    }

    /** The driver says [need] is also available at [p]: a driver report, so the place shows up in that list too. */
    fun markAlsoHere(p: ServicePlace, need: ServiceNeed) {
        serviceStore.report(CrewReport(p.id, need, ok = true, atMs = System.currentTimeMillis()))
        serviceStore.status = "${p.name}: '${need.label}'도 된다고 기록했어요. ${need.label} 목록에도 나와요."
    }

    fun serviceOptions(need: ServiceNeed): List<PlaceOption> =
        PitServiceRank.options(need, servicePlaces, serviceStore.reports, carHere, System.currentTimeMillis())

    /** The fault from the last trip has cleared on the car's own reading: ask once whether it was fixed there. */
    val visitToConfirm: PendingVisit? get() {
        val c = crewCheck ?: return null
        if (c.spots.values.none { it != SpotStatus.UNKNOWN }) return null
        return serviceStore.pending.takeIf { PitServiceRank.shouldAsk(it, c.cards.map { card -> card.id }.toSet(), System.currentTimeMillis()) }
    }

    fun goToService(card: FaultCard, need: ServiceNeed, o: PlaceOption) {
        val n = navi
        if (n == null) { serviceStore.status = "내비가 연결되지 않아 안내를 시작하지 못했어요."; return }
        n.goTo(NaviStop(o.place.name, o.place.lat, o.place.lon, o.place.address))
        serviceStore.visit(PendingVisit(o.place.id, o.place.name, need, card.id, System.currentTimeMillis()))
        // What the navi actually answered is shown live next to this (pitServiceUi.naviStatus).
        serviceStore.status = "${o.place.name} 경로를 보냈어요. 왼쪽 지도를 보고 가세요. 해결되면 여기서 한 번만 확인해 주세요."
    }

    fun reportNotHere(need: ServiceNeed, o: PlaceOption) {
        val at = System.currentTimeMillis()
        serviceStore.report(CrewReport(o.place.id, need, ok = false, atMs = at))
        serviceStore.followUp = FollowUp(o.place.id, o.place.name, need, at, fact = null)
        serviceStore.status = "${o.place.name}: '${need.label} 안 됨'으로 기록했어요. 다른 운전자가 확인하기 전까지 목록에서 뺄게요."
    }

    /** yes = fixed there (car-confirmed), false = somewhere else, null = later. */
    fun answerVisit(yes: Boolean?) {
        val v = serviceStore.pending
        if (v != null && yes == true) {
            val at = System.currentTimeMillis()
            val next = PitServiceRank.nextFact(v.placeId, v.need, serviceStore.reports)
            serviceStore.report(CrewReport(v.placeId, v.need, ok = true, atMs = at, carConfirmed = true))
            serviceStore.followUp = next?.let { FollowUp(v.placeId, v.placeName, v.need, at, it) }
        }
        serviceStore.status = if (yes == true && v != null) "${v.placeName}을(를) '${v.need.verb}'로 확인했어요. 다음 안내에 먼저 나와요." else ""
        serviceStore.visit(null)
    }

    /** PIT FINDER guidance: no fault involved, so no "was it fixed here?" question later. */
    fun goToPlace(need: ServiceNeed, o: PlaceOption) {
        val n = navi
        if (n == null) { serviceStore.status = "내비가 연결되지 않아 안내를 시작하지 못했어요."; return }
        n.goTo(NaviStop(o.place.name, o.place.lat, o.place.lon, o.place.address))
        serviceStore.status = "${o.place.name} 경로를 보냈어요. 왼쪽 지도를 보고 가세요. 다녀오면 '정보'에서 됐는지 알려 주세요."
    }

    /** The driver says it worked here (no car sensor behind it), then one extra one-tap question. */
    fun reportDone(need: ServiceNeed, o: PlaceOption) {
        val at = System.currentTimeMillis()
        val next = PitServiceRank.nextFact(o.place.id, need, serviceStore.reports)
        serviceStore.report(CrewReport(o.place.id, need, ok = true, atMs = at))
        serviceStore.followUp = next?.let { FollowUp(o.place.id, o.place.name, need, at, it) }
        serviceStore.status = "${o.place.name}: '${need.verb}'로 기록했어요."
    }

    /** One tap on the extra question. "YES"/"NO" for a condition, a [FailReason] name for a failure, null = skip. */
    fun answerFollowUp(answer: String?) {
        val f = serviceStore.followUp ?: return
        serviceStore.followUp = null
        if (answer == null) return
        val fact = f.fact
        if (fact != null) serviceStore.amend(f, fact = fact to (answer == "YES"))
        else runCatching { FailReason.valueOf(answer) }.getOrNull()?.let { serviceStore.amend(f, fail = it) }
        serviceStore.status = "${f.placeName} 정보를 저장했어요. 다음 안내에 반영돼요."
    }

    val pitServiceUi: PitServiceUi get() = PitServiceUi(
        options = ::serviceOptions,
        onGo = ::goToService,
        onNotHere = ::reportNotHere,
        dataNote = officialStatus + " · " + evStatus + " · " + oilStatus + " · " + (if (officialPlaces.isNotEmpty()) "세차장·정비소는 공공데이터(${OfficialWash.SOURCE} · ${OfficialRepair.SOURCE} · ${OfficialRestArea.SOURCE}) · 오일·공기압·워셔액은 업종으로 추정 · 그 밖의 장소는 예시 데이터예요"
            else "장소는 예시 데이터예요") + " · 크루 리포트(운전자·차량 센서 확인)는 이 차에만 저장돼요",
        status = serviceStore.status,
        prompt = visitToConfirm,
        onPromptAnswer = ::answerVisit,
        followUp = serviceStore.followUp,
        onFollowUp = ::answerFollowUp,
        detail = { PitServiceRank.detail(it, serviceStore.reports) },
        naviStatus = navi?.state?.status ?: "내비 연결 없음",
        optionsAll = { PitServiceRank.options(it, servicePlaces, serviceStore.reports, carHere, System.currentTimeMillis(), limit = 10) },
        onGoPlace = ::goToPlace,
        onDoneHere = ::reportDone,
        history = serviceStore.reports,
        placeName = { id -> servicePlaces.firstOrNull { it.id == id }?.name },
        openNavi = { navi?.openSearch(it) },
        showNavi = { navi?.showNavi() },
        saved = serviceStore.saved,
        onToggleSaved = serviceStore::toggleSaved,
        places = servicePlaces,
        distanceTo = { p -> carHere?.let { distanceKm(it, GeoPoint(0, p.lat, p.lon)) } },
        onAlsoHere = ::markAlsoHere
    )

    // Reactions start last: Main.immediate runs the first collect inside the constructor, so every property they
    // read (pitPlan, crewCheck, serviceAnchor …) must already be initialised above.
    init {
        sendSnapshotNotifications()
        scope.launch { while (isActive) { delay(15_000L); now = System.currentTimeMillis() } }
        // The energy log learns from the real car only.
        scope.launch {
            snapshotFlow { listOf(vehicle.soc, realVehicle.distance, realRange.kilometers, demoOn) }.collect { (soc, odo, km, d) ->
                if (d != true) energyLog?.record(soc as String?, odo as String?, km as Double?)
            }
        }
        // Box call watches the plan while driving.
        scope.launch { snapshotFlow { pitPlan to driving }.collect { (plan, drv) -> boxCall?.onPlan(plan, driving = drv) } }
        // LAST DRIVE records from real driving spells.
        scope.launch {
            snapshotFlow { realAccess }.collect { a ->
                val v = realVehicle
                val c = conditionsNow
                spell.update(a, demoOn, PitWallInputs.socPercent(vehicle.soc), parseOdometer(v.distance), pitPlan.arrivalPercent.value,
                    boxCall?.history ?: emptyList(), System.currentTimeMillis(),
                    routeKm = PitWallInputs.remainingKm(navi?.state, System.currentTimeMillis()), tempC = c.tempC, rainy = c.rainy)?.let { driveRecords?.add(it) }
            }
        }
        // Stint events, the stint's starting estimate, and stop-now radio.
        scope.launch {
            snapshotFlow { listOf(driving, pitPlan.remaining.value, pitPlan.arrivalPercent.value, boxCall?.history, crewCheck) }.collect {
                val say = stint.update(driving, pitPlan.remaining.value, pitPlan.arrivalPercent.value, boxCall?.history ?: emptyList(), crewCheck, System.currentTimeMillis())
                trackEvents = stint.events; stintStartArrival = stint.startArrival
                say.forEach { boxCall?.announce(it) }
            }
        }
        // Demo PIT SERVICE places sit around the first known car position, moved only after a long drive.
        scope.launch {
            snapshotFlow { carHere }.collect { h ->
                val a = serviceAnchor
                if (h != null && (a == null || distanceKm(a, h) > 20.0)) serviceAnchor = h
            }
        }
        // Public car-wash data around the anchor (Seoul City Hall until the navi gives a position).
        scope.launch {
            snapshotFlow { serviceAnchor }.collect { a ->
                val at = a ?: GeoPoint(0, 37.5665, 126.9780)
                val (places, status) = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val errors = mutableListOf<String>()
                    fun <T> part(name: String, f: () -> List<T>): List<T> = try { f() } catch (t: Throwable) {
                        android.util.Log.e("PitstopData", "$name load failed", t); errors += "$name: ${t.javaClass.simpleName} ${t.message ?: ""}"; emptyList()
                    }
                    val w = part("세차장") { OfficialWash.near(OfficialWash.load(appContext), at) }
                    val r = part("정비소") { OfficialRepair.near(OfficialRepair.load(appContext), at) }
                    val h = part("휴게소") { OfficialRestArea.near(OfficialRestArea.load(appContext), at) }
                    android.util.Log.i("PitstopData", "near ${at.lat},${at.lon}: wash ${w.size}, repair ${r.size}, rest ${h.size}")
                    (w + r + h) to ("공공데이터 · 주변 세차장 ${w.size} · 정비소 ${r.size} · 휴게소 ${h.size}곳" +
                        (if (a == null) " (위치 대기 · 서울시청 기준)" else "") + errors.joinToString("") { " · 오류 $it" })
                }
                officialPlaces = places; officialStatus = status
            }
        }
        // EV chargers and their live state (needs EV_CHARGER_KEY in local.properties); state refreshed every 5 minutes.
        scope.launch {
            snapshotFlow { serviceAnchor }.collect { a ->
                val at = a ?: GeoPoint(0, 37.5665, 126.9780)
                val r = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    try { EvCharger.near(appContext, BuildConfig.EV_CHARGER_KEY, at) } catch (t: Throwable) {
                        android.util.Log.e("PitstopData", "EV chargers", t); EvCharger.Result(emptyList(), "충전소 오류: ${t.message ?: t.javaClass.simpleName}")
                    }
                }
                evStations = r.stations; evStatus = r.status
            }
        }
        // Fuel stations and prices around the car (needs OPINET_KEY); prices refreshed every 30 minutes.
        scope.launch {
            snapshotFlow { carHere ?: serviceAnchor }.collect { h ->
                val at = h ?: GeoPoint(0, 37.5665, 126.9780)
                val last = oilAt
                if (last != null && distanceKm(last, at) < 2.0 && System.currentTimeMillis() - oilMs < 30 * 60_000L) return@collect
                oilAt = at; oilMs = System.currentTimeMillis()
                val r = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    try { Opinet.near(BuildConfig.OPINET_KEY, at) } catch (t: Throwable) {
                        android.util.Log.e("PitstopData", "Opinet", t); Opinet.Result(emptyList(), "주유소 오류: ${t.message ?: t.javaClass.simpleName}")
                    }
                }
                oilStations = r.stations; oilStatus = r.status
            }
        }
        scope.launch {
            while (isActive) {
                delay(5 * 60_000L)
                val list = evStations
                if (list.isEmpty()) continue
                val ok = kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { EvCharger.updateStatus(BuildConfig.EV_CHARGER_KEY, list) }.isSuccess }
                evStations = list.toList()   // new list so the places are rebuilt with the new state
                val free = list.sumOf { s -> s.fast.count { it.stat == "2" } }
                evStatus = "충전소 ${list.size}곳 · 지금 쓸 수 있는 급속 ${free}대" + if (ok) "" else " · 상태 갱신 실패"
            }
        }
    }

    fun release() { scope.cancel() }
}
