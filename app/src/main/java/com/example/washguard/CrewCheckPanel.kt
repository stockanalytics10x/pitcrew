package com.example.washguard

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CcPanel = Color(0xFF0B1820)
private val CcDeep = Color(0xFF050E14)
private val CcTile = Color(0xFF0F1E27)
private val CcLine = Color(0xFF263C48)
private val CcInk = Color(0xFFE6EDF1)
private val CcMuted = Color(0xFF8DA4B2)
private val CcOrange = Color(0xFFFF7A28)
private val CcCyan = Color(0xFF39BCE9)
private val CcGreen = Color(0xFF30CD86)
private val CcRed = Color(0xFFFF4B4B)
private val CcYellow = Color(0xFFFFC94A)

fun levelColor(l: FaultLevel): Color = when (l) {
    FaultLevel.STOP_NOW -> CcRed
    FaultLevel.SERVICE -> CcOrange
    FaultLevel.CHARGE -> CcCyan
    FaultLevel.SELF_FIX -> CcYellow
    FaultLevel.INFO -> CcGreen
}

/** Where each checked part sits on the top-down car picture (fractions of the picture, front at the top). */
private val spotPosition = mapOf(
    CrewSpot.TIRE_FL to (0.10f to 0.21f), CrewSpot.TIRE_FR to (0.90f to 0.21f),
    CrewSpot.TIRE_RL to (0.10f to 0.77f), CrewSpot.TIRE_RR to (0.90f to 0.77f),
    CrewSpot.HOOD to (0.50f to 0.09f), CrewSpot.DASH to (0.38f to 0.31f),
    CrewSpot.CHARGE_PORT to (0.88f to 0.64f), CrewSpot.BODY to (0.50f to 0.55f)
)

/** Where a problem is drawn on the top-down car picture (fractions of the picture, front at the top). */
private const val CAR_ASPECT = 887f / 1774f

/**
 * The PITSTOP car picture with the problem spots marked on it. Nothing is drawn on the car while all is fine;
 * a small chip under the car says ✓ 이상 없음 / ? 신호 대기 / ! n건.
 */
