package com.example.washguard

import java.time.Instant
import java.time.format.DateTimeFormatter

data class PitTiming(val kind:String,val title:String,val reason:String)

fun washRecordLabel(lastWash:Long):String = if(lastWash<=0) "기록 없음" else
    Instant.ofEpochMilli(lastWash).atZone(KST).format(DateTimeFormatter.ofPattern("MM월 dd일"))

fun pitChargeTiming(soc:Int?):PitTiming {
    val advice=chargingAdvice(soc)
    return PitTiming("CHARGE",when {
        soc==null || soc !in 0..100 -> "충전 시점 확인 필요"
        soc<=20 -> "출발 전 충전 검토"
        soc<=50 -> "다음 이동 전 충전 계획"
        else -> "예정 경로와 잔량 비교"
    },advice.description)
}

fun pitWashTiming(weather:WeatherState,lastWash:Long):PitTiming {
    val advice=nextWashAdvice(weather.nearRain,weather.forecast?.probability,
        weather.forecast?.precipitationMm, "",weather.nearKnown,weather.historyWetHours)
    val title=when {
        weather.nearRain!=null -> "강수가 지난 뒤 세차 검토"
        weather.forecast==null || !weather.nearKnown -> "세차 시점 · 예보 확인 필요"
        weather.forecast.probability>=60 || (weather.forecast.precipitationMm?:0.0)>=1.0 -> "내일 강수 후 다시 확인"
        else -> "세차 일정 검토 가능"
    }
    return PitTiming("WASH",title,"${advice.description.trim()} 마지막 세차: ${washRecordLabel(lastWash)}. 실제 오염을 확인하세요.")
}

fun pitTiming(soc:Int?,weather:WeatherState,lastWash:Long):PitTiming =
    if(soc!=null && soc in 0..20) pitChargeTiming(soc) else pitWashTiming(weather,lastWash)
