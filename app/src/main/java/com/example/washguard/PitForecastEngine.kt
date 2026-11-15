package com.example.washguard

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/** One day of the KMA short-range forecast. Today counts only the hours still ahead. */
data class DayForecast(
    val date: LocalDate,
    /** Highest hourly probability of precipitation that day, %. Null when no hourly value arrived. */
    val maxPop: Int?,
    /** Total forecast precipitation, mm. Null when any hour was missing. */
    val rainMm: Double?,
    /** True when every expected hour had a probability value. */
    val complete: Boolean
)

/** A vehicle reading saved by [EnergyLog]. Any field can be missing. */
data class EnergySample(val time: Long, val soc: Double?, val odometerKm: Double?, val rangeKm: Double?)

data class PitForecastInput(
    val nowMs: Long,
    val socPercent: Double?,
    val rangeKm: Double?,
    /** Navi distance left today, km; only when a fresh active route exists. */
    val remainingKm: Double?,
    val samples: List<EnergySample>,
    val days: List<DayForecast>,
    /** Ultra-short forecast: first rain time in the next hours, or null. */
    val nearRain: String?,
    val lastWash: Long,
    /** Hours with observed rain/snow near the car since the last wash (ASOS). */
    val wetHoursSinceWash: Int,
    val zone: ZoneId = KST_ZONE,
    val powertrain: Powertrain = Powertrain.EV,
    /** Finished drives, for the weekday pattern (km per weekday). */
    val records: List<DriveRecord> = emptyList(),
    /** km per % learned from this car's drives; replaces range ÷ level when present. */
    val learnedKmPerPercent: Double? = null,
    /** What rain does to this car's efficiency, learned (e.g. -0.08). Applied on forecast days with rain. */
    val rainEffect: Double? = null
)

enum class ChargeStatus { NOW, BY_DATE, LATER, NO_HISTORY, UNKNOWN }
enum class DayWeather { DRY, WET, UNKNOWN }
enum class WashRating { GOOD, OK, BAD, UNKNOWN }
enum class WashNeed { EXPOSED, DUE, NO_RECORD, RECENT }

data class ChargeForecast(
    val status: ChargeStatus,
    val kmPerPercent: Sourced,
    val dailyKm: Sourced,
    val dailyUsePercent: Sourced,
    /** Days of odometer history behind [dailyKm]. */
    val historyDays: Double,
    /** Charge expected at the start of each forecast day (index 0 = now). */
    val morningSoc: List<Double?>,
    /** Day index (0 = today) by which to charge. May be beyond the forecast strip. */
    val chargeByIndex: Int?,
    val chargeByDate: LocalDate?,
    /** Km the car can still go before reaching 20%, from the car's own numbers. */
    val kmTo20: Sourced,
    val reason: String,
    /** Chance (0–1) that the level is under 20% by the end of each forecast day, from this car's own past days. */
    val risk: List<Double> = emptyList()
)

data class WashDay(val date: LocalDate, val weather: DayWeather, val rating: WashRating, val pop: Int?, val rainMm: Double?)

data class WashForecast(
    val days: List<WashDay>,
    val recommendedIndex: Int?,
    val need: WashNeed,
    val needText: String,
    val reason: String
)

data class PitForecast(
    val charge: ChargeForecast,
    val wash: WashForecast,
    /** Day index where charging and washing fit together, if any. */
    val combinedIndex: Int?,
    val headline: String,
    /** How each number was derived, in order. Shown on screen. */
    val derivation: List<String>,
    val powertrain: Powertrain = Powertrain.EV
)

val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

/**
 * Pit forecast: when to charge and when to wash over the next few days.
 * Pure Kotlin. Every result comes from the car's own readings, the logged odometer and the KMA forecast;
 * with too little history it says so instead of guessing a date.
 */
object PitForecastEngine {
    const val SAFE_SOC = 20.0
    const val HISTORY_WINDOW_DAYS = 14L
    const val MIN_HISTORY_DAYS = 3.0
    const val WASH_DUE_DAYS = 14L
    const val FORECAST_DAYS = 4
    private const val DAY_MS = 86_400_000L

