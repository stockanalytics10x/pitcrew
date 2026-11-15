package com.example.washguard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val CgInk = Color(0xFFE6EDF1)
private val CgMuted = Color(0xFF8DA4B2)
private val CgLine = Color(0xFF1E3340)
private val CgCard = Color(0xFF0E1A21)
private val CgRow = Color(0xFF13222B)
private val CgOrange = Color(0xFFFF7A28)
private val CgBlue = Color(0xFF3D8BFF)
private val CgSky = Color(0xFF7FB6FF)
private val CgGreen = Color(0xFF2FD07F)
private val CgRed = Color(0xFFFF5A4E)
private val CgCyan = Color(0xFF39BCE9)
private val CgAmber = Color(0xFFF2A33A)
private val CgShape = RoundedCornerShape(16.dp)

/** The charge to aim for: the Pit Wall's target when a stop is needed, otherwise the usual 80% cap. */
fun chargeTarget(plan: PitPlan?): Pair<Int, String> {
    val t = plan?.chargeTargetPercent?.value?.roundToInt()
    return if (t != null && plan.needsPit) t to "도착 여유 기준" else 80 to "평소 권장 상한"
}

@Composable
private fun CgText(text: String, size: Int, color: Color = CgInk, bold: Boolean = false, modifier: Modifier = Modifier) =
    Text(text, color = color, fontSize = size.sp, lineHeight = (size * 1.3).sp, fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.Medium, modifier = modifier)

@Composable
private fun CgCardBox(title: String, modifier: Modifier = Modifier, right: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.background(CgCard, CgShape).border(1.dp, CgLine, CgShape).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(4.dp, 20.dp).background(CgOrange)); Spacer(Modifier.width(10.dp))
            CgText(title, 20, bold = true); Spacer(Modifier.weight(1f)); right?.let { CgText(it, 15, CgMuted) }
        }
        content()
    }
}

/**
 * Shown when the app recognises charging (see [ChargeDetector]). The battery reading is the car's; the rate, time to
 * target and arrival are estimates from this session's own readings and the Pit Wall plan, and are labelled so.
 */
