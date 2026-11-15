package com.example.washguard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private val DdInk = Color(0xFFE6EDF1)
private val DdMuted = Color(0xFF8DA4B2)
private val DdLine = Color(0xFF263C48)
private val DdRow = Color(0xFF13222B)
private val DdOrange = Color(0xFFFF7A28)
private val DdCyan = Color(0xFF39BCE9)
private val DdTeal = Color(0xFF2FC7A0)
private val DdAmber = Color(0xFFF2A33A)
private val DdBlue = Color(0xFF3D8BFF)
private val DdGrey = Color(0xFF5E6E78)
/** Charge only goes up while parked at a charger, never during a drive: up to 3% is regeneration, more is a bad reading. */
private fun DriveRecord.oddRise(): Boolean = startSoc != null && endSoc != null && endSoc - startSoc > 3.0
private val DdGreen = Color(0xFF2FD07F)
private val DdRed = Color(0xFFFF5A4E)
private val DdPurple = Color(0xFF8B7CF6)
private val DdShape = RoundedCornerShape(16.dp)

/** Predicted minus actual arrival, only when the drive covered its route (same rule as the learning engine). */
private fun DriveRecord.delta(): Double? {
    val km = (endOdoKm ?: return null) - (startOdoKm ?: return null)
    val p = predictedArrival ?: return null; val e = endSoc ?: return null; val r = routeKm ?: return null
    return if (r > 0 && abs(km - r) <= r * 0.15) p - e else null
}
private fun DriveRecord.km(): Double? = if (endOdoKm != null && startOdoKm != null) endOdoKm - startOdoKm else null
private fun f0(v: Double) = String.format(Locale.US, "%.0f", v)
private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)

private fun dayLabel(ms: Long, now: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(KST).toLocalDate(); val t = Instant.ofEpochMilli(now).atZone(KST).toLocalDate()
    val hm = Instant.ofEpochMilli(ms).atZone(KST).format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (d) { t -> "오늘 $hm"; t.minusDays(1) -> "어제 $hm"; else -> d.format(DateTimeFormatter.ofPattern("MM/dd")) + " $hm" }
}
private fun condLabel(r: DriveRecord) = listOfNotNull(r.rainy?.let { if (it) "비" else "맑음" }, r.tempC?.let { "${f0(it)}℃" }).joinToString(" ")

@Composable
private fun DdCard(title: String, sub: String?, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.clip(DdShape).background(Brush.verticalGradient(listOf(Color(0xFF0E1A21), Color(0xFF09131A)))).border(1.dp, DdLine, DdShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(20.dp).background(DdOrange)); Spacer(Modifier.width(10.dp))
            Text(title, color = DdInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            sub?.let { Text("  ·  $it", color = DdMuted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        content()
    }

@Composable
private fun Chip(text: String, color: Color = DdInk) = Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false,
    modifier = Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, color.copy(alpha = .45f), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 6.dp))

@Composable
private fun DeltaChip(d: Double?, best: Boolean, size: Int = 18) {
    val (bg, fg) = when {
        d == null -> DdRow to DdMuted
        best -> DdPurple.copy(alpha = .25f) to Color(0xFFC9C2FF)
        abs(d) <= 2.0 -> DdGreen.copy(alpha = .2f) to DdGreen
        else -> DdAmber.copy(alpha = .2f) to DdAmber
    }
    Text(d?.let { "Δ " + (if (it > 0) "+" else if (it < 0) "−" else "") + f0(abs(it)) } ?: "—", color = fg, fontSize = size.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 12.dp, vertical = 4.dp))
}

/**
 * DRIVE before the drive, as a pit wall data screen: the last stint in one line, then what this car's drives show —
 * energy per stint, how the arrival prediction has converged, efficiency by condition, and the weekly pattern.
 * The second tab (주행 기록) lists every drive like a timing tower with a detail panel. All from the drives saved in this car
 * (or the built-in demo history, marked as such).
 */
