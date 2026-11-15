package com.example.washguard

import android.content.pm.PackageManager
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SgPanel = Color(0xFF0B1820)
private val SgTile = Color(0xFF0F1E27)
private val SgLine = Color(0xFF263C48)
private val SgInk = Color(0xFFE6EDF1)
private val SgMuted = Color(0xFF8DA4B2)
private val SgOrange = Color(0xFFFF7A28)
private val SgCyan = Color(0xFF39BCE9)
private val SgGreen = Color(0xFF30CD86)
private val SgRed = Color(0xFFFF4B4B)

/** Permissions the app uses, with what each one is for. */
val APP_PERMISSIONS = listOf(
    Triple("pleos.car.permission.NAVI_ROUTE", "내비 경로", "목적지·남은 거리 받기"),
    Triple("pleos.car.permission.NAVI_ROUTE_SEARCH", "내비 경유지", "충전소를 경유지로 넣기"),
    Triple("pleos.car.permission.NAVI_CUSTOM_MAP", "내비 화면", "내비 충전소·세차장 검색 열기"),
    Triple("android.car.permission.CAR_ENERGY", "주행 가능 거리", "차량 남은 거리 읽기"),
    Triple("pleos.car.permission.CAR_SAFETY", "차량 점검 정보", "타이어 공기압·브레이크액·워셔액·출력 제한"),
    Triple("pleos.car.permission.PRIVATE_CAR_SAFETY", "경고등 정보", "계기판 경고등(TPMS·12V·에어백 등)"),
    Triple("pleos.car.permission.TTS_SERVICE", "음성 출력", "Box 콜 무전 말하기"),
    Triple("pleos.car.permission.STT_SERVICE", "음성 인식", "\"응/아니\" 대답 듣기"),
    Triple("android.permission.RECORD_AUDIO", "마이크", "대답을 듣는 마이크"),
    Triple("android.car.permission.CAR_SPEED", "차량 속도", "주행 화면 속도·흐름"),
    Triple("pleos.car.permission.FUSED_LOCATION", "차량 위치", "날씨·세차 기록 위치")
)

/**
 * SETTINGS: what the app is allowed to use, a request button for each, and the radio voice check.
 * [tick] changes after every permission result so the list re-reads the granted state.
 */
@Composable
fun SettingsPanel(
    tick: Int,
    voiceStatus: String,
    onRequest: (String) -> Unit,
    onVoiceTest: () -> Unit,
    onVoiceRestart: () -> Unit,
    onOpenLegacy: () -> Unit,
    modifier: Modifier = Modifier,
    listenStatus: String = "",
    onListenTest: () -> Unit = {},
    demo: DemoController? = null,
    powertrain: PowertrainSetting? = null,
    detected: Powertrain = Powertrain.UNKNOWN
) {
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") tick
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (powertrain != null) Section("차량 종류") {
            val c = powertrain.choice
            Text("지금 · ${if (c == PowertrainChoice.AUTO) "${detected.label} (자동 인식)" else "${c.label} (직접 선택)"}", color = SgInk, fontSize = 16.sp)
            Text("배터리 값이 오면 전기차, 연료 값이 오면 내연기관, 둘 다 오면 하이브리드로 인식해요. 전기차는 충전·충전소, 내연기관은 주유·주유소로 안내해요. 시연 모드는 여기서 고른 종류로 진행해요(자동이면 전기차).",
                color = SgMuted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PowertrainChoice.entries.forEach { o -> Chip(o.label, SgOrange, filled = c == o) { powertrain.set(o) } }
            }
        }
        if (demo != null) Section("시연 모드 · PT") {
            Text("시나리오 · 대구 가는 길 (${demo.steps.size}장면)", color = SgInk, fontSize = 16.sp)
            demo.steps.forEachIndexed { i, st -> Text("${i + 1}. ${demo.say(st.title)} — ${demo.say(st.caption)}", color = SgMuted, fontSize = 12.sp) }
            Text("켜는 동안 화면 위에 DEMO 막대가 뜨고, 모든 값에 '시연'이 붙어요. 실제 기록(주행 기록·에너지 기록)에는 남지 않아요.", color = SgMuted, fontSize = 12.sp)
            if (demo.active) Text("상태 · ${demo.consoleStatus}", color = SgInk, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!demo.active) Chip("시연 시작 ▷", SgOrange, filled = true, onClick = { demo.start() })
                else Chip("시연 끝내기 ■", SgRed, onClick = { demo.stop() })
            }
        }
        Section("CREW RADIO · 음성") {
            Text("음성 상태 · $voiceStatus", color = SgInk, fontSize = 16.sp)
            Text("백그라운드 · ${PitWallService.status} (앱이 내비 뒤에 있어도 무전이 나가요 · 알림에서 끌 수 있어요)", color = SgMuted, fontSize = 13.sp)
            Text("대답 인식 · $listenStatus", color = SgInk, fontSize = 16.sp)
            Text("소리가 안 나면: '음성 출력' 권한 → 음성 다시 연결 → 무전 테스트. 대답을 못 들으면: '마이크'·'음성 인식' 권한 → 듣기 테스트 후 \"응\"이라고 말하기. 에뮬레이터는 마이크 입력을 따로 켜야 해요.",
                color = SgMuted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("무전 테스트 ▷", SgOrange, filled = true, onClick = onVoiceTest)
                Chip("듣기 테스트 (6초)", SgCyan, onClick = onListenTest)
                Chip("음성 다시 연결", SgCyan, onClick = onVoiceRestart)
            }
        }
        Section("권한") {
            APP_PERMISSIONS.forEach { (perm, label, use) ->
                val granted = context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SgTile).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    // PRIVATE_* permissions are granted only to apps the carmaker approves; a request just opens settings.
                    val makerOnly = perm.contains(".PRIVATE_")
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (granted) SgGreen else if (makerOnly) SgMuted else SgRed))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, color = SgInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("$use · ${perm.substringAfterLast('.')}", color = SgMuted, fontSize = 12.sp)
                    }
                    if (granted) Text("허용됨", color = SgGreen, fontSize = 13.sp)
                    else if (makerOnly) Text("제조사 승인 앱 전용", color = SgMuted, fontSize = 13.sp)
                    else Chip("허용 요청", SgOrange, onClick = { onRequest(perm) })
                }
            }
            Text("요청해도 창이 뜨지 않으면 시스템 설정의 앱 권한 화면이 열려요. 거기서 허용해 주세요. 경고등 정보는 제조사 승인 앱만 받을 수 있어, 그 대신 공개 신호(타이어 저압·브레이크액·워셔액·출력 제한)로 점검해요.", color = SgMuted, fontSize = 12.sp)
        }
        Section("기타 설정") {
            Text("위치 기록·날씨 지역·API 키 설정 ›", color = SgCyan, fontSize = 14.sp,
                modifier = Modifier.clickable(onClick = onOpenLegacy).padding(vertical = 6.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(SgPanel).border(1.dp, SgLine, RoundedCornerShape(6.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = SgOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        content()
    }
}

@Composable
private fun Chip(text: String, color: Color, filled: Boolean = false, onClick: () -> Unit) {
    Text(text, color = if (filled) Color(0xFF050D12) else color, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (filled) color else color.copy(alpha = .12f))
            .border(1.dp, color.copy(alpha = .6f), RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 13.dp))
}
