package com.example.washguard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val DrInk = Color(0xFFE6EDF1)
private val DrMuted = Color(0xFF8DA4B2)
private val DrLine = Color(0xFF263C48)
private val DrPanel = Color(0xFF09151C)
private val DrTile = Color(0xFF0E1E27)
private val DrOrange = Color(0xFFFF7A28)
private val DrCyan = Color(0xFF39BCE9)
private val DrGreen = Color(0xFF30CD86)

@Composable private fun T(text: String, size: Int = 14, color: Color = DrInk, bold: Boolean = false, modifier: Modifier = Modifier) =
    Text(text, color = color, fontSize = size.sp, lineHeight = (size * 1.3).sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = modifier)

@Composable private fun Panel(modifier: Modifier = Modifier, title: String, note: String? = null, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.clip(RoundedCornerShape(5.dp)).background(Brush.linearGradient(listOf(DrPanel, Color(0xFF050E14)))).border(1.dp, DrLine, RoundedCornerShape(5.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(title, 12, DrOrange, bold = true, modifier = Modifier.weight(1f))
            note?.let { T(it, 12, DrMuted) }
        }
        content()
    }

/** A horizontal bar for a value in [-max, max] around a centre line. */
@Composable private fun SignedBar(v: Double, max: Double, color: Color, modifier: Modifier) =
    Row(modifier.height(12.dp)) {
        val f = (abs(v) / max).coerceIn(0.0, 1.0).toFloat()
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) { if (v < 0) Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(color, RoundedCornerShape(2.dp))) }
        Box(Modifier.width(1.dp).fillMaxHeight().background(DrMuted))
        Box(Modifier.weight(1f).fillMaxHeight()) { if (v > 0) Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(color, RoundedCornerShape(2.dp))) }
    }

/**
 * DRIVE REPORT: after a drive. The last drive, what the Pit Wall learned from this car (efficiency by condition,
 * how right its predictions were, the weekday pattern) and the earlier drives. Demo mode shows the demo history.
 */