@Composable
fun CarFaultView(check: CrewCheck, selectedId: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val selectedSpot = check.cards.firstOrNull { it.id == selectedId }?.spot
    BoxWithConstraints(modifier) {
        // Where the picture actually lands inside the box with ContentScale.Fit.
        val imgW = minOf(maxWidth, maxHeight * CAR_ASPECT)
        val imgH = imgW / CAR_ASPECT
        val left = (maxWidth - imgW) / 2
        val top = (maxHeight - imgH) / 2
        Image(painterResource(R.drawable.pitstop_vehicle_top_w), "차량 상단 이미지 · 문제 부위 표시", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        val pulse = rememberInfiniteTransition(label = "fault").animateFloat(1f, 1.3f,
            infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
        check.cards.groupBy { it.spot }.forEach { (spot, cards) ->
            val (fx, fy) = spotPosition.getValue(spot)
            val card = cards.first()
            val c = levelColor(card.level)
            val size: Dp = if (spot == selectedSpot) 34.dp else 28.dp
            Box(Modifier.offset(x = left + imgW * fx - size / 2, y = top + imgH * fy - size / 2).size(size)
                .scale(pulse.value).clip(CircleShape).background(c.copy(alpha = .25f)).border(2.dp, c, CircleShape)
                .clickable { onSelect(card.id) }, contentAlignment = Alignment.Center) {
                Text(if (cards.size > 1) "${cards.size}" else "!", color = c, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        val unknown = check.spots.values.all { it == SpotStatus.UNKNOWN }
        val (chip, color) = when {
            !check.allClear -> "! ${check.cards.size}건 · ${check.worst!!.label}" to levelColor(check.worst!!)
            unknown -> "? 점검 신호 대기" to CcMuted
            else -> "✓ 이상 없음" to CcGreen
        }
        Text(chip, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).clip(RoundedCornerShape(10.dp)).background(CcDeep.copy(alpha = .9f))
                .border(1.dp, color.copy(alpha = .6f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** What the PITSTOP page needs from PIT SERVICE: ranked places, and the driver's actions on them. */
class PitServiceUi(
    val options: (ServiceNeed) -> List<PlaceOption>,
    val onGo: (FaultCard, ServiceNeed, PlaceOption) -> Unit,
    val onNotHere: (ServiceNeed, PlaceOption) -> Unit,
    val dataNote: String,
    val status: String,
    /** Visit whose fault has since cleared: ask once whether it was fixed there. */
    val prompt: PendingVisit? = null,
    val onPromptAnswer: (Boolean?) -> Unit = {},
    /** One extra one-tap question after a report. */
    val followUp: FollowUp? = null,
    val onFollowUp: (String?) -> Unit = {},
    /** Everything known about a place, for its information page. */
    val detail: (ServicePlace) -> List<NeedInfo> = { emptyList() },
    /** What the navi last answered (shown after "안내" so a failed request is not silent). */
    val naviStatus: String = "",
    /** PIT FINDER: more places per kind, guidance without a fault, a positive report, past reports, the navi's own search. */
    val optionsAll: (ServiceNeed) -> List<PlaceOption> = { emptyList() },
    val onGoPlace: (ServiceNeed, PlaceOption) -> Unit = { _, _ -> },
    val onDoneHere: (ServiceNeed, PlaceOption) -> Unit = { _, _ -> },
    val history: List<CrewReport> = emptyList(),
    val placeName: (String) -> String? = { null },
    val openNavi: (NativeSearchKind) -> Unit = {},
    /** Saved places (star) and the places known around the car, for PIT FINDER. */
    val saved: List<String> = emptyList(),
    val onToggleSaved: (String) -> Unit = {},
    val places: List<ServicePlace> = emptyList(),
    val distanceTo: (ServicePlace) -> Double? = { null },
    /** Add the place the car is at now (name optional, services chosen by tap). */
    /** The driver marks another service as available at this place (e.g. a garage that also washes cars). */
    val showNavi: () -> Unit = {},
    val onAlsoHere: (ServicePlace, ServiceNeed) -> Unit = { _, _ -> }
)

/** "2일 전" style age of a confirmation. */
fun agoLabel(ms: Long?, nowMs: Long = System.currentTimeMillis()): String? {
    if (ms == null) return null
    val d = ((nowMs - ms) / 86_400_000L).coerceAtLeast(0)
    return when { d == 0L -> "오늘"; d < 7 -> "${d}일 전"; d < 60 -> "${d / 7}주 전"; else -> "${d / 30}개월 전" }
}

/**
 * Place information page, one row per fact (label left, value right): what each service's evidence says, the car-sensor
 * confirmations, last confirmed, fee, self/staff, hours, source. Missing facts say "정보 없음", never a guess.
 */
@Composable
fun PlaceInfoDialog(o: PlaceOption, need: ServiceNeed, service: PitServiceUi, onGo: () -> Unit, onNotHere: () -> Unit, onClose: () -> Unit, onDone: (() -> Unit)? = null) {
    val details = service.detail(o.place)
    val demo = o.place.offers.values.any { it == Evidence.DEMO }
    @Composable fun Row2(label: String, value: String, color: Color = CcMuted, bold: Boolean = false) =
        Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = CcInk, fontSize = 22.sp, modifier = Modifier.weight(1f))
            Text(value, color = color, fontSize = 22.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        }
    @Composable fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(CcLine))
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(.72f).fillMaxHeight(.9f).clip(RoundedCornerShape(10.dp)).background(CcDeep).border(1.dp, CcLine, RoundedCornerShape(10.dp))
            .verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.place.name, color = CcInk, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (demo) Text("예시", color = CcInk, fontSize = 17.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(CcTile).padding(horizontal = 10.dp, vertical = 3.dp))
                Spacer(Modifier.width(8.dp))
                val starred = o.place.id in service.saved
                Text(if (starred) "★ 저장됨" else "☆ 저장", color = if (starred) CcOrange else CcMuted, fontSize = 20.sp,
                    modifier = Modifier.clickable { service.onToggleSaved(o.place.id) }.padding(6.dp))
            }
            Text(listOfNotNull(o.distanceKm?.let { "%.1f km".format(it) }, o.place.hours?.let { "운영 $it" }, o.place.address.takeIf { it.isNotBlank() }).joinToString(" · "),
                color = CcMuted, fontSize = 20.sp)
            Spacer(Modifier.height(8.dp)); Divider()
            details.forEach { n ->
                val bad = n.lastNoMs != null && (n.lastOkMs == null || n.lastNoMs > n.lastOkMs)
                val value = when {
                    bad -> "최근 이용 불가 제보" + (n.lastFail?.let { " · ${it.label}" } ?: "")
                    n.yes > 0 -> "최근 정상 이용 제보"
                    n.listed == Evidence.OFFICIAL -> "공식 데이터에 있음"
                    n.listed == Evidence.LIKELY -> "있을 가능성 높음"
                    n.listed == Evidence.DEMO -> "예시 데이터"
                    else -> "정보 없음"
                }
                Row2(n.need.label, value, if (bad) CcOrange else if (n.yes > 0) CcGreen else CcMuted, bold = bad || n.yes > 0)
            }
            val missing = ServiceNeed.values().filter { n -> details.none { it.need == n && (it.listed != null || it.yes > 0) } }
            if (missing.isNotEmpty()) {
                Text("여기서 이것도 할 수 있었나요? 누르면 해당 목록에도 나와요", color = CcMuted, fontSize = 19.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    missing.forEach { n ->
                        Text("+ ${n.label}", color = CcInk, fontSize = 19.sp, modifier = Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, CcLine, RoundedCornerShape(20.dp))
                            .clickable { service.onAlsoHere(o.place, n) }.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Divider()
            val cur = details.firstOrNull { it.need == need } ?: details.firstOrNull()
            Row2("차량 센서 확인", cur?.carConfirmed?.takeIf { it > 0 }?.let { "${it}건 · 경고 해제로 확인" } ?: "아직 없음", if ((cur?.carConfirmed ?: 0) > 0) CcGreen else CcMuted, (cur?.carConfirmed ?: 0) > 0)
            Row2("운전자 확인", cur?.let { "됨 ${it.yes} · 안 됨 ${it.no}" } ?: "아직 없음")
            Row2("마지막 이용 확인", agoLabel(cur?.lastOkMs) ?: "정보 없음")
            val tags = cur?.tags ?: emptyList()
            fun tag(vararg opts: String) = tags.firstOrNull { it in opts } ?: "정보 없음"
            Row2("요금", tag("무료", "유료"))
            Row2("이용 방식", tag("셀프", "직원 서비스"))
            Row2("이용 시간", tag("24시간", "영업시간 중").let { if (it == "정보 없음") o.place.hours ?: it else it })
            Row2("정보 출처", listOfNotNull(o.place.source, if ((cur?.yes ?: 0) + (cur?.no ?: 0) > 0) "방문자 제보" else null).joinToString(" · "))
            Divider()
            Text("ⓘ 방문자 제보와 예시 데이터는 공식 확인 정보가 아니며 지금 상태와 다를 수 있어요. 차량 센서 확인은 경고가 실제로 해제된 기록이에요.",
                color = CcMuted, fontSize = 17.sp, modifier = Modifier.padding(vertical = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("안내 ›", color = Color(0xFF050D12), fontSize = 23.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(CcGreen).clickable(onClick = onGo).padding(horizontal = 28.dp, vertical = 16.dp))
                if (onDone != null) Text("${need.label} 됐어요", color = CcGreen, fontSize = 20.sp,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, CcGreen.copy(alpha = .6f), RoundedCornerShape(5.dp)).clickable(onClick = onDone).padding(horizontal = 18.dp, vertical = 16.dp))
                Text("안 됐어요", color = CcInk, fontSize = 20.sp,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, CcLine, RoundedCornerShape(5.dp)).clickable(onClick = onNotHere).padding(horizontal = 18.dp, vertical = 16.dp))
                Spacer(Modifier.weight(1f))
                Text("닫기", color = CcMuted, fontSize = 22.sp, modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 10.dp, vertical = 12.dp))
            }
        }
    }
}

/** The one extra question after a report: a condition of use, or why it did not work. One tap, or skip. */
@Composable
fun FollowUpCard(f: FollowUp, onAnswer: (String?) -> Unit, modifier: Modifier = Modifier) {
    val fact = f.fact
    val color = if (fact != null) CcGreen else CcOrange
    @Composable fun Choice(label: String, value: String, filled: Boolean = false) =
        Text(label, color = if (filled) Color(0xFF050D12) else CcInk, fontSize = 15.sp, fontWeight = if (filled) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.clip(RoundedCornerShape(5.dp)).then(if (filled) Modifier.background(color) else Modifier.border(1.dp, CcLine, RoundedCornerShape(5.dp)))
                .clickable { onAnswer(value) }.padding(horizontal = 16.dp, vertical = 10.dp))
    Column(modifier.clip(RoundedCornerShape(5.dp)).background(Brush.linearGradient(listOf(CcPanel, CcDeep)))
        .border(1.dp, color.copy(alpha = .7f), RoundedCornerShape(5.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("PIT INFO UPDATE · 하나만 더", color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(if (fact != null) "고마워요! ${f.placeName} · ${fact.question}" else "${f.placeName} · 무슨 일이었나요?", color = CcInk, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("한 번만 눌러 주시면 다음 운전자가 헛걸음하지 않아요. 건너뛰어도 괜찮아요.", color = CcMuted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (fact != null) { Choice(fact.yesLabel, "YES", filled = true); Choice(fact.noLabel, "NO") }
            else FailReason.values().forEach { Choice(it.label, it.name) }
            Text("건너뛰기", color = CcMuted, fontSize = 14.sp, modifier = Modifier.clickable { onAnswer(null) }.padding(horizontal = 8.dp, vertical = 10.dp))
        }
    }
}

/**
 * After a trip to a place the fault cleared on its own reading (e.g. tyre pressure back to normal):
 * one tap turns it into a confirmed place for the next driver.
 */
@Composable
fun VisitConfirmCard(visit: PendingVisit, onYes: () -> Unit, onNo: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(5.dp)).background(Brush.linearGradient(listOf(CcPanel, CcDeep)))
        .border(1.dp, CcGreen.copy(alpha = .7f), RoundedCornerShape(5.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("PIT INFO UPDATE · 차량 센서 확인", color = CcGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text("${visit.need.label} 문제가 해결됐어요. ${visit.placeName}에서 하셨나요?", color = CcInk, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("확인해 주시면 다음 운전자에게 \"${visit.need.verb}\"로 먼저 안내돼요.", color = CcMuted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("네, 여기서 했어요", color = Color(0xFF050D12), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(CcGreen).clickable(onClick = onYes).padding(horizontal = 16.dp, vertical = 10.dp))
            Text("다른 곳에서 했어요", color = CcInk, fontSize = 15.sp,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, CcLine, RoundedCornerShape(5.dp)).clickable(onClick = onNo).padding(horizontal = 16.dp, vertical = 10.dp))
            Text("나중에", color = CcMuted, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onLater).padding(horizontal = 8.dp, vertical = 10.dp))
        }
    }
}
