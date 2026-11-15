package com.example.washguard

import androidx.compose.foundation.ScrollState

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val RBg = Color(0xFF050D12)
private val RPanel = Color(0xFF09151C)
private val RLine = Color(0xFF263C48)
private val RInk = Color(0xFFE6EDF1)
private val RMuted = Color(0xFF8DA4B2)
private val ROrange = Color(0xFFFF7A28)
private val RCyan = Color(0xFF39BCE9)
private val RGreen = Color(0xFF30CD86)

/** Shown in the header so the running build can be identified on the emulator. */
const val APP_BUILD_TAG = "1115-FIX"
val referenceRoutes = setOf("GARAGE", "HOME", "DRIVE", "REPORT", "PITSTOP", "STRATEGY", "STRATEGY_WASH", "SETTINGS", "FINDER", "DRIVE_REPORT", "DRIVE_DONE", "PIT_A", "PIT_B", "PIT_C", "CHARGING")

/** The reference board is rebuilt as native components. Only the vehicle is a raster asset. */
@Composable
fun ReferenceFlowScreen(page: String, vehicle: VehicleDataState, range: RangeState, weather: WeatherState,
    session: DriveSession, destination: String?, onOpen: (String) -> Unit, onFinish: () -> Unit, onRestart: () -> Unit, lastWash: Long = 0, onStartTelemetry: () -> Unit = {},
    washController: WashModeController? = null, navi: NaviSnapshot? = null, onWashCompleted: () -> Unit = {}, energySamples: List<EnergySample> = emptyList(), onVoiceTest: () -> Unit = {}, voiceStatus: String = "",
    permissionTick: Int = 0, onRequestPermission: (String) -> Unit = {}, onVoiceRestart: () -> Unit = {},
    listenStatus: String = "", onListenTest: () -> Unit = {}, demo: Boolean = false, demoController: DemoController? = null, driveRecords: List<DriveRecord> = emptyList(),
    crewCheck: CrewCheck? = null, crewStatus: String = "", pitService: PitServiceUi? = null,
    pitPlan: PitPlan? = null, powertrainSetting: PowertrainSetting? = null, detectedPowertrain: Powertrain = Powertrain.UNKNOWN,
    learned: EfficiencyModel.Learned = EfficiencyModel.NONE, health: HealthInput? = null, charge: ChargeSession = ChargeSession()) {
    BoxWithConstraints(Modifier.fillMaxSize().background(RBg)) {
        val density = LocalDensity.current
        val scale = (maxWidth.value / 1280f).coerceIn(.7f, 2.5f)
        CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
            var radio by remember { mutableStateOf(false) }
            if (radio) AlertDialog(onDismissRequest = { radio = false }, title = { Text("CREW RADIO") },
                text = { Text("주행 중 피트인이 필요해지면 Pit Wall이 음성으로 Box 콜을 보냅니다. 차량 상태는 차에서 받은 값만 보여 주고, 충전·주유·세차 판단 근거는 PITSTOP에서 확인할 수 있어요.") },
                confirmButton = { TextButton(onClick = { radio = false }) { Text("확인") } },
                dismissButton = { TextButton(onClick = { onVoiceTest(); radio = false }) { Text("무전 테스트 ▷ · $voiceStatus") } })
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(78.dp).fillMaxHeight().background(Color(0xFF081219)).border(1.dp, RLine), horizontalAlignment = Alignment.CenterHorizontally) {
                    RText("PF", 17, RMuted, Modifier.padding(vertical = 18.dp))
                    Spacer(Modifier.weight(1f))
                    listOf("GARAGE" to "GARAGE", "DRIVE" to "DRIVE", "PITSTOP" to "PITSTOP", "FINDER" to "FINDER").forEach { (route, title) ->
                        val selected = if (page in setOf("REPORT","DRIVE_REPORT","DRIVE_DONE")) route == "DRIVE" else if (page in setOf("STRATEGY","STRATEGY_WASH","PIT_A","PIT_B","PIT_C","CHARGING")) route == "PITSTOP" else if (page == "HOME") route == "GARAGE" else page == route
                        Column(Modifier.fillMaxWidth().height(112.dp).background(if (selected) Brush.horizontalGradient(listOf(ROrange.copy(alpha=.12f),Color.Transparent)) else Brush.horizontalGradient(listOf(Color.Transparent,Color.Transparent)))
                            .clickable { onOpen(route) }.padding(vertical=20.dp), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            RGlyph(route, if(selected) ROrange else RMuted, Modifier.size(30.dp))
                            RText(title, 10, if(selected) ROrange else RMuted)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick={onOpen("SETTINGS")}, modifier=Modifier.size(64.dp)) { RText("⚙",24,RMuted) }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().height(52.dp).background(Color(0xFF081219)).padding(horizontal=24.dp), verticalAlignment=Alignment.CenterVertically) {
                        RText("PITCREW", 16, RInk)
                        RText(" / ",16,RMuted,Modifier.padding(horizontal=14.dp))
                        RText(if(page=="STRATEGY") "PIT STRATEGY" else if(page=="HOME") "GARAGE" else if(page=="DRIVE_REPORT") "DRIVE REPORT" else if(page=="DRIVE_DONE") "DRIVE REPORT" else if(page=="PIT_A") "PITSTOP · A" else if(page=="PIT_B") "PITSTOP · B" else if(page=="PIT_C") "PITSTOP · C" else if(page=="CHARGING") "PITSTOP · 충전" else page,16,ROrange)
                        Spacer(Modifier.weight(1f))
                        if (demo) RText("시연",13,ROrange,Modifier.border(1.dp,ROrange,RoundedCornerShape(4.dp)).padding(horizontal=10.dp,vertical=2.dp))
                        RText(LocalTime.now(KST).format(DateTimeFormatter.ofPattern("HH:mm")),14,RInk,Modifier.padding(start=20.dp))
                    }
                    HorizontalDivider(color=RLine)
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val compact = maxWidth < 950.dp
                        Canvas(Modifier.fillMaxSize()) {
                            val step=18.dp.toPx()
                            var x=0f
                            while(x<size.width) { var y=0f; while(y<size.height) { drawCircle(RCyan.copy(alpha=.06f),.7.dp.toPx(),Offset(x,y)); y+=step }; x+=step }
                        }
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min=maxHeight).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                            when(page) {
                                "GARAGE","HOME" -> RGarageGrid(vehicle,range,weather,compact,onOpen,lastWash,navi,energySamples,demo,driveRecords,crewCheck,pitPlan,learned,voiceStatus,health) { radio=true }
                                // Before the drive: pit wall data from this car's drives (the old live-flow page stays for replay sessions).
                                "DRIVE_DONE" -> DriveDone(driveRecords, System.currentTimeMillis(), pitPlan?.powertrain ?: Powertrain.EV, onOpen, session.telemetry, pitPlan?.kmPerPercent?.value)
                                "DRIVE" -> if (session.demo) RDrive(session,destination,compact,onOpen,onFinish,onRestart,onStartTelemetry) else {
                                    // No drives saved yet: show the built-in example history, marked as such (screen only;
                                    // it never feeds the engine or the DB).
                                    // A drive counts once it covered some distance; on the emulator the odometer often stays at 0,
                                    // which leaves records with no distance that can't fill any chart.
                                    val usable = !needsExample(driveRecords)
                                    val example = demo || !usable
                                    val nowMs = if (example) DemoHistory.NOW_MS else System.currentTimeMillis()
                                    DriveData(if (example) DemoHistory.records(DemoHistory.NOW_MS) else driveRecords,
                                        if (example && !demo) DemoHistory.learned(DemoScenario.KM_PER_PERCENT, pitPlan?.powertrain ?: Powertrain.EV) else learned,
                                        nowMs, example, pitPlan?.powertrain ?: Powertrain.EV,
                                        if (example) DemoHistory.TEMP_C else weather.nowTempC,
                                        if (example) DemoHistory.RAINY else (weather.nearRain != null || (weather.days.firstOrNull()?.maxPop ?: 0) >= PitWallEngine.RAIN_PROBABILITY), onOpen)
                                }
                                // Past drives and what the engine learned from them: opened after a drive ends or from GARAGE.
                                "DRIVE_REPORT" -> {
                                    val now = if (demo) DemoHistory.NOW_MS else System.currentTimeMillis()
                                    DriveReport(if (demo) DemoHistory.records(DemoHistory.NOW_MS) else driveRecords, learned, now, demo, pitPlan?.powertrain ?: Powertrain.EV, compact, onOpen)
                                }
                                "FINDER" -> if (pitService != null) PitFinder(pitService, pitPlan?.powertrain ?: Powertrain.EV, compact, onOpen) else RText("장소 정보를 불러오지 못했어요",20)
                                "REPORT" -> RReport(session,compact,onOpen) { radio=true }
                                "PITSTOP","PIT_A","PIT_B","PIT_C" -> {
                                    val context = LocalContext.current
                                    val wc = washController ?: remember { WashModeController(context).apply { initialize() } }
                                    if (washController == null) DisposableEffect(wc) { onDispose { wc.release() } }
                                    val now = rememberNow()
                                    val plan = pitPlan ?: remember(vehicle.soc, range.kilometers, navi, weather, now, energySamples) {
                                        PitWallEngine.plan(PitWallInputs.of(vehicle.soc, range.kilometers, navi, weather, now, demo = demo, energySamples = energySamples)) }
                                    val usable = !needsExample(driveRecords)
                                    val example = demo || !usable
                                    val recs = if (example) DemoHistory.records(DemoHistory.NOW_MS) else driveRecords
                                    val forecast = remember(plan, weather, now, energySamples, lastWash) {
                                        PitForecastEngine.forecast(PitForecastInput(now, plan.soc.value, plan.range.value, plan.remaining.value, energySamples, weather.days, weather.nearRain,
                                            lastWash, weather.historyWetHours, powertrain = plan.powertrain, records = if (demo) emptyList() else driveRecords,
                                            learnedKmPerPercent = plan.kmPerPercent.value.takeIf { plan.learnedNote != null }, rainEffect = plan.learnedRainEffect)) }
                                    val check = crewCheck ?: FaultTriage.check(HealthInput(telltales = null))
                                    val shownNow = if (example) DemoHistory.NOW_MS else now
                                    // PITSTOP is the overview; PIT_A / PIT_B / PIT_C are the energy, maintenance and wash pages.
                                    if (page == "PITSTOP") PitstopOverview(plan, forecast, recs, example, shownNow, check, lastWash, pitService, onOpen)
                                    else PitstopTabs(plan, forecast, recs, example, shownNow, check, wc.state, { wc.executeWashMode() }, { wc.checkAllStatus() },
                                        lastWash, onWashCompleted, pitService, onOpen, initialTab = when (page) { "PIT_A" -> 0; "PIT_B" -> 1; else -> 2 })
                                }
                                // Opened by itself when the car looks like it is charging (ChargeDetector).
                                "CHARGING" -> ChargingScreen(charge, pitPlan, crewCheck, rememberNow(), onOpen)
                                "STRATEGY" -> RStrategy(vehicle,range,weather,lastWash,compact,onOpen,onWashCompleted = onWashCompleted,plan = pitPlan,energySamples = if (demo || needsExample(driveRecords)) DemoHistory.samples() else energySamples,records = if (demo || needsExample(driveRecords)) DemoHistory.records(DemoHistory.NOW_MS) else driveRecords,example = demo || needsExample(driveRecords))
                                "STRATEGY_WASH" -> RStrategy(vehicle,range,weather,lastWash,compact,onOpen,initialKind = "WASH",onWashCompleted = onWashCompleted,plan = pitPlan,energySamples = if (demo || needsExample(driveRecords)) DemoHistory.samples() else energySamples,records = if (demo || needsExample(driveRecords)) DemoHistory.records(DemoHistory.NOW_MS) else driveRecords,example = demo || needsExample(driveRecords))
                                "SETTINGS" -> { RHeading("00","SETTINGS","권한 · 무전 음성 · 연결"); SettingsPanel(permissionTick, voiceStatus, onRequestPermission, onVoiceTest, onVoiceRestart, { onOpen("TRAVEL") }, Modifier.fillMaxWidth(), listenStatus, onListenTest, demoController, powertrainSetting, detectedPowertrain)
                                    RText("차량 점검 연결 · " + crewStatus.ifBlank { "연결 전" },12,RMuted)
                                    pitService?.let { RText(it.dataNote.substringBefore(" · 세차장·정비소는").substringBefore(" · 장소는"),12,RMuted) }
                                    RText(PitWallDb.summary(LocalContext.current).let { driveRecords.size; it },12,RMuted)
                                    RText("앱 버전 · $APP_BUILD_TAG",12,RMuted) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RText(text:String,size:Int=14,color:Color=RInk,modifier:Modifier=Modifier) {
    // Nothing smaller than 12 in the car: small labels are raised, not shrunk further.
    val sz = size.coerceAtLeast(12)
    Text(text,color=color,fontSize=sz.sp,lineHeight=(sz*1.3).sp,fontWeight=if(sz>=26) FontWeight.Bold else FontWeight.Medium,modifier=modifier)
}
@Composable
private fun RHeading(number:String,title:String,subtitle:String) {
    Column(verticalArrangement=Arrangement.spacedBy(1.dp)) { RText(number,23,ROrange); RText(title,40); RText(subtitle,17,RMuted) }
}
@Composable
private fun RBox(modifier:Modifier=Modifier,accent:Boolean=false,content:@Composable ColumnScope.()->Unit) {
    Column(modifier.clip(RoundedCornerShape(5.dp)).background(Brush.linearGradient(listOf(RPanel,Color(0xFF050E14)))).border(1.dp,if(accent) ROrange.copy(alpha=.65f) else RLine,RoundedCornerShape(5.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
}
@Composable
private fun RAction(title:String,modifier:Modifier=Modifier,filled:Boolean=false,onClick:()->Unit) {
    if(filled) Button(onClick=onClick,modifier=modifier.heightIn(min=52.dp),shape=RoundedCornerShape(4.dp),colors=ButtonDefaults.buttonColors(containerColor=ROrange,contentColor=RBg)) { Text(title,fontSize=14.sp) }
    else TextButton(onClick=onClick,modifier=modifier.heightIn(min=52.dp)) { RText(title,14,ROrange) }
}
@Composable
private fun RSplit(compact:Boolean,leftWeight:Float=1f,equalHeight:Boolean=false,left:@Composable (Modifier)->Unit,right:@Composable (Modifier)->Unit) {
    if(compact) { left(Modifier.fillMaxWidth()); right(Modifier.fillMaxWidth()) }
    else { val h=if(equalHeight) Modifier.fillMaxHeight() else Modifier
        Row(Modifier.fillMaxWidth().then(if(equalHeight) Modifier.height(IntrinsicSize.Min) else Modifier),horizontalArrangement=Arrangement.spacedBy(16.dp)) { left(Modifier.weight(leftWeight).then(h)); right(Modifier.weight(1f).then(h)) } }
}

@Composable
private fun RDrive(session:DriveSession,destination:String?,compact:Boolean,onOpen:(String)->Unit,onFinish:()->Unit,onRestart:()->Unit,onStartTelemetry:()->Unit) {
    if(!session.demo) {
        RHeading("02","DRIVE","TOURING · LIVE FLOW")
        RLiveTelemetry(session.telemetry ?: SpeedFlow(),false,onStartTelemetry,onFinish) {onOpen("REPORT")}
        return
    }

    RSplit(compact,.65f,left={m ->
        Column(m,verticalArrangement=Arrangement.spacedBy(15.dp)) {
            RHeading("02","DRIVE","LIVE TELEMETRY")
            RText("YOUR DRIVE IN REAL TIME",14,RMuted)
            RText(if(session.demo) "● DEMO SESSION / 예시 운행" else "SDK TELEMETRY",13,ROrange)
            RText(destination ?: "현대차 내비 목적지 연결 대기",14,RMuted)
            RText("주행 기록",12,RMuted)
            RText(if(session.completed) "종료됨" else "데모 준비",28)
            RAction(if(session.completed) "새 Demo 운행" else "Demo 운행 종료 →",filled=true) {if(session.completed) onRestart() else onFinish()}
            RAction("REPORT") {onOpen("REPORT")}
        }
    },right={m -> RBox(m) {
        RText("SECTOR FLOW   /   DEMO 경로 도식",12,RMuted)
        RRoute(Modifier.fillMaxWidth().height(238.dp),true)
        RText("CURRENT SEGMENT   /   B-3  ·  도심 구간 예시",14,ROrange)
    }})
    RBox(Modifier.fillMaxWidth()) {
        RText("DRIVING BALANCE   /   DEMO 지표",13,RMuted)
        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            listOf(Triple("ACCELERATION",session.smoothness,RGreen),Triple("BRAKING",session.smoothness,RCyan),Triple("CORNERING",session.similarity,ROrange),Triple("ENERGY USE",session.efficiency,RGreen)).forEach { (name,score,color) ->
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) { RText(name,11,RMuted); RText(if(score==null) "미연결" else "DEMO",16,color); RBars(score,Modifier.fillMaxWidth().height(32.dp),color); RText(score?.toString() ?: "—",23) }
            }
            RGraph(session.flow,Modifier.weight(1.5f).height(94.dp),ROrange)
        }
    }
    RText("주행 중 상세 정보는 잠깁니다. 데모 운행은 실제 위치 기록을 시작하거나 중단하지 않습니다.",11,RMuted)
}

@Composable
private fun RReport(session:DriveSession,compact:Boolean,onOpen:(String)->Unit,onRadio:()->Unit) {
    if(!session.demo && session.completed) {
        RHeading("03","DRIVE REPORT","OBSERVED SPEED FLOW")
        RSpeedReport(session.telemetry ?: SpeedFlow())
        RAction("주행 기록 · 학습 결과 보기") {onOpen("DRIVE_REPORT")}
        RAction("DRIVE로 돌아가기") {onOpen("DRIVE")}
        return
    }

    RHeading("03","DRIVE REPORT","STINT SUMMARY")
    if(!session.completed) { RBox(Modifier.fillMaxWidth()) { RText("운행 종료 후 확인할 수 있어요.",24); RAction("LIVE TELEMETRY로 돌아가기") {onOpen("DRIVE")} }; return }
    RText(if(session.demo) "DEMO · 이번 주행과 동일 경로 과거 기록을 가정한 비교입니다." else "주행 기록 · 실제 과거 경로 비교 저장소 연결 대기",15,RMuted)
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        listOf("SIMILARITY" to session.similarity,"SMOOTHNESS" to session.smoothness,"EFFICIENCY" to session.efficiency).forEach { (name,value) -> RBox(Modifier.weight(1f)) {RText(name,12,RMuted); RText(value?.toString() ?: "—",32,if(name=="EFFICIENCY") RGreen else RInk)} }
    }
    RSplit(compact,left={m -> RBox(m) {
        RText("ROUTE ANALYSIS",15)
        RGraph(session.flow,Modifier.fillMaxWidth().height(135.dp),RGreen)
        session.segments.forEach { RText("${it.name}    ${it.similarity} / ${it.smoothness} / ${it.efficiency}",16,RMuted) }
    }},right={m -> RBox(m) {
        RText("KEY INSIGHTS",15)
        RText("동일 경로에서 구간별 변화를 살펴보세요.",20,RCyan)
        RText("점수·경로는 예시입니다. 실제 급제동 횟수·연비·주행 시간은 수신 전 표시하지 않습니다.",16,RMuted)
        RAction("CREW DEBRIEF  ▷",filled=true,onClick=onRadio)
    }})
}

// ── shared look of the GARAGE mock-up (cards, round marks, pills), used by PIT STRATEGY as well
private val GGreen = Color(0xFF2FD07F)
private val GRed = Color(0xFFFF5A4E)
private val GCardShape = RoundedCornerShape(16.dp)
private val GRow = Color(0xFF13222B)
@Composable private fun GMark(kind: Char, color: Color, size: Int = 26) = Box(Modifier.size(size.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
    Text(kind.toString(), color = RBg, fontSize = (size * .62f).sp, fontWeight = FontWeight.Black)
}
@Composable private fun GPill(label: String, color: Color = RInk, filled: Boolean = false, onClick: () -> Unit) =
    Text("$label  ›", color = if (filled) RBg else color, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(24.dp))
        .then(if (filled) Modifier.background(color) else Modifier.border(1.dp, color.copy(alpha = .45f), RoundedCornerShape(24.dp)))
        .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 11.dp))
@Composable private fun GCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.clip(GCardShape).background(Brush.verticalGradient(listOf(Color(0xFF0E1A21), Color(0xFF09131A)))).border(1.dp, RLine, GCardShape).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
@Composable private fun GHead(title: String, accent: String?, pill: Pair<String, () -> Unit>? = null) = Row(verticalAlignment = Alignment.CenterVertically) {
    Text(title, color = RInk, fontSize = 23.sp, fontWeight = FontWeight.Bold)
    accent?.let { Text("  ·  $it", color = ROrange, fontSize = 23.sp, fontWeight = FontWeight.Bold) }
    Spacer(Modifier.weight(1f))
    pill?.let { (l, go) -> GPill(l, onClick = go) }
}
@Composable private fun GLine(label: String, value: String, color: Color = RInk, mark: Char? = null) =
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(GRow).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = RMuted, fontSize = 19.sp, modifier = Modifier.width(150.dp))
        mark?.let { GMark(it, color, 22); Spacer(Modifier.width(10.dp)) }
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
    }

