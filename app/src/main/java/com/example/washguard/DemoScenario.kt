package com.example.washguard

import kotlin.math.roundToInt

/**
 * One scene of the PT demo. Values are what the car and the navi report during that scene.
 * Every number on screen is labelled "시연" while the demo runs.
 */
data class DemoStep(
    val title: String,
    val caption: String,
    /** Page to show when the scene starts (the driving screen appears by itself when [driving]). */
    val page: String,
    val soc: Int,
    /** When set, the charge rises from [soc] to this value during the scene (charging). */
    val socEnd: Int? = null,
    val remainingKm: Double,
    val driving: Boolean = false,
    val speedKmh: Int = 0,
    /** Warning lights on during the scene (TelltaleType names), shown as the pit crew check. */
    val faults: Set<String> = emptySet(),
    /** Crew radio said once when the scene starts. */
    val radio: String? = null
) {
    val charging: Boolean get() = socEnd != null
}

object DemoScenario {
    /** km per % used to derive range in the demo: IONIQ 5 Long Range spec (478 km per charge). */
    const val KM_PER_PERCENT = PitWallInputs.SPEC_KM_PER_PERCENT

    /** "대구 가는 길": calm commute → long trip with too little charge → Box call → charge → off again. */
    val roadToDaegu = listOf(
        DemoStep("평소 출근", "목적지 25km · {E} 80% → 피트인 없이 완주", "GARAGE", soc = 80, remainingKm = 25.0),
        DemoStep("장거리 목적지", "대구 250km · {E} 27% → {R}이 필요해요", "GARAGE", soc = 27, remainingKm = 250.0),
        DemoStep("출발 · Box 콜", "주행 시작 → Pit Wall이 먼저 무전을 보내요", "DRIVE", soc = 27, remainingKm = 250.0, driving = true, speedKmh = 80),
        DemoStep("주행 중 · 타이어 경고", "TPMS 경고등 → 크루가 무전으로 알리고 다음 피트에서 해결해요", "DRIVE", soc = 25, remainingKm = 240.0,
            driving = true, speedKmh = 80, faults = setOf(TPMS),
            radio = "타이어 공기압이 낮아요. 급한 조작을 피하고, {P}에서 공기도 같이 넣을게요."),
        DemoStep("{P} 도착 · {R} + 공기압", "{R}하는 동안 PIT SERVICE에서 공기 넣을 곳 ‘안내’", "PITSTOP", soc = 25, socEnd = 80, remainingKm = 230.0,
            faults = setOf(TPMS)),
        DemoStep("공기압 정상 · 정보 업데이트", "센서가 정상 확인 → ‘여기서 하셨나요?’ 한 번 → 다음 운전자에게 확인된 곳으로", "PITSTOP", soc = 80, remainingKm = 230.0),
        DemoStep("다시 출발", "80% · 타이어 정상 → 대구까지 여유 있게 완주", "GARAGE", soc = 80, remainingKm = 230.0)
    )

    const val TPMS = "TPMS"

    /** Scene text is written once with {E} energy · {R} fill-up · {P} place, and read out for this car's powertrain. */
    fun words(text: String, pt: Powertrain): String =
        text.replace("{E}", pt.energy).replace("{R}이 ", pt.refuelSubject + " ").replace("{R}", pt.refuel).replace("{P}", pt.place)

    fun rangeKm(soc: Double): Double = soc * KM_PER_PERCENT

    /** Pit Wall input for a scene at the given (possibly animated) charge. */
    fun input(step: DemoStep, soc: Double, weather: WeatherState, powertrain: Powertrain = Powertrain.EV) =
        PitWallInput(soc, rangeKm(soc), step.remainingKm, weather = weather, demo = true, powertrain = powertrain,
            learned = DemoHistory.learned(KM_PER_PERCENT, powertrain))

    /** Charge shown [elapsedMs] into a charging scene: +1% every [msPerPercent], stopping at the end value. */
    fun chargingSoc(step: DemoStep, elapsedMs: Long, msPerPercent: Long = 350): Int {
        val end = step.socEnd ?: return step.soc
        val gained = (elapsedMs / msPerPercent).toInt()
        return (step.soc + gained).coerceAtMost(end)
    }

    fun percentText(v: Double) = "${v.roundToInt()}%"
}