    fun forecast(input: PitForecastInput): PitForecast {
        val today = Instant.ofEpochMilli(input.nowMs).atZone(input.zone).toLocalDate()
        val charge = charge(input, today)
        val wash = wash(input, today)

        val k = charge.chargeByIndex
        val w = wash.recommendedIndex
        val combined = if (k != null && w != null && w <= k && k < FORECAST_DAYS) w else null

        val pt = input.powertrain
        val headline = when {
            combined != null -> "${dayLabel(today, combined)} ${pt.refuel}하면서 세차까지"
            charge.status == ChargeStatus.NOW -> "오늘 ${pt.refuelSubject} 필요해요"
            charge.status == ChargeStatus.BY_DATE && k != null -> "${dayLabel(today, k)}까지 ${pt.refuel}하세요"
            w != null && wash.need != WashNeed.RECENT -> "${dayLabel(today, w)} 세차가 좋아요"
            w != null -> "세차는 급하지 않아요 · ${dayLabel(today, w)}가 좋아요"
            else -> "앞으로 ${FORECAST_DAYS}일 안에 정할 피트가 없어요"
        }
        return PitForecast(charge, wash, combined, headline, derivation(input, charge, wash, today), pt)
    }

    // ---- charge ----

    private const val MAX_KMH = 200.0
    const val MAX_DAILY_KM = 600.0

    /**
     * A plain-words note when the daily distance should not be trusted yet: too little history, an odometer log that
     * looks wrong, or nothing at all. Null when the number is fine to show as it is.
     */
    fun dailyNote(c: ChargeForecast): String? = when {
        c.dailyKm.value == null && c.historyDays >= MIN_HISTORY_DAYS -> "주행거리계 기록이 이상해서 다시 확인하고 있어요. 하루 평균은 기록이 정상으로 쌓이면 다시 계산해요"
        c.dailyKm.value == null -> "기록이 ${"%.0f".format(c.historyDays)}일치뿐이라 아직 계산하지 않았어요. 3일 넘게 쌓이면 계산해요"
        c.historyDays < 7 -> "기록이 ${"%.0f".format(c.historyDays)}일치라 아직 정확하지 않을 수 있어요. 1주일이 쌓이면 더 정확해져요"
        else -> null
    }

    /** Average km per day from the odometer over the last 14 days. Null with less than 3 days of history. */
    fun dailyKm(samples: List<EnergySample>, nowMs: Long): Pair<Double?, Double> {
        val window = samples.filter { it.odometerKm != null && it.time <= nowMs && it.time >= nowMs - HISTORY_WINDOW_DAYS * DAY_MS }
            .sortedBy { it.time }
            .filter { it.odometerKm!! > 0.0 }                // 0 = the car sent no odometer
        if (window.size < 2) return null to 0.0
        val span = (window.last().time - window.first().time).toDouble() / DAY_MS
        if (span < MIN_HISTORY_DAYS) return null to span
        // Add up step by step and skip steps no car could drive (faster than 200 km/h on average, or backwards):
        // an odometer that was reset or set by hand (emulator, new head unit) must not count as driving.
        var delta = 0.0; var good = 0
        window.zipWithNext { a, b ->
            val step = b.odometerKm!! - a.odometerKm!!
            val hours = (b.time - a.time) / 3_600_000.0
            if (step >= 0 && step <= MAX_KMH * hours + 5.0) { delta += step; good++ }
        }
        if (good == 0) return null to span
        val daily = delta / span
        if (daily > MAX_DAILY_KM) return null to span           // no one drives this much every day: the log is wrong
        return daily to span
    }

    /** Weekday pattern window and the drives per weekday needed to trust it. */
    const val PATTERN_DAYS = 28L
    const val PATTERN_MIN_WEEKS = 2

    /**
     * Average km driven on each weekday over the last [PATTERN_DAYS], from finished drives (odometer).
     * Days without a drive count as 0 km. Null until the records span [PATTERN_MIN_WEEKS] weeks.
     */
    fun weekdayKm(records: List<DriveRecord>, nowMs: Long, zone: ZoneId = KST_ZONE): Map<java.time.DayOfWeek, Double>? =
        weekdayStats(records, nowMs, zone)?.mapValues { it.value.first }

