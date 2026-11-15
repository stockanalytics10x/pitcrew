package com.example.washguard

/**
 * Builds the Pit Wall input from what the app actually received. Pure Kotlin.
 *
 * - SOC comes from the vehicle text ("52%"), range from RangeClient.
 * - Remaining distance counts only while the navi says a route is active and the reading is fresh;
 *   an old or inactive reading becomes "missing", never a guess.
 */
object PitWallInputs {
    /** Navi is polled every 30 s and also pushes events; older than this is treated as stale. */
    const val NAVI_FRESH_MS = 120_000L

    fun socPercent(text: String?): Double? =
        text?.trim()?.removeSuffix("%")?.trim()?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }

    fun remainingKm(navi: NaviSnapshot?, nowMs: Long): Double? {
        if (navi == null || !navi.connected || navi.active != true) return null
        val km = navi.remainKm ?: return null
        if (navi.updated <= 0 || nowMs - navi.updated > NAVI_FRESH_MS) return null
        return km.takeIf { it >= 0.0 }
    }

    /**
     * Last resort when the car sends no usable range and nothing is logged yet: the published spec of the
     * vehicle this app is shown with (IONIQ 5 Long Range 2WD 19", 84.0 kWh, 478 km combined per charge,
     * Hyundai 27MY spec sheet). Always labelled as a spec-based estimate on screen.
     */
    const val SPEC_KM_PER_PERCENT = 4.78
    const val SPEC_NOTE = "차량 주행 가능 거리 신호가 없어 제원 기준(아이오닉 5 롱레인지 1회 충전 478km → 1%당 4.8km)으로 추정했어요."

    /** km per % from this car's most recent usable reading in the energy log, or null. */
    fun kmPerPercentFromLog(samples: List<EnergySample>): Double? =
        samples.filter { (it.soc ?: 0.0) >= 5.0 && (it.rangeKm ?: 0.0) > 0.0 }.maxByOrNull { it.time }
            ?.let { it.rangeKm!! / it.soc!! }

    fun of(
        socText: String?,
        rangeKm: Double?,
        navi: NaviSnapshot?,
        weather: WeatherState,
        nowMs: Long,
        pitAtKm: Double? = null,
        demo: Boolean = false,
        energySamples: List<EnergySample> = emptyList(),
        powertrain: Powertrain = Powertrain.EV,
        learned: EfficiencyModel.Learned = EfficiencyModel.NONE
    ) = PitWallInput(
        powertrain = powertrain,
        socPercent = socPercent(socText),
        rangeKm = rangeKm,
        remainingKm = remainingKm(navi, nowMs),
        pitAtKm = pitAtKm,
        weather = weather,
        demo = demo,
        // The IONIQ 5 spec is a last resort for EVs only; a fuel car without range or log stays "unknown".
        kmPerPercentFallback = kmPerPercentFromLog(energySamples) ?: if (powertrain.usesFuel) null else SPEC_KM_PER_PERCENT,
        fallbackNote = kmPerPercentFromLog(energySamples)
            ?.let { "차량 주행 가능 거리 신호가 0이라, 이 차의 최근 값(1%당 ${String.format(java.util.Locale.US, "%.1f", it)}km)으로 추정했어요." }
            ?: SPEC_NOTE,
        learned = learned
    )
}
