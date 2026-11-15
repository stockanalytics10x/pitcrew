package com.example.washguard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
fun TravelScreen(travel: TravelClient, weather: WeatherClient, onStart: () -> Unit, onBack: () -> Unit, vehicleData: VehicleDataState = VehicleDataState(), range: RangeState = RangeState(), onConnectRange: () -> Unit = {}) {
    val state = travel.state
    var consent by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val format = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(KST)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(onClick = onBack) { Text("← 홈으로", fontSize = 23.sp) }
        Text("설정", fontSize = 36.sp)
        Text(state.status, fontSize = 23.sp)
        Text("앱이 화면에 표시되는 동안 위치 표본을 기기에 저장합니다. 백그라운드에서는 기록하지 않습니다. 최대 30일 보관하며 전체 경로는 외부로 보내지 않습니다.", fontSize = 22.sp, lineHeight = 32.sp)
        Text("날씨 조회에는 현재 예보 격자와 과거 관측소 번호·날짜를 사용합니다. 과거 비교는 마지막 세차 이후 최근 7일 내 표본만 사용합니다. ASOS는 전일 자료까지 제공하므로 오늘 기록은 자료 대기로 표시합니다. 실제 오염을 확정하지 않습니다.", fontSize = 21.sp, lineHeight = 30.sp)
        Button(onClick = { if (state.enabled) { travel.disable(); weather.useSeoul() } else consent = true }, modifier = Modifier.heightIn(min = 64.dp)) { Text(if (state.enabled) "위치 기록 끄기" else "동의 후 위치 기록 시작", fontSize = 24.sp) }
        if (state.enabled) OutlinedButton(onClick = onStart) { Text("위치 연결 재시도", fontSize = 22.sp) }
        Text("저장 표본 ${state.points.size}개 · 현재 위치 ${state.current?.let { "%.3f, %.3f".format(it.lat, it.lon) } ?: "수신 대기"}", fontSize = 23.sp)
        Text("마지막 세차: ${if (state.lastWash == 0L) "기록 없음" else format.format(Instant.ofEpochMilli(state.lastWash))}", fontSize = 23.sp)
        Button(onClick = { travel.washed(); weather.invalidate(); weather.refresh() }) { Text("지금 세차 완료 기록", fontSize = 23.sp) }
        HorizontalDivider()
        Text("기상청 데이터", fontSize = 30.sp)
        Text("날씨는 피트크루 서버를 통해 자동 조회합니다. 앱에 인증키를 입력할 필요가 없습니다.", fontSize = 22.sp)
        Text("예보 격자와 관측소 번호·날짜만 서버와 기상청으로 보내며 전체 이동 경로는 보내지 않습니다.", fontSize = 21.sp, lineHeight = 30.sp)
        OutlinedButton(onClick = { weather.useVehicle(state.current) }) { Text("차량 위치로 날씨 조회", fontSize = 23.sp) }
        OutlinedButton(onClick = weather::useSeoul) { Text("서울 수동 기준으로 조회", fontSize = 23.sp) }
        OutlinedButton(onClick = weather::refresh, enabled = !weather.state.loading) { Text("예보·과거 관측 새로고침", fontSize = 23.sp) }
        Text("${weather.state.region}\n${weather.state.status}\n${weather.state.nearStatus}\n과거 오염 가능성: ${weather.state.dirtRisk}\n${weather.state.history}", fontSize = 22.sp, lineHeight = 32.sp)
        HorizontalDivider()
        Text("차량 연결 정보", fontSize = 28.sp)
        Text("배터리: ${vehicleData.socStatus}\n누적 주행거리: ${vehicleData.distanceStatus}\n주행 가능 거리: ${range.status}", fontSize = 22.sp, lineHeight = 32.sp)
        OutlinedButton(onClick = onConnectRange) { Text("주행 가능 거리 연결 / 재시도", fontSize = 22.sp) }
        HorizontalDivider()
        Text("최근 위치 표본 (최대 20개 표시)", fontSize = 28.sp)
        if (state.points.isEmpty()) Text("기록 시작 후 시간·좌표가 쌓입니다. 이전 경로를 자동으로 가져오지는 않습니다.", fontSize = 22.sp)
        state.points.takeLast(20).asReversed().forEach { Text("${format.format(Instant.ofEpochMilli(it.time))} · ${"%.3f, %.3f".format(it.lat, it.lon)}", fontSize = 21.sp) }
        OutlinedButton(onClick = { deleting = true }) { Text("위치·세차·과거 관측 기록 전체 삭제", fontSize = 22.sp) }
        Text("출처: 기상청 단기/초단기예보 · ASOS 시간관측 · 기상청 관측소 메타데이터. 관측소 30km 밖, 결측 및 조회 실패는 미확인입니다. 미세먼지·실내 주차·실제 오염은 아직 알 수 없습니다.", fontSize = 20.sp, lineHeight = 29.sp)
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("위치 기록을 시작할까요?") }, text = { Text("표시 중인 앱에서 시간·차량 좌표를 기기에 최대 30일 저장합니다. 날씨 조회 시 서버와 기상청에 보내는 정보는 기상청 격자·관측소·날짜를 사용합니다. 기록 끄기와 삭제가 가능합니다.") },
        confirmButton = { TextButton(onClick = { consent = false; onStart() }) { Text("동의하고 시작") } }, dismissButton = { TextButton(onClick = { consent = false }) { Text("취소") } })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("기록을 모두 삭제할까요?") }, text = { Text("저장된 위치·세차 시각·과거 관측 캐시를 삭제하고 기록을 끕니다. 복원할 수 없습니다. 서버의 인증키 설정은 유지됩니다.") },
        confirmButton = { TextButton(onClick = { deleting = false; weather.invalidate(); travel.clear { weather.useSeoul() } }) { Text("전체 삭제") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("취소") } })
}