@Composable
private fun RStrategy(vehicle:VehicleDataState,range:RangeState,weather:WeatherState,lastWash:Long,compact:Boolean,onOpen:(String)->Unit,initialKind:String?=null,onWashCompleted:()->Unit={},plan:PitPlan?=null,energySamples:List<EnergySample> = emptyList(),records:List<DriveRecord> = emptyList(),example:Boolean = false) {
    // One energy tab for this car (충전 for an EV, 주유 for a fuel car) next to 세차. Same look as GARAGE.
    val pt = plan?.powertrain ?: Powertrain.EV
    val soc=vehicle.soc.removeSuffix("%").toIntOrNull()?.takeIf {it in 0..100}
    var kind by remember(initialKind) {mutableStateOf(if (initialKind == "WASH") "WASH" else if (initialKind == null && pitTiming(soc,weather,lastWash).kind == "WASH") "WASH" else "ENERGY")}
    var confirmWash by remember { mutableStateOf(false) }
    val routed = plan != null && plan.verdict != PitVerdict.NO_DESTINATION && plan.verdict != PitVerdict.UNKNOWN
    val timing = if (kind == "WASH") pitWashTiming(weather,lastWash)
        else if (routed) PitTiming("ENERGY", raceBriefingHeadline(plan!!), plan.reason)
        else pitChargeTiming(soc).let { t -> if (pt.usesFuel) PitTiming("ENERGY", t.title.replace("충전","주유"), t.reason.replace("충전","주유")) else t }
    val washPlan = PitWallEngine.washVerdict(weather)
    // Wash timing for the next four days (same forecast as PITSTOP), instead of the old one-line advice.
    val stratNow = rememberNow()
    val fullFc = remember(weather, lastWash, stratNow, plan, energySamples, records) {
        PitForecastEngine.forecast(PitForecastInput(stratNow, plan?.soc?.value, plan?.range?.value, plan?.remaining?.value, energySamples, weather.days, weather.nearRain,
            lastWash, weather.historyWetHours, powertrain = pt, records = records, learnedKmPerPercent = plan?.kmPerPercent?.value?.takeIf { plan.learnedNote != null }, rainEffect = plan?.learnedRainEffect))
    }
    val washFc = fullFc.wash
    val chargeFc = fullFc.charge
    fun dayName4(i: Int) = when (i) { 0 -> "오늘"; 1 -> "내일"; 2 -> "모레"
        else -> washFc.days.getOrNull(i)?.date?.dayOfWeek?.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN)?.let { "${it}요일" } ?: "${i}일 뒤" }
    val washTitle = washFc.recommendedIndex?.let { i -> if (i == 0) "오늘 세차하기 좋아요" else "세차는 ${dayName4(i)}이 좋아요" }
        ?: if (washFc.days.isEmpty()) "예보를 기다리는 중이에요" else "4일 안에는 맑은 날이 없어요"
    // Without a route the band says when to charge, from the four-day outlook.
    val energyTitle = when (chargeFc.status) {
        ChargeStatus.NOW -> "오늘 ${pt.refuel}하는 게 좋아요"
        ChargeStatus.BY_DATE -> chargeFc.chargeByIndex?.let { "${dayName4(it)}까지 ${pt.refuel}하면 돼요" } ?: "평소대로 타면 여유 있어요"
        ChargeStatus.LATER -> "4일 안에는 ${pt.refuel}이 필요 없어요"
        else -> null
    }
    if(confirmWash) AlertDialog(onDismissRequest={confirmWash=false},title={Text("세차 완료 기록")},
        text={Text("오늘 세차를 마친 것으로 기록할까요? 다음 세차 판단과 강수 비교의 기준이 돼요.")},
        confirmButton={TextButton(onClick={confirmWash=false;onWashCompleted()}){Text("기록")}},
        dismissButton={TextButton(onClick={confirmWash=false}){Text("취소")}})

    // ── title | tabs
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("06", color = ROrange, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("PIT STRATEGY", color = RInk, fontSize = 50.sp, fontWeight = FontWeight.Black, lineHeight = 54.sp)
            Text("WHEN · WHERE  —  언제 들르고, 어디로 갈지", color = RMuted, fontSize = 18.sp)
        }
        Row(Modifier.clip(RoundedCornerShape(28.dp)).background(GRow).border(1.dp, RLine, RoundedCornerShape(28.dp)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("ENERGY" to pt.refuel, "WASH" to "세차").forEach { (id, label) ->
                val sel = kind == id
                Text(label, color = if (sel) RBg else RInk, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(24.dp))
                    .background(if (sel) ROrange else Color.Transparent).clickable { kind = id }.padding(horizontal = 30.dp, vertical = 12.dp))
            }
        }
    }

    // ── verdict band
    val (tag, bandColor, mark) = if (kind == "WASH") when (washPlan.verdict) {
        WashVerdict.OK -> Triple("WASH OK", GGreen, '✓'); WashVerdict.HOLD -> Triple("WASH HOLD", ROrange, '!'); else -> Triple("WASH ?", RMuted, '?')
    } else when (plan?.verdict) {
        PitVerdict.FINISH_OK -> Triple("NO STOP", GGreen, '✓')
        PitVerdict.PIT_ADVISED -> Triple("BOX PLAN", ROrange, '!')
        PitVerdict.PIT_REQUIRED -> Triple("BOX THIS STINT", GRed, '!')
        else -> Triple(if (routed) "STANDBY" else "NO ROUTE", RMuted, '·')
    }
    Row(Modifier.fillMaxWidth().clip(GCardShape).background(bandColor.copy(alpha = .1f)).border(1.5.dp, bandColor.copy(alpha = .8f), GCardShape)
        .padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        GMark(mark, bandColor, 46); Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(tag, color = bandColor, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(if (kind == "WASH") washTitle else if (!routed && energyTitle != null) energyTitle else timing.title, color = RInk, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 2)
            Text(if (kind == "WASH") washFc.reason.ifBlank { washPlan.reason } else if (!routed && energyTitle != null) "평소 주행량으로 매일 아침 잔량을 계산했어요" else timing.reason, color = RInk.copy(alpha = .85f), fontSize = 19.sp, maxLines = 2)
        }
    }

    // ── WHEN | WHERE: same height on both tabs, so switching does not move the page
    val boxH = 380.dp
    RSplit(compact, 1.15f, equalHeight = true, left = { m ->
        GCard(m.height(boxH).verticalScroll(remember(kind) { ScrollState(0) })) {
            GHead("01 언제", "WHEN")
            if (kind == "ENERGY") {
                // four days: expected charge each morning if the car is driven as usual; the day to charge is framed
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    (0 until 4).forEach { i ->
                        val v = chargeFc.morningSoc.getOrNull(i)
                        val c = when { v == null -> RMuted; v < 20 -> GRed; v < 30 -> ROrange; else -> GGreen }
                        val by = chargeFc.chargeByIndex == i
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (by) ROrange.copy(alpha = .16f) else GRow)
                            .border(if (by) 2.dp else 1.dp, if (by) ROrange else RLine, RoundedCornerShape(12.dp)).padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(dayName4(i) + if (i == 0) " 지금" else " 아침", color = RInk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(v?.let { if (it < 0) "부족" else "%.0f%%".format(it) } ?: "—", color = c, fontSize = 24.sp, fontWeight = FontWeight.Black)
                            Text(if (by) "${pt.refuel}" else " ", color = ROrange, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                // how it was judged
                val dk = chargeFc.dailyKm.value; val du = chargeFc.dailyUsePercent.value; val kp = chargeFc.kmPerPercent.value
                GLine("하루 평균 주행", dk?.let { "%.0f km · %s".format(it, if (example) "예시 기록" else chargeFc.dailyKm.label) } ?: "기록이 쌓이면 계산해요")
                if (!example) PitForecastEngine.dailyNote(chargeFc)?.let { Text("ⓘ $it", color = ROrange, fontSize = 15.sp) }
                GLine("하루 ${pt.energy} 사용", if (du != null && kp != null) "%.1f%% (1%%당 %.1f km)".format(du, kp) else "—")
                Text(chargeFc.reason, color = RMuted, fontSize = 15.sp, maxLines = 2)
                plan?.arrivalPercent?.value?.let { a ->
                    GLine("도착 예상", "%.0f%%".format(a) + (plan.arrivalSpread?.let { " ±%.0f".format(it) } ?: ""),
                        if (plan.needsPit) bandColor else GGreen, if (plan.needsPit) '!' else '✓')
                }
                if (routed && plan!!.needsPit) {
                    plan.socAtPitPercent.value?.let { GLine("들를 때 잔량", "%.0f%% 남았을 때".format(it), RCyan) }
                    plan.chargeTargetPercent.value?.let { GLine("${pt.refuel} 목표", "%.0f%%까지".format(it), ROrange) }
                }
                plan?.rangeNote?.let { Text("ⓘ $it", color = RMuted, fontSize = 15.sp) }
            } else {
                // four days, the recommended one framed in orange
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    (0 until 4).forEach { i ->
                        val d = washFc.days.getOrNull(i)
                        val (c, l) = when (d?.rating) { WashRating.GOOD -> GGreen to "좋음"; WashRating.OK -> RCyan to "보통"; WashRating.BAD -> ROrange to "비"; else -> RMuted to "—" }
                        val rec = washFc.recommendedIndex == i
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (rec) ROrange.copy(alpha = .16f) else GRow)
                            .border(if (rec) 2.dp else 1.dp, if (rec) ROrange else RLine, RoundedCornerShape(12.dp)).padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(dayName4(i), color = RInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(l, color = c, fontSize = 22.sp, fontWeight = FontWeight.Black)
                            Text(d?.pop?.let { "비 $it%" } ?: "—", color = RMuted, fontSize = 14.sp)
                            if (rec) Text("추천", color = ROrange, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                GLine("세차 필요", washFc.needText.ifBlank { "—" })
                GLine("마지막 세차", washRecordLabel(lastWash))
                GPill("세차 완료 기록", GGreen, filled = true) { confirmWash = true }
            }
        }
    }, right = { m ->
        GCard(m.height(boxH)) {
            GHead("02 어디서", "WHERE")
            if (kind == "WASH") {
                Text("세차장 찾기", color = RInk, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text("비 소식이 없는 날, 가까운 세차장을 찾아요. 세차 중에는 PITSTOP의 세차 모드가 창문과 공조를 준비해요.", color = RInk.copy(alpha = .85f), fontSize = 19.sp)
            } else {
                Text("${pt.place} 찾기", color = RInk, fontSize = 28.sp, fontWeight = FontWeight.Black)
                val at = plan?.socAtPitPercent?.value; val target = plan?.chargeTargetPercent?.value
                when {
                    routed && plan!!.needsPit -> {
                        Text("이번 주행: " + listOfNotNull(at?.let { "${it.toInt()}% 남았을 때" }, target?.let { "${it.toInt()}%까지 ${pt.refuel}" }).joinToString(" → ").ifEmpty { "중간에 한 번 ${pt.refuel}" },
                            color = RCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("달리는 중에는 Box 콜이 먼저 불러 경유지로 넣어 드려요.", color = RMuted, fontSize = 17.sp)
                    }
                    routed -> Text("이번 주행은 ${pt.refuel} 없이 도착할 수 있어요.", color = GGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    else -> Text("목적지를 내비에 넣으면 들를 지점과 ${pt.refuel}량을 계산해요.", color = RMuted, fontSize = 18.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            GPill(if (kind == "WASH") "기본 내비에서 세차장 찾기" else "기본 내비에서 ${pt.place} 찾기", ROrange, filled = true) {
                onOpen(if (kind == "WASH") "NAVI_WASH" else pt.searchRoute)
            }
            GPill("PIT FINDER에서 장소 정보 보기", RCyan) { onOpen("FINDER") }
            Text("가격·우회 시간 비교는 공공데이터를 연결한 뒤 추가할 예정이에요.", color = RMuted, fontSize = 14.sp)
        }
    })
    GPill("‹ PITSTOP으로", RMuted) { onOpen("PITSTOP") }
}
@Composable
private fun RGraph(values:List<Float>,modifier:Modifier,color:Color,comparison:Boolean=false) {
    Canvas(modifier) {
        repeat(5) {i -> drawLine(RLine.copy(alpha=.6f),Offset(0f,size.height*i/4),Offset(size.width,size.height*i/4)) }
        repeat(9) {i -> drawLine(RLine.copy(alpha=.3f),Offset(size.width*i/8,0f),Offset(size.width*i/8,size.height)) }
        fun plot(data:List<Float>,ink:Color) { if(data.size<2)return; val path=Path();data.forEachIndexed {i,v -> val p=Offset(size.width*i/(data.size-1),size.height*(1-v.coerceIn(0f,1f)));if(i==0)path.moveTo(p.x,p.y)else path.lineTo(p.x,p.y);drawCircle(ink,2.dp.toPx(),p)};drawPath(path,ink,style=Stroke(1.5.dp.toPx())) }
        if(comparison)plot(values.mapIndexed {i,v -> (v+(if(i%3==0) -.12f else .08f)).coerceIn(0f,1f)},RCyan)
        plot(values,color)
    }
}
@Composable
private fun RBars(score:Int?,modifier:Modifier,color:Color) { Canvas(modifier) {repeat(20) {i ->drawRect(if(score!=null && i<score/5)color else RLine,Offset(size.width*i/20,0f),Size(size.width/28,size.height))}} }

/** Deliberately schematic: no fabricated geographic map or navigation guidance. */
@Composable
private fun RRoute(modifier:Modifier,loop:Boolean) {
    Canvas(modifier) {
        repeat(12) {i ->drawLine(RLine.copy(alpha=.23f),Offset(size.width*i/11,0f),Offset(size.width*(i+2)/11,size.height))}
        val points=if(loop) listOf(.12f to .65f,.18f to .30f,.36f to .24f,.42f to .12f,.70f to .15f,.84f to .35f,.76f to .62f,.59f to .70f,.40f to .88f,.21f to .85f,.12f to .65f)
            else listOf(.05f to .8f,.20f to .58f,.34f to .66f,.48f to .40f,.63f to .43f,.78f to .32f,.94f to .12f)
        points.zipWithNext().forEachIndexed {i,(a,b) -> val start=Offset(a.first*size.width,a.second*size.height);val end=Offset(b.first*size.width,b.second*size.height);drawLine(if(loop && i<5)RCyan else ROrange,start,end,2.dp.toPx());drawCircle(RInk,3.dp.toPx(),start)}
        val a=points.last();drawCircle(ROrange,8.dp.toPx(),Offset(a.first*size.width,a.second*size.height))
    }
}
@Composable
private fun RGlyph(kind:String,color:Color,modifier:Modifier) { Canvas(modifier) {
    val w=size.width;val h=size.height;val stroke=2.dp.toPx()
    when(kind) {
        "GARAGE" -> {val p=Path();p.moveTo(w*.1f,h*.45f);p.lineTo(w*.5f,h*.1f);p.lineTo(w*.9f,h*.45f);p.moveTo(w*.23f,h*.38f);p.lineTo(w*.23f,h*.9f);p.lineTo(w*.77f,h*.9f);p.lineTo(w*.77f,h*.38f);drawPath(p,color,style=Stroke(stroke));drawRect(color,Offset(w*.43f,h*.62f),Size(w*.15f,h*.28f),style=Stroke(stroke))}
        "DRIVE" -> repeat(5){i ->val height=listOf(.25f,.6f,.9f,.5f,.25f)[i]*h;drawLine(color,Offset(w*(i+1)/6,(h-height)/2),Offset(w*(i+1)/6,(h+height)/2),stroke)}
        "ENERGY" -> {drawRoundRect(color,Offset(w*.22f,h*.15f),Size(w*.55f,h*.75f),androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),style=Stroke(stroke));drawLine(color,Offset(w*.4f,h*.05f),Offset(w*.6f,h*.05f),stroke);drawLine(color,Offset(w*.5f,h*.4f),Offset(w*.5f,h*.7f),stroke);drawLine(color,Offset(w*.35f,h*.55f),Offset(w*.65f,h*.55f),stroke)}
        "FUEL" -> { // fuel pump: body, gauge window, hose and nozzle
            drawRoundRect(color,Offset(w*.16f,h*.12f),Size(w*.44f,h*.76f),androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),style=Stroke(stroke))
            drawRect(color,Offset(w*.26f,h*.22f),Size(w*.24f,h*.2f),style=Stroke(stroke))
            drawLine(color,Offset(w*.08f,h*.9f),Offset(w*.68f,h*.9f),stroke)
            val hose=Path();hose.moveTo(w*.6f,h*.36f);hose.lineTo(w*.76f,h*.36f);hose.lineTo(w*.86f,h*.48f);hose.lineTo(w*.86f,h*.74f)
            hose.quadraticBezierTo(w*.86f,h*.82f,w*.79f,h*.82f);hose.quadraticBezierTo(w*.72f,h*.82f,w*.72f,h*.74f);hose.lineTo(w*.72f,h*.58f);hose.lineTo(w*.6f,h*.58f)
            drawPath(hose,color,style=Stroke(stroke))
        }
        "FINDER" -> { drawCircle(color,w*.26f,Offset(w*.42f,h*.42f),style=Stroke(stroke)); drawLine(color,Offset(w*.61f,h*.61f),Offset(w*.88f,h*.88f),stroke*1.4f) }
        "PITSTOP" -> {drawLine(color,Offset(w*.2f,h*.85f),Offset(w*.7f,h*.3f),stroke*2);drawArc(color,10f,270f,false,Offset(w*.48f,h*.05f),Size(w*.4f,h*.4f),style=Stroke(stroke*1.5f));drawCircle(color,w*.08f,Offset(w*.19f,h*.85f),style=Stroke(stroke))}
        "WEATHER" -> {drawCircle(color,w*.25f,Offset(w*.5f,h*.35f),style=Stroke(stroke));repeat(3){i ->drawLine(color,Offset(w*(i+1)/4,h*.7f),Offset(w*(i+1)/4-w*.08f,h*.9f),stroke)}}
        else -> {val p=Path();p.moveTo(w*.5f,h*.08f);p.lineTo(w*.85f,h*.28f);p.lineTo(w*.75f,h*.7f);p.lineTo(w*.5f,h*.92f);p.lineTo(w*.25f,h*.7f);p.lineTo(w*.15f,h*.28f);p.close();drawPath(p,color,style=Stroke(stroke))}
    }
} }

@Preview(widthDp=1280,heightDp=720,name="Reference / Garage")
@Composable
private fun ReferenceGaragePreview() {
    MaterialTheme { ReferenceFlowScreen("GARAGE",VehicleDataState(),RangeState(),WeatherState(),DemoDriveRepository().session.value,null,{},{},{}) }
}



@Composable
internal fun RLiveTelemetry(t:SpeedFlow,restricted:Boolean,onStart:()->Unit,onFinish:()->Unit,onReport:()->Unit) {
    val a=t.acceleration
    val forward=t.trace.map {it.coerceAtLeast(0f)}.maxOrNull()
    val slowing=t.trace.map {(-it).coerceAtLeast(0f)}.maxOrNull()
    if(restricted) {
        RBox(Modifier.fillMaxWidth(),accent=true) {
            RText("LIVE TOURING / ${t.status}",12,ROrange)
            RText(t.feedback,25)
            RText("속도 ${t.speedKmh?.let {"%.0f km/h".format(it)}?:"—"}   ·   가속·감속 ${a?.let {"%+.2f m/s²".format(it)}?:"—"}",18,RCyan)
            RText("상세 흐름과 리포트는 주차 후 확인",12,RMuted)
            SeoulRoadView(Modifier.fillMaxWidth().height(240.dp),restricted=true)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow=maxWidth<760.dp
        RSplit(narrow,.62f,left={m -> Column(m,verticalArrangement=Arrangement.spacedBy(14.dp)) {
            RText("YOUR DRIVE IN REAL TIME",12,RMuted)
            RText("주행의 흐름을 읽다",24)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {RText("CURRENT SPEED",10,RMuted);RText(t.speedKmh?.let {"%.0f".format(it)}?:"—",32);RText("km/h · 차량 수신",10,RMuted)}
                Column(Modifier.weight(1f)) {RText("LONGITUDINAL",10,RMuted);RText(a?.let {"%+.2f".format(it)}?:"—",28,ROrange);RText("m/s² · 추정",10,RMuted)}
            }
            RText(if(t.recording) "● RECORDING" else "○ STANDBY",11,if(t.recording) RGreen else RMuted)
            RText(t.status,11,RMuted)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(!t.recording) RAction("기록 시작",filled=true,onClick=onStart)
                else RAction("종료 · 리포트",filled=true,onClick=onFinish)
                if(!t.recording && t.samples>0) RAction("REPORT ›",onClick=onReport)
            }
        }},right={m -> RBox(m) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                RText("ROAD FLOW",13)
                Spacer(Modifier.weight(1f));RText("${"%.0f".format(t.observedSeconds)} s",18,RCyan)
            }
            RText("서울 오프라인 도로 · 현재 위치와 지나온 길",10,RMuted)
            SeoulRoadView(Modifier.fillMaxWidth().height(238.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                RText("━ 주변 도로",10,RMuted);RText("━ 지나온 길",10,RCyan);RText("▲ 현재 위치",10,ROrange)
            }
        }})
    }
    RBox(Modifier.fillMaxWidth()) {
        RText("DRIVING BALANCE / 수집 구간의 변화",12,RMuted)
        Row(horizontalArrangement=Arrangement.spacedBy(18.dp)) {
            RFlowGauge("ACCELERATION",forward,ROrange,Modifier.weight(1f))
            RFlowGauge("DECELERATION",slowing,RCyan,Modifier.weight(1f))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                RText("SIGNAL",10,RMuted);RText(if(t.speedKmh!=null) "RECEIVING" else "WAITING",15,if(t.speedKmh!=null)RGreen else RMuted)
                RText("${t.samples} samples",14);RText("수신 공백 ${t.gaps}회",10,RMuted)
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                RText("OBSERVED TIME",10,RMuted);RText("%.0f s".format(t.observedSeconds),23)
                RText("연속 수집 구간",10,RMuted)
            }
        }
    }
    RBox(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
            RGlyph("DRIVE",ROrange,Modifier.size(30.dp))
            Column(Modifier.weight(1f)) {RText("CREW RADIO / TEXT BRIEFING",10,ROrange);RText(t.feedback,17)}
        }
    }
    RText("속도는 차에서 받은 값이고, 가속·감속은 속도로 계산한 추정값이에요. 운전 점수를 매기지 않아요.",10,RMuted)
}

@Composable
private fun RFlowGauge(title:String,value:Float?,color:Color,modifier:Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        RText(title,10,RMuted)
        RText(value?.let {if(it<.4f) "LOW CHANGE" else "RECENT PEAK"}?:"NO DATA",14,color)
        RBars(value?.let {((it/4f).coerceIn(0f,1f)*100).toInt()},Modifier.fillMaxWidth().height(20.dp),color)
        RText(value?.let {"%.2f m/s²".format(it)}?:"—",14)
    }
}

@Composable
private fun RTouringGraph(values:List<Float>,modifier:Modifier) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            repeat(5) {i ->drawLine(RLine.copy(alpha=.55f),Offset(0f,size.height*i/4),Offset(size.width,size.height*i/4),1f)}
            repeat(11) {i ->drawLine(RLine.copy(alpha=.28f),Offset(size.width*i/10,0f),Offset(size.width*i/10,size.height),1f)}
            drawLine(RMuted.copy(alpha=.5f),Offset(0f,size.height/2),Offset(size.width,size.height/2),1f)
            if(values.size>=2) {
                fun point(i:Int)=Offset(size.width*i/(values.size-1),size.height*(.5f-values[i].coerceIn(-4f,4f)/8f))
                values.indices.drop(1).forEach {i ->
                    val color=when {values[i]>.4f->ROrange;values[i]<-.4f->RCyan;else->RGreen}
                    drawLine(color,point(i-1),point(i),2.dp.toPx())
                    if(i%4==0 || i==values.lastIndex)drawCircle(color,2.dp.toPx(),point(i))
                }
            }
        }
        if(values.size<2) Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally) {
            RText("WAITING FOR TELEMETRY",12,RMuted);RText("연속 속도 신호를 기다립니다",11,RMuted)
        }
        RText("+4",9,RMuted,Modifier.align(Alignment.TopEnd))
        RText("0",9,RMuted,Modifier.align(Alignment.CenterEnd))
        RText("−4 m/s²",9,RMuted,Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun RSpeedReport(t:SpeedFlow) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        RText("STINT SUMMARY / 수집한 주행의 흐름",13,RMuted)
        RText("RECORDED / ${t.samples} SIGNALS",11,RCyan)
    }
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        listOf(Triple("OBSERVED TIME","%.0f s".format(t.observedSeconds),RInk),
            Triple("AVG. SPEED",t.averageKmh?.let {"%.1f km/h".format(it)}?:"—",RInk),
            Triple("ACCEL. CHANGE",if(t.observedSeconds>0) "${t.accelerationEvents} 구간" else "—",ROrange),
            Triple("DECEL. CHANGE",if(t.observedSeconds>0) "${t.decelerationEvents} 구간" else "—",RCyan)
        ).forEach {(label,value,color)->RBox(Modifier.weight(1f)) {RText(label,10,RMuted);RText(value,25,color)}}
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        RSplit(maxWidth<760.dp,1.15f,left={m -> RBox(m) {
            RText("FLOW ANALYSIS",14)
            RText("속도에서 계산한 앞뒤 가속·감속",11,RMuted)
            RTouringGraph(t.trace,Modifier.fillMaxWidth().height(180.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                RText("━ 가속",10,ROrange);RText("━ 일정",10,RGreen);RText("━ 감속",10,RCyan)
            }
            RText("최근 120개 값 기준",10,RMuted)
        }},right={m -> Column(m,verticalArrangement=Arrangement.spacedBy(12.dp)) {
            RBox(Modifier.fillMaxWidth()) {
                RText("KEY INSIGHTS",14)
                RInsight("DRIVE",if(t.observedSeconds>0) "가속 변화 ${t.accelerationEvents}구간 · 감속 변화 ${t.decelerationEvents}구간" else "연속 신호 부족 · 흐름 평가 대기",ROrange)
                RInsight("ENERGY","전비·에너지 효율은 아직 평가하지 않습니다.",RMuted)
                RInsight("WEATHER","수신 공백 ${t.gaps}회 · 공백과 후진은 분석에서 제외",RCyan)
            }
            RBox(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    RGlyph("DRIVE",ROrange,Modifier.size(28.dp))
                    Column(Modifier.weight(1f)) {
                        RText("CREW DEBRIEF",10,ROrange)
                        RText(if(t.observedSeconds<=0) "주행을 기록하면 구간별 흐름을 복기할 수 있어요." else if(t.accelerationEvents+t.decelerationEvents==0) "수집 구간에서 설정 기준을 넘는 큰 속도 변화는 없었어요." else "변화가 컸던 구간을 교통 상황과 함께 돌아보세요.",14)
                    }
                }
            }
        }})
    }
    RText("+2.0 / −2.5 m/s² 이상이 1초 넘게 이어진 구간만 셌어요. 위험 운전 판정이 아니에요.",10,RMuted)
}