@Composable
fun DriveReport(records: List<DriveRecord>, learned: EfficiencyModel.Learned, nowMs: Long, demo: Boolean, pt: Powertrain, compact: Boolean, onOpen: (String) -> Unit) {
    val clock = DateTimeFormatter.ofPattern("M/d (E) HH:mm", Locale.KOREAN)
    fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(KST_ZONE).format(clock)
    fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    fun pct(v: Double) = "${if (v >= 0) "+" else "−"}${(abs(v) * 100).roundToInt()}%"
    val per = if (pt.usesFuel) "연료 1%당" else "배터리 1%당"

    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            T("02", 23, DrOrange); T("DRIVE REPORT", 40); T("주행 기록과 Pit Wall이 이 차에서 배운 것", 17, DrMuted)
        }
        if (demo) T("시연 기록", 13, DrOrange, modifier = Modifier.border(1.dp, DrOrange, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 2.dp))
    }

    // ── last drive
    val last = records.lastOrNull()
    Panel(Modifier.fillMaxWidth(), "LAST DRIVE · 지난 주행", last?.let { at(it.endMs) }) {
        if (last == null) {
            T("아직 기록된 주행이 없어요", 20)
            T("기어를 D로 놓고 1분 넘게 달린 뒤 P로 돌아오면 여기에 남아요. 출발·도착 잔량과 Pit Wall 예측이 맞았는지 비교해요.", 13, DrMuted)
        } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            @Composable fun Cell(label: String, value: String, color: Color = DrInk) = Column(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(DrTile).padding(10.dp)) {
                T(label, 12, DrMuted); T(value, 20, color, bold = true)
            }
            Cell("거리 · 시간", "${DriveRecordText.distance(last) ?: "—"} · ${DriveRecordText.duration(last)}")
            Cell("${pt.energy} 출발 → 도착", DriveRecordText.soc(last))
            Cell("예측 vs 실제", DriveRecordText.prediction(last) ?: "목적지 없이 주행", if (last.predictedArrival != null) DrCyan else DrMuted)
            Cell("Box 콜", DriveRecordText.radio(last))
        }
    }

    // ── what the Pit Wall learned
    val groups = EfficiencyModel.groups(records)
    val acc = EfficiencyModel.accuracy(records)
    val trips = EfficiencyModel.trips(records)
    val weekday = PitForecastEngine.weekdayKm(records, nowMs)
    val learnedBlock: @Composable (Modifier) -> Unit = { m ->
        Panel(m, "LEARNED · 이 차의 실제 효율", "학습한 주행 ${trips.size}회") {
            if (trips.size < 4) {
                T("주행이 4회 이상 쌓이면 조건별 효율을 보여 드려요.", 15)
                T("3km 넘게 달리고 중간에 충전·주유하지 않은 주행만 써요. 지금 ${trips.size}회.", 12, DrMuted)
            } else {
                val all = trips.map { it.kmPerPercent }.average()
                T("평균 $per ${f1(all)} km", 22, bold = true)
                learned.learnedKmPerPercent?.let { T("지금 날씨·시간대에 맞춘 학습값 ${f1(it)} km · 판단에는 차량 값과 섞어 ${f1(learned.kmPerPercent ?: it)} km를 써요", 12, DrCyan) }
                val max = (groups.maxOfOrNull { abs(it.vsAll) } ?: 0.1).coerceAtLeast(0.05)
                groups.forEach { g ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        T(g.label, 13, modifier = Modifier.width(150.dp))
                        SignedBar(g.vsAll, max, if (g.vsAll < 0) DrOrange else DrGreen, Modifier.weight(1f))
                        T("${pct(g.vsAll)} · ${g.trips}회", 13, if (g.vsAll < 0) DrOrange else DrGreen, modifier = Modifier.width(92.dp))
                    }
                }
                T("평균보다 효율이 낮은 조건(주황)에서는 Pit Wall이 도착 잔량을 더 낮게 예상해요. 가정한 숫자가 아니라 이 차 기록에서 나온 값이에요.", 12, DrMuted)
            }
        }
    }
    val accuracyBlock: @Composable (Modifier) -> Unit = { m ->
        Panel(m, "PREDICTION · 예측이 맞았나", acc?.let { "검증 ${it.checked}회" }) {
            if (acc == null) {
                T("목적지를 넣고 끝까지 달린 주행이 쌓이면, 예측 도착 잔량과 실제를 비교해요.", 15)
                T("평가 대상은 운전자가 아니라 Pit Wall의 예측이에요.", 12, DrMuted)
            } else {
                T("평균 오차 ${f1(acc.meanAbsError)}%p", 22, bold = true)
                T(if (acc.meanError > 0.5) "평균 ${f1(acc.meanError)}%p 낙관적으로 예측했어요 → 지금은 그만큼 빼고 판단해요"
                  else if (acc.meanError < -0.5) "평균 ${f1(-acc.meanError)}%p 비관적으로 예측했어요 → 지금은 그만큼 더하고 판단해요"
                  else "치우침 없이 예측하고 있어요", 13, DrCyan)
                learned.spread?.let { T("다음 예측은 ±${it.roundToInt()}%p 범위로 보여 주고, 판정은 범위의 아래쪽으로 내려요.", 12, DrMuted) }
                val max = (acc.recent.maxOfOrNull { abs(it) } ?: 1.0).coerceAtLeast(2.0)
                T("최근 ${acc.recent.size}회 (예측 − 실제)", 12, DrMuted)
                acc.recent.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SignedBar(e, max, if (e > 0) DrOrange else DrCyan, Modifier.weight(1f))
                        T("${if (e >= 0) "+" else "−"}${f1(abs(e))}", 12, DrMuted, modifier = Modifier.width(48.dp).padding(start = 8.dp))
                    }
                }
            }
        }
    }
    if (compact) { learnedBlock(Modifier.fillMaxWidth()); accuracyBlock(Modifier.fillMaxWidth()) }
    else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        learnedBlock(Modifier.weight(1.15f).fillMaxHeight()); accuracyBlock(Modifier.weight(1f).fillMaxHeight())
    }

    // ── weekday pattern
    Panel(Modifier.fillMaxWidth(), "PATTERN · 요일별 주행", "최근 4주") {
        if (weekday == null) T("2주 넘게 기록이 쌓이면 요일별로 얼마나 달리는지 보여 주고, 충전·주유할 날 예측에 써요.", 14, DrMuted)
        else {
            val max = (weekday.values.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
            Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                DayOfWeek.values().forEach { d ->
                    val km = weekday[d] ?: 0.0
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        T("${km.roundToInt()}", 12, DrMuted)
                        Box(Modifier.fillMaxWidth(.6f).fillMaxHeight((km / max * .7).toFloat().coerceAtLeast(.02f)).background(if (EfficiencyModel.weekend(d)) DrOrange else DrCyan, RoundedCornerShape(2.dp)))
                        T(d.getDisplayName(java.time.format.TextStyle.SHORT, Locale.KOREAN), 13)
                    }
                }
            }
            T("하루 평균 km · 피트 플래너(4일)는 이 패턴으로 충전·주유할 날을 계산해요.", 12, DrMuted)
        }
    }

    // ── earlier drives
    Panel(Modifier.fillMaxWidth(), "HISTORY · 이전 주행", "전체 ${records.size}회 · 차 안에만 저장") {
        if (records.isEmpty()) T("기록된 주행이 없어요.", 14, DrMuted)
        records.takeLast(12).reversed().forEach { r ->
            val trip = EfficiencyModel.trips(listOf(r)).firstOrNull()
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)).background(DrTile).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                T(at(r.startMs), 13, modifier = Modifier.width(130.dp))
                T(DriveRecordText.distance(r) ?: "—", 13, modifier = Modifier.width(70.dp))
                T(DriveRecordText.soc(r), 13, modifier = Modifier.weight(1f))
                T(trip?.let { "1%당 ${f1(it.kmPerPercent)}km" } ?: "—", 13, DrCyan, modifier = Modifier.width(110.dp))
                T(listOfNotNull(r.tempC?.let { "${it.roundToInt()}℃" }, if (r.rainy == true) "비" else null).joinToString(" "), 13, DrMuted, modifier = Modifier.width(60.dp))
                T(trip?.arrivalError?.let { "예측 ${if (it >= 0) "+" else "−"}${f1(abs(it))}%p" } ?: "", 13, DrMuted, modifier = Modifier.width(100.dp))
            }
        }
    }
    T("‹ GARAGE로", 14, DrOrange, modifier = Modifier.clickable { onOpen("GARAGE") }.padding(vertical = 10.dp))
}
