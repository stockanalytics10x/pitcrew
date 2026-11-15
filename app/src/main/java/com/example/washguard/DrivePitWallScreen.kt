package com.example.washguard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DvBg = Color(0xFF050D12)
private val DvTile = Color(0xFF0F1E27)
private val DvLine = Color(0xFF263C48)
private val DvInk = Color(0xFFE6EDF1)
private val DvMuted = Color(0xFF8DA4B2)
private val DvOrange = Color(0xFFFF7A28)
private val DvCyan = Color(0xFF39BCE9)
private val DvGreen = Color(0xFF30CD86)
private val DvRed = Color(0xFFFF4B4B)

private fun accentOf(v: PitVerdict) = when (v) {
    PitVerdict.FINISH_OK -> DvGreen
    PitVerdict.PIT_ADVISED -> DvOrange
    PitVerdict.PIT_REQUIRED -> DvRed
    else -> DvMuted
}

private fun tagOf(v: PitVerdict) = when (v) {
    PitVerdict.FINISH_OK -> "NO STOP"
    PitVerdict.PIT_ADVISED -> "PIT ADVISED"
    PitVerdict.PIT_REQUIRED -> "BOX THIS STINT"
    PitVerdict.NO_DESTINATION -> "NO ROUTE"
    PitVerdict.UNKNOWN -> "STANDBY"
}

private val Display = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(R.font.michroma_regular))

/**
 * Full-screen driving layout (mock-up "PIT WALL LIVE", without the trend strip):
 *   PIT WALL 판정 — verdict band, arrival estimate ±, distance to the pit (or to the finish), arrival time from the navi
 *   STINT TRACK   — the stint number, the track view, and the points we really know: start, now, pit(s), finish
 *   TEAM RADIO    — the latest Box call (or what the Pit Wall would say now) and the car's state
 *   ROAD FLOW     — the road driven so far (offline Seoul map; the route ahead is not given by the navi)
 * Nothing to press while moving.
 */
