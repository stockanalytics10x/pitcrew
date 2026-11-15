package com.example.washguard

import kotlin.math.ceil

/**
 * Pit Wall: decides whether the current trip can be finished without a stop, and if not,
 * how much to charge at the pit. Pure Kotlin, no Android, so every rule is unit tested.
 *
 * Every number it returns is either read from the vehicle / navigation (MEASURED), derived
 * only from those readings (ESTIMATED), supplied by the demo script (DEMO), or absent (MISSING).
 * Missing inputs never fall back to a default; the plan says what is missing instead.
 */
object PitWallEngine {

    /** Product rules of thumb, shown to the user as such. Not an official standard. */
    const val ARRIVAL_COMFORT_PERCENT = 20.0
    const val ARRIVAL_MINIMUM_PERCENT = 10.0
    const val CHARGE_STEP_PERCENT = 5
    const val CHARGE_CAP_PERCENT = 80
    const val RAIN_PROBABILITY = 60
    const val RAIN_MM = 1.0

    fun plan(input: PitWallInput): PitPlan {
        val demo = input.demo
        val pt = input.powertrain
        val cap = pt.capPercent
        val measured = if (demo) DataSource.DEMO else DataSource.MEASURED
        val estimated = if (demo) DataSource.DEMO else DataSource.ESTIMATED

        val soc = input.socPercent?.takeIf { it in 0.0..100.0 }
        // A range of 0 km while the battery still has charge is not a real reading (seen on the emulator
        // when only the charge is changed). Then fall back to this car's own last km-per-% from the log.
        val rawRange = input.rangeKm?.takeIf { it >= 0.0 }?.takeIf { it > 0.0 || (soc != null && soc < 1.0) }
        val fallback = input.kmPerPercentFallback?.takeIf { it > 0.0 && it.isFinite() }
        val rangeFromLog = rawRange == null && soc != null && soc > 0.0 && fallback != null
        val lk = input.learned.kmPerPercent?.takeIf { it > 0.0 && it.isFinite() }
        val rangeLearned = rawRange == null && soc != null && soc > 0.0 && lk != null
        val range = if (rangeLearned) soc!! * (input.learned.learnedKmPerPercent ?: lk!!).coerceAtLeast(0.1) else if (rangeFromLog) soc!! * fallback!! else rawRange
        val remaining = input.remainingKm?.takeIf { it >= 0.0 }
        val pitAt = input.pitAtKm?.takeIf { it >= 0.0 }

        val socValue = Sourced(soc, if (soc == null) DataSource.MISSING else measured)
        val rangeValue = Sourced(range, when { range == null -> DataSource.MISSING; rangeFromLog || rangeLearned -> estimated; else -> measured })
        val remainingValue = Sourced(remaining, if (remaining == null) DataSource.MISSING else measured)

        val wash = washVerdict(input.weather)

        // 1. km per percent comes only from the vehicle's own two readings.
        // 1b. Blended with what this car's own drives under conditions like now taught (when there are any).
        val kmPerPercent = lk ?: if (soc != null && range != null && soc > 0.0 && range > 0.0) range / soc else null
        val spread = input.learned.spread
        val kppValue = Sourced(kmPerPercent, if (kmPerPercent == null) DataSource.MISSING else estimated)

        fun result(
            verdict: PitVerdict,
            reason: String,
            arrival: Double? = null,
            target: Int? = null,
            socAtPit: Double? = null,
            secondStop: Boolean = false
        ) = PitPlan(
            verdict = verdict,
            reason = reason,
            soc = socValue,
            range = rangeValue,
            remaining = remainingValue,
            kmPerPercent = kppValue,
            arrivalPercent = Sourced(arrival, if (arrival == null) DataSource.MISSING else estimated),
            chargeTargetPercent = Sourced(target?.toDouble(), if (target == null) DataSource.MISSING else estimated),
            socAtPitPercent = Sourced(socAtPit, if (socAtPit == null) DataSource.MISSING else estimated),
            needsSecondStop = secondStop,
            wash = wash,
            rangeNote = if (rangeLearned) "차량 주행 가능 거리 신호가 없어 이 차의 주행 기록으로 추정했어요."
                else if (rangeFromLog) input.fallbackNote ?: "차량 주행 가능 거리 신호가 없어 추정했어요." else null,
            pitKnown = pitAt != null,
            powertrain = pt,
            arrivalSpread = if (arrival != null) spread else null,
            learnedNote = input.learned.note,
            learnedRainEffect = input.learned.factors.firstOrNull { it.factor == EfficiencyModel.Factor.RAIN }?.effect
        )

        if (soc == null) return result(PitVerdict.UNKNOWN, "${pt.energy} 잔량 신호를 받지 못했어요.")
        if (range == null) return result(PitVerdict.UNKNOWN, "예상 주행 가능 거리 신호를 받지 못했어요.")
        if (kmPerPercent == null) return result(PitVerdict.UNKNOWN, "잔량 또는 주행 가능 거리가 0이라 계산할 수 없어요.")
        if (remaining == null) return result(PitVerdict.NO_DESTINATION, "목적지가 없어 잔량만 확인했어요.")

        // 2. Arrival charge if the driver goes straight to the destination, minus the bias past predictions showed.
        val arrival = soc - remaining / kmPerPercent - input.learned.bias
        // The verdict uses the low end of the ± band: the less certain, the earlier the call.
        val judged = arrival - (spread ?: 0.0)
        val band = spread?.let { "(±${it.toInt()})" } ?: ""

        // 3. Verdict by arrival charge.
        if (judged >= ARRIVAL_COMFORT_PERCENT) {
            return result(PitVerdict.FINISH_OK, "도착 때 약 ${arrival.toInt()}%${band} 남아요.", arrival)
        }
        val verdict = if (judged >= ARRIVAL_MINIMUM_PERCENT) PitVerdict.PIT_ADVISED else PitVerdict.PIT_REQUIRED

        // 4. Charge target at the pit: the distance left after the pit plus the comfort margin.
        //    Without a known pit position, plan as if charging now (the whole remaining distance).
        val pitDistance = pitAt?.coerceAtMost(remaining) ?: 0.0
        val socAtPit = soc - pitDistance / kmPerPercent
        if (socAtPit < 0.0) {
            return result(
                PitVerdict.PIT_REQUIRED,
                "지금 잔량으로는 그 피트까지 가기 어려워요. 더 가까운 곳이 필요해요.",
                arrival, null, socAtPit
            )
        }
        val afterPitKm = remaining - pitDistance
        val needed = afterPitKm / kmPerPercent + ARRIVAL_COMFORT_PERCENT + input.learned.bias.coerceAtLeast(0.0) + (spread ?: 0.0)
        val rounded = (ceil(needed / CHARGE_STEP_PERCENT) * CHARGE_STEP_PERCENT).toInt()
        val target = rounded.coerceAtMost(cap).coerceAtLeast(ceil(socAtPit).toInt())
        val secondStop = rounded > cap

        val now = if (arrival < 0.0) "이대로는 도착 전에 ${pt.energy}가 바닥나요." else "이대로 가면 도착 때 약 ${arrival.toInt()}%${band}예요."
        val reason = when {
            secondStop -> "$now ${cap}%까지 ${pt.refuel}해도 여유가 부족해 한 번 더 들러야 해요."
            verdict == PitVerdict.PIT_REQUIRED -> "$now ${target}%까지 ${pt.refuelSubject} 필요해요."
            else -> "$now ${target}%까지 ${pt.refuel}하면 여유 있게 도착해요."
        }
        return result(verdict, reason, arrival, target, socAtPit, secondStop)
    }

