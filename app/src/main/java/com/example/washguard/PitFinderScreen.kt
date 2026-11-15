package com.example.washguard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.format.DateTimeFormatter

private val FInk = Color(0xFFE6EDF1)
private val FMuted = Color(0xFF8DA4B2)
private val FLine = Color(0xFF263C48)
private val FPanel = Color(0xFF09151C)
private val FTile = Color(0xFF13232D)
private val FOrange = Color(0xFFFF7A28)
private val FCyan = Color(0xFF39BCE9)
private val FGreen = Color(0xFF30CD86)
private val FDark = Color(0xFF050D12)

/** Lower part has the same height in every state, so the centred page does not move (content scrolls inside). */
private val FINDER_BOX = 420.dp
// Choosing a task is the main job, so the tiles are big until one is chosen; then they shrink to a row and the
// list takes the room. Tile + box heights add up the same both ways, so the page does not move.
private val TILE_BIG = 250.dp
private val TILE_SMALL = 92.dp
private val SAVED_BOX = FINDER_BOX + TILE_SMALL - TILE_BIG
private const val RECENT_DAYS = 30L

@Composable private fun T(text: String, size: Int = 14, color: Color = FInk, bold: Boolean = false, modifier: Modifier = Modifier) =
    Text(text, color = color, fontSize = size.sp, lineHeight = (size * 1.3).sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = modifier)

private enum class Task(val icon: String) { ENERGY("energy"), WASH("wash"), TIRE("tire"), REPAIR("wrench"), SUPPLY("bottle") }

/** One filter chip for a task: a condition the list must meet. */
private data class Filter(val label: String, val test: (PlaceOption, Long) -> Boolean)

private fun recent(o: PlaceOption, now: Long) = o.lastOkMs != null && now - o.lastOkMs <= RECENT_DAYS * 86_400_000L

@Composable
private fun TaskIcon(kind: String, color: Color, modifier: Modifier) = Canvas(modifier) {
    val w = size.width; val h = size.height; val s = 2.dp.toPx()
    when (kind) {
        "fuel" -> { drawRoundRect(color, Offset(w * .18f, h * .12f), Size(w * .42f, h * .76f), androidx.compose.ui.geometry.CornerRadius(3f), style = Stroke(s))
            drawRect(color, Offset(w * .27f, h * .22f), Size(w * .24f, h * .2f), style = Stroke(s))
            val p = Path(); p.moveTo(w * .6f, h * .36f); p.lineTo(w * .78f, h * .36f); p.lineTo(w * .86f, h * .5f); p.lineTo(w * .86f, h * .78f); p.lineTo(w * .72f, h * .78f); p.lineTo(w * .72f, h * .6f); p.lineTo(w * .6f, h * .6f)
            drawPath(p, color, style = Stroke(s)) }
        "charge" -> { val p = Path(); p.moveTo(w * .58f, h * .06f); p.lineTo(w * .24f, h * .56f); p.lineTo(w * .5f, h * .56f); p.lineTo(w * .42f, h * .94f); p.lineTo(w * .78f, h * .42f); p.lineTo(w * .52f, h * .42f); p.close()
            drawPath(p, color, style = Stroke(s)) }
        "wash" -> { val p = Path(); p.moveTo(w * .5f, h * .1f); p.cubicTo(w * .2f, h * .5f, w * .18f, h * .88f, w * .5f, h * .9f); p.cubicTo(w * .82f, h * .88f, w * .8f, h * .5f, w * .5f, h * .1f)
            drawPath(p, color, style = Stroke(s)) }
        "tire" -> { drawCircle(color, w * .4f, Offset(w * .5f, h * .5f), style = Stroke(s)); drawCircle(color, w * .17f, Offset(w * .5f, h * .5f), style = Stroke(s))
            repeat(6) { i -> val a = Math.toRadians(i * 60.0); drawLine(color, Offset(w * .5f + (w * .17f * Math.cos(a)).toFloat(), h * .5f + (w * .17f * Math.sin(a)).toFloat()), Offset(w * .5f + (w * .4f * Math.cos(a)).toFloat(), h * .5f + (w * .4f * Math.sin(a)).toFloat()), s * .6f) } }
        "wrench" -> { drawLine(color, Offset(w * .22f, h * .8f), Offset(w * .62f, h * .4f), s * 2f); drawArc(color, 20f, 280f, false, Offset(w * .48f, h * .1f), Size(w * .4f, h * .4f), style = Stroke(s * 1.4f)) }
        else -> { // washer fluid: windscreen with a spray
            val p = Path(); p.moveTo(w * .08f, h * .88f); p.quadraticBezierTo(w * .5f, h * .34f, w * .92f, h * .88f); p.close()
            drawPath(p, color, style = Stroke(s))
            drawLine(color, Offset(w * .5f, h * .88f), Offset(w * .5f, h * .62f), s)
            listOf(.28f to .3f, .5f to .14f, .72f to .3f).forEach { (x, y) -> drawCircle(color, w * .055f, Offset(w * x, h * y)) } }
    }
}

