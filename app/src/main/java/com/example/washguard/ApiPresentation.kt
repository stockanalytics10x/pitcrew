package com.example.washguard

fun weatherHeadline(state: WeatherState): String = when {
    state.loading -> "기상 정보 확인 중"
    state.forecast != null -> "내일 강수 ${state.forecast.probability}%"
    state.status.contains("실패") || state.status.contains("오류") -> "날씨 조회 실패 · 재시도"
    state.status.contains("위치") || state.region.contains("위치 대기") -> "새 차량 위치 필요"
    else -> "날씨 미수신 · 설정 확인"
}

