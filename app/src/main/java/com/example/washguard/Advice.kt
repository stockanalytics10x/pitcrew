package com.example.washguard

data class CareAdvice(val title: String, val description: String)

// Initial product rules, not a prediction of driving range or a professional safety assessment.
fun chargingAdvice(soc: Int?): CareAdvice = when {
    soc == null || soc !in 0..100 -> CareAdvice("배터리 확인 필요", "차량 배터리 값을 받은 뒤 충전 안내를 표시합니다.")
    soc <= 20 -> CareAdvice("충전소 확인 권장", "잔량 ${soc}%. 이동 전에 경로와 이용 가능한 충전소를 확인하세요.")
    soc <= 50 -> CareAdvice("충전 계획 확인", "잔량 ${soc}%. 이동 거리와 주행 가능 거리를 확인해 충전을 계획하세요.")
    else -> CareAdvice("잔량 ${soc}% · 경로 확인", "잔량만으로 충전 필요 여부를 확정할 수 없습니다. 목적지까지의 거리를 함께 확인하세요.")
}