@Composable
fun ChargingScreen(charge: ChargeSession, plan: PitPlan?, check: CrewCheck?, nowMs: Long, onOpen: (String) -> Unit) {
    val (target, targetWhy) = chargeTarget(plan)
    val soc = charge.soc
    val phase = charge.phase
    val title = when (phase) { ChargePhase.READY -> "충전 준비"; ChargePhase.DONE -> "충전 끝"; else -> "충전 중" }
    val accent = when (phase) { ChargePhase.DONE -> CgGreen; ChargePhase.READY -> CgMuted; else -> CgBlue }
    val minutes = charge.startMs?.let { (((charge.endedMs ?: nowMs) - it) / 60_000).toInt() }
    val eta = charge.minutesTo(target)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp, 40.dp).background(CgOrange)); Spacer(Modifier.width(14.dp))
            CgText(title, 36, bold = true)
            Spacer(Modifier.width(18.dp))
            CgText("PIT IN · ${charge.basis.ifBlank { "차량 신호" }}", 15, CgSky,
                modifier = Modifier.background(CgBlue.copy(alpha = .18f), RoundedCornerShape(15.dp)).padding(horizontal = 14.dp, vertical = 5.dp))
            Spacer(Modifier.weight(1f))
            CgText(listOfNotNull(minutes?.let { "${it}분째" }, charge.startSoc?.let { "시작 $it%" }).joinToString(" · "), 18, CgMuted)
        }

        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // Gauge
            Column(Modifier.weight(1f).fillMaxHeight().background(CgCard, CgShape).border(1.dp, CgLine, CgShape).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = 26.dp.toPx(); val r = (size.minDimension - w) / 2
                        val tl = Offset(center.x - r, center.y - r); val sz = Size(r * 2, r * 2)
                        drawArc(CgLine, 0f, 360f, false, tl, sz, style = Stroke(w))
                        drawArc(accent, -90f, 360f * ((soc ?: 0) / 100f), false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                        val a = Math.toRadians(-90.0 + 360.0 * target / 100.0)
                        val inner = r - w / 2 - 4.dp.toPx(); val outer = r + w / 2 + 4.dp.toPx()
                        drawLine(CgGreen, Offset(center.x + inner * cos(a).toFloat(), center.y + inner * sin(a).toFloat()),
                            Offset(center.x + outer * cos(a).toFloat(), center.y + outer * sin(a).toFloat()), 5.dp.toPx())
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CgText(soc?.let { "$it%" } ?: "—", 80, bold = true)
                        CgText("목표 $target% · $targetWhy", 18, CgMuted)
                    }
                }
                when (phase) {
                    ChargePhase.DONE -> {
                        CgText("이번 충전", 18, CgMuted)
                        CgText("+${charge.gained}%" + (minutes?.let { " · ${it}분" } ?: ""), 36, CgGreen, true)
                    }
                    ChargePhase.READY -> {
                        CgText("배터리가 오르는지 확인하는 중", 20, CgMuted)
                        CgText("1% 오르면 충전 중으로 바뀌어요", 16, CgMuted)
                    }
                    else -> {
                        CgText("목표까지", 18, CgMuted)
                        CgText(when { eta == 0 -> "도달"; eta != null -> "약 ${eta}분"; else -> "계산 중" }, 38, CgSky, true)
                        CgText(charge.ratePerMin?.let { "분당 ${"%.1f".format(it)}% 오르는 중 · 추정" } ?: "두 번 이상 오르면 남은 시간을 계산해요", 15, CgMuted)
                    }
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                ArrivalCard(plan, soc, target)
                WaitingCard(plan, check, onOpen)
            }
        }

        ChargeFlowCard(charge, target, eta, phase)

        if (phase == ChargePhase.DONE) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            CgText("PITSTOP으로", 24, Color(0xFF071015), true, Modifier.weight(1f).background(CgGreen, RoundedCornerShape(40.dp))
                .clickable { onOpen("PITSTOP") }.padding(vertical = 22.dp).wrapContentWidth())
            CgText("GARAGE로", 24, CgInk, false, Modifier.weight(1f).border(2.dp, Color(0xFF3B5563), RoundedCornerShape(40.dp))
                .clickable { onOpen("GARAGE") }.padding(vertical = 22.dp).wrapContentWidth())
        }
        CgText("배터리 값은 차에서 받은 실측이에요. 충전 속도·남은 시간·도착 잔량은 이번 충전 기록과 Pit Wall 계산으로 낸 추정이에요.", 14, CgMuted)
    }
}

@Composable
private fun ArrivalCard(plan: PitPlan?, soc: Int?, target: Int) {
    val arrival = plan?.arrivalPercent?.value
    val planSoc = plan?.soc?.value
    val kpp = plan?.kmPerPercent?.value
    if (arrival != null && planSoc != null && plan.remaining.value != null) {
        val atTarget = (arrival + (target - planSoc)).roundToInt()
        CgCardBox("$target%면 이렇게 도착해요", Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Bottom) {
                CgText("$atTarget%", 44, if (atTarget >= PitWallEngine.ARRIVAL_COMFORT_PERCENT) CgGreen else CgAmber, true)
                Spacer(Modifier.width(14.dp))
                CgText(listOfNotNull(plan.arrivalSpread?.let { "±${it.roundToInt()}" }, "목적지 도착 예상 · 추정").joinToString(" · "), 20, CgMuted,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            CgText("남은 거리 ${plan.remaining.value.roundToInt()}km · 목표 도달 시 무전으로 알려 드려요", 15, CgMuted)
        }
    } else {
        CgCardBox("$target%까지 채우면", Modifier.fillMaxWidth()) {
            if (kpp != null && soc != null) {
                val more = ((target - soc).coerceAtLeast(0) * kpp).roundToInt()
                Row(verticalAlignment = Alignment.Bottom) {
                    CgText("+${more}km", 44, CgGreen, true); Spacer(Modifier.width(14.dp))
                    CgText("더 갈 수 있어요 · 추정", 20, CgMuted, modifier = Modifier.padding(bottom = 8.dp))
                }
                CgText("총 약 ${(target * kpp).roundToInt()}km · 이 차 효율 ${"%.1f".format(kpp)}km/% 기준", 15, CgMuted)
            } else CgText("목적지나 효율 기록이 없어 거리 계산은 생략했어요", 18, CgMuted)
        }
    }
}

@Composable
private fun WaitingRow(mark: String, markColor: Color, title: String, sub: String, warn: Boolean = false, button: String? = null, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(if (warn) CgRed.copy(alpha = .10f) else CgRow, RoundedCornerShape(10.dp))
        .then(if (warn) Modifier.border(1.dp, CgRed.copy(alpha = .5f), RoundedCornerShape(10.dp)) else Modifier)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).background(markColor, CircleShape), contentAlignment = Alignment.Center) { CgText(mark, 15, Color(0xFF071015), true) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { CgText(title, 18, bold = true); CgText(sub, 15, CgMuted) }
        button?.let { CgText(it, 15, Color(0xFF071015), true, Modifier.background(CgOrange, RoundedCornerShape(15.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp)) }
    }
}