    /** Wash decision from the forecast only. It never judges how dirty the car is. */
    fun washVerdict(weather: WeatherState): WashPlan {
        val forecast = weather.forecast
        val probability = forecast?.probability
        val mm = forecast?.precipitationMm
        return when {
            weather.nearRain != null -> WashPlan(WashVerdict.HOLD, "강수 중이라 세차는 미뤄요.")
            forecast == null || !weather.nearKnown -> WashPlan(WashVerdict.UNKNOWN, "예보를 받지 못해 세차 판단을 보류해요.")
            (probability != null && probability >= RAIN_PROBABILITY) || (mm ?: 0.0) >= RAIN_MM ->
                WashPlan(WashVerdict.HOLD, "강수 예보 ${probability ?: 0}%라 비가 그친 뒤 다시 볼게요.")
            else -> WashPlan(WashVerdict.OK, "강수 예보 ${probability}%라 세차해도 괜찮아요. 오염 정도는 직접 확인하세요.")
        }
    }
}

data class PitWallInput(
    /** Battery state of charge from the vehicle, 0..100. */
    val socPercent: Double?,
    /** Range the vehicle reports, km. */
    val rangeKm: Double?,
    /** Distance left to the destination from navigation, km. Null when no route is active. */
    val remainingKm: Double?,
    /** Distance from here to the chosen pit stop along the route, km. Null = plan as if charging now. */
    val pitAtKm: Double? = null,
    val weather: WeatherState = WeatherState(),
    /** True when any of the numbers come from the demo script rather than the vehicle. */
    val demo: Boolean = false,
    /** This car's own last km per % (from the energy log), used only when the live range reading is unusable. */
    val kmPerPercentFallback: Double? = null,
    /** Shown on screen when [kmPerPercentFallback] was used: where that number came from. */
    val fallbackNote: String? = null,
    /** EV (battery, charge) or combustion (fuel, refuel): changes the words and the fill-up cap. */
    val powertrain: Powertrain = Powertrain.EV,
    /** What this car's own drives taught (efficiency under conditions like now, bias, ± band). NONE = not used. */
    val learned: EfficiencyModel.Learned = EfficiencyModel.NONE
)

