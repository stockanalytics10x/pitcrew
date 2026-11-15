
package com.example.washguard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Sky = Color(0xFF326A76)
val Background = Color(0xFFF0F1EE)
val TextDark = Color(0xFF142238)

/** Keeps the redesigned screens in the dark reference UI instead of the older light pages. */
fun darkRoute(route: String): String = when (route) {
    "CARE_DETAILS" -> "STRATEGY_WASH"
    "MYCAR", "ENERGY" -> "STRATEGY"
    else -> route
}

@Composable
fun PitstopApp(
    pit: PitWallSession,
    onRefreshWeather: () -> Unit = {},
    travelClient: TravelClient? = null,
    weatherClient: WeatherClient? = null,
    onStartRecording: () -> Unit = {},
    onConnectRange: () -> Unit = {},
    driveRepository: DriveRepository? = null,
    onStartTelemetry: () -> Unit = {},
    washController: WashModeController? = null,
    permissionTick: Int = 0,
    onRequestPermission: (String) -> Unit = {}
) {
    val demoRepository = remember { DemoDriveRepository() }
    val repository = driveRepository ?: demoRepository
    val session by repository.session.collectAsState()
    // Drive/park comes from the car (DriveAccessMonitor in the runtime); the session decides what the screens show.
    val naviClient = pit.navi
    val boxCall = pit.boxCall
    val demo = pit.demo
    val energyLog = pit.energyLog
    val driveRecords = pit.driveRecords
    val demoOn = pit.demoOn
    val demoStep = pit.demoStep
    val access = pit.access
    val parked = pit.parked
    val navigation = remember(naviClient) { NaviHelperDriveBoundary(naviClient) }
    val realVehicle = pit.realVehicle
    val realRange = pit.realRange
    val vehicleData = pit.vehicle
    val range = pit.range
    val weather = pit.weather
    val naviState = pit.naviState
    val pitPlan = pit.pitPlan
    val crewCheck = pit.crewCheck
    val carHere = pit.carHere
    val pitService = pit.pitServiceUi
    val trackEvents = pit.trackEvents
    val stintStartArrival = pit.stintStartArrival
    var page by rememberSaveable {
        mutableStateOf("GARAGE")
    }
    // When a drive ends (D → P), open its report. The record is saved on the same change, so wait a moment for it.
    var wasDriving by remember { mutableStateOf(false) }
    LaunchedEffect(parked) {
        // Speed is read only while a speed recording runs, so start it with the drive and close it on P.
        if (!parked) { wasDriving = true; if (!demoOn && session.telemetry?.recording != true) onStartTelemetry() }
        else if (wasDriving) { wasDriving = false; repository.finish(access); if (!demoOn) { kotlinx.coroutines.delay(1200); page = "DRIVE_DONE" } }
    }
    // Charging is inferred from the car (port / switch / battery rising while parked); when it is confirmed, show the charging page.
    var charge by remember { mutableStateOf(ChargeSession()) }
    val chargeInputs by rememberUpdatedState(Triple(parked, vehicleData.socInt, vehicleData.chargePort to vehicleData.chargeSwitch))
    LaunchedEffect(Unit) {
        while (true) {
            val (p, soc, sig) = chargeInputs
            charge = ChargeDetector.step(charge, p, soc, sig.first, sig.second, System.currentTimeMillis())
            kotlinx.coroutines.delay(2000)
        }
    }
    var targetCalled by remember { mutableStateOf(false) }
    LaunchedEffect(charge.phase) {
        when (charge.phase) {
            ChargePhase.CHARGING -> if (page != "CHARGING") page = "CHARGING"
            ChargePhase.DONE -> boxCall?.announce("충전이 끝났어요. 이번 충전 +${charge.gained}%")
            ChargePhase.NONE -> { targetCalled = false; if (page == "CHARGING") page = "GARAGE" }
            else -> {}
        }
    }
    LaunchedEffect(charge.soc) {
        val target = chargeTarget(pitPlan).first
        if (charge.phase == ChargePhase.CHARGING && !targetCalled && (charge.soc ?: 0) >= target) {
            targetCalled = true; boxCall?.announce("목표 $target%에 도달했어요. 출발해도 좋아요")
        }
    }
    LaunchedEffect(demoOn, demo?.index) { if (demoOn && demoStep != null && demoStep.page != "DRIVE") page = demoStep.page }
    // Any old light page (saved state, older links) is shown in the dark UI instead.
    LaunchedEffect(page) { val dark = darkRoute(page); if (dark != page) page = dark }

    var vehicleType by rememberSaveable {
        mutableStateOf("전기차")
    }

    CompositionLocalProvider(LocalRoadTravel provides (travelClient?.state ?: TravelState()), LocalRoadConnect provides onStartRecording, LocalRoadFlow provides (session.telemetry ?: SpeedFlow())) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Sky,
            background = Background,
            surface = Color.White,
            onSurface = TextDark
        )
    ) {
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Background)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth().weight(1f)
                .background(Background)
        ) {
            val isWide = maxWidth >= 700.dp

            if (!parked) {
                DriveScreen(session, access, navigation.destinationLabel(),
                    onFinish = { repository.finish(access); page = "REPORT" }, onReport = { page = "REPORT" }, onNavigate = { page = it }, pitPlan = pitPlan, voiceStatus = boxCall?.voiceStatus ?: "연결 안 됨", radio = boxCall?.history ?: emptyList(), deltaArrival = StintMath.delta(stintStartArrival, pitPlan.arrivalPercent.value),
                    trackEvents = trackEvents, crewCheck = crewCheck, trackSpeedKmh = pit.demoSpeedKmh, remainMinutes = naviState?.remainMinutes,
                    tempC = if (demoOn) DemoHistory.TEMP_C else weather.nowTempC,
                    rainy = if (demoOn) DemoHistory.RAINY else (weather.nearRain != null || (weather.days.firstOrNull()?.maxPop ?: 0) >= PitWallEngine.RAIN_PROBABILITY),
                    conditionEffect = (if (demoOn) DemoHistory.learned(DemoScenario.KM_PER_PERCENT, pit.powertrain) else pit.learned).conditionEffect,
                    odometerKm = Regex("[0-9]+(\\.[0-9]+)?").find(vehicleData.distance.replace(",", ""))?.value?.toDoubleOrNull(),
                    todayTypicalKm = run { val nowMs = if (demoOn) DemoHistory.NOW_MS else System.currentTimeMillis()
                        PitForecastEngine.weekdayKm(if (demoOn) DemoHistory.records(DemoHistory.NOW_MS) else driveRecords?.records ?: emptyList(), nowMs)
                            ?.get(java.time.Instant.ofEpochMilli(nowMs).atZone(KST).dayOfWeek) })
            } else if (page in referenceRoutes) {
                ReferenceFlowScreen(page, vehicleData, range, weather, session, navigation.destinationLabel(),
                    onStartTelemetry = onStartTelemetry, onOpen = { page = darkRoute(it) }, onFinish = { repository.finish(access); page = "REPORT" },
                    onRestart = { if(session.demo) repository.restart(access) else onStartTelemetry(); page = "DRIVE" }, lastWash = travelClient?.state?.lastWash ?: 0, washController = washController, navi = naviState, demo = demoOn, onWashCompleted = { travelClient?.washed(); weatherClient?.invalidate(); weatherClient?.refresh() }, energySamples = pit.energySamples, onVoiceTest = { boxCall?.testCall() }, voiceStatus = boxCall?.voiceStatus ?: "연결 안 됨", permissionTick = permissionTick, onRequestPermission = onRequestPermission, onVoiceRestart = { boxCall?.restartVoice() }, listenStatus = boxCall?.listenStatus ?: "", onListenTest = { boxCall?.testListen() }, demoController = demo, driveRecords = driveRecords?.records ?: emptyList(), crewCheck = crewCheck, crewStatus = pit.crewStatus, pitService = pitService,
                    pitPlan = pitPlan, powertrainSetting = pit.powertrainSetting, detectedPowertrain = pit.detectedPowertrain,
                    learned = if (demoOn) DemoHistory.learned(DemoScenario.KM_PER_PERCENT, pit.powertrain) else pit.learned, health = pit.healthInput, charge = charge)
            } else if (page.startsWith("FIX_")) {
                // From a pit crew fault card: open the navi search for the place that fixes it.
                val kind = runCatching { NativeSearchKind.valueOf(page.removePrefix("FIX_")) }.getOrNull()
                val card = crewCheck?.cards?.firstOrNull { it.fix?.kind == kind }
                NativeSearchScreen(naviClient, onBack = { page = "PITSTOP" }, preferred = kind, autoOpen = true,
                    reason = card?.let { "${it.title} · ${it.fix?.note}" })
            } else if (page in setOf("NAVI_SEARCH","NAVI_WASH","NAVI_FUEL","NAVI_CHARGE","WASH")) {
                NativeSearchScreen(naviClient, onBack={page="PITSTOP"}, preferred=when(page) {
                    "NAVI_WASH","WASH" -> NativeSearchKind.WASH
                    "NAVI_FUEL" -> NativeSearchKind.FUEL
                    "NAVI_CHARGE" -> NativeSearchKind.CHARGE
                    else -> null
                })
            } else if (page == "TRAVEL" && travelClient != null && weatherClient != null) {
                TravelScreen(travelClient, weatherClient, onStartRecording, onBack = { page = "HOME" }, vehicleData = vehicleData, range = range, onConnectRange = onConnectRange)
            } else {
                // Unknown page: back to the GARAGE.
                LaunchedEffect(page) { page = "GARAGE" }
            }
        }
        if (parked && page !in referenceRoutes) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF171E23)).padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf("GARAGE" to "GARAGE", "DRIVE" to "DRIVE", "PITSTOP" to "PITSTOP").forEach { (route, label) ->
                    TextButton(onClick = { page = route }, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
                        Text(label, color = if (page == route || (route == "GARAGE" && page == "HOME")) Color(0xFFF47C46) else Color(0xFFAAB4B8))
                    }
                }
            }
        }
        }
        if (demoOn && demo != null) DemoPresenterBar(demo, Modifier.align(Alignment.TopCenter))
        boxCall?.let { bc -> BoxCallOverlay(bc.ui, onAccept = bc::accept, onDecline = bc::decline, modifier = Modifier.align(Alignment.BottomCenter), listenStatus = bc.listenStatus) }
        }
    }
    }
}