@Composable
fun DriveData(records: List<DriveRecord>, learned: EfficiencyModel.Learned, nowMs: Long, demo: Boolean, pt: Powertrain,
              todayTempC: Double?, todayRainy: Boolean?, onOpen: (String) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val sorted = remember(records) { records.sortedByDescending { it.startMs } }
    val trips = remember(records) { EfficiencyModel.trips(records) }
    val acc = remember(records) { EfficiencyModel.accuracy(records) }
    val totalKm = sorted.mapNotNull { it.km() }.filter { it > 0 }.sum()
    val trust = when {
        acc != null && acc.checked >= 5 && acc.meanAbsError <= 2.5 -> "신뢰도 높음" to DdGreen
        acc != null && acc.checked >= 3 -> "신뢰도 보통" to DdAmber
        else -> "예측 학습 중" to DdMuted
    }

    // ── title, tabs, session chips
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(5.dp).height(34.dp).background(DdOrange)); Spacer(Modifier.width(12.dp))
        Text("02", color = DdOrange, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text("  DRIVE · Pit Wall Data", color = DdInk, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(18.dp))
        Row(Modifier.clip(RoundedCornerShape(24.dp)).background(DdRow).border(1.dp, DdLine, RoundedCornerShape(24.dp)).padding(4.dp)) {
            listOf("분석", "주행 기록").forEachIndexed { i, l ->
                Text(l, color = if (tab == i) Color(0xFF071015) else DdInk, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (tab == i) DdOrange else Color.Transparent).clickable { tab = i }
                        .padding(horizontal = 20.dp, vertical = 8.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        if (demo) { Chip("예시 기록", DdOrange); Spacer(Modifier.width(8.dp)) }
        Chip("주행 ${sorted.size}회"); Spacer(Modifier.width(8.dp))
        Chip("${f0(totalKm)} km"); Spacer(Modifier.width(8.dp))
        Chip(trust.first, trust.second)
    }

    // Every tab uses the same body height, so switching tabs never moves the page.
    Column(Modifier.fillMaxWidth().height(BODY_H), verticalArrangement = Arrangement.spacedBy(12.dp)) { DriveDataBody(tab, { tab = it }, sorted, trips, acc, learned, nowMs, pt, todayTempC, todayRainy, records, demo, onOpen) }
}

private val BODY_H = 620.dp

@Composable
private fun ColumnScope.DriveDataBody(tab: Int, setTab: (Int) -> Unit, sorted: List<DriveRecord>, trips: List<EfficiencyModel.Trip>, acc: EfficiencyModel.Accuracy?,
                                      learned: EfficiencyModel.Learned, nowMs: Long, pt: Powertrain, todayTempC: Double?, todayRainy: Boolean?,
                                      records: List<DriveRecord>, demo: Boolean, onOpen: (String) -> Unit) {
    if (sorted.isEmpty()) {
        DdCard("아직 기록된 주행이 없어요", null, Modifier.fillMaxWidth().fillMaxHeight()) {
            Text("기어를 D로 놓고 1분 넘게 달린 뒤 P로 돌아오면 이 차 안에 저장돼요.\n쌓인 기록으로 조건별 효율, 예측 정확도, 요일 패턴을 보여 드려요.", color = DdMuted, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            Text("출발 전 확인은 GARAGE  ›", color = DdCyan, fontSize = 18.sp, modifier = Modifier.clickable { onOpen("GARAGE") })
        }
        return
    }

    if (tab == 1) { TimingTower(sorted, trips, nowMs, pt); return }

    // ── last stint
    val last = sorted.first()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DdRow).border(1.dp, DdLine, RoundedCornerShape(12.dp)).clickable { setTab(1) }
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Text("지난 주행", color = DdInk, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF1C2A33)).padding(horizontal = 12.dp, vertical = 4.dp))
        Text(dayLabel(last.startMs, nowMs) + condLabel(last).let { if (it.isBlank()) "" else " · $it" }, color = DdInk, fontSize = 20.sp, modifier = Modifier.weight(1f))
        last.km()?.let { Text("${f1(it)} km", color = DdInk, fontSize = 20.sp) }
        if (last.startSoc != null && last.endSoc != null) Text("${f0(last.startSoc)} → ${f0(last.endSoc)}%", color = DdInk, fontSize = 20.sp)
        last.predictedArrival?.let { p -> Text("예측 ${f0(p)}% · 실제 ${last.endSoc?.let { f0(it) } ?: "—"}%", color = DdMuted, fontSize = 19.sp) }
        DeltaChip(last.delta(), false)
    }

    // ── charts: two rows of two
    Row(Modifier.fillMaxWidth().height(270.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        DdCard("${pt.energy} 사용", "최근 8회 · 막대 = 출발 → 도착 잔량", Modifier.weight(1.1f).fillMaxHeight()) {
            val rows = sorted.filter { it.startSoc != null && it.endSoc != null }.take(8).reversed()
            val tm = androidx.compose.ui.text.rememberTextMeasurer()
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.width(54.dp).fillMaxHeight().padding(bottom = 20.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                    rows.forEach { r ->
                        // charged before this drive: it started higher than the previous one ended (charging happens parked)
                        val prev = sorted.firstOrNull { it.startMs < r.startMs && it.endSoc != null }
                        val charged = prev != null && r.startSoc != null && r.startSoc - prev.endSoc!! >= 3
                        Text(Instant.ofEpochMilli(r.startMs).atZone(KST).format(DateTimeFormatter.ofPattern("MM/dd")) + if (charged) " ⚡" else "",
                            color = if (charged) DdBlue else DdMuted, fontSize = 14.sp) }
                }
                Canvas(Modifier.weight(1f).fillMaxHeight()) {
                    val axisH = 20.dp.toPx(); val h = size.height - axisH; val labelW = 64.dp.toPx(); val w = size.width - labelW
                    val rowH = h / max(rows.size, 1); fun x(p: Double) = (p.coerceIn(0.0, 100.0) / 100.0 * w).toFloat()
                    val axisStyle = androidx.compose.ui.text.TextStyle(color = DdMuted, fontSize = 12.sp)
                    listOf(0.0, 20.0, 50.0, 100.0).forEach { p ->
                        if (p != 20.0) drawLine(DdLine, Offset(x(p), 0f), Offset(x(p), h), 1f)
                        val t = tm.measure("${p.toInt()}%", if (p == 20.0) axisStyle.copy(color = DdRed) else axisStyle)
                        drawText(t, topLeft = Offset((x(p) - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), h + 3.dp.toPx()))
                    }
                    drawLine(DdRed, Offset(x(20.0), 0f), Offset(x(20.0), h), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                    val valStyle = androidx.compose.ui.text.TextStyle(color = DdInk, fontSize = 13.sp)
                    rows.forEachIndexed { i, r ->
                        val a = r.startSoc!!; val b = r.endSoc!!
                        val cold = (r.tempC ?: 10.0) < 5.0
                        val c = if (r.oddRise()) DdGrey else if (r.rainy == true || cold) DdAmber else DdTeal
                        val y = rowH * i + rowH * .5f; val bh = rowH * .5f
                        drawRoundRect(c, Offset(x(minOf(a, b)), y - bh / 2), androidx.compose.ui.geometry.Size(max(x(maxOf(a, b)) - x(minOf(a, b)), 4f), bh),
                            androidx.compose.ui.geometry.CornerRadius(bh / 2))
                        // the numbers next to the bar's right end: start → arrival
                        val t = tm.measure("${f0(a)}→${f0(b)}%", valStyle)
                        drawText(t, topLeft = Offset(x(maxOf(a, b)) + 6.dp.toPx(), y - t.size.height / 2f))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(DdTeal to "맑은 날", DdAmber to "비·저온", DdGrey to "잔량 기록 이상").forEach { (c, l) ->
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(c)); Text(l, color = DdMuted, fontSize = 13.sp)
                }
                Text("┆ 20% 권장선", color = DdRed, fontSize = 13.sp)
            }
        }
        DdCard("Prediction Delta", "예측 − 실제 (%p)", Modifier.weight(1f).fillMaxHeight()) {
            val errs = trips.filter { it.arrivalError != null }.sortedBy { it.conditions.atMs }.map { it.arrivalError!! }.takeLast(30)
            if (errs.isEmpty()) {
                Text("목적지를 넣고 끝까지 달린 주행이 쌓이면 예측과 실제를 비교해요.", color = DdMuted, fontSize = 18.sp)
            } else {
                val lim = max(5.0, errs.maxOf { abs(it) } + 1)
                Canvas(Modifier.fillMaxWidth().weight(1f)) {
                    val w = size.width; val h = size.height
                    fun y(v: Double) = (h / 2 - v / lim * (h / 2)).toFloat()
                    drawRect(DdTeal.copy(alpha = .14f), Offset(0f, y(2.0)), androidx.compose.ui.geometry.Size(w, y(-2.0) - y(2.0)))
                    drawLine(DdMuted.copy(alpha = .6f), Offset(0f, y(0.0)), Offset(w, y(0.0)), 1.5f)
                    val step = if (errs.size > 1) w / (errs.size - 1) else 0f
                    val p = Path(); errs.forEachIndexed { i, e -> val px = step * i; if (i == 0) p.moveTo(px, y(e)) else p.lineTo(px, y(e)) }
                    drawPath(p, DdPurple, style = Stroke(4f))
                    drawCircle(DdPurple, 8f, Offset(step * (errs.size - 1), y(errs.last())))
                }
                Row { Text("1회", color = DdMuted, fontSize = 13.sp); Spacer(Modifier.weight(1f)); Text("띠 = ±2%p 이내 · 위아래 ±${f0(lim)}", color = DdMuted, fontSize = 13.sp); Spacer(Modifier.weight(1f)); Text("${errs.size}회", color = DdMuted, fontSize = 13.sp) }
                val half = errs.size / 2
                val trend = if (errs.size >= 6) errs.drop(half).map { abs(it) }.average() - errs.take(half).map { abs(it) }.average() else null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("평균 ${f1(acc?.meanAbsError ?: errs.map { abs(it) }.average())}%p", color = DdInk, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    when {
                        trend == null -> Text("기록이 더 쌓이면 추세를 보여 줘요", color = DdMuted, fontSize = 16.sp)
                        trend < -0.3 -> Text("↓ 줄어드는 중", color = DdGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        trend > 0.3 -> Text("↑ 커지는 중", color = DdAmber, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        else -> Text("→ 안정적", color = DdCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().height(232.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        DdCard("Conditions", "조건별 효율 (평소 대비)", Modifier.weight(1f).fillMaxHeight()) {
            val overall = trips.map { it.kmPerPercent }.average().takeIf { !it.isNaN() }
            val bands = listOf(EfficiencyModel.TempBand.COLD to "5℃ 미만", EfficiencyModel.TempBand.MILD to "보통", EfficiencyModel.TempBand.HOT to "25℃ 초과")
            val todayBand = EfficiencyModel.tempBand(todayTempC)
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(70.dp))
                bands.forEach { (_, l) -> Text(l, color = DdMuted, fontSize = 15.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            }
            listOf(false to "맑음", true to "비").forEach { (rain, label) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(label, color = DdInk, fontSize = 18.sp, modifier = Modifier.width(64.dp))
                    bands.forEach { (b, _) ->
                        val cell = trips.filter { EfficiencyModel.tempBand(it.conditions.tempC) == b && it.conditions.rainy == rain }
                        val v = if (overall != null && cell.size >= 2) cell.map { it.kmPerPercent }.average() / overall - 1 else null
                        val today = todayBand == b && todayRainy == rain
                        val (bg, fg) = when {
                            v == null -> DdRow to DdMuted
                            abs(v) < .02 -> DdGreen.copy(alpha = .16f) to DdGreen
                            v < -.08 -> DdRed.copy(alpha = .2f) to Color(0xFFFF8A80)
                            v < 0 -> DdAmber.copy(alpha = .18f) to DdAmber
                            else -> DdGreen.copy(alpha = .16f) to DdGreen
                        }
                        Text(v?.let { if (abs(it) < .02) "평소" else (if (it > 0) "+" else "−") + f0(abs(it) * 100) + "%" } ?: "기록 없음", color = fg, fontSize = if (v == null) 15.sp else 20.sp,
                            fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(bg)
                                .then(if (today) Modifier.border(2.dp, DdOrange, RoundedCornerShape(8.dp)) else Modifier).padding(vertical = 10.dp))
                    }
                }
            }
            // The grid mixes conditions (cold days are often rainy too); the engine separates each one.
            if (learned.factors.isNotEmpty()) Text("엔진이 따로 떼어 본 영향  " + learned.factors.joinToString(" · ") { "${it.factor.label} ${if (it.effect < 0) "−" else "+"}${f0(abs(it.effect) * 100)}%" },
                color = DdCyan, fontSize = 14.sp, maxLines = 1)
            else Text("주황 테두리 = 오늘 조건 · 칸마다 2회 이상 달린 기록만", color = DdMuted, fontSize = 13.sp)
        }
        DdCard("Weekly Pattern", "요일별 평균 km", Modifier.weight(1f).fillMaxHeight()) {
            val pattern = remember(records, nowMs) { PitForecastEngine.weekdayKm(records, nowMs)?.takeIf { p -> p.values.all { it <= PitForecastEngine.MAX_DAILY_KM } } }
            val today = Instant.ofEpochMilli(nowMs).atZone(KST).dayOfWeek
            if (pattern == null) Text("2주 넘게 기록이 쌓이면 요일별로 얼마나 달리는지 보여 드려요.", color = DdMuted, fontSize = 18.sp)
            else {
                val top = max(pattern.values.maxOrNull() ?: 1.0, 1.0)
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    DayOfWeek.values().forEach { d ->
                        val v = pattern[d] ?: 0.0
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(f0(v), color = if (d == today) DdInk else DdMuted, fontSize = 14.sp)
                            Box(Modifier.fillMaxWidth().fillMaxHeight((v / top).toFloat().coerceIn(.04f, .82f)).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (d == today) DdPurple else DdPurple.copy(alpha = .45f)))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DayOfWeek.values().forEach { d -> Text(d.getDisplayName(TextStyle.SHORT, Locale.KOREAN), color = if (d == today) DdInk else DdMuted, fontSize = 15.sp,
                        fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                }
            }
        }
    }
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text((if (demo) "예시 기록이에요. 실제 주행이 쌓이면 바뀌어요 · " else "") + (if (learned.trips > 0) "${learned.stage} · 학습에 쓴 주행 ${learned.trips}회" + (if (learned.dropped > 0) " · 이상한 기록 ${learned.dropped}건 제외" else "") else "학습할 주행이 아직 없어요"), color = DdMuted, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Text("출발 전 확인은 GARAGE  ›", color = DdCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onOpen("GARAGE") }.padding(vertical = 4.dp))
    }
}

/** 주행 기록 (a timing tower): every drive as a row (Δ coloured like sector times), and the selected drive in detail. */
@Composable
private fun TimingTower(sorted: List<DriveRecord>, trips: List<EfficiencyModel.Trip>, nowMs: Long, pt: Powertrain) {
    var sel by remember(sorted) { mutableStateOf(0) }
    val best = sorted.mapNotNull { it.delta() }.minByOrNull { abs(it) }
    val overall = trips.map { it.kmPerPercent }.average().takeIf { !it.isNaN() }
    Row(Modifier.fillMaxWidth().fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1.15f).fillMaxHeight().clip(DdShape).background(Color(0xFF0B161D)).border(1.dp, DdLine, DdShape)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                listOf("#" to .5f, "날짜 · 조건" to 2.6f, "거리" to 1f, "잔량" to 1.3f, "Δ" to 1f).forEach { (l, w) -> Text(l, color = DdMuted, fontSize = 14.sp, modifier = Modifier.weight(w)) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(DdLine))
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                sorted.forEachIndexed { i, r ->
                    val d = r.delta(); val charged = r.oddRise()
                    Row(Modifier.fillMaxWidth().background(if (i == sel) Color(0xFF16252E) else Color.Transparent).clickable { sel = i }
                        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(4.dp).height(24.dp).background(if (i == sel) DdOrange else Color.Transparent)); Spacer(Modifier.width(6.dp))
                        Text("${sorted.size - i}", color = DdMuted, fontSize = 16.sp, modifier = Modifier.weight(.45f))
                        Text(dayLabel(r.startMs, nowMs) + condLabel(r).let { if (it.isBlank()) "" else " · $it" }, color = DdInk, fontSize = 17.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2.6f))
                        Text(r.km()?.let { f1(it) } ?: "—", color = DdInk, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Text(if (r.startSoc != null && r.endSoc != null) "${f0(r.startSoc)}→${f0(r.endSoc)}" else "—", color = if (charged) DdGrey else DdInk, fontSize = 17.sp, modifier = Modifier.weight(1.3f))
                        Box(Modifier.weight(1f)) { DeltaChip(d, d != null && d == best, 15) }
                    }
                }
            }
        }
        val r = sorted.getOrNull(sel) ?: return@Row
        val km = r.km(); val used = if (r.startSoc != null && r.endSoc != null) r.startSoc - r.endSoc else null
        val kpp = if (km != null && used != null && used > 0) km / used else null
        Column(Modifier.weight(1f).fillMaxHeight().clip(DdShape).background(Brush.verticalGradient(listOf(Color(0xFF0E1A21), Color(0xFF09131A)))).border(1.dp, DdLine, DdShape)
            .padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val start = Instant.ofEpochMilli(r.startMs).atZone(KST); val end = Instant.ofEpochMilli(r.endMs).atZone(KST)
            Text("주행 #${sorted.size - sel} · " + start.format(DateTimeFormatter.ofPattern("MM/dd (E) HH:mm", Locale.KOREAN)) + "–" + end.format(DateTimeFormatter.ofPattern("HH:mm")),
                color = DdMuted, fontSize = 17.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.predictedArrival?.let { "예측 ${f0(it)}% · 실제 ${r.endSoc?.let { e -> f0(e) } ?: "—"}%" } ?: "예측 없이 달린 주행", color = DdInk, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp)); DeltaChip(r.delta(), r.delta() != null && r.delta() == best, 20)
            }
            @Composable fun Cell(label: String, value: String, mod: Modifier, accent: Color = DdInk) =
                Column(mod.clip(RoundedCornerShape(10.dp)).background(DdRow).padding(12.dp)) { Text(label, color = DdMuted, fontSize = 14.sp); Text(value, color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cell("거리 · 시간", (km?.let { "${f1(it)} km" } ?: "—") + " · ${(r.endMs - r.startMs) / 60_000}분", Modifier.weight(1f))
                Cell("${pt.energy} 잔량", if (r.startSoc != null && r.endSoc != null) "${f0(r.startSoc)} → ${f0(r.endSoc)}%" else "—", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val vs = if (kpp != null && overall != null) kpp / overall - 1 else null
                Cell("효율", (kpp?.let { "${f1(it)} km/%" } ?: "중간 충전 · 계산 안 함") + (vs?.let { "  " + (if (it < 0) "−" else "+") + f0(abs(it) * 100) + "%" } ?: ""),
                    Modifier.weight(1f), if (vs != null && vs < -0.02) DdAmber else DdInk)
                Cell("조건", condLabel(r).ifBlank { "기록 없음" } + (if (start.hour >= 20 || start.hour < 6) " · 야간" else ""), Modifier.weight(1f))
            }
            if (r.startSoc != null && r.endSoc != null) {
                Text("잔량 흐름", color = DdMuted, fontSize = 14.sp)
                Canvas(Modifier.fillMaxWidth().height(34.dp)) {
                    val w = size.width; fun x(p: Double) = (p.coerceIn(0.0, 100.0) / 100 * w).toFloat()
                    drawRoundRect(DdLine, Offset(0f, 10f), androidx.compose.ui.geometry.Size(w, 16f), androidx.compose.ui.geometry.CornerRadius(8f))
                    val a = minOf(r.startSoc, r.endSoc); val b = maxOf(r.startSoc, r.endSoc)
                    drawRoundRect(if (r.oddRise()) DdGrey else DdAmber, Offset(x(a), 10f), androidx.compose.ui.geometry.Size(max(x(b) - x(a), 4f), 16f), androidx.compose.ui.geometry.CornerRadius(8f))
                    drawLine(DdRed, Offset(x(20.0), 2f), Offset(x(20.0), 34f), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
                }
            }
            Text("TEAM RADIO", color = DdCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(if (r.boxCalls > 0) "Box 콜 ${r.boxCalls}회 · 수락 ${r.boxAccepted}회" else "이 주행에서는 Pit Wall이 부를 일이 없었어요", color = DdInk, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Text(when {
                kpp == null -> if (r.oddRise()) "주행 중 잔량이 크게 올라 기록이 이상해요. 학습에는 쓰지 않았어요" else "짧은 주행이라 학습에는 쓰지 않았어요"
                r.delta() != null -> "학습 반영됨 · 이 주행의 오차가 다음 예측 범위에 들어가요"
                else -> "학습 반영됨 · 효율만 쓰고, 목적지가 없어 예측 비교는 없어요"
            }, color = DdMuted, fontSize = 15.sp)
        }
    }
}

/**
 * Right after a drive ends (D → P): the report for that drive — prediction against the actual arrival, distance, time,
 * charge used, efficiency against this car's usual, conditions, Box calls, and what the engine took from it.
 */
@Composable
fun DriveDone(records: List<DriveRecord>, nowMs: Long, pt: Powertrain, onOpen: (String) -> Unit, flow: SpeedFlow? = null, carKpp: Double? = null) {
    val last = records.maxByOrNull { it.startMs }?.takeIf { nowMs - it.endMs < 15 * 60_000 }
    val trips = remember(records) { EfficiencyModel.trips(records) }
    val acc = remember(records) { EfficiencyModel.accuracy(records) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(5.dp).height(34.dp).background(DdOrange)); Spacer(Modifier.width(12.dp))
        Text("02", color = DdOrange, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("  DRIVE · 주행 리포트", color = DdInk, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (last != null) Chip("방금 끝난 주행 · " + Instant.ofEpochMilli(last.startMs).atZone(KST).format(DateTimeFormatter.ofPattern("HH:mm")) + "–" +
            Instant.ofEpochMilli(last.endMs).atZone(KST).format(DateTimeFormatter.ofPattern("HH:mm")), DdOrange)
    }
    Column(Modifier.fillMaxWidth().height(BODY_H), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (last == null) {
            DdCard("이번 주행은 기록되지 않았어요", null, Modifier.fillMaxWidth().fillMaxHeight()) {
                Text("기어 D로 1분 넘게 달려야 기록돼요. 지금까지의 주행은 분석 화면에서 볼 수 있어요.", color = DdMuted, fontSize = 20.sp)
                Spacer(Modifier.weight(1f))
                Text("내 차 분석 보기  ›", color = DdCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onOpen("DRIVE") })
            }
            return@Column
        }
        val km = last.km(); val d = last.delta()
        val used = if (last.startSoc != null && last.endSoc != null) last.startSoc - last.endSoc else null
        val kpp = if (km != null && used != null && used > 0) km / used else null
        // "Usual" = this car's other drives (not this one); with fewer than 2 of them there is nothing to compare.
        val others = trips.filter { it.conditions.atMs != last.startMs }
        val overall = if (others.size >= 2) others.map { it.kmPerPercent }.average() else null
        val vs = if (kpp != null && overall != null) kpp / overall - 1 else null
        val refKpp = overall ?: carKpp
        val expectedUse = if (km != null && refKpp != null && refKpp > 0) km / refKpp else null
        // ── result
        Row(Modifier.fillMaxWidth().height(200.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DdCard("예측 vs 실제", "도착 ${pt.energy}", Modifier.weight(1.1f).fillMaxHeight()) {
                if (last.predictedArrival != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("예측 ${f0(last.predictedArrival)}% · 실제 ${last.endSoc?.let { f0(it) } ?: "—"}%", color = DdInk, fontSize = 38.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.width(14.dp)); DeltaChip(d, false, 24)
                    }
                    Text(when {
                        d == null -> "경로를 많이 벗어나서 예측 비교에서는 뺐어요"
                        abs(d) <= 2 -> "예측이 맞았어요. 오차 ${f1(abs(d))}%p"
                        d > 0 -> "예측보다 ${f1(d)}%p 덜 남았어요. 다음 예측에 반영해요"
                        else -> "예측보다 ${f1(-d)}%p 더 남았어요. 다음 예측에 반영해요"
                    }, color = DdMuted, fontSize = 19.sp)
                } else {
                    Text("${last.startSoc?.let { f0(it) } ?: "—"}% → ${last.endSoc?.let { f0(it) } ?: "—"}%", color = DdInk, fontSize = 40.sp, fontWeight = FontWeight.Black)
                    Text("목적지 없이 달려서 예측 비교는 없어요", color = DdMuted, fontSize = 19.sp)
                }
                Spacer(Modifier.weight(1f))
                acc?.let { Text("지금까지 예측 오차 평균 ${f1(it.meanAbsError)}%p · ${it.checked}회", color = DdCyan, fontSize = 17.sp) }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                @Composable fun Tile(label: String, value: String, mod: Modifier, accent: Color = DdInk) =
                    Column(mod.clip(RoundedCornerShape(12.dp)).background(DdRow).border(1.dp, DdLine, RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.Center) {
                        Text(label, color = DdMuted, fontSize = 15.sp); Text(value, color = accent, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile("거리 · 시간", (km?.let { "${f1(it)} km" } ?: "—") + " · ${(last.endMs - last.startMs) / 60_000}분", Modifier.weight(1f).fillMaxHeight())
                    Tile("${pt.energy} 사용", used?.let { if (it >= 0) "−${f0(it)}%" else if (-it <= 3) "+${f0(-it)}% (회생제동)" else "기록 확인 필요" } ?: "—", Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile("효율", (kpp?.let { "${f1(it)} km/%" } ?: "계산 안 함") + (vs?.let { "  " + (if (it < 0) "−" else "+") + f0(abs(it) * 100) + "%" } ?: ""),
                        Modifier.weight(1f).fillMaxHeight(), if (vs != null && vs < -0.02) DdAmber else DdInk)
                    Tile("조건", condLabel(last).ifBlank { "기록 없음" }, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        // ── energy, radio, learning
        Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DdCard("${pt.energy} 흐름", "출발 → 도착", Modifier.weight(1.1f).fillMaxHeight()) {
                if (last.startSoc != null && last.endSoc != null) {
                    val tm = androidx.compose.ui.text.rememberTextMeasurer()
                    Canvas(Modifier.fillMaxWidth().weight(1f)) {
                        val w = size.width; val barY = size.height * .4f; val bh = size.height * .2f
                        fun x(p: Double) = (p.coerceIn(0.0, 100.0) / 100 * w).toFloat()
                        drawRoundRect(DdLine, Offset(0f, barY), androidx.compose.ui.geometry.Size(w, bh), androidx.compose.ui.geometry.CornerRadius(bh / 2))
                        val a = minOf(last.startSoc, last.endSoc); val b = maxOf(last.startSoc, last.endSoc)
                        drawRoundRect(if (last.oddRise()) DdGrey else DdAmber, Offset(x(a), barY), androidx.compose.ui.geometry.Size(max(x(b) - x(a), 6f), bh),
                            androidx.compose.ui.geometry.CornerRadius(bh / 2))
                        drawLine(DdRed, Offset(x(20.0), barY - 10f), Offset(x(20.0), barY + bh + 10f), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 5f)))
                        val st = androidx.compose.ui.text.TextStyle(color = DdInk, fontSize = 15.sp)
                        // start above the bar, arrival below it, so close values never overlap
                        val ts = tm.measure("출발 ${f0(last.startSoc)}%", st)
                        drawText(ts, topLeft = Offset((x(last.startSoc) - ts.size.width / 2f).coerceIn(0f, w - ts.size.width), barY - ts.size.height - 6f))
                        val te = tm.measure("도착 ${f0(last.endSoc)}%", st.copy(color = DdAmber))
                        drawText(te, topLeft = Offset((x(last.endSoc) - te.size.width / 2f).coerceIn(0f, w - te.size.width), barY + bh + 6f))
                        val t20 = tm.measure("20%", st.copy(color = DdRed, fontSize = 12.sp))
                        drawText(t20, topLeft = Offset((x(20.0) - t20.size.width / 2f).coerceIn(0f, w), barY + bh + 6f))
                    }
                }
            }
            DdCard("TEAM RADIO", null, Modifier.weight(.8f).fillMaxHeight()) {
                Text(if (last.boxCalls > 0) "Box 콜 ${last.boxCalls}회" else "Box 콜 없음", color = DdInk, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(if (last.boxCalls > 0) "수락 ${last.boxAccepted}회 · 거절·무응답 ${last.boxCalls - last.boxAccepted}회" else "이번 주행에서는 Pit Wall이 부를 일이 없었어요", color = DdMuted, fontSize = 17.sp)
            }
            DdCard("학습", "Pit Wall이 배운 것", Modifier.weight(1f).fillMaxHeight()) {
                Text(when {
                    kpp == null -> if (last.oddRise()) "주행 중 잔량이 크게 올라 기록이 이상해요. 학습에는 쓰지 않았어요" else "짧은 주행이거나 잔량을 거의 안 써서 학습에는 쓰지 않았어요"
                    d != null -> "효율과 예측 오차 모두 반영했어요. 다음 예측 범위가 더 정확해져요"
                    else -> "효율을 반영했어요. 목적지를 넣고 달리면 예측 정확도도 쌓여요"
                }, color = DdInk, fontSize = 18.sp)
                Text("학습에 쓴 주행 ${trips.size}회", color = DdMuted, fontSize = 15.sp)
            }
        }
        // ── analysis of this drive
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DdCard("예상 vs 실제 사용", if (overall != null) "평소 효율 기준" else "차량 효율 기준", Modifier.weight(1f).fillMaxHeight()) {
                if (expectedUse != null && used != null && used >= 0) {
                    val diff = expectedUse - used
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("예상 −${f1(expectedUse)}%", color = DdMuted, fontSize = 20.sp); Spacer(Modifier.width(12.dp))
                        Text("실제 −${f0(used)}%", color = DdInk, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(when { diff >= 0.5 -> "예상보다 ${f1(diff)}%p 덜 썼어요"; diff <= -0.5 -> "예상보다 ${f1(-diff)}%p 더 썼어요"; else -> "예상대로 썼어요" },
                        color = if (diff <= -0.5) DdAmber else DdGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("잔량이 1% 단위라 짧은 주행은 차이가 크게 보일 수 있어요", color = DdMuted, fontSize = 13.sp)
                } else Text("거리와 잔량이 모두 있어야 비교할 수 있어요", color = DdMuted, fontSize = 17.sp)
            }
            DdCard("주행 흐름", "속도 기준", Modifier.weight(1f).fillMaxHeight()) {
                val f = flow
                if (f != null && f.samples > 5) {
                    val speedNote = when {
                        (f.averageKmh ?: 0.0) > 180 || (f.maxKmh ?: 0.0) > 250 -> "속도 값이 비정상이에요. 차량 신호를 다시 확인하고 있어요"
                        f.observedSeconds < 120 -> "속도 기록이 ${f.observedSeconds.toInt()}초뿐이라 평균이 정확하지 않을 수 있어요"
                        else -> null
                    }
                    speedNote?.let { Text("ⓘ $it", color = DdAmber, fontSize = 13.sp, maxLines = 1) }
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        Column { Text("평균", color = DdMuted, fontSize = 14.sp); Text(f.averageKmh?.let { "${f0(it)} km/h" } ?: "—", color = DdInk, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                        Column { Text("최고", color = DdMuted, fontSize = 14.sp); Text(f.maxKmh?.let { "${f0(it)} km/h" } ?: "—", color = DdInk, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                        Column { Text("급가속 · 급감속", color = DdMuted, fontSize = 14.sp); Text("${f.accelerationEvents} · ${f.decelerationEvents}회", color = DdInk, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                    }
                    val n = f.trace.size.coerceAtLeast(1)
                    val up = f.trace.count { it > 0.5f } / n.toFloat(); val down = f.trace.count { it < -0.5f } / n.toFloat()
                    Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
                        if (up > 0) Box(Modifier.weight(up).fillMaxHeight().background(DdAmber))
                        if (1 - up - down > 0) Box(Modifier.weight(1 - up - down).fillMaxHeight().background(DdTeal))
                        if (down > 0) Box(Modifier.weight(down).fillMaxHeight().background(DdCyan))
                    }
                    Text("가속 ${(up * 100).toInt()}% · 일정 ${((1 - up - down) * 100).toInt()}% · 감속 ${(down * 100).toInt()}%", color = DdMuted, fontSize = 14.sp)
                } else Text("이번 주행의 속도 신호가 부족해요", color = DdMuted, fontSize = 17.sp)
            }
            DdCard("남은 ${pt.energy}로", "이번 효율이면", Modifier.weight(1f).fillMaxHeight()) {
                val end = last.endSoc
                if (end != null && kpp != null) {
                    Text("약 ${f0(end * kpp)} km", color = DdInk, fontSize = 28.sp, fontWeight = FontWeight.Black)
                    Text("20%까지는 약 ${f0(((end - 20).coerceAtLeast(0.0)) * kpp)} km", color = DdMuted, fontSize = 17.sp)
                    refKpp?.let { Text((if (overall != null) "평소 효율" else "차량 효율") + "이면 약 ${f0(end * it)} km", color = DdCyan, fontSize = 16.sp) }
                } else Text("효율을 계산하지 못했어요", color = DdMuted, fontSize = 17.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("내 차 분석 보기  ›", color = DdOrange, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onOpen("DRIVE") }.padding(vertical = 6.dp))
            Text("GARAGE  ›", color = DdCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onOpen("GARAGE") }.padding(vertical = 6.dp))
        }
    }
}

// ───────────────────────────── PITSTOP · A 충전/주유 · B 정비 · C 세차 ─────────────────────────────

/** The last charge (or refuel) seen between saved drives, and how the car has done since. */
private data class LastCharge(val atMs: Long, val fromPct: Double, val toPct: Double, val kmSince: Double, val usedSince: Double?)

private fun lastCharge(records: List<DriveRecord>): LastCharge? {
    val r = records.filter { it.startSoc != null && it.endSoc != null }.sortedBy { it.startMs }
    for (i in r.indices.reversed()) {
        if (i == 0) break
        val before = r[i - 1].endSoc!!; val after = r[i].startSoc!!
        if (after - before >= 3) {
            val since = r.drop(i)
            val km = since.mapNotNull { it.km() }.filter { it > 0 }.sum()
            return LastCharge(r[i].startMs, before, after, km, after - since.last().endSoc!!)
        }
    }
    return null
}

/**
 * PITSTOP: what needs doing now (one line of chips), then three tabs — A energy, B maintenance, C wash — each in a
 * fixed-height body so switching never moves the page. A link to PIT FINDER for picking a place directly.
 */
@Composable
fun PitstopTabs(plan: PitPlan, forecast: PitForecast, records: List<DriveRecord>, example: Boolean, nowMs: Long, check: CrewCheck,
                washState: WashGuardState, onWashMode: () -> Unit, onWashRefresh: () -> Unit, lastWash: Long, onWashCompleted: () -> Unit,
                service: PitServiceUi?, onOpen: (String) -> Unit, initialTab: Int = 0) {
    val pt = plan.powertrain
    var tab by remember(initialTab) { mutableStateOf(initialTab) }
    var faultSel by remember(check.cards.map { it.id }) { mutableStateOf(check.cards.firstOrNull()?.id) }
    var washDone by remember { mutableStateOf(false) }
    if (washDone) androidx.compose.material3.AlertDialog(onDismissRequest = { washDone = false }, title = { Text("세차 완료 기록") },
        text = { Text("오늘 세차를 마친 것으로 기록할까요? 다음 세차 판단과 강수 비교의 기준이 돼요.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { washDone = false; onWashCompleted() }) { Text("기록") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { washDone = false }) { Text("취소") } })

    // ── title, tabs, finder link
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(5.dp).height(34.dp).background(DdOrange)); Spacer(Modifier.width(12.dp))
        Text("05", color = DdOrange, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("  " + listOf("${pt.refuel} 관리", "정비 · 점검", "세차 관리")[tab], color = DdInk, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(18.dp))
        Row(Modifier.clip(RoundedCornerShape(24.dp)).background(DdRow).border(1.dp, DdLine, RoundedCornerShape(24.dp)).padding(4.dp)) {
            listOf("A ${pt.refuel}", "B 정비", "C 세차").forEachIndexed { i, l ->
                val warn = i == 1 && !check.allClear
                Text(l + if (warn) " !" else "", color = if (tab == i) Color(0xFF071015) else if (warn) DdRed else DdInk, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (tab == i) DdOrange else Color.Transparent).clickable { tab = i }
                        .padding(horizontal = 18.dp, vertical = 8.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Text("‹ PITSTOP", color = DdInk, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, DdLine, RoundedCornerShape(20.dp)).clickable { onOpen("PITSTOP") }
                .padding(horizontal = 16.dp, vertical = 8.dp))
        Spacer(Modifier.width(8.dp))
        Text("직접 장소 찾기  ›", color = DdCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, DdCyan.copy(alpha = .5f), RoundedCornerShape(20.dp)).clickable { onOpen("FINDER") }
                .padding(horizontal = 16.dp, vertical = 8.dp))
    }

    // (the overview shows what needs doing; the detail pages go straight to their content)
    if (false) {
    // ── what needs doing now: up to three chips, each opens its tab
    data class Todo(val text: String, val color: Color, val tab: Int)
    val todos = buildList {
        check.cards.sortedBy { it.level.rank }.take(2).forEach { add(Todo("${it.level.label} · ${it.title}", levelColor(it.level), 1)) }
        when {
            plan.verdict == PitVerdict.PIT_REQUIRED -> add(Todo("가는 길에 꼭 ${pt.refuel}", DdRed, 0))
            plan.verdict == PitVerdict.PIT_ADVISED -> add(Todo("가는 길에 ${pt.refuel} 1번 권장", DdAmber, 0))
            forecast.charge.status == ChargeStatus.NOW -> add(Todo("오늘 ${pt.refuel}하면 좋아요", DdAmber, 0))
        }
        forecast.wash.recommendedIndex?.let { i -> add(Todo(if (i == 0) "오늘 세차하기 좋아요" else "세차는 ${dayWord(i, forecast.wash.days.getOrNull(i)?.date)} 추천", DdCyan, 2)) }
    }.take(3)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DdRow).border(1.dp, DdLine, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("지금 필요한 작업", color = DdInk, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF1C2A33)).padding(horizontal = 12.dp, vertical = 4.dp))
        if (todos.isEmpty()) Text("지금 할 일은 없어요. 출발해도 좋아요", color = DdGreen, fontSize = 19.sp)
        todos.forEach { t ->
            Text(t.text + "  ›", color = t.color, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).border(1.dp, t.color.copy(alpha = .5f), RoundedCornerShape(18.dp)).clickable { tab = t.tab }
                    .padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
    }
    service?.prompt?.let { v -> VisitConfirmCard(v, onYes = { service.onPromptAnswer(true) }, onNo = { service.onPromptAnswer(false) }, onLater = { service.onPromptAnswer(null) }, modifier = Modifier.fillMaxWidth()) }
    service?.followUp?.let { f -> FollowUpCard(f, service.onFollowUp, Modifier.fillMaxWidth()) }

    Column(Modifier.fillMaxWidth().height(640.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (tab) {
            0 -> PitEnergyTab(plan, forecast, records, example, nowMs, onOpen)
            1 -> PitCareTab(check, onOpen)
            else -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val w = forecast.wash
                Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // ── the answer and the four days
                    val ok = plan.wash.verdict == WashVerdict.OK
                    val title = w.recommendedIndex?.let { i -> if (i == 0) "오늘 세차하기 좋아요" else "${dayWord(i, w.days.getOrNull(i)?.date)} 세차를 추천해요" }
                        ?: if (w.days.isEmpty()) "예보를 기다리는 중이에요" else "4일 안에는 맑은 날이 없어요"
                    val tc = if (w.recommendedIndex != null) DdCyan else DdAmber
                    Column(Modifier.fillMaxWidth().clip(DdShape).background(tc.copy(alpha = .10f)).border(1.5.dp, tc.copy(alpha = .75f), DdShape).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("PIT PLANNER · 언제 세차할까요?", color = DdMuted, fontSize = 17.sp)
                        Text(title, color = DdInk, fontSize = 32.sp, fontWeight = FontWeight.Black, maxLines = 1)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            w.days.take(4).forEachIndexed { i, d ->
                                val (c, l) = when (d.rating) { WashRating.GOOD -> DdGreen to "좋음"; WashRating.OK -> DdCyan to "보통"; WashRating.BAD -> DdAmber to "비"; else -> DdMuted to "—" }
                                val rec = w.recommendedIndex == i
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (rec) DdOrange.copy(alpha = .16f) else Color(0xFF0E1A21))
                                    .border(if (rec) 2.dp else 1.dp, if (rec) DdOrange else DdLine, RoundedCornerShape(12.dp)).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(dayWord(i, d.date), color = DdInk, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                    Text(l, color = c, fontSize = 24.sp, fontWeight = FontWeight.Black)
                                    Text(d.pop?.let { "비 $it%" } ?: "—", color = DdMuted, fontSize = 15.sp)
                                    if (rec) Text("추천", color = DdOrange, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Text("오늘 · " + (if (ok) "세차해도 좋아요" else if (plan.wash.verdict == WashVerdict.HOLD) "미루는 게 좋아요" else "판단 보류") + " — " + plan.wash.reason,
                            color = DdInk.copy(alpha = .85f), fontSize = 16.sp, maxLines = 2)
                    }
                    // ── how it was worked out
                    DdCard("어떻게 판단했나요", "기상청 단기예보", Modifier.fillMaxWidth().weight(1f)) {
                        @Composable fun Step(n: String, text: String) = Row(verticalAlignment = Alignment.Top) {
                            Text(n, color = DdOrange, fontSize = 17.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(28.dp))
                            Text(text, color = DdInk, fontSize = 17.sp)
                        }
                        Step("1", "날마다 강수확률 ${PitWallEngine.RAIN_PROBABILITY}% 이상이거나 비 ${PitWallEngine.RAIN_MM.toInt()}mm 이상이면 \"비 오는 날\"")
                        Step("2", "그날과 다음 날 모두 비가 없으면 \"좋음\" (세차 뒤 바로 비 맞지 않게), 다음 날 예보가 없으면 \"보통\"")
                        Step("3", "가장 빠른 \"좋음\" 날을 추천, 없으면 가장 빠른 \"보통\" 날")
                        Step("4", "세차가 필요한지: ${w.needText.ifBlank { "기록 없음" }} · 마지막 세차 ${washRecordLabel(lastWash)}")
                        Text(w.reason, color = DdCyan, fontSize = 16.sp, maxLines = 2)
                    }
                }
                Column(Modifier.weight(.95f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DdCard("세차 전 체크", null, Modifier.fillMaxWidth()) {
                        val items = listOf("창문 · 선루프 닫기", "사이드미러 접기", "${if (pt.usesFuel) "주유구" else "충전구"} 닫힘 확인", "실내 귀중품 정리", "와이퍼 자동 끄기")
                        var done by remember { mutableStateOf(setOf<Int>()) }
                        items.chunked(2).forEachIndexed { r, row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEachIndexed { c, label -> val idx = r * 2 + c; val on = idx in done
                                    Text((if (on) "✓ " else "○ ") + label, color = if (on) DdGreen else DdInk, fontSize = 17.sp, maxLines = 1,
                                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).border(1.dp, if (on) DdGreen else DdLine, RoundedCornerShape(16.dp))
                                            .clickable { done = if (on) done - idx else done + idx }.padding(horizontal = 12.dp, vertical = 8.dp))
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("세차장 찾기  ›", color = DdCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).border(1.dp, DdCyan.copy(alpha = .5f), RoundedCornerShape(22.dp)).clickable { onOpen("FINDER") }.padding(vertical = 10.dp))
                            Text("세차 완료 기록", color = Color(0xFF071015), fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(DdGreen).clickable { washDone = true }.padding(vertical = 10.dp))
                        }
                    }
                    WashModeCard(state = washState, onExecute = onWashMode, onRefresh = onWashRefresh, onOpenWashNavi = { onOpen("NAVI_WASH") }, modifier = Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

private fun dayWord(i: Int, d: java.time.LocalDate?) = when (i) {
    0 -> "오늘"; 1 -> "내일"; 2 -> "모레"
    else -> d?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.KOREAN)?.let { "${it}요일" } ?: "${i}일 뒤"
}

@Composable
private fun PitEnergyTab(plan: PitPlan, forecast: PitForecast, records: List<DriveRecord>, example: Boolean, nowMs: Long, onOpen: (String) -> Unit) {
    val pt = plan.powertrain
    val soc = plan.soc.value
    val target = plan.chargeTargetPercent.value ?: if (pt.usesFuel) 100.0 else 80.0
    val lc = remember(records) { lastCharge(records) }
    val trips = remember(records) { EfficiencyModel.trips(records) }
    val usual = trips.map { it.kmPerPercent }.average().takeIf { !it.isNaN() }
    val cf = forecast.charge
    // ── the answer first: when to charge
    val (whenTitle, whenColor) = when {
        plan.verdict == PitVerdict.PIT_REQUIRED -> "이번 주행 중에 꼭 ${pt.refuel}하세요" to DdRed
        plan.verdict == PitVerdict.PIT_ADVISED -> "이번 주행 중에 한 번 ${pt.refuel}하세요" to DdAmber
        cf.status == ChargeStatus.NOW -> "오늘 ${pt.refuel}하세요" to DdAmber
        cf.status == ChargeStatus.BY_DATE && cf.chargeByIndex != null -> "${dayWord(cf.chargeByIndex, cf.chargeByDate)}까지 ${pt.refuel}하면 돼요" to DdOrange
        cf.status == ChargeStatus.LATER -> "4일 안에는 ${pt.refuel}할 필요 없어요" to DdGreen
        else -> "주행 기록이 쌓이면 ${pt.refuel}할 날을 알려 드려요" to DdMuted
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth().weight(1.25f).clip(DdShape).background(whenColor.copy(alpha = .10f)).border(1.5.dp, whenColor.copy(alpha = .75f), DdShape).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PIT PLANNER · 언제 ${pt.refuel}할까요?", color = DdMuted, fontSize = 17.sp)
            Text(whenTitle, color = DdInk, fontSize = 36.sp, fontWeight = FontWeight.Black, maxLines = 1)
            // four mornings: a level bar each, the day to charge framed
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                (0 until 4).forEach { i ->
                    val v = cf.morningSoc.getOrNull(i); val by = cf.chargeByIndex == i
                    val c = when { v == null -> DdMuted; v < 20 -> DdRed; v < 30 -> DdAmber; else -> DdGreen }
                    Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(if (by) DdOrange.copy(alpha = .18f) else Color(0xFF0E1A21))
                        .border(if (by) 2.dp else 1.dp, if (by) DdOrange else DdLine, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(dayWord(i, forecast.wash.days.getOrNull(i)?.date) + if (i == 0) " 지금" else " 아침", color = DdInk, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (by) Text(pt.refuel, color = Color(0xFF071015), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(DdOrange).padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                        Text(v?.let { if (it < 0) "부족" else "${f0(it)}%" } ?: "—", color = c, fontSize = 30.sp, fontWeight = FontWeight.Black)
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(DdLine)) {
                            Box(Modifier.fillMaxWidth(((v ?: 0.0) / 100).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(c))
                        }
                        // Chance of being under 20% by the end of that day (replayed past days).
                        cf.risk.getOrNull(i)?.let { r ->
                            Text("20% 아래 확률 ${f0(r * 100)}%", color = if (r >= PitForecastEngine.RISK) DdOrange else DdMuted, fontSize = 14.sp, maxLines = 1)
                        }
                    }
                }
            }
            val ec = remember(records) { EngineCheck.energy(records, pt) }
            Text(if (cf.risk.isNotEmpty())
                    "어떻게 판단했나요  ·  지난 4주 요일별 실제 주행을 ${PitForecastEngine.RUNS}번 다시 굴려, 20% 아래로 갈 확률이 ${f0(PitForecastEngine.RISK * 100)}%를 넘는 날 · 1%당 ${cf.kmPerPercent.value?.let { f1(it) } ?: "—"} km" +
                        (plan.learnedRainEffect?.let { " · 비 오는 날 ${f0(it * 100)}%" } ?: "")
                else "어떻게 판단했나요  ·  하루 평균 ${cf.dailyKm.value?.let { "${f0(it)} km" } ?: "—"}" + (if (example) " (예시 기록)" else " (${cf.dailyKm.label})") +
                    " ÷ 1%당 ${cf.kmPerPercent.value?.let { f1(it) } ?: "—"} km = 하루 ${cf.dailyUsePercent.value?.let { f1(it) } ?: "—"}% 사용 → 20% 아래로 내려가는 날", color = DdMuted, fontSize = 15.sp, maxLines = 1)
            ec?.let { Text("엔진 검증  ·  지난 주행 ${it.checked}회를 그 전 기록만으로 미리 맞혀 보니 평균 오차 ${f1(it.enginePp)}%p (평균 하나로 맞히면 ${f1(it.averagePp)}%p) · 2%p 안 ${f0(it.within2 * 100)}%",
                color = DdCyan, fontSize = 15.sp, maxLines = 1) }
            if (!example) PitForecastEngine.dailyNote(cf)?.let { Text("ⓘ $it", color = DdAmber, fontSize = 15.sp, maxLines = 1) }
        }
        // ── now, and since the last charge
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1.1f).fillMaxHeight().clip(DdShape).background(DdRow).border(1.dp, DdLine, DdShape).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) { Text("현재 ${pt.energy}", color = DdMuted, fontSize = 15.sp); Text(soc?.let { "${f0(it)}%" } ?: "—", color = DdInk, fontSize = 40.sp, fontWeight = FontWeight.Black) }
                    Column(Modifier.weight(1f)) { Text("주행 가능", color = DdMuted, fontSize = 15.sp); Text(plan.range.value?.let { "${f0(it)} km" } ?: "—", color = DdInk, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                    Column(Modifier.weight(1f)) { Text("추천 목표", color = DdMuted, fontSize = 15.sp); Text("${f0(target)}%", color = DdGreen, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("가까운 ${pt.place} 보기  ›", color = Color(0xFF071015), fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(DdOrange).clickable { onOpen("FINDER") }.padding(horizontal = 16.dp, vertical = 10.dp))
                    Text("내비에서 골라 경유지 추가  ›", color = DdCyan, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(22.dp)).border(1.dp, DdCyan.copy(alpha = .5f), RoundedCornerShape(22.dp)).clickable { onOpen(pt.searchRoute) }
                            .padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
            @Composable fun Tile(label: String, value: String, sub: String, mod: Modifier, accent: Color = DdInk) =
                Column(mod.clip(RoundedCornerShape(14.dp)).background(DdRow).border(1.dp, DdLine, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.Center) {
                    Text(label, color = DdMuted, fontSize = 15.sp); Text(value, color = accent, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1); Text(sub, color = DdMuted, fontSize = 13.sp, maxLines = 1)
                }
            val k = lc?.let { c -> c.usedSince?.takeIf { it >= 1 }?.let { c.kmSince / it } }
            val vs = if (k != null && usual != null) k / usual - 1 else null
            Tile("지난 ${pt.refuel}", lc?.let { dayLabel(it.atMs, nowMs) } ?: "기록 없음", lc?.let { "${f0(it.fromPct)}% → ${f0(it.toPct)}%" } ?: "주행 사이에 잔량이 오르면 기록돼요", Modifier.weight(.8f).fillMaxHeight())
            Tile("${pt.refuel} 후 주행", lc?.let { "${f0(it.kmSince)} km" } ?: "—", lc?.usedSince?.let { "${pt.energy} ${f0(it)}% 사용" } ?: "", Modifier.weight(.7f).fillMaxHeight())
            Tile("${pt.refuel} 후 효율", k?.let { "${f1(it)} km/%" } ?: "—", vs?.let { "평소보다 " + (if (it < 0) "${f0(-it * 100)}% 낮음" else "${f0(it * 100)}% 높음") } ?: "평소 ${usual?.let { f1(it) } ?: "—"} km/%",
                Modifier.weight(.8f).fillMaxHeight(), if (vs != null && vs < -0.03) DdAmber else DdInk)
        }
        if (example) Text("4일 예측 · 지난 ${pt.refuel} · 효율은 예시 기록 기준이에요. 실제 주행이 쌓이면 바뀌어요", color = DdOrange, fontSize = 13.sp)
    }
}

/**
 * Maintenance page: every checked item in a list (tyres, under the hood, warning lights, charge port, doors).
 * An item with a problem opens up: how serious it is, what it is in plain Korean, what to do now, how to drive,
 * and where to get it fixed. Items without data say so; they are never shown as fine.
 */
@Composable
private fun PitCareTab(check: CrewCheck, onOpen: (String) -> Unit) {
    val known = check.spots.values.count { it != SpotStatus.UNKNOWN }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("점검 항목 ${CrewSpot.values().size}개 · 신호 받은 항목 ${known}개", color = DdInk, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            listOf(FaultLevel.STOP_NOW, FaultLevel.SERVICE, FaultLevel.SELF_FIX).forEach { l ->
                val n = check.cards.count { it.level == l }
                Text("${l.label} $n", color = if (n > 0) levelColor(l) else DdMuted, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, (if (n > 0) levelColor(l) else DdLine).copy(alpha = .7f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).clip(DdShape).background(Color(0xFF0B161D)).border(1.dp, DdLine, DdShape).verticalScroll(rememberScrollState())) {
            // problems first, then the rest in a fixed order
            val spots = CrewSpot.values().sortedBy { sp -> check.cards.filter { it.spot == sp }.minOfOrNull { it.level.rank } ?: 99 }
            spots.forEachIndexed { idx, sp ->
                if (idx > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(DdLine))
                val cards = check.cards.filter { it.spot == sp }.sortedBy { it.level.rank }
                val st = check.spots[sp] ?: SpotStatus.UNKNOWN
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (mark, c) = when { cards.isNotEmpty() -> '!' to levelColor(cards.first().level); st == SpotStatus.OK -> '✓' to DdGreen; else -> '?' to DdMuted }
                    Box(Modifier.size(30.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) { Text(mark.toString(), color = Color(0xFF071015), fontSize = 17.sp, fontWeight = FontWeight.Black) }
                    Spacer(Modifier.width(14.dp))
                    Text(sp.label + when (sp) { CrewSpot.HOOD -> " · 엔진오일 · 브레이크액 · 워셔액"; CrewSpot.DASH -> " 경고등"; else -> "" },
                        color = DdInk, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(when { cards.isNotEmpty() -> cards.joinToString(" · ") { it.title }; st == SpotStatus.OK -> "정상"; else -> "신호 없음" },
                        color = c, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                cards.forEach { card ->
                    val lc = levelColor(card.level)
                    Column(Modifier.fillMaxWidth().padding(start = 62.dp, end = 18.dp, bottom = 14.dp).clip(RoundedCornerShape(12.dp)).background(lc.copy(alpha = .08f))
                        .border(1.dp, lc.copy(alpha = .5f), RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(card.level.label, color = Color(0xFF071015), fontSize = 15.sp, fontWeight = FontWeight.Black,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(lc).padding(horizontal = 10.dp, vertical = 3.dp))
                            Spacer(Modifier.width(10.dp)); Text(card.title, color = DdInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                        @Composable fun Line(label: String, text: String, strong: Boolean = false) = Row {
                            Text(label, color = DdMuted, fontSize = 16.sp, modifier = Modifier.width(110.dp))
                            Text(text, color = DdInk, fontSize = if (strong) 18.sp else 16.sp, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal)
                        }
                        Line("무슨 문제", card.what)
                        Line("지금 할 일", card.now, strong = true)
                        Line("운전", card.drive)
                        card.fix?.let { fx ->
                            Text(fx.button + "  ›", color = Color(0xFF071015), fontSize = 17.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(DdOrange).clickable { onOpen("FIX_" + fx.kind.name) }.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                }
            }
        }
        Text("조치하고 경고등이 꺼지면 이 목록이 자동으로 정상으로 바뀌어요 · 근거: ${FaultTriage.MANUAL}", color = DdMuted, fontSize = 14.sp, maxLines = 1)
    }
}

/**
 * PITSTOP overview (mock-up): three columns — A energy, B maintenance, C wash — each with a verdict band, the few facts
 * behind it, and one action. Tapping a column opens its own page. Only data the app really has is shown.
 */
@Composable
fun PitstopOverview(plan: PitPlan, forecast: PitForecast, records: List<DriveRecord>, example: Boolean, nowMs: Long, check: CrewCheck,
                    lastWash: Long, service: PitServiceUi?, onOpen: (String) -> Unit) {
    val pt = plan.powertrain
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(5.dp).height(40.dp).background(DdOrange)); Spacer(Modifier.width(14.dp))
        Text("PITSTOP", color = DdInk, fontSize = 40.sp, fontWeight = FontWeight.Black)
        Text("   ${pt.refuel} · 정비 · 세차까지, 내 차를 위한 피트스탑", color = DdMuted, fontSize = 18.sp, maxLines = 1, softWrap = false)
        Spacer(Modifier.weight(1f))
        Text("직접 장소 찾기  ›", color = DdInk, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
            modifier = Modifier.clip(RoundedCornerShape(24.dp)).border(1.dp, DdLine, RoundedCornerShape(24.dp)).clickable { onOpen("FINDER") }
                .padding(horizontal = 18.dp, vertical = 10.dp))
    }
    service?.prompt?.let { v -> VisitConfirmCard(v, onYes = { service.onPromptAnswer(true) }, onNo = { service.onPromptAnswer(false) }, onLater = { service.onPromptAnswer(null) }, modifier = Modifier.fillMaxWidth()) }
    service?.followUp?.let { f -> FollowUpCard(f, service.onFollowUp, Modifier.fillMaxWidth()) }

    @Composable fun Column3(title: String, route: String, mod: Modifier, content: @Composable ColumnScope.() -> Unit) =
        Column(mod.clip(DdShape).background(Brush.verticalGradient(listOf(Color(0xFF0E1A21), Color(0xFF09131A)))).border(1.dp, DdLine, DdShape)
            .clickable { onOpen(route) }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = DdInk, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text("›", color = DdMuted, fontSize = 26.sp)
            }
            content()
        }
    @Composable fun Band(mark: Char, color: Color, title: String, sub: String) =
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = .13f)).border(1.5.dp, color.copy(alpha = .8f), RoundedCornerShape(12.dp))
            .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) { Text(mark.toString(), color = Color(0xFF071015), fontSize = 22.sp, fontWeight = FontWeight.Black) }
            Spacer(Modifier.width(12.dp))
            Column { Text(title, color = DdInk, fontSize = 23.sp, fontWeight = FontWeight.Black, maxLines = 1); Text(sub, color = DdInk.copy(alpha = .8f), fontSize = 15.sp, maxLines = 1) }
        }
    @Composable fun Fact(label: String, value: String, accent: Color = DdInk) = Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = DdMuted, fontSize = 16.sp, modifier = Modifier.weight(1f)); Text(value, color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
    @Composable fun Action(label: String, onClick: () -> Unit) = Text(label, color = DdOrange, fontSize = 19.sp, fontWeight = FontWeight.Bold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).border(1.5.dp, DdOrange, RoundedCornerShape(24.dp)).clickable(onClick = onClick).padding(vertical = 12.dp))

    Row(Modifier.fillMaxWidth().height(650.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // ── A energy
        Column3("A. ${pt.refuel} 관리", "PIT_A", Modifier.weight(1f).fillMaxHeight()) {
            val (m, c, t) = when (plan.verdict) {
                PitVerdict.PIT_REQUIRED -> Triple('!', DdRed, "가는 길에 꼭 ${pt.refuel}")
                PitVerdict.PIT_ADVISED -> Triple('!', DdAmber, "${pt.refuel} 1번 권장")
                else -> when (forecast.charge.status) {
                    ChargeStatus.NOW -> Triple('!', DdAmber, "오늘 ${pt.refuel} 권장")
                    else -> Triple('✓', DdGreen, "잔량 여유")
                }
            }
            val cf = forecast.charge
            Band(m, c, t, when (cf.status) { ChargeStatus.BY_DATE -> cf.chargeByIndex?.let { "${dayWord(it, cf.chargeByDate)}까지 ${pt.refuel}하면 돼요" } ?: "평소대로 타면 여유 있어요"
                ChargeStatus.LATER -> "4일 안에는 ${pt.refuel}이 필요 없어요"; ChargeStatus.NOW -> "오늘 안에 ${pt.refuel}하세요"; else -> plan.reason })
            val soc = plan.soc.value; val target = plan.chargeTargetPercent.value ?: if (pt.usesFuel) 100.0 else 80.0
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) { Text("현재 잔량", color = DdMuted, fontSize = 15.sp); Text(soc?.let { "${f0(it)}%" } ?: "—", color = DdInk, fontSize = 46.sp, fontWeight = FontWeight.Black) }
                Column(Modifier.weight(1f)) {
                    Text("추천 목표", color = DdMuted, fontSize = 15.sp); Text("${f0(target)}%", color = DdInk, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(DdLine)) {
                        Box(Modifier.fillMaxWidth(((soc ?: 0.0) / 100).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(DdOrange))
                    }
                }
            }
            val lc = remember(records) { lastCharge(records) }
            val usual = remember(records) { EfficiencyModel.trips(records).map { it.kmPerPercent }.average().takeIf { !it.isNaN() } }
            Fact("지난 ${pt.refuel} 이후 주행", lc?.let { "${f0(it.kmSince)} km" } ?: "—")
            Fact("마지막 ${pt.refuel}", lc?.let { dayLabel(it.atMs, nowMs) } ?: "기록 없음")
            val k = lc?.let { l -> l.usedSince?.takeIf { it >= 1 }?.let { l.kmSince / it } }
            Fact("지난 효율", k?.let { "${f1(it)} km/%" + (usual?.let { u -> "  " + (if (it >= u) "+" else "−") + f1(abs(it - u)) } ?: "") } ?: "—",
                if (k != null && usual != null && k < usual) DdAmber else DdInk)
            if (example) Text("예시 기록 기준", color = DdOrange, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Action("${pt.place} 찾기 · 경유지 추가") { onOpen(pt.searchRoute) }
        }
        // ── B maintenance
        Column3("B. 정비 · 차량 점검", "PIT_B", Modifier.weight(1f).fillMaxHeight()) {
            val worst = check.cards.minByOrNull { it.level.rank }
            when {
                worst == null && check.spots.values.all { it == SpotStatus.UNKNOWN } -> Band('?', DdMuted, "점검 신호 대기", "차에서 경고등 정보를 기다리는 중")
                worst == null -> Band('✓', DdGreen, "이상 없음", "경고등 · 공기압 · 액류 모두 정상")
                worst.level == FaultLevel.STOP_NOW -> Band('!', DdRed, "즉시 정차", "총 ${check.cards.size}건의 이상이 확인됐어요")
                else -> Band('!', DdAmber, "점검이 필요해요", "총 ${check.cards.size}건의 이상이 확인됐어요")
            }
            if (worst != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(worst.title, color = DdInk, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(worst.level.label, color = levelColor(worst.level), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(levelColor(worst.level).copy(alpha = .18f)).padding(horizontal = 10.dp, vertical = 3.dp))
                }
                Text(worst.what, color = DdMuted, fontSize = 15.sp, maxLines = 2)
                Text("지금 할 일", color = DdInk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("• " + worst.now, color = DdInk, fontSize = 15.sp, maxLines = 2)
                Text("• 조치 후 경고등이 꺼지면 자동으로 정상으로 바뀌어요", color = DdInk, fontSize = 15.sp, maxLines = 2)
            } else {
                Text("다음에 들를 때 확인할 것", color = DdInk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("• 경고등이 켜지면 여기서 바로 알려 드려요\n• 장소 정보 확인은 PIT FINDER에서", color = DdMuted, fontSize = 15.sp)
            }
            Spacer(Modifier.weight(1f))
            val near = service?.optionsAll?.invoke(ServiceNeed.REPAIR)?.firstOrNull()
            near?.let { o -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DdRow).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("가까운 정비소", color = DdMuted, fontSize = 14.sp); Spacer(Modifier.width(10.dp))
                Text(o.place.name, color = DdInk, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                Text(o.distanceKm?.let { "${f1(it)} km" } ?: "", color = DdMuted, fontSize = 15.sp)
            } }
            Action(worst?.fix?.button ?: "정비 장소 찾기") { worst?.fix?.let { onOpen("FIX_" + it.kind.name) } ?: onOpen("FINDER") }
        }
        // ── C wash
        Column3("C. 세차 관리", "PIT_C", Modifier.weight(1f).fillMaxHeight()) {
            val w = forecast.wash
            val title = w.recommendedIndex?.let { i -> if (i == 0) "오늘 세차 추천" else "${dayWord(i, w.days.getOrNull(i)?.date)} 세차 추천" } ?: "4일 안엔 비 소식"
            Band(if (w.recommendedIndex != null) '✓' else '!', if (w.recommendedIndex != null) DdCyan else DdAmber, title, w.needText.ifBlank { "세차 기록이 없어요" })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0 until 4).forEach { i ->
                    val d = w.days.getOrNull(i); val rec = w.recommendedIndex == i
                    val (c, l) = when (d?.rating) { WashRating.GOOD -> DdGreen to "좋음"; WashRating.OK -> DdCyan to "보통"; WashRating.BAD -> DdAmber to "비"; else -> DdMuted to "—" }
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (rec) DdCyan.copy(alpha = .14f) else DdRow)
                        .border(if (rec) 2.dp else 1.dp, if (rec) DdCyan else DdLine, RoundedCornerShape(10.dp)).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(dayWord(i, d?.date), color = DdInk, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(l, color = c, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Fact("세차 전 체크", "5개 항목 ›")
            Fact("최근 세차", washRecordLabel(lastWash))
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("세차장 찾기", color = DdInk, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).border(1.dp, DdLine, RoundedCornerShape(24.dp)).clickable { onOpen("NAVI_WASH") }.padding(vertical = 12.dp))
                Text("세차 모드", color = DdOrange, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).border(1.5.dp, DdOrange, RoundedCornerShape(24.dp)).clickable { onOpen("PIT_C") }.padding(vertical = 12.dp))
            }
        }
    }
}