@Composable
private fun RInsight(icon:String,message:String,color:Color) {
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        RGlyph(icon,color,Modifier.size(24.dp));RText(message,13,RInk,Modifier.weight(1f))
    }
}


// ───────────────────────────── GARAGE · F1 pre-race garage ─────────────────────────────
// Before the start an F1 garage checks: the race strategy (stops), the energy load to the flag, the track
// conditions, every car system, the radio, and what the earlier sessions taught. Same here, one screen:
//   hero    GRID READY / BOX PLAN / HOLD / CHECK: one verdict from Pit Wall (this drive) and Pit Crew (the car)
//   STRATEGY  Pit Wall: stops, arrival ±spread, charge target, next charge day
//   SYSTEMS   Pit Crew: tyres, under the hood, warning lights, doors (energy warnings belong to Pit Wall)
//   TRACK     today's conditions and what they do to efficiency (learned), wash timing
//   RADIO·DATA  voice check, prediction accuracy from past drives, link to the drive report

private data class GridCall(val tag: String, val title: String, val sub: String, val color: Color, val go: Pair<String, String>?)

private fun dayName(i: Int, d: java.time.LocalDate?): String = when (i) {
    0 -> "오늘"; 1 -> "내일"; 2 -> "모레"
    else -> d?.dayOfWeek?.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN)?.let { "${it}요일" } ?: "${i}일 뒤"
}