@Composable private fun BottomButton(label: String, color: Color = FInk, onClick: () -> Unit) =
    T(label, 17, color, bold = true, modifier = Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, color.copy(alpha = .5f), RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp))

/**
 * PIT FINDER: "어떤 작업이 필요하세요?" → one row of five tasks. With no task chosen, the lower part shows saved places
 * and visit history; choosing a task shows that task's filters and its places (confirmed first). Tap a place for its page.
 */
@Composable
fun PitFinder(service: PitServiceUi, pt: Powertrain, compact: Boolean, onOpen: (String) -> Unit) {
    val now = System.currentTimeMillis()
    var task by remember { mutableStateOf<Task?>(null) }
    var on by remember(task) { mutableStateOf(setOf<String>()) }
    var info by remember { mutableStateOf<Pair<PlaceOption, ServiceNeed>?>(null) }
    info?.let { (o, need) -> PlaceInfoDialog(o, need, service,
        onGo = { info = null; service.onGoPlace(need, o) },
        onNotHere = { info = null; service.onNotHere(need, o) },
        onClose = { info = null },
        onDone = { info = null; service.onDoneHere(need, o) }) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("PIT FINDER", color = FOrange, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("무엇이 필요하세요?", color = FInk, fontSize = 46.sp, fontWeight = FontWeight.Black, lineHeight = 52.sp)
        Text("필요한 것을 고르면 갈 수 있는 가까운 장소를 보여 드려요", color = FMuted, fontSize = 20.sp) }

    // ── five tasks, one row
    val labels = mapOf(Task.ENERGY to pt.place, Task.WASH to "세차장", Task.TIRE to "타이어·공기압", Task.REPAIR to "정비", Task.SUPPLY to "소모품")
    val hints = mapOf(Task.ENERGY to (if (pt.usesFuel) "가까운 주유소" else "가까운 충전소"), Task.WASH to "셀프 · 자동 세차", Task.TIRE to "공기 주입기 · 타이어",
        Task.REPAIR to "엔진오일 · 점검", Task.SUPPLY to "워셔액 구입")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Task.values().forEach { t ->
            val sel = task == t
            val big = task == null
            Column(Modifier.weight(1f).height(if (big) TILE_BIG else TILE_SMALL).clip(RoundedCornerShape(14.dp)).background(if (sel) FOrange else FTile)
                .border(1.dp, if (big) FLine else Color.Transparent, RoundedCornerShape(14.dp))
                .clickable { task = if (sel) null else t }.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                TaskIcon(if (t == Task.ENERGY) (if (pt.usesFuel) "fuel" else "charge") else t.icon, if (sel) FDark else FInk, Modifier.size(if (big) 72.dp else 28.dp))
                Spacer(Modifier.height(if (big) 18.dp else 8.dp))
                T(labels[t]!!, if (big) 26 else 16, if (sel) FDark else FInk, bold = true)
                if (big) T(hints[t]!!, 15, FMuted, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    service.followUp?.let { FollowUpCard(it, service.onFollowUp, Modifier.fillMaxWidth()) }

    val box = Modifier.fillMaxWidth().height(FINDER_BOX).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(FPanel, Color(0xFF050E14))))
        .border(1.dp, FLine, RoundedCornerShape(12.dp))
    val t = task
    if (t == null) {
        // ── saved places + visit history
        @Composable fun Saved(m: Modifier) = Column(m.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            T("저장한 장소", 17, bold = true)
            val places = service.saved.mapNotNull { id -> service.places.firstOrNull { it.id == id } }
            if (places.isEmpty()) T("장소 정보에서 ☆를 누르면 여기에 모여요.", 14, FMuted)
            places.forEach { p ->
                val need = p.offers.keys.firstOrNull() ?: ServiceNeed.AIR
                val opt = service.optionsAll(need).firstOrNull { it.place.id == p.id } ?: PlaceOption(p, service.distanceTo(p), p.offers[need] ?: Evidence.DEMO, 0, 0, 0)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FTile).clickable { info = opt to need }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("★", 16, FOrange); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) { T(p.name, 15, bold = true); T(p.offers.keys.joinToString(" · ") { it.label }, 12, FMuted) }
                    T(service.distanceTo(p)?.let { "%.1f km".format(it) } ?: "", 13, FMuted)
                }
            }
        }
        @Composable fun History(m: Modifier) = Column(m.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            T("크루 리포트 · 방문 기록", 17, bold = true)
            if (service.history.isEmpty()) T("장소 정보에서 '됐어요 / 안 됐어요'를 누르거나, 경고가 해결된 뒤 '여기서 하셨나요?'에 답하면 남아요.", 14, FMuted)
            service.history.sortedByDescending { it.atMs }.take(30).forEach { r ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FTile).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(service.placeName(r.placeId) ?: r.placeId, 15, bold = true)
                        T(listOfNotNull(r.need.label, agoLabel(r.atMs, now), if (r.carConfirmed) "차량 센서 확인" else null).joinToString(" · ") +
                            r.facts.map { (f, y) -> if (y) f.yesTag else f.noTag }.joinToString("") { " · $it" }, 12, FMuted)
                    }
                    T(if (r.ok) "✓ 됨" else "✕ 안 됨" + (r.fail?.let { " · ${it.label}" } ?: ""), 14, if (r.ok) FGreen else FOrange, bold = true)
                }
            }
        }
        val savedBox = Modifier.fillMaxWidth().height(SAVED_BOX).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(FPanel, Color(0xFF050E14))))
            .border(1.dp, FLine, RoundedCornerShape(12.dp))
        if (compact) Column(savedBox) { Saved(Modifier.fillMaxWidth().weight(1f)); Box(Modifier.fillMaxWidth().height(1.dp).background(FLine)); History(Modifier.fillMaxWidth().weight(1f)) }
        else Row(savedBox) { Saved(Modifier.weight(1f).fillMaxHeight()); Box(Modifier.width(1.dp).fillMaxHeight().background(FLine)); History(Modifier.weight(1.3f).fillMaxHeight()) }
        Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.CenterStart) { T("저장한 장소와 방문 기록은 이 차 안에만 저장돼요.", 12, FMuted) }
        return
    }

    // ── one task: its filters and places
    val needs = when (t) { Task.TIRE -> listOf(ServiceNeed.AIR); Task.REPAIR -> listOf(ServiceNeed.OIL, ServiceNeed.REPAIR); Task.SUPPLY -> listOf(ServiceNeed.WASHER); Task.WASH -> listOf(ServiceNeed.WASH)
        Task.ENERGY -> if (pt.usesFuel) listOf(ServiceNeed.FUEL) else listOf(ServiceNeed.CHARGE); else -> emptyList() }
    val filters = when (t) {
        Task.TIRE -> listOf(Filter("휴게소") { o, _ -> "고속도로 휴게소" in o.place.labels }, Filter("타이어 전문") { o, _ -> "타이어 전문" in o.place.labels }, Filter("셀프") { o, _ -> "셀프" in o.tags }, Filter("직원 도움") { o, _ -> "직원 서비스" in o.tags },
            Filter("무료") { o, _ -> "무료" in o.tags }, Filter("24시간") { o, _ -> "24시간" in o.tags }, Filter("최근 확인") { o, n -> recent(o, n) })
        Task.REPAIR -> listOf(Filter("엔진오일") { _, _ -> true }, Filter("점검·수리") { _, _ -> true }, Filter("종합정비") { o, _ -> "종합정비" in o.place.labels },
            Filter("카센터") { o, _ -> "부분정비(카센터)" in o.place.labels }, Filter("최근 확인") { o, n -> recent(o, n) })
        Task.SUPPLY -> listOf(Filter("휴게소") { o, _ -> "고속도로 휴게소" in o.place.labels }, Filter("24시간") { o, _ -> "24시간" in o.tags }, Filter("최근 확인") { o, n -> recent(o, n) })
        // Chargers: live state from 한국환경공단 (labels like "급속 2/4 사용 가능").
        Task.ENERGY -> if (pt.usesFuel) listOf(Filter("알뜰주유소") { o, _ -> o.place.labels.any { "알뜰" in it } }, Filter("최근 확인") { o, n -> recent(o, n) })
        else listOf(Filter("급속 비어 있음") { o, _ -> o.place.labels.any { it.startsWith("급속 ") && !it.startsWith("급속 0/") } },
            Filter("완속") { o, _ -> o.place.labels.any { it.startsWith("완속 ") } }, Filter("누구나 이용") { o, _ -> "누구나 이용" in o.place.labels },
            Filter("주차 무료") { o, _ -> "주차 무료" in o.place.labels }, Filter("최근 확인") { o, n -> recent(o, n) })
        // From the public car-wash data (type, other business) and what drivers confirmed.
        Task.WASH -> listOf(Filter("셀프") { o, _ -> "셀프세차" in o.place.labels || "셀프" in o.tags }, Filter("자동") { o, _ -> "자동세차" in o.place.labels },
            Filter("손세차") { o, _ -> "손세차" in o.place.labels }, Filter("주유하면서") { o, _ -> "주유소 겸업" in o.place.labels },
            Filter("충전하면서") { o, _ -> "충전소 겸업" in o.place.labels }, Filter("최근 확인") { o, n -> recent(o, n) })
        else -> emptyList()
    }
    Column(box.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("${labels[t]} 가능한 장소", 18, bold = true, modifier = Modifier.weight(1f))
            if (needs.isNotEmpty()) T("확인된 곳 먼저 · 가까운 순 · 30km 안", 12, FMuted)
        }
        if (filters.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            filters.forEach { f ->
                val sel = f.label in on
                T(f.label, 14, if (sel) FDark else FInk, bold = sel, modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (sel) FCyan else FTile)
                    .clickable { on = if (sel) on - f.label else on + f.label }.padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }
        if (t == Task.SUPPLY) T("지금은 워셔액을 살 수 있는 곳만 다뤄요. 와이퍼·전구·12V 배터리는 다음 단계예요.", 13, FMuted)
        if (needs.isEmpty()) {
            T("${labels[t]} 목록은 아직 없어요", 18, bold = true)
            T("${labels[t]} 데이터(공공데이터)를 연결하기 전이라 지금은 기본 내비 검색으로 찾아요. 연결되면 여기서도 확인된 곳을 먼저 보여 드려요.", 14, FMuted)
        } else {
            // Repair: the 엔진오일 / 점검·수리 chips choose which service; none chosen = both.
            val pick = if (t == Task.REPAIR) needs.filter { n -> (n == ServiceNeed.OIL && "엔진오일" in on) || (n == ServiceNeed.REPAIR && "점검·수리" in on) }.ifEmpty { needs } else needs
            val conds = filters.filter { it.label in on && it.label !in setOf("엔진오일", "점검·수리") }
            val list = pick.flatMap { n -> service.optionsAll(n).map { it to n } }
                .groupBy { it.first.place.id }.map { (_, v) -> v.minWith(compareBy({ it.first.evidence.rank }, { -it.first.yes })) }
                .filter { (o, _) -> conds.all { it.test(o, now) } }
                .sortedWith(compareBy({ it.first.evidence.rank }, { -it.first.yes }, { it.first.distanceKm ?: Double.MAX_VALUE }))
            if (list.isEmpty()) T(if (conds.isEmpty()) "주변에 아는 곳이 아직 없어요." else "조건에 맞게 확인된 곳이 아직 없어요. 필터를 줄여 보세요.", 15, FMuted)
            list.forEachIndexed { i, (o, need) ->
                val doesWhat = if (t == Task.REPAIR) needs.filter { it in o.place.offers.keys }.joinToString(" · ") { if (it == ServiceNeed.OIL) "엔진오일" else "점검·수리" } else null
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FTile).clickable { info = o to need }.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    T("${i + 1}", 18, FOrange, bold = true, modifier = Modifier.width(20.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T(o.place.name, 16, bold = true)
                            if (o.place.id in service.saved) T("  ★", 14, FOrange)
                        }
                        T(listOfNotNull(doesWhat, o.badge, agoLabel(o.lastOkMs, now)?.let { "마지막 확인 $it" }).joinToString(" · "), 12, if (o.yes > 0) FGreen else FMuted)
                        if (o.tags.isNotEmpty() || o.place.labels.isNotEmpty() || o.place.hours != null)
                            T((o.place.labels + o.tags + listOfNotNull(o.place.hours?.let { "평일 $it" })).distinct().joinToString(" · "), 12, FCyan)
                    }
                    T(o.distanceKm?.let { "%.1f km".format(it) } ?: "—", 14, FMuted)
                    T("정보", 14, FOrange, bold = true, modifier = Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, FOrange.copy(alpha = .6f), RoundedCornerShape(5.dp))
                        .clickable { info = o to need }.padding(horizontal = 14.dp, vertical = 7.dp))
                    T("안내 ›", 14, FDark, bold = true, modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(FGreen)
                        .clickable { service.onGoPlace(need, o) }.padding(horizontal = 14.dp, vertical = 7.dp))
                }
            }
            T(service.dataNote, 12, FMuted)
        }
        if (service.status.isNotBlank()) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FCyan.copy(alpha = .1f)).padding(10.dp)) {
            T(service.status, 14, FCyan, bold = true)
            if (service.naviStatus.isNotBlank()) T("내비 응답 · ${service.naviStatus}", 13)
            T("내비 화면 열기 ›", 15, FCyan, bold = true, modifier = Modifier.clickable { service.showNavi() }.padding(top = 6.dp))
        }
        val navi = when (t) { Task.ENERGY -> pt.searchKind; Task.WASH -> NativeSearchKind.WASH; Task.TIRE -> NativeSearchKind.TIRE; Task.REPAIR -> NativeSearchKind.SERVICE; Task.SUPPLY -> NativeSearchKind.SUPPLY }
        T("기본 내비에서 ${labels[t]} 찾기 ›", 15, FCyan, bold = true, modifier = Modifier.clickable { service.openNavi(navi) }.padding(vertical = 6.dp))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BottomButton("‹ 저장한 장소 · 크루 리포트") { task = null }
    }
}