@Composable
private fun WaitingCard(plan: PitPlan?, check: CrewCheck?, onOpen: (String) -> Unit) {
    CgCardBox("기다리는 동안", Modifier.fillMaxWidth()) {
        val cards = check?.cards.orEmpty().sortedBy { it.level.rank }.take(2)
        if (check == null) WaitingRow("?", CgMuted, "차량 점검", "점검 신호를 아직 받지 못했어요")
        else if (cards.isEmpty()) WaitingRow("✓", CgGreen, "차량 점검", "경고 없음")
        else cards.forEach { c ->
            WaitingRow("!", CgRed, c.title, c.now, warn = true, button = c.fix?.let { "안내" }) { c.fix?.let { onOpen("FIX_${it.kind.name}") } }
        }
        when (plan?.wash?.verdict) {
            WashVerdict.OK -> WaitingRow("i", CgCyan, "충전하는 동안 세차해도 좋아요", plan.wash.reason)
            WashVerdict.HOLD -> WaitingRow("i", CgCyan, "세차는 미루세요", plan.wash.reason)
            else -> {}
        }
    }
}

@Composable
private fun ChargeFlowCard(charge: ChargeSession, target: Int, eta: Int?, phase: ChargePhase) {
    val start = charge.startSoc
    CgCardBox("충전 흐름", Modifier.fillMaxWidth(), right = start?.let { "$it% → ${charge.soc}%" }) {
        val samples = charge.samples
        if (samples.size < 2) { CgText("배터리가 오르면 여기에 흐름이 그려져요", 16, CgMuted); return@CgCardBox }
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            val t0 = samples.first().ms
            val tNow = samples.last().ms
            val tEnd = if (phase == ChargePhase.CHARGING && eta != null && eta > 0) tNow + eta * 60_000L else tNow
            val span = (tEnd - t0).coerceAtLeast(1L).toFloat()
            val lo = (samples.minOf { it.soc } - 5).coerceAtLeast(0).toFloat()
            val hi = maxOf(target, samples.maxOf { it.soc }).plus(3).coerceAtMost(100).toFloat()
            fun x(ms: Long) = (ms - t0) / span * size.width
            fun y(v: Float) = size.height - (v - lo) / (hi - lo) * size.height
            drawLine(CgLine, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            drawLine(CgGreen, Offset(0f, y(target.toFloat())), Offset(size.width, y(target.toFloat())), 2.dp.toPx())
            val path = Path()
            samples.forEachIndexed { i, s -> if (i == 0) path.moveTo(x(s.ms), y(s.soc.toFloat())) else path.lineTo(x(s.ms), y(s.soc.toFloat())) }
            drawPath(path, CgBlue, style = Stroke(4.dp.toPx()))
            val last = samples.last()
            if (tEnd > tNow) drawLine(CgBlue, Offset(x(tNow), y(last.soc.toFloat())), Offset(x(tEnd), y(target.toFloat())), 3.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f)))
            drawCircle(CgBlue, 7.dp.toPx(), Offset(x(tNow), y(last.soc.toFloat())))
        }
        Row(Modifier.fillMaxWidth()) {
            CgText("시작", 13, CgMuted); Spacer(Modifier.weight(1f))
            CgText(if (phase == ChargePhase.CHARGING && eta != null && eta > 0) "지금 → ${eta}분 뒤 목표 $target% (추정)" else "목표 $target%", 13, CgSky)
        }
    }
}
