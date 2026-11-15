package com.example.washguard

import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class NativeSearchKind(val label: String, val keyword: String?) {
    WASH("세차장", "세차장"), FUEL("주유소", "주유소"), CHARGE("주변 충전소", null),
    // Places to fix what the pit crew found (searched by keyword in the Hyundai navi).
    TIRE("타이어 공기 주입·타이어 전문점", "타이어"), SERVICE("블루핸즈(현대차 정비)", "블루핸즈"), OIL("엔진오일 교환", "엔진오일"),
    SUPPLY("자동차용품", "자동차용품");
    companion object { val GENERAL = listOf(WASH, FUEL, CHARGE) }
}

fun nativeSearchRequest(kind: NativeSearchKind): ai.pleos.playground.navi.data.NavigationScreenRequest = when(kind) {
    NativeSearchKind.CHARGE -> ai.pleos.playground.navi.data.NavigationScreenRequest.NearbyChargingStations
    else -> ai.pleos.playground.navi.data.NavigationScreenRequest.SearchPlace(requireNotNull(kind.keyword))
}

@Composable
fun NativeSearchScreen(client: NaviClient?, onBack: () -> Unit, preferred: NativeSearchKind? = null, autoOpen: Boolean = false, reason: String? = null) {
    val state = client?.state ?: NaviSnapshot()
    // Coming from a pit crew fault card: open the navi search right away, once the navi is connected.
    var opened by remember(preferred) { mutableStateOf(false) }
    LaunchedEffect(preferred, state.connected) { if (autoOpen && preferred != null && state.connected && !opened) { opened = true; client?.openSearch(preferred) } }
    Column(Modifier.fillMaxSize().background(Color(0xFF071218)).verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Text("PIT STRATEGY / HYUNDAI NAVIGATION", color=Color(0xFFFF7A28),fontSize=20.sp)
        Text("장소는 현대차 내비에서 찾으세요",color=Color.White,fontSize=30.sp)
        Text("피트크루에서 검색을 요청하면 기본 내비의 검색 화면이 열립니다. 장소 선택과 경로·경유지 설정은 내비에서 진행하세요.",color=Color(0xFFADBDC6),fontSize=20.sp)
        Text(if(state.connected) "현대차 내비 연결됨" else "현대차 내비 연결 대기",color=Color(0xFF62CEDA),fontSize=18.sp)
        if(reason!=null) Text(reason,color=Color(0xFFFFC94A),fontSize=19.sp)
        val kinds=if(preferred==null)NativeSearchKind.GENERAL else listOf(preferred)+NativeSearchKind.GENERAL.filter {it!=preferred}
        kinds.forEach {kind ->
            OutlinedButton(onClick={client?.openSearch(kind)},enabled=state.connected&&!state.busy,
                modifier=Modifier.fillMaxWidth().heightIn(min=72.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                colors=ButtonDefaults.outlinedButtonColors(contentColor=Color(0xFFFF7A28))) {
                Text("${kind.label} 검색 열기  →",fontSize=23.sp)
            }
        }
        if(state.searchStatus.isNotBlank()) Text(state.searchStatus,color=Color.White,fontSize=20.sp)
        if(!state.connected) Button(onClick={client?.retry()},enabled=client!=null,modifier=Modifier.heightIn(min=64.dp)) {Text("내비 연결 재시도",fontSize=20.sp)}
        Text("검색과 위치 처리는 현대차 기본 내비에서 진행합니다. 처음 실행한 내비가 로딩 중이면 지도가 준비된 뒤 다시 검색해 주세요.",color=Color(0xFFADBDC6),fontSize=17.sp)
        TextButton(onClick=onBack,modifier=Modifier.heightIn(min=64.dp)) {Text("← Pitstop으로",color=Color(0xFF62CEDA),fontSize=20.sp)}
    }
}