@Composable
fun DrivePitWallScreen(
    plan: PitPlan,
    speedKmh: Double?,
    voiceStatus: String,
    modifier: Modifier = Modifier,
    radio: List<BoxCallRecord> = emptyList(),
    deltaArrival: Double? = null,
    events: List<TrackEvent> = emptyList(),
    crewCheck: CrewCheck? = null,
    finishLabel: String = "FINISH",
    remainMinutes: Double? = null,
    destination: String? = null,
    /** What this car usually drives on this weekday (learned); used when there is no destination. */
    todayTypicalKm: Double? = null,
    /** Car odometer, km, when the car sends it; otherwise the distance is integrated from the speed. */
    odometerKm: Double? = null,
    /** Today's conditions and what they did to this car's efficiency before (learned), for the track-condition line. */
    tempC: Double? = null,
    rainy: Boolean? = null,
    conditionEffect: Double? = null
) {
    val accent = accentOf(plan.verdict)
    val track = StintMath.track(plan)
    val pt = plan.powertrain
    fun km(v: Double?) = v?.let { if (it < 10) String.format(Locale.US, "%.1f", it) else it.toInt().toString() } ?: "—"
    // No destination: judge from the range left, the 20% line and what this car usually drives today.
    val free = plan.verdict == PitVerdict.NO_DESTINATION
    val kpp = plan.kmPerPercent.value
    val soc = plan.soc.value
    val toReserve = if (kpp != null && soc != null) ((soc - PitWallEngine.ARRIVAL_COMFORT_PERCENT) * kpp).coerceAtLeast(0.0) else null
    val freeCall: Triple<String, String, Color>? = if (!free) null else {
        val range = plan.range.value
        when {
            range == null || soc == null -> Triple("신호 확인 중", "잔량 신호를 기다리고 있어요", DvMuted)
            soc < PitWallEngine.ARRIVAL_COMFORT_PERCENT || range < 30 -> Triple("${pt.refuel} 권장", "약 ${km(range)} km 남았어요. 가까운 ${pt.place}를 찾아 두세요", DvRed)
            todayTypicalKm != null && toReserve != null && toReserve < todayTypicalKm * 1.2 -> Triple("오늘은 빠듯해요", "평소 오늘 ${km(todayTypicalKm)} km · 20%까지 ${km(toReserve)} km", DvOrange)
            else -> Triple("자유 주행 · 여유", "약 ${km(range)} km 더 갈 수 있어요", DvGreen)
        }
    }
    val accentNow = freeCall?.third ?: accent
    // ── this stint, live: when it began, the charge it began with, and how far the car has gone since
    val stintStart = androidx.compose.runtime.remember { System.currentTimeMillis() }
    val startSoc = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Double?>(null) }
    if (startSoc.value == null && soc != null) startSoc.value = soc
    val startOdo = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Double?>(null) }
    if (startOdo.value == null && odometerKm != null && odometerKm > 0) startOdo.value = odometerKm
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    var speedKm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableDoubleStateOf(0.0) }
    val speedNow by androidx.compose.runtime.rememberUpdatedState(speedKmh)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(1000); tick = System.currentTimeMillis(); speedKm += (speedNow ?: 0.0) / 3600.0 }
    }
    val odoKm = startOdo.value?.let { a -> odometerKm?.let { (it - a).takeIf { d -> d >= 0 } } }
    val drivenKm = odoKm?.takeIf { it > 0.05 } ?: speedKm
    val usedPct = startSoc.value?.let { a -> soc?.let { a - it } }
    val expectedPct = if (kpp != null && kpp > 0) drivenKm / kpp else null
    val livedelta = if (usedPct != null && expectedPct != null && drivenKm >= 0.5) expectedPct - usedPct else null
    BoxWithConstraints(modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF13202A), Color(0xFF0A1218), Color(0xFF060C11))))) {
        val s = (maxWidth.value / 1280f).coerceIn(.6f, 2f)
        val shape = RoundedCornerShape((16 * s).dp)
        val cardBg = Brush.verticalGradient(listOf(Color(0xFF0E1A21), Color(0xFF09131A)))
        @Composable fun Card(modifier: Modifier, title: String, sub: String? = null, content: @Composable ColumnScope.() -> Unit) =
            Column(modifier.clip(shape).background(cardBg).border(1.dp, DvLine, shape).padding((18 * s).dp), verticalArrangement = Arrangement.spacedBy((10 * s).dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width((5 * s).dp).height((22 * s).dp).background(DvOrange)); Spacer(Modifier.width((12 * s).dp))
                    Text(title, color = DvInk, fontSize = (22 * s).sp, fontWeight = FontWeight.Bold)
                    sub?.let { Text("   |   $it", color = DvMuted, fontSize = (17 * s).sp) }
                }
                content()
            }
        @Composable fun Mark(c: Char, color: Color, size: Float) = Box(Modifier.size((size * s).dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Text(c.toString(), color = DvBg, fontSize = (size * .6f * s).sp, fontWeight = FontWeight.Black)
        }
        @Composable fun Stat(label: String, value: String, unit: String, extra: String? = null, bar: Float? = null, valueColor: Color = DvInk) =
            Row(Modifier.fillMaxWidth().padding(vertical = (4 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = DvMuted, fontSize = (19 * s).sp, modifier = Modifier.width((150 * s).dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(value, color = valueColor, fontSize = (38 * s).sp, fontFamily = Display, maxLines = 1)
                        Text(" $unit", color = DvMuted, fontSize = (19 * s).sp, modifier = Modifier.padding(bottom = (6 * s).dp))
                        Spacer(Modifier.weight(1f))
                        extra?.let { Text(it, color = DvMuted, fontSize = (18 * s).sp, modifier = Modifier.padding(bottom = (6 * s).dp)) }
                    }
                    bar?.let { f -> Box(Modifier.fillMaxWidth().height((6 * s).dp).clip(RoundedCornerShape(3.dp)).background(DvLine)) {
                        Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).fillMaxHeight().background(valueColor)) } }
                }
            }

        Column(Modifier.fillMaxSize().padding(horizontal = (26 * s).dp, vertical = (16 * s).dp), verticalArrangement = Arrangement.spacedBy((14 * s).dp)) {
            // ── top bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width((5 * s).dp).height((26 * s).dp).background(DvOrange)); Spacer(Modifier.width((12 * s).dp))
                Column {
                    Text("PIT WALL LIVE", color = DvInk, fontSize = (28 * s).sp, fontWeight = FontWeight.Black, letterSpacing = (1 * s).sp)
                    Text("실시간 주행 모니터링", color = DvMuted, fontSize = (16 * s).sp)
                }
                Spacer(Modifier.width((18 * s).dp)); Box(Modifier.weight(1f).height(1.dp).background(DvLine)); Spacer(Modifier.width((18 * s).dp))
                if (plan.soc.source == DataSource.DEMO) Text("DEMO / NOT LIVE", color = DvOrange, fontSize = (15 * s).sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = (16 * s).dp).border(1.dp, DvOrange, RoundedCornerShape((4 * s).dp)).padding(horizontal = (10 * s).dp, vertical = (2 * s).dp))
                Text(speedKmh?.let { "${it.toInt()} km/h" } ?: "", color = DvMuted, fontSize = (18 * s).sp)
                Spacer(Modifier.width((18 * s).dp))
                Text(LocalTime.now(KST).format(DateTimeFormatter.ofPattern("HH:mm")), color = DvInk, fontSize = (20 * s).sp, fontWeight = FontWeight.SemiBold)
            }

            // ── row 1: verdict | stint track
            Row(Modifier.fillMaxWidth().weight(1.3f), horizontalArrangement = Arrangement.spacedBy((16 * s).dp)) {
                Card(Modifier.weight(.95f).fillMaxHeight(), "PIT WALL 판정") {
                    val mark = if (freeCall != null) (if (freeCall.third == DvGreen) '✓' else if (freeCall.third == DvMuted) '?' else '!')
                        else when (plan.verdict) { PitVerdict.FINISH_OK -> '✓'; PitVerdict.UNKNOWN, PitVerdict.NO_DESTINATION -> '?'; else -> '!' }
                    val title = when (plan.verdict) {
                        PitVerdict.FINISH_OK -> "논스톱 가능"
                        PitVerdict.PIT_ADVISED -> "${pt.refuel} 1번 권장"
                        PitVerdict.PIT_REQUIRED -> if (plan.needsSecondStop) "${pt.refuel} 2번 필요" else "${pt.refuel} 필수"
                        PitVerdict.NO_DESTINATION -> freeCall!!.first
                        PitVerdict.UNKNOWN -> "신호 확인 중"
                    }
                    val sub = when (plan.verdict) {
                        PitVerdict.FINISH_OK -> "도착까지 여유 있어요"
                        PitVerdict.PIT_ADVISED -> "들르면 여유 있게 도착해요"
                        PitVerdict.PIT_REQUIRED -> "이대로는 도착이 빠듯해요"
                        PitVerdict.NO_DESTINATION -> freeCall!!.second
                        PitVerdict.UNKNOWN -> plan.reason
                    }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape((12 * s).dp)).background(accentNow.copy(alpha = .14f)).border(1.5.dp, accentNow.copy(alpha = .8f), RoundedCornerShape((12 * s).dp))
                        .padding((14 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                        Mark(mark, accentNow, 44f); Spacer(Modifier.width((14 * s).dp))
                        Column {
                            Text(title, color = DvInk, fontSize = (34 * s).sp, fontWeight = FontWeight.Black, maxLines = 1)
                            Text(sub, color = DvInk.copy(alpha = .85f), fontSize = (18 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (free) {
                        // LIVE DELTA: what this stint used so far against what the learned efficiency expected.
                        fun p1(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
                        Stat("지금까지 사용", usedPct?.let { "−" + p1(it.coerceAtLeast(0.0)) } ?: "—", "%", "${km(drivenKm)} km 주행")
                        Box(Modifier.fillMaxWidth().height(1.dp).background(DvLine))
                        Stat("예상 사용", expectedPct?.let { "−" + p1(it) } ?: "—", "%", kpp?.let { "학습 효율 ${p1(it)} km/%" })
                        Box(Modifier.fillMaxWidth().height(1.dp).background(DvLine))
                        Stat("LIVE DELTA", livedelta?.let { (if (it >= 0) "+" else "−") + p1(kotlin.math.abs(it)) } ?: "—", if (livedelta != null) "%p" else "",
                            livedelta?.let { if (it >= 0.2) "예상보다 덜 썼어요" else if (it <= -0.2) "예상보다 더 쓰는 중" else "예상대로예요" } ?: "0.5km 달리면 계산해요",
                            null, livedelta?.let { if (it >= 0.2) DvGreen else if (it <= -0.2) DvOrange else DvCyan } ?: DvMuted)
                    } else {
                    val arrival = plan.arrivalPercent.value
                    Stat("도착 예상 잔량", arrival?.let { if (it < 0) "부족" else "${it.toInt()}" } ?: "—", if (arrival != null && arrival >= 0) "%" else "",
                        plan.arrivalSpread?.let { "±%.0f".format(it) }, arrival?.let { (it / 100).toFloat() }, if (arrival != null && arrival < PitWallEngine.ARRIVAL_COMFORT_PERCENT) DvRed else DvOrange)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(DvLine))
                    when {
                        track?.pitKm != null -> Stat("다음 피트까지", km(track.pitKm), "km", plan.chargeTargetPercent.value?.let { "${it.toInt()}%까지 ${pt.refuel}" })
                        plan.needsPit && track?.chargeByKm != null -> Stat("${pt.refuel} 마감", km(track.chargeByKm), "km 안", null, null, DvOrange)
                        else -> Stat("목적지까지", km(plan.remaining.value), "km")
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(DvLine))
                    val eta = remainMinutes?.takeIf { it >= 0 }
                    Stat("도착 예상 시각", eta?.let { LocalTime.now(KST).plusMinutes(it.toLong()).format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "—", "",
                        eta?.let { "약 ${it.toInt()}분" })
                    }
                }
                Card(Modifier.weight(1.35f).fillMaxHeight(), "TRACK", "이번 주행 구간") {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(DvLine))
                    // Real points along this drive (km ahead): pit(s) and finish, or without a route the 20% line and empty.
                    val startKm = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Double?>(null) }
                    val rem = plan.remaining.value
                    if (rem != null && (startKm.value == null || rem > startKm.value!!)) startKm.value = rem
                    val horizon = if (!free) startKm.value else plan.range.value?.takeIf { it > 0 }
                    val driven = if (!free) ((startKm.value ?: 0.0) - (rem ?: 0.0)).coerceAtLeast(0.0) else 0.0
                    val ahead = buildList<Double?> {
                        if (!free) {
                            when { track?.pitKm != null -> add(track.pitKm); plan.needsPit && track?.chargeByKm != null -> add(track.chargeByKm) }
                            if (plan.needsSecondStop) add(null)
                            add(rem)
                        } else { add(toReserve); add(plan.range.value) }
                    }
                    val stints = when { plan.needsSecondStop -> 3; plan.needsPit -> 2; else -> 1 }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        StintLane(horizon, driven, ahead, s, Modifier.fillMaxSize(), speedKmh)
                        // the stint number on a dark plate, so the roads never run through it
                        Row(Modifier.clip(RoundedCornerShape((10 * s).dp)).background(Color(0xFF0A1218).copy(alpha = .82f)).padding(horizontal = (10 * s).dp),
                            verticalAlignment = Alignment.Bottom) {
                            Text("구간 ", color = DvMuted, fontSize = (18 * s).sp, modifier = Modifier.padding(bottom = (14 * s).dp))
                            Text("01", color = DvInk, fontSize = (60 * s).sp, fontFamily = Display)
                            Text(" / %02d".format(stints), color = DvMuted, fontSize = (28 * s).sp, fontFamily = Display, modifier = Modifier.padding(bottom = (10 * s).dp))
                        }
                    }
                    Text("도로 모양은 연출 · 점의 위치는 실제 거리", color = DvMuted.copy(alpha = .7f), fontSize = (12 * s).sp)
                }
            }

            // ── row 2: team radio | road flow
            Row(Modifier.fillMaxWidth().weight(.8f), horizontalArrangement = Arrangement.spacedBy((16 * s).dp)) {
                Card(Modifier.weight(1f).fillMaxHeight(), "TEAM RADIO") {
                    val clock = DateTimeFormatter.ofPattern("HH:mm")
                    val lastBox = radio.maxByOrNull { it.atMs }
                    // The radio line follows the verdict, so it never contradicts the card above.
                    val boxText = lastBox?.let { r ->
                        java.time.Instant.ofEpochMilli(r.atMs).atZone(KST).format(clock) + "  " + (if (r.level == BoxLevel.REQUIRED) "Box, box. " else "") +
                            "도착 ${r.arrivalPercent?.let { if (it < 0) "부족" else "${it.toInt()}%" } ?: "—"} · ${pt.place}에 들를까요? ▸ " +
                            when (r.outcome) { BoxOutcome.ACCEPTED -> "응"; BoxOutcome.DECLINED -> "다음에"; BoxOutcome.NO_ANSWER -> "응답 없음" }
                    } ?: when (plan.verdict) {
                        PitVerdict.FINISH_OK -> "도착까지 여유 있어요. 필요하면 먼저 부를게요."
                        PitVerdict.PIT_ADVISED -> "${pt.place}에 한 번 들르면 여유 있게 도착해요."
                        PitVerdict.PIT_REQUIRED -> "곧 ${pt.place}에 들러야 해요. 부르면 \"응\"이라고 답해 주세요."
                        else -> "필요할 때 Pit Wall이 먼저 불러요."
                    }
                    @Composable fun RadioRow(label: String, labelColor: Color, mark: Char?, text: String) =
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape((12 * s).dp)).background(DvTile).border(1.dp, DvLine, RoundedCornerShape((12 * s).dp))
                            .padding(horizontal = (16 * s).dp, vertical = (12 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                            mark?.let { Mark(it, labelColor, 26f); Spacer(Modifier.width((10 * s).dp)) }
                            Text(label, color = labelColor, fontSize = (20 * s).sp, fontWeight = FontWeight.Bold)
                            Text("   |   ", color = DvMuted, fontSize = (18 * s).sp)
                            Text(text, color = DvInk, fontSize = (19 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    RadioRow("Box Call", if (lastBox?.level == BoxLevel.REQUIRED || plan.verdict == PitVerdict.PIT_REQUIRED) DvRed else DvOrange, null, boxText)
                    val worst = crewCheck?.cards?.firstOrNull()
                    when {
                        worst != null -> RadioRow("차량 상태", levelColor(worst.level), '!', worst.title + (if (crewCheck.cards.size > 1) " 외 ${crewCheck.cards.size - 1}건" else "") + " · " + worst.plan)
                        crewCheck != null && crewCheck.spots.values.none { it == SpotStatus.UNKNOWN } -> RadioRow("차량 상태", DvGreen, '✓', "경고 없이 정상이에요")
                        else -> RadioRow("차량 상태", DvMuted, '?', "점검 신호를 기다리는 중")
                    }
                    // Track condition: today's weather and time, and what such days did to this car's efficiency
                    val night = LocalTime.now(KST).hour.let { it >= 20 || it < 6 }
                    val cond = listOfNotNull(tempC?.let { "${it.toInt()}℃" }, rainy?.let { if (it) "비" else "맑음" }, if (night) "야간" else null).joinToString(" · ").ifBlank { "날씨 받는 중" }
                    val eff = conditionEffect
                    RadioRow("트랙 컨디션", DvCyan, null, cond + "   " + when {
                        eff == null -> "효율 영향은 기록이 쌓이면 보여요"
                        eff <= -0.02 -> "이런 날 평소보다 ${(-eff * 100).toInt()}% 덜 가요"
                        eff >= 0.02 -> "이런 날 평소보다 ${(eff * 100).toInt()}% 더 가요"
                        else -> "평소와 비슷한 효율"
                    })
                }
                Card(Modifier.weight(1f).fillMaxHeight(), "DRIVE TIMER", "이번 주행") {
                    val sec = ((tick - stintStart) / 1000).coerceAtLeast(0)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Column {
                            Text("경과 시간", color = DvMuted, fontSize = (16 * s).sp)
                            Text("%02d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60), color = DvInk, fontSize = (46 * s).sp, fontFamily = Display, maxLines = 1)
                        }
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text("현재 속도", color = DvMuted, fontSize = (16 * s).sp)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("${(speedKmh ?: 0.0).toInt()}", color = DvOrange, fontSize = (46 * s).sp, fontFamily = Display)
                                Text(" km/h", color = DvMuted, fontSize = (17 * s).sp, modifier = Modifier.padding(bottom = (8 * s).dp))
                            }
                        }
                    }
                    val avg = if (sec > 30) drivenKm / (sec / 3600.0) else null
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy((10 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                        @Composable fun Tile(label: String, value: String, mod: Modifier) =
                            Column(mod.clip(RoundedCornerShape((10 * s).dp)).background(DvTile).border(1.dp, DvLine, RoundedCornerShape((10 * s).dp)).padding((12 * s).dp)) {
                                Text(label, color = DvMuted, fontSize = (15 * s).sp)
                                Text(value, color = DvInk, fontSize = (24 * s).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        Tile("이번 주행", "${km(drivenKm)} km", Modifier.weight(1f))
                        Tile("평균 속도", avg?.let { "${it.toInt()} km/h" } ?: "—", Modifier.weight(1f))
                        Tile("출발", java.time.Instant.ofEpochMilli(stintStart).atZone(KST).format(DateTimeFormatter.ofPattern("HH:mm")), Modifier.weight(1f))
                    }
                    Text(if (odoKm == null) "거리는 속도로 계산" else "거리는 차 주행거리계", color = DvMuted, fontSize = (13 * s).sp)
                }
            }
        }
    }
}

private fun verdictKo(v: PitVerdict) = when (v) {
    PitVerdict.FINISH_OK -> "완주 가능"
    PitVerdict.PIT_ADVISED -> "피트 권장"
    PitVerdict.PIT_REQUIRED -> "피트 필수"
    PitVerdict.NO_DESTINATION -> "목적지 없음"
    PitVerdict.UNKNOWN -> "확인 중"
}

/**
 * STINT TRACK, top-down, drawn after the mock-up: thin glowing white roads that fade towards their ends (the outer one
 * dimmer), a white centre lane, cyan points where the real points are, and the car as an orange chevron.
 * Geometry is taken from the mock-up in a square-ish area, so the look stays the same on any card size.
 */
@Composable
private fun StintLane(horizonKm: Double?, drivenKm: Double, points: List<Double?>, s: Float, modifier: Modifier, speedKmh: Double? = null) {
    // The roads flow past at the car's speed, so the car is seen moving; the points stay at their real distances.
    var metres by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val v = ((speedKmh ?: 0.0).toFloat() / 3.6f).coerceIn(0f, 70f)
    androidx.compose.runtime.LaunchedEffect(v > 0f) {
        if (v <= 0f) return@LaunchedEffect
        var last = 0L
        while (true) { androidx.compose.runtime.withFrameNanos { t -> if (last != 0L) metres += v * ((t - last) / 1e9f); last = t } }
    }
    val speedNow by androidx.compose.runtime.rememberUpdatedState(v)
    androidx.compose.foundation.Canvas(modifier) {
        val pxPerMetre = size.height / 160f              // one screen = 160 m of road
        val flow = (metres * pxPerMetre) % size.height
        val moving = speedNow > 0f
        val tw = minOf(size.width, size.height * 1.0f); val th = size.height
        val x0 = (size.width - tw) / 2f
        fun X(f: Float) = x0 + tw * f
        fun Y(f: Float) = th * f
        val round = androidx.compose.ui.graphics.StrokeCap.Round
        val rw = tw * .022f
        // a road: soft grey halo, white core; the brush fades it towards both ends like in the mock-up
        fun road(p: androidx.compose.ui.graphics.Path, strength: Float, width: Float = rw) {
            val fade = Brush.verticalGradient(0f to Color.White.copy(alpha = 0f), .14f to Color.White.copy(alpha = .9f * strength), .7f to Color.White.copy(alpha = strength),
                1f to Color.White.copy(alpha = .35f * strength))
            val halo = Brush.verticalGradient(0f to Color(0xFF9AA6AD).copy(alpha = 0f), .2f to Color(0xFF9AA6AD).copy(alpha = .35f * strength), 1f to Color(0xFF9AA6AD).copy(alpha = .15f * strength))
            drawPath(p, halo, style = androidx.compose.ui.graphics.drawscope.Stroke(width * 2.6f, cap = round))
            drawPath(p, fade, style = androidx.compose.ui.graphics.drawscope.Stroke(width, cap = round))
        }
        val P = { androidx.compose.ui.graphics.Path() }
        for (shift in listOf(flow, flow - th)) translate(0f, shift) {
        // left outer road (dimmer): gentle S down the left edge
        road(P().apply { moveTo(X(.13f), Y(.0f)); cubicTo(X(.17f), Y(.22f), X(.02f), Y(.42f), X(.05f), Y(.62f)); cubicTo(X(.08f), Y(.78f), X(.03f), Y(.9f), X(.03f), Y(1f)) }, .55f)
        // left inner road: straight down, then sweeping right along the bottom
        road(P().apply { moveTo(X(.17f), Y(.0f)); lineTo(X(.17f), Y(.66f)); cubicTo(X(.17f), Y(.86f), X(.30f), Y(.91f), X(.47f), Y(.91f)); cubicTo(X(.70f), Y(.91f), X(.88f), Y(.93f), X(1f), Y(.96f)) }, 1f)
        // top right road: down a little, then away to the right
        road(P().apply { moveTo(X(.63f), Y(.0f)); lineTo(X(.63f), Y(.10f)); cubicTo(X(.63f), Y(.21f), X(.70f), Y(.24f), X(.81f), Y(.24f)); lineTo(X(1f), Y(.22f)) }, .9f)
        }
        // centre lane, a bit wider
        val cx = X(.46f)
        road(P().apply { moveTo(cx, Y(0f)); lineTo(cx, Y(1f)) }, 1f, rw * 1.45f)
        if (moving) {
            val gap = th / 7f
            var yy = (flow % gap) - gap
            while (yy < th) { drawLine(Color(0xFF0E1A21), androidx.compose.ui.geometry.Offset(cx, yy), androidx.compose.ui.geometry.Offset(cx, yy + gap * .35f), rw * .7f); yy += gap }
        }

        val top = Y(.12f); val bottom = Y(.94f)
        val total = (horizonKm ?: 0.0).coerceAtLeast(0.1)
        fun y(kmFromStart: Double) = bottom - ((kmFromStart / total).coerceIn(0.0, 1.0)).toFloat() * (bottom - top)
        val carY = if (horizonKm == null) bottom - (bottom - top) * .2f else y(drivenKm).coerceAtMost(bottom - (bottom - top) * .12f)
        val r = tw * .034f
        fun dot(py: Float) {
            drawCircle(Color(0xFF1EA7E6), r, androidx.compose.ui.geometry.Offset(cx, py))
            drawCircle(Color(0xFFC6ECFB), r, androidx.compose.ui.geometry.Offset(cx, py), style = androidx.compose.ui.graphics.drawscope.Stroke(r * .16f))
        }
        dot(bottom)
        points.forEach { k -> dot(if (k == null || horizonKm == null) top + (bottom - top) * .08f else y(drivenKm + k)) }
        val c = tw * .055f
        P().apply { moveTo(cx - c, carY + c * .5f); lineTo(cx, carY - c * .45f); lineTo(cx + c, carY + c * .5f) }
            .let { drawPath(it, Color(0xFFFF7A28), style = androidx.compose.ui.graphics.drawscope.Stroke(tw * .03f, cap = round, join = androidx.compose.ui.graphics.StrokeJoin.Round)) }
    }
}