@Composable
private fun RGarageGrid(vehicle:VehicleDataState,range:RangeState,weather:WeatherState,compact:Boolean,onOpen:(String)->Unit,lastWash:Long,navi:NaviSnapshot?,
                        energySamples:List<EnergySample>,demo:Boolean,driveRecords:List<DriveRecord>,crewCheck:CrewCheck?,pitPlan:PitPlan?,
                        learned:EfficiencyModel.Learned,voiceStatus:String,health:HealthInput?,onRadio:()->Unit) {
    val now = rememberNow()
    val localPlan = remember(vehicle.soc, range.kilometers, navi, weather, now, energySamples) {
        PitWallEngine.plan(PitWallInputs.of(vehicle.soc, range.kilometers, navi, weather, now, demo = demo, energySamples = energySamples))
    }
    val plan = pitPlan ?: localPlan
    val pt = plan.powertrain
    val forecast = remember(plan, weather, now, energySamples, lastWash) {
        PitForecastEngine.forecast(PitForecastInput(now, plan.soc.value, plan.range.value, plan.remaining.value,
            energySamples, weather.days, weather.nearRain, lastWash, weather.historyWetHours, powertrain = pt,
            records = if (demo) emptyList() else driveRecords, learnedKmPerPercent = plan.kmPerPercent.value.takeIf { plan.learnedNote != null }, rainEffect = plan.learnedRainEffect))
    }
    fun pct(v: Double?) = v?.let { "%.0f%%".format(it) } ?: "—"
    val spread = plan.arrivalSpread?.let { " ±%.0f".format(it) } ?: ""
    // Car faults that are not about energy (energy is Pit Wall's call).
    val faults = crewCheck?.cards?.filter { it.level != FaultLevel.CHARGE }?.sortedBy { it.level.rank } ?: emptyList()
    val worst = faults.firstOrNull()

    // ── one call for the hero: car first when it must not go, then the stint, then smaller car items
    val call = when {
        worst != null && worst.level == FaultLevel.STOP_NOW -> GridCall("HOLD", "출발하지 마세요", "${worst.title} · ${worst.now}", Color(0xFFFF4D4D), "PITSTOP" to "조치 보기")
        worst != null && worst.level == FaultLevel.SERVICE -> GridCall("HOLD", "정비 점검 먼저", "${worst.title} · ${worst.drive}", Color(0xFFFF4D4D), "FINDER" to "정비소 찾기")
        plan.verdict == PitVerdict.PIT_REQUIRED -> GridCall("BOX THIS STINT", if (plan.needsSecondStop) "가는 길에 ${pt.refuel} 2번" else "가는 길에 꼭 ${pt.refuel}",
            "도착 예상 ${pct(plan.arrivalPercent.value)}$spread · ${plan.chargeTargetPercent.value?.let { "%.0f%%까지".format(it) } ?: ""} ${pt.refuel}".trim(), Color(0xFFFF4D4D), "FINDER" to "${pt.place} 찾기")
        worst != null && worst.level == FaultLevel.SELF_FIX -> GridCall("CHECK", "${worst.title} 확인", worst.now, ROrange, "PITSTOP" to "조치 보기")
        plan.verdict == PitVerdict.PIT_ADVISED -> GridCall("BOX PLAN", "가는 길에 ${pt.refuel} 1번", "도착 예상 ${pct(plan.arrivalPercent.value)}$spread · 여유 있게 가려면 1스톱", ROrange, "FINDER" to "${pt.place} 찾기")
        plan.verdict == PitVerdict.FINISH_OK -> GridCall("GRID READY", "출발 준비 완료", "논스톱 가능 · 도착 예상 ${pct(plan.arrivalPercent.value)}$spread", RGreen, null)
        plan.verdict == PitVerdict.NO_DESTINATION -> GridCall("GRID READY", "출발 준비 완료",
            "목적지 없음 · 지금 ${pt.energy}로 약 ${plan.range.value?.let { "%.0f km".format(it) } ?: "—"}", RGreen, null)
        else -> GridCall("STANDBY", "차량 신호 확인 중", plan.reason, RMuted, null)
    }

    // Look follows the GARAGE mock-up (title left, car centre, figures right, a verdict band, cards with icon rows
    // and round status marks); the content is ours: Pit Wall = this drive, Pit Crew = the car.
    val green = Color(0xFF2FD07F); val red = Color(0xFFFF5A4E)
    val cardShape = RoundedCornerShape(16.dp)
    val cardBg = Brush.verticalGradient(listOf(Color(0xFF0E1A21), Color(0xFF09131A)))
    @Composable fun Mark(kind: Char, color: Color, size: Int = 26) = Box(Modifier.size(size.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(kind.toString(), color = RBg, fontSize = (size * .62f).sp, fontWeight = FontWeight.Black)
    }
    @Composable fun Pill(label: String, color: Color = RInk, onClick: () -> Unit) =
        Text("$label  ›", color = color, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clip(RoundedCornerShape(22.dp))
            .border(1.dp, color.copy(alpha = .45f), RoundedCornerShape(22.dp)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 9.dp))
    @Composable fun CardHead(icon: String?, title: String, accent: String?, pill: Pair<String, () -> Unit>?) = Row(verticalAlignment = Alignment.CenterVertically) {
        icon?.let { RGlyph(it, RInk, Modifier.size(26.dp)); Spacer(Modifier.width(12.dp)) }
        Text(title, color = RInk, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        accent?.let { Text("  ·  $it", color = ROrange, fontSize = 23.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.weight(1f))
        pill?.let { (l, go) -> Pill(l, onClick = go) }
    }
    @Composable fun StatusRow(icon: @Composable () -> Unit, label: String, value: String, color: Color, mark: Char) =
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF13222B)).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(14.dp))
            Text(label, color = RInk, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(118.dp))
            Mark(mark, color, 24); Spacer(Modifier.width(10.dp))
            Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    @Composable fun Drop(c: Color) = Canvas(Modifier.size(24.dp)) {
        val p = androidx.compose.ui.graphics.Path(); val w = size.width; val h = size.height
        p.moveTo(w * .5f, h * .05f); p.cubicTo(w * .9f, h * .5f, w * .92f, h * .95f, w * .5f, h * .95f); p.cubicTo(w * .08f, h * .95f, w * .1f, h * .5f, w * .5f, h * .05f)
        drawPath(p, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.2.dp.toPx()))
    }

    // ── Crew rows (shared by the hero and the card)
    // Six rows. A reading from the car when there is one (tyre pressure, fluid levels); otherwise "경고 없음" when the
    // warning lights were received and none is on; "미수신" when nothing came in. Never "정상" from no data.
    data class CrewRow(val label: String, val bad: FaultCard?, val badText: String?, val okText: String?)
    val tires = listOf(CrewSpot.TIRE_FL, CrewSpot.TIRE_FR, CrewSpot.TIRE_RL, CrewSpot.TIRE_RR)
    val lightsKnown = crewCheck != null && crewCheck.spots[CrewSpot.DASH] != SpotStatus.UNKNOWN
    fun level(v: String?, ok: String) = when (v) { "NORMAL", "HIGH" -> ok; null, "ERROR" -> if (lightsKnown) "경고 없음" else null; else -> null }
    fun lowText(v: String?) = when (v) { "LOW" -> "부족"; "CRITICALLY_LOW" -> "매우 부족"; else -> null }
    fun hoodFault(word: String) = faults.firstOrNull { it.spot == CrewSpot.HOOD && it.title.contains(word) }
    val psi = tires.map { health?.tirePressure?.get(it) }
    val unit = psi.filterNotNull().average().let { if (it.isNaN()) "" else if (it in 15.0..80.0) " psi" else if (it in 150.0..450.0) " kPa" else "" }
    val crewRows = listOf(
        CrewRow("타이어", faults.firstOrNull { it.spot in tires }, null,
            if (psi.all { it != null }) psi.joinToString(" · ") { "%.0f".format(it) } + unit
            else if (crewCheck != null && tires.all { crewCheck.spots[it] != SpotStatus.UNKNOWN }) "경고 없음" else null),
        CrewRow("엔진오일", hoodFault("오일"), lowText(health?.engineOil), level(health?.engineOil, "정상 레벨")),
        CrewRow("브레이크액", hoodFault("브레이크"), lowText(health?.brakeFluid), level(health?.brakeFluid, "정상 레벨")),
        CrewRow("워셔액", hoodFault("워셔"), if (health?.washerLow == true) "부족" else null,
            if (health?.washerLow == false) "충분" else if (lightsKnown) "경고 없음" else null),
        CrewRow("경고등", faults.firstOrNull { it.spot == CrewSpot.DASH }, null, if (lightsKnown) "켜진 것 없음" else null),
        CrewRow("문 · 트렁크", faults.firstOrNull { it.spot == CrewSpot.BODY }, null, if (crewCheck?.spots?.get(CrewSpot.BODY)?.let { it != SpotStatus.UNKNOWN } == true) "닫힘" else null))
    val crewIssues = crewRows.count { it.bad != null || it.badText != null }
    val crewOk = crewRows.count { it.bad == null && it.badText == null && it.okText != null }

    // ── hero: title | car | figures
    Row(Modifier.fillMaxWidth().height(182.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(.95f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("01", color = ROrange, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("GARAGE", color = RInk, fontSize = 50.sp, fontWeight = FontWeight.Black, lineHeight = 54.sp)
            Text("READY FOR YOUR NEXT DRIVE", color = RMuted, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text("출발 전에 이번 주행과\n차 상태를 한 번에 확인하세요.", color = RInk, fontSize = 18.sp, lineHeight = 26.sp)
        }
        Box(Modifier.weight(1.35f).fillMaxHeight()) {
            val (carImage, carDesc) = if (pt.usesFuel) R.drawable.garage_sedan_w to "그랜저 후면 콘셉트 이미지" else R.drawable.garage_ioniq5_w to "아이오닉 5 후면 콘셉트 이미지"
            Image(painterResource(carImage), carDesc, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to RBg, .18f to Color.Transparent, .82f to Color.Transparent, 1f to RBg)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.7f to Color.Transparent, 1f to RBg)))
        }
        Row(Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(1.dp).fillMaxHeight(.8f).background(RLine))
            Column(Modifier.padding(start = 20.dp).clickable { onOpen("PITSTOP") }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) { RGlyph(if (pt.usesFuel) "FUEL" else "ENERGY", RInk, Modifier.size(20.dp)); Text("  ${pt.energy} 잔량", color = RInk, fontSize = 17.sp) }
                        Text(pct(plan.soc.value), color = RInk, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        Box(Modifier.width(96.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(RLine)) {
                            Box(Modifier.fillMaxWidth(((plan.soc.value ?: 0.0) / 100).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(ROrange))
                        }
                    }
                    Column {
                        Text("주행 가능 거리", color = RInk, fontSize = 17.sp)
                        Text(plan.range.value?.let { "%.0f km".format(it) } ?: "—", color = RInk, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(RLine))
                Text("차량 상태", color = RInk, fontSize = 17.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (m, c, t) = when {
                        crewIssues > 0 -> Triple('!', red, "점검 ${crewIssues}건")
                        crewOk == crewRows.size -> Triple('✓', green, "이상 없음")
                        else -> Triple('?', RMuted, "확인 중")
                    }
                    Mark(m, c, 28); Text("  $t", color = c, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // ── verdict band
    val bandColor = call.color
    Row(Modifier.fillMaxWidth().clip(cardShape).background(bandColor.copy(alpha = .1f)).border(1.5.dp, bandColor.copy(alpha = .8f), cardShape)
        .padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Mark(when (call.tag) { "GRID READY" -> '✓'; "STANDBY" -> '?'; else -> '!' }, bandColor, 46)
        Spacer(Modifier.width(18.dp))
        Column {
            Text(call.tag, color = bandColor, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(call.title, color = RInk, fontSize = 32.sp, fontWeight = FontWeight.Black)
        }
        Box(Modifier.padding(horizontal = 20.dp).width(1.dp).height(40.dp).background(RLine))
        Text(call.sub, color = RInk, fontSize = 20.sp, modifier = Modifier.weight(1f), maxLines = 2)
        Pill(call.go?.second ?: "CREW CHECK 상태", bandColor) { onOpen(call.go?.first ?: "PITSTOP") }
    }

    // ── row: briefing (Pit Wall) | Pit Crew
    val energyLine = when (plan.verdict) {
        PitVerdict.FINISH_OK -> Triple("충분해요", green, '✓')
        PitVerdict.PIT_ADVISED -> Triple("1번 들르면 좋아요", ROrange, '!')
        PitVerdict.PIT_REQUIRED -> Triple(if (plan.needsSecondStop) "2번 들러야 해요" else "꼭 들러야 해요", red, '!')
        PitVerdict.NO_DESTINATION -> Triple("목적지 없음", RMuted, '·')
        PitVerdict.UNKNOWN -> Triple("신호 확인 중", RMuted, '?')
    }
    val careLine = when {
        faults.isNotEmpty() -> Triple(faults.first().title, levelColor(faults.first().level), '!')
        crewCheck != null && crewOk == crewRows.size -> Triple("이상 없어요", green, '✓')
        else -> Triple("확인 중", RMuted, '?')
    }
    val w = forecast.wash
    val washLine = when {
        plan.wash.verdict == WashVerdict.OK -> Triple("오늘 해도 좋아요", green, '✓')
        w.recommendedIndex != null && w.recommendedIndex > 0 -> Triple("${dayName(w.recommendedIndex, w.days.getOrNull(w.recommendedIndex)?.date)}이 더 좋아요", ROrange, '!')
        plan.wash.verdict == WashVerdict.HOLD -> Triple("오늘은 미뤄요", ROrange, '!')
        else -> Triple("예보 대기", RMuted, '?')
    }
    RSplit(compact, 1.25f, equalHeight = true, left = { m ->
        Column(m.clip(cardShape).background(cardBg).border(1.dp, RLine, cardShape).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHead("GARAGE", "출발 브리핑", "PIT WALL", "판단 근거 보기" to { onOpen("STRATEGY") })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(.8f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF13222B)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("다음 주행", color = RMuted, fontSize = 17.sp)
                    val km = plan.remaining.value
                    Text(if (km != null) "목적지까지" else if (navi?.connected == true) "목적지 없음" else "내비 대기 중", color = RInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (km != null) Text("%.0f km".format(km), color = RInk, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    else {
                        // No destination: what this car usually drives on this weekday (learned from the odometer).
                        val nowMs = if (demo) DemoHistory.NOW_MS else now
                        val pattern = remember(driveRecords.size, demo) { PitForecastEngine.weekdayKm(if (demo) DemoHistory.records(DemoHistory.NOW_MS) else driveRecords, nowMs) }
                        val day = java.time.Instant.ofEpochMilli(nowMs).atZone(KST).dayOfWeek
                        pattern?.get(day)?.let { Text("${day.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN)}요일 평균 %.0f km".format(it), color = RInk, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                        plan.range.value?.let { Text("지금 약 %.0f km 갈 수 있어요".format(it), color = RMuted, fontSize = 17.sp) }
                    }
                    val c = forecast.charge
                    when (c.status) {
                        ChargeStatus.NOW -> "다음 ${pt.refuel} · 오늘"
                        ChargeStatus.BY_DATE -> c.chargeByIndex?.let { "다음 ${pt.refuel} · ${dayName(it, c.chargeByDate)}까지" }
                        ChargeStatus.LATER -> "다음 ${pt.refuel} · 4일 뒤 이후"
                        else -> null
                    }?.let { Text(it, color = RCyan, fontSize = 17.sp) }
                }
                Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusRow({ RGlyph(if (pt.usesFuel) "FUEL" else "ENERGY", RInk, Modifier.size(24.dp)) }, "${pt.refuel}", energyLine.first, energyLine.second, energyLine.third)
                    StatusRow({ RGlyph("PITSTOP", RInk, Modifier.size(24.dp)) }, "정비·점검", careLine.first, careLine.second, careLine.third)
                    StatusRow({ Drop(RInk) }, "세차", washLine.first, washLine.second, washLine.third)
                }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, RLine, RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("ⓘ  ", color = RMuted, fontSize = 18.sp)
                if (plan.arrivalPercent.value != null) {
                    Text("도착 시 예상 ${pt.energy} ", color = RInk, fontSize = 19.sp)
                    Text(pct(plan.arrivalPercent.value) + spread, color = RInk, fontSize = 21.sp, fontWeight = FontWeight.Black)
                    Text(if (plan.needsPit) "   ${plan.chargeTargetPercent.value?.let { "%.0f%%".format(it) } ?: "—"}까지 ${pt.refuel} 권장" else "   (권장 20% 이상)", color = RMuted, fontSize = 17.sp)
                } else Text("목적지를 정하면 도착 잔량을 계산해요", color = RMuted, fontSize = 18.sp)
            }
            plan.rangeNote?.let { Text("ⓘ $it", color = RMuted, fontSize = 15.sp) }
        }
    }, right = { m ->
        Column(m.clip(cardShape).background(cardBg).border(1.dp, RLine, cardShape).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHead(null, "CREW CHECK · 차량 점검", null, "전체 보기" to { onOpen("PITSTOP") })
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (crewCheck != null) CarFaultView(crewCheck, null, { onOpen("PITSTOP") }, Modifier.width(96.dp).height(196.dp))
                else Image(painterResource(R.drawable.pitstop_vehicle_top_w), "차량 상단 이미지", Modifier.width(96.dp).height(196.dp), contentScale = ContentScale.Fit)
                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF13222B)).padding(horizontal = 14.dp, vertical = 4.dp)) {
                    crewRows.forEachIndexed { i, r ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(RLine.copy(alpha = .6f)))
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.label, color = RInk, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            val (mk, c, t) = when {
                                r.bad != null -> Triple('!', levelColor(r.bad.level), r.badText ?: r.bad.title)
                                r.badText != null -> Triple('!', ROrange, r.badText)
                                r.okText != null -> Triple('✓', green, r.okText)
                                else -> Triple('?', RMuted, "미수신")
                            }
                            Mark(mk, c, 20); Text("  $t", color = if (mk == '!') c else RInk, fontSize = 17.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    })

    // ── row: track (conditions + learning) | radio · data
    val temp = if (demo) DemoHistory.TEMP_C else weather.nowTempC
    val rainy = if (demo) DemoHistory.RAINY else (weather.nearRain != null || (weather.days.firstOrNull()?.maxPop ?: 0) >= PitWallEngine.RAIN_PROBABILITY)
    val t = java.time.Instant.ofEpochMilli(if (demo) DemoHistory.NOW_MS else now).atZone(KST)
    val records = if (demo) DemoHistory.records(DemoHistory.NOW_MS) else driveRecords
    val acc = remember(records.size) { EfficiencyModel.accuracy(records) }
    RSplit(compact, 1.25f, equalHeight = true, left = { m ->
        Column(m.clip(cardShape).background(cardBg).border(1.dp, RLine, cardShape).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHead("WEATHER", "오늘 주행 조건", "CONDITIONS", null)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOfNotNull(temp?.let { "%.0f℃".format(it) }, if (rainy) "비" else "맑음", if (t.hour >= 20 || t.hour < 6) "야간" else null).forEach {
                    Text(it, color = RInk, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFF13222B))
                        .border(1.dp, RLine, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 7.dp))
                }
                Spacer(Modifier.weight(1f))
                val eff = learned.conditionEffect
                if (eff != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("평소 대비 효율", color = RMuted, fontSize = 16.sp)
                        Text(if (eff <= -0.02) "−%.0f%%".format(-eff * 100) else if (eff >= 0.02) "+%.0f%%".format(eff * 100) else "비슷",
                            color = if (eff <= -0.02) ROrange else green, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
            val eff2 = learned.conditionEffect
            // Which of today's conditions apply; the engine weighs them together, so this says "on days like this", not "because".
            val days = listOfNotNull(temp?.let { if (it < 5) "추운 날" else if (it > 25) "더운 날" else null }, if (rainy) "비 오는 날" else null, if (t.hour >= 20 || t.hour < 6) "밤" else null)
            val basis = if (demo) "시연 기록 기준" else "내 주행 기록 ${learned.trips}회 기준"
            Text(when {
                eff2 == null -> "주행 기록이 4번 이상 쌓이면 이런 날 얼마나 덜 가는지 알려 드려요"
                days.isEmpty() -> (if (eff2 <= -0.02) "오늘 같은 조건엔 평소보다 덜 갔어요" else if (eff2 >= 0.02) "오늘 같은 조건엔 평소보다 더 갔어요" else "오늘 같은 조건엔 평소와 비슷했어요") + " ($basis)"
                else -> days.joinToString("·") + (if (eff2 <= -0.02) "엔 평소보다 덜 갔어요" else if (eff2 >= 0.02) "엔 평소보다 더 갔어요" else "엔 평소와 비슷했어요") + " ($basis)"
            }, color = RInk.copy(alpha = .85f), fontSize = 18.sp)
        }
    }, right = { m ->
        Column(m.clip(cardShape).background(cardBg).border(1.dp, RLine, cardShape).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHead("DRIVE", "RADIO · DATA", null, null)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("무전", color = RInk, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(voiceStatus.ifBlank { "확인 전" }.take(14), color = RMuted, fontSize = 16.sp)
                Spacer(Modifier.width(10.dp)); Pill("테스트", RCyan, onRadio)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("예측 오차", color = RInk, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(acc?.let { "평균 %.1f%%p · %d회".format(it.meanAbsError, it.checked) } ?: "검증 기록 없음", color = if (acc != null) RInk else RMuted, fontSize = 18.sp)
            }
            Text("지난 주행 리포트  ›", color = ROrange, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onOpen("DRIVE_REPORT") })
        }
    })
}


/** Until the car has a few real drives with distance, screens show the built-in example history (marked as such). */
const val MIN_REAL_DRIVES = 5
fun needsExample(records: List<DriveRecord>): Boolean =
    records.count { r -> r.endOdoKm != null && r.startOdoKm != null && r.endOdoKm - r.startOdoKm >= 1.0 } < MIN_REAL_DRIVES
