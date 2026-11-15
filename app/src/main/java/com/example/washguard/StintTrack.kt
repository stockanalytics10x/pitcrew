package com.example.washguard

/**
 * Stint map for the driving screen: where along the route the charge runs low, from the car's own km per %.
 * Positions are km from here. Pure, unit tested; null when there is no route or no km-per-% yet.
 */
data class StintTrack(
    val totalKm: Double,
    /** Charging should happen inside this stretch: from where the charge reaches 30% to where it reaches 10%. */
    val windowStartKm: Double,
    val windowEndKm: Double,
    /** Where the charge reaches the 20% comfort line. */
    val reserveKm: Double,
    /** Where the battery would run out. Beyond [totalKm] means it reaches the finish. */
    val emptyKm: Double,
    val reachesFinish: Boolean,
    /** Where the chosen charging stop is; null until a real stop is chosen (no lane is drawn for a guess). */
    val pitKm: Double? = null,
    /** Charge before this point (the charge would reach 10%); used while no stop is chosen. */
    val chargeByKm: Double? = null
)

enum class TrackEventKind { BOX, FAULT }

/** Something that happened on this stint, kept with the remaining km at that moment so it can sit behind the car. */
data class TrackEvent(val kind: TrackEventKind, val atMs: Long, val remainingKm: Double, val label: String, val text: String)

object StintMath {
    const val WINDOW_OPEN_PERCENT = 30.0
    const val WINDOW_CLOSE_PERCENT = 10.0

    fun track(plan: PitPlan): StintTrack? {
        val total = plan.remaining.value ?: return null
        val soc = plan.soc.value ?: return null
        val kpp = plan.kmPerPercent.value ?: return null
        if (total <= 0.0 || kpp <= 0.0) return null
        fun at(percent: Double) = ((soc - percent) * kpp).coerceAtLeast(0.0)
        val empty = soc * kpp
        return StintTrack(
            totalKm = total,
            windowStartKm = at(WINDOW_OPEN_PERCENT).coerceAtMost(total),
            windowEndKm = at(WINDOW_CLOSE_PERCENT).coerceAtMost(total),
            reserveKm = at(PitWallEngine.ARRIVAL_COMFORT_PERCENT).coerceAtMost(total),
            emptyKm = empty,
            reachesFinish = empty >= total,
            pitKm = if (plan.needsPit && plan.pitKnown) plan.socAtPitPercent.value?.let { ((soc - it) * kpp).coerceIn(0.0, total) } else null,
            chargeByKm = if (plan.needsPit) at(WINDOW_CLOSE_PERCENT).coerceAtMost(total) else null
        )
    }

    /** Battery along the route (km from here → %): straight to the finish, or down to the pit, up to the target, on to the finish. */
    fun energyProfile(plan: PitPlan): List<Pair<Double, Double>> {
        val t = track(plan) ?: return emptyList()
        val soc = plan.soc.value ?: return emptyList()
        val kpp = plan.kmPerPercent.value ?: return emptyList()
        // Without a chosen stop the engine plans as if charging now, so the profile charges at the start.
        val pit = t.pitKm ?: if (plan.needsPit) 0.0 else null
        val atPit = plan.socAtPitPercent.value; val target = plan.chargeTargetPercent.value
        return if (pit != null && atPit != null && target != null)
            listOf(0.0 to soc, pit to atPit, pit to target, t.totalKm to target - (t.totalKm - pit) / kpp)
        else listOf(0.0 to soc, t.totalKm to soc - t.totalKm / kpp)
    }

    /** Km behind the car for an event recorded at [eventRemainingKm]; null once it is out of view. */
    fun behindKm(eventRemainingKm: Double, nowRemainingKm: Double?, maxKm: Double = 30.0): Double? {
        val now = nowRemainingKm ?: return null
        val d = eventRemainingKm - now
        return if (d < 0 || d > maxKm) null else d
    }

    /** Change of the arrival estimate since this stint started, in % points; null without both values. */
    fun delta(startArrival: Double?, nowArrival: Double?): Double? =
        if (startArrival == null || nowArrival == null) null else nowArrival - startArrival
}