enum class PitVerdict {
    FINISH_OK,      // 도착 예상 ≥ 20%
    PIT_ADVISED,    // 10% ≤ 도착 예상 < 20%
    PIT_REQUIRED,   // 도착 예상 < 10%
    NO_DESTINATION, // 경로 없음
    UNKNOWN         // 차량 신호 부족
}

enum class DataSource { MEASURED, ESTIMATED, DEMO, MISSING }

data class Sourced(val value: Double?, val source: DataSource) {
    val label: String get() = when (source) {
        DataSource.MEASURED -> "실측"
        DataSource.ESTIMATED -> "추정"
        DataSource.DEMO -> "시연"
        DataSource.MISSING -> "없음"
    }
}

enum class WashVerdict { OK, HOLD, UNKNOWN }

data class WashPlan(val verdict: WashVerdict, val reason: String)

data class PitPlan(
    val verdict: PitVerdict,
    val reason: String,
    val soc: Sourced,
    val range: Sourced,
    val remaining: Sourced,
    val kmPerPercent: Sourced,
    val arrivalPercent: Sourced,
    val chargeTargetPercent: Sourced,
    val socAtPitPercent: Sourced,
    /** True when even the 80% cap is not enough to arrive with the comfort margin. */
    val needsSecondStop: Boolean,
    val wash: WashPlan,
    /** Set when the live range reading was 0 or missing and the range was estimated; says from what. */
    val rangeNote: String? = null,
    /** True only when a real charging stop was chosen (its distance came in); otherwise the plan assumes charging now. */
    val pitKnown: Boolean = false,
    val powertrain: Powertrain = Powertrain.EV,
    /** ± band of [arrivalPercent] from checked past predictions, % points (null = not enough history). */
    val arrivalSpread: Double? = null,
    /** What the learning used, in words (e.g. "이 차 기록 12회 학습 · 저온·비엔 평소보다 효율 −7%"). */
    val learnedNote: String? = null,
    /** What rain does to this car's efficiency, learned from its drives (e.g. -0.06); null until seen. */
    val learnedRainEffect: Double? = null
) {
    val rangeFromLog: Boolean get() = rangeNote != null
    val needsPit: Boolean get() = verdict == PitVerdict.PIT_ADVISED || verdict == PitVerdict.PIT_REQUIRED
}