    /**
     * Per weekday: (usual km, km to plan with). The second adds [PATTERN_CAUTION] standard deviations of that weekday,
     * so a busy week does not find the battery lower than planned: an early reminder is cheap, a late one is not.
     */
    fun weekdayStats(records: List<DriveRecord>, nowMs: Long, zone: ZoneId = KST_ZONE): Map<java.time.DayOfWeek, Pair<Double, Double>>? {
        val from = nowMs - PATTERN_DAYS * DAY_MS
        val rs = records.filter { it.startMs in from..nowMs && it.startOdoKm != null && it.endOdoKm != null && it.endOdoKm >= it.startOdoKm }
        if (rs.isEmpty()) return null
        val firstDay = java.time.Instant.ofEpochMilli(rs.minOf { it.startMs }).atZone(zone).toLocalDate()
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val spanDays = java.time.temporal.ChronoUnit.DAYS.between(firstDay, today).toInt()
        if (spanDays < PATTERN_MIN_WEEKS * 7) return null
        val perDay = rs.groupBy { java.time.Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
            .mapValues { (_, v) -> v.sumOf { it.endOdoKm!! - it.startOdoKm!! } }
        val days = (0 until spanDays).map { firstDay.plusDays(it.toLong()) }
        // Each weekday has only a few samples (4 in 4 weeks), so it is pulled toward the overall daily average:
        // one unusual Tuesday must not make every Tuesday look like a road trip.
        val overall = days.sumOf { perDay[it] ?: 0.0 } / days.size
        val overallSd = kotlin.math.sqrt(days.sumOf { ((perDay[it] ?: 0.0) - overall).let { d -> d * d } } / days.size)
        return java.time.DayOfWeek.values().associateWith { d ->
            val ks = days.filter { it.dayOfWeek == d }.map { perDay[it] ?: 0.0 }
            val mean = (ks.sum() + PATTERN_SHRINK * overall) / (ks.size + PATTERN_SHRINK)
            val sd = kotlin.math.sqrt((ks.sumOf { (it - mean) * (it - mean) } + PATTERN_SHRINK * overallSd * overallSd) / (ks.size + PATTERN_SHRINK))
            mean to mean + PATTERN_CAUTION * sd
        }
    }

    const val PATTERN_CAUTION = 0.0

    /** Replays of the past days, and the chance of going under 20% that sets the charge day. */
    const val RUNS = 500
    const val RISK = 0.2

    /** One past day: km driven, and how efficient that day was compared with the window as a whole (1 = usual). */
    data class PastDay(val km: Double, val efficiency: Double)

    /** Each past day in the pattern window, by weekday, and all of them. Null until two weeks of drives. */
    fun weekdaySamples(records: List<DriveRecord>, nowMs: Long, zone: ZoneId = KST_ZONE): Pair<Map<java.time.DayOfWeek, List<PastDay>>, List<PastDay>>? {
        val from = nowMs - PATTERN_DAYS * DAY_MS
        val rs = records.filter { it.startMs in from..nowMs && it.startOdoKm != null && it.endOdoKm != null && it.endOdoKm >= it.startOdoKm }
        if (rs.isEmpty()) return null
        val firstDay = Instant.ofEpochMilli(rs.minOf { it.startMs }).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val spanDays = java.time.temporal.ChronoUnit.DAYS.between(firstDay, today).toInt()
        if (spanDays < PATTERN_MIN_WEEKS * 7) return null
        val byDay = rs.groupBy { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
        val perDay = byDay.mapValues { (_, v) -> v.sumOf { (it.endOdoKm!! - it.startOdoKm!!).coerceAtMost(MAX_DAILY_KM) } }
        // Efficiency of each day from its learnable drives; days with too little use count as usual.
        val trips = EfficiencyModel.clean(EfficiencyModel.trips(rs))
        val overall = trips.sumOf { it.km }.let { km -> if (km > 0) km / trips.sumOf { it.usedPercent } else null }
        val dayEff = trips.groupBy { Instant.ofEpochMilli(it.conditions.atMs).atZone(zone).toLocalDate() }.mapValues { (_, ts) ->
            val used = ts.sumOf { it.usedPercent }
            if (overall == null || used < 3) 1.0 else (ts.sumOf { it.km } / used / overall).coerceIn(0.6, 1.4)
        }
        val days = (0 until spanDays).map { firstDay.plusDays(it.toLong()) }
        val all = days.map { PastDay(perDay[it] ?: 0.0, dayEff[it] ?: 1.0) }
        return days.indices.groupBy({ days[it].dayOfWeek }, { all[it] }) to all
    }

    /** Pseudo-days of the overall average added to each weekday. */
    const val PATTERN_SHRINK = 1.0

    private fun charge(input: PitForecastInput, today: LocalDate): ChargeForecast {
        val soc = input.socPercent?.takeIf { it in 0.0..100.0 }
        val range = input.rangeKm?.takeIf { it > 0.0 }
        val kpp = input.learnedKmPerPercent?.takeIf { it > 0.0 } ?: if (soc != null && range != null && soc > 0.0) range / soc else null
        val (daily, span) = dailyKm(input.samples, input.nowMs)
        val use = if (daily != null && kpp != null) daily / kpp else null

        val kppS = Sourced(kpp, if (kpp == null) DataSource.MISSING else DataSource.ESTIMATED)
        val dailyS = Sourced(daily, if (daily == null) DataSource.MISSING else DataSource.ESTIMATED)
        val useS = Sourced(use, if (use == null) DataSource.MISSING else DataSource.ESTIMATED)
        val to20 = if (soc != null && kpp != null) ((soc - SAFE_SOC) * kpp).coerceAtLeast(0.0) else null
        val to20S = Sourced(to20, if (to20 == null) DataSource.MISSING else DataSource.ESTIMATED)

        fun result(status: ChargeStatus, reason: String, morning: List<Double?> = List(FORECAST_DAYS) { null }, by: Int? = null, risk: List<Double> = emptyList()) =
            ChargeForecast(status, kppS, dailyS, useS, span, morning, by, by?.let { today.plusDays(it.toLong()) }, to20S, reason, risk)

        if (soc == null || kpp == null) return result(ChargeStatus.UNKNOWN, "잔량 또는 주행 가능 거리 신호가 없어요.")
        val trip = input.remainingKm?.takeIf { it >= 0.0 }?.let { it / kpp } ?: 0.0
        val start = soc - trip
        if (start <= SAFE_SOC) return result(
            ChargeStatus.NOW,
            if (trip > 0) "오늘 목적지까지 가면 ${start.coerceAtLeast(0.0).roundToInt()}%예요." else "지금 ${soc.roundToInt()}%라 여유가 적어요.",
            listOf(start.coerceAtLeast(0.0)) + List(FORECAST_DAYS - 1) { null }, 0
        )
        val stats = weekdayStats(input.records, input.nowMs, input.zone)
        val pattern = stats?.mapValues { it.value.first }
        val planKm = stats?.mapValues { it.value.second }
        if (use == null && pattern == null) return result(
            ChargeStatus.NO_HISTORY,
            "20%까지 약 ${to20!!.roundToInt()} km 남았어요. 주행 기록이 ${MIN_HISTORY_DAYS.toInt()}일 이상 모이면 날짜를 계산해요.",
            listOf(start) + List(FORECAST_DAYS - 1) { null }
        )
        // Weekday pattern when there are two weeks of drives; otherwise the same use every day.
        val byDate = input.days.associateBy { it.date }
        val rainCut = input.rainEffect?.takeIf { it < 0.0 }
        fun kppOn(i: Int) = if (rainCut != null && dayWeather(byDate[today.plusDays(i.toLong())]) == DayWeather.WET) kpp * (1 + rainCut) else kpp
        // Today: only what is usually still ahead (the usual distance minus what was already driven today).
        val drivenToday = input.records.filter { Instant.ofEpochMilli(it.startMs).atZone(input.zone).toLocalDate() == today && it.endMs <= input.nowMs }
            .sumOf { r -> ((r.endOdoKm ?: 0.0) - (r.startOdoKm ?: 0.0)).coerceIn(0.0, 1000.0) }
        val dayUse = List(FORECAST_DAYS + 7) { i ->
            val km = planKm?.get(today.plusDays(i.toLong()).dayOfWeek) ?: daily ?: 0.0
            (if (i == 0) (km - drivenToday).coerceAtLeast(0.0) else km) / kppOn(i)
        }
        val rainyDays = (0 until FORECAST_DAYS).count { kppOn(it) != kpp }
        val morning = List(FORECAST_DAYS) { i -> (start - (0 until i).sumOf { dayUse[it] }).coerceAtLeast(0.0) }
        if (dayUse.take(7).sum() < 0.35) return result(ChargeStatus.LATER, "기록상 주행이 거의 없어 당분간 ${input.powertrain.refuelSubject} 필요 없어요.", morning)
        var level = start; var by = 0
        while (by < FORECAST_DAYS + 7 && level - dayUse[by] >= SAFE_SOC) { level -= dayUse[by]; by++ }
        // With a weekday history the day comes from chances instead: replay this car's own past days many times.
        val pool = weekdaySamples(input.records, input.nowMs, input.zone)
        val risk = pool?.let { (byDow, all) ->
            val rnd = java.util.Random(today.toEpochDay())
            val hit = IntArray(FORECAST_DAYS + 7)
            repeat(RUNS) {
                var lv = start
                for (i in 0 until FORECAST_DAYS + 7) {
                    val ks = byDow[today.plusDays(i.toLong()).dayOfWeek].orEmpty()
                    // Mostly that weekday's own days; now and then any day, so an unseen busy day stays possible.
                    val pd = if (ks.isEmpty() || rnd.nextInt(ks.size + 1) == 0) all[rnd.nextInt(all.size)] else ks[rnd.nextInt(ks.size)]
                    // That day's distance, at that day's efficiency relative to now: a bad day is replayed as a bad day.
                    val km = if (i == 0) (pd.km - drivenToday).coerceAtLeast(0.0) else pd.km
                    lv -= km / (kppOn(i) * pd.efficiency)
                    if (lv < SAFE_SOC) { hit[i]++; break }
                }
            }
            var acc = 0; hit.map { acc += it; acc.toDouble() / RUNS }
        }
        if (risk != null) by = risk.indexOfFirst { it >= RISK }.let { if (it < 0) FORECAST_DAYS + 7 else it }
        val status = if (by < FORECAST_DAYS) ChargeStatus.BY_DATE else ChargeStatus.LATER
        val how = if (pattern != null) {
            val wk = java.time.DayOfWeek.values().filter { !EfficiencyModel.weekend(it) }.map { pattern[it]!! }.average()
            val we = listOf(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY).map { pattern[it]!! }.average()
            "요일별 기록으로 보면 평일 하루 약 ${wk.roundToInt()}km, 주말 약 ${we.roundToInt()}km를 달려요. "
        } else "하루 약 ${use!!.roundToInt().coerceAtLeast(1)}%씩 쓰면 "
        val rainNote = if (rainyDays > 0) "비 예보 ${rainyDays}일은 이 차 기록대로 ${if (input.powertrain.usesFuel) "연비" else "전비"} ${(kotlin.math.abs(rainCut!!) * 100).roundToInt()}% 낮게 잡았어요. " else ""
        val chance = risk?.getOrNull(by)?.let { "이 차의 지난 4주를 ${RUNS}번 다시 굴려 보면 ${dayLabel(today, by)}까지 20% 아래로 갈 확률이 ${(it * 100).roundToInt()}%예요. " } ?: "${dayLabel(today, by)} 주행 중 20% 아래로 내려가요. "
        val reason = "$how$rainNote$chance${if (by < FORECAST_DAYS + 7) "그날 출발 전까지 ${input.powertrain.refuel}하세요." else ""}"
        return result(if (risk != null && by >= FORECAST_DAYS) ChargeStatus.LATER else status, reason, morning, by.takeIf { it < FORECAST_DAYS + 7 } ?: by, risk?.take(FORECAST_DAYS).orEmpty())
    }

    // ---- wash ----

    fun dayWeather(day: DayForecast?): DayWeather = when {
        day == null || !day.complete || day.maxPop == null -> DayWeather.UNKNOWN
        day.maxPop >= PitWallEngine.RAIN_PROBABILITY || (day.rainMm ?: 0.0) >= PitWallEngine.RAIN_MM -> DayWeather.WET
        else -> DayWeather.DRY
    }

    private fun wash(input: PitForecastInput, today: LocalDate): WashForecast {
        val byDate = input.days.associateBy { it.date }
        val dates = (0 until FORECAST_DAYS).map { today.plusDays(it.toLong()) }
        val weather = dates.mapIndexed { i, d ->
            if (i == 0 && input.nearRain != null) DayWeather.WET else dayWeather(byDate[d])
        }
        // Day after the strip is used only to rate the last day.
        val after = dayWeather(byDate[today.plusDays(FORECAST_DAYS.toLong())])
        val days = dates.mapIndexed { i, d ->
            val next = if (i + 1 < FORECAST_DAYS) weather[i + 1] else after
            val rating = when (weather[i]) {
                DayWeather.UNKNOWN -> WashRating.UNKNOWN
                DayWeather.WET -> WashRating.BAD
                DayWeather.DRY -> when (next) { DayWeather.DRY -> WashRating.GOOD; DayWeather.WET -> WashRating.BAD; DayWeather.UNKNOWN -> WashRating.OK }
            }
            WashDay(d, weather[i], rating, byDate[d]?.maxPop, byDate[d]?.rainMm)
        }
        val rec = days.indexOfFirst { it.rating == WashRating.GOOD }.takeIf { it >= 0 }
            ?: days.indexOfFirst { it.rating == WashRating.OK }.takeIf { it >= 0 }

        val daysSince = if (input.lastWash > 0) (input.nowMs - input.lastWash) / DAY_MS else null
        val (need, needText) = when {
            input.lastWash <= 0 -> WashNeed.NO_RECORD to "세차 기록이 없어요"
            input.wetHoursSinceWash > 0 -> WashNeed.EXPOSED to "세차 후 근처 관측소에서 비·눈 ${input.wetHoursSinceWash}시간"
            daysSince != null && daysSince >= WASH_DUE_DAYS -> WashNeed.DUE to "세차한 지 ${daysSince}일"
            else -> WashNeed.RECENT to "세차한 지 ${daysSince ?: 0}일 · 비 맞은 기록 없음"
        }
        val reason = when {
            rec == null && weather.all { it == DayWeather.UNKNOWN } -> "예보를 받지 못해 세차일을 고를 수 없어요."
            rec == null -> "앞으로 ${FORECAST_DAYS}일은 비가 오거나 바로 다음 날 비 예보가 있어요."
            days[rec].rating == WashRating.GOOD -> "${dayLabel(today, rec)}와 다음 날 모두 비 예보가 없어요."
            else -> "${dayLabel(today, rec)}는 맑지만 다음 날 예보는 아직 없어요."
        }
        return WashForecast(days, rec, need, needText, reason)
    }

    // ---- explanation ----

    private fun derivation(input: PitForecastInput, c: ChargeForecast, w: WashForecast, today: LocalDate): List<String> = buildList {
        val soc = input.socPercent; val range = input.rangeKm
        // km per % can come from past drives while the car's range or charge reading is still missing.
        val kpp = c.kmPerPercent.value
        when {
            kpp != null && range != null && soc != null && soc > 0 ->
                add("1%당 ${fmt(kpp)} km = 주행 가능 ${range.roundToInt()} km ÷ 잔량 ${soc.roundToInt()}% (차량 값)")
            kpp != null -> add("1%당 ${fmt(kpp)} km (지난 주행 기록에서 학습)")
            else -> add("1%당 주행거리: 잔량·주행 가능 거리 신호 대기")
        }
        val daily = c.dailyKm.value
        if (daily != null) add("하루 평균 ${fmt(daily)} km = 최근 오도미터 증가량 ÷ ${fmt(c.historyDays)}일")
        else add("주행 기록 ${fmt(c.historyDays)}일치 · ${MIN_HISTORY_DAYS.toInt()}일 이상 모이면 ${input.powertrain.refuel} 날짜를 계산해요")
        c.dailyUsePercent.value?.let { add("하루 소모 약 ${fmt(it)}% = 하루 평균 거리 ÷ 1%당 주행거리") }
        input.remainingKm?.let { if (c.kmPerPercent.value != null) add("오늘 목적지까지 ${it.roundToInt()} km를 먼저 뺐어요") }
        add("${input.powertrain.refuel} 마감 = 예상 잔량이 ${SAFE_SOC.toInt()}% 아래로 내려가기 전날")
        add("세차일 = 그날과 다음 날 모두 강수확률 ${PitWallEngine.RAIN_PROBABILITY}% 미만·${PitWallEngine.RAIN_MM.toInt()}mm 미만 (기상청 단기예보)")
        add("세차 필요 근거: ${w.needText}. 실제 오염 정도는 알 수 없어요.")
    }

    fun dayLabel(today: LocalDate, index: Int): String {
        val d = today.plusDays(index.toLong())
        val dow = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
        return when (index) {
            0 -> "오늘"
            1 -> "내일($dow)"
            2 -> "모레($dow)"
            else -> "${d.monthValue}/${d.dayOfMonth}($dow)"
        }
    }

    private fun fmt(v: Double): String = if (v >= 10) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)
}
