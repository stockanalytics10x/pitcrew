package com.example.washguard

/**
 * Tells "charging now" from the car's own signals. The SDK has no readable charging state, so it is inferred:
 *  - parked, and the charge port open or the charge switch on  → READY (probably plugged in)
 *  - then the battery rises by [RISE_TO_CONFIRM] %              → CHARGING (confirmed by the battery itself)
 *  - no port/switch signal at all, but the battery rises by [RISE_NO_SIGNAL] % while parked → CHARGING
 *  - port closed / switch off, battery full, or no rise for [STALL_MS] → DONE
 * Leaving P resets everything: a drive never charges.
 */
enum class ChargePhase { NONE, READY, CHARGING, DONE }

data class ChargeSample(val ms: Long, val soc: Int)

data class ChargeSession(
    val phase: ChargePhase = ChargePhase.NONE,
    /** Readings since the session (or, in NONE, the parked baseline) began; one per change of SOC. */
    val samples: List<ChargeSample> = emptyList(),
    /** How it was recognised, in plain words. */
    val basis: String = "",
    val lastRiseMs: Long? = null,
    val endedMs: Long? = null
) {
    val startSoc: Int? get() = samples.firstOrNull()?.soc
    val startMs: Long? get() = samples.firstOrNull()?.ms
    val soc: Int? get() = samples.lastOrNull()?.soc
    val gained: Int get() = (soc ?: 0) - (startSoc ?: 0)

    /** % per minute, measured from the first rise to the latest one. Null until two rises are seen at least a minute apart. */
    val ratePerMin: Double? get() {
        val s = startSoc ?: return null
        val first = samples.firstOrNull { it.soc > s } ?: return null
        val last = samples.last()
        val minutes = (last.ms - first.ms) / 60_000.0
        if (last.soc <= first.soc || minutes < 1.0) return null
        return (last.soc - first.soc) / minutes
    }

    /** Minutes to [target] at the measured rate (estimate). */
    fun minutesTo(target: Int): Int? {
        val now = soc ?: return null
        if (now >= target) return 0
        val r = ratePerMin ?: return null
        return kotlin.math.ceil((target - now) / r).toInt()
    }
}

object ChargeDetector {
    const val RISE_TO_CONFIRM = 1
    const val RISE_NO_SIGNAL = 2
    const val STALL_MS = 5 * 60_000L

    fun step(prev: ChargeSession, parked: Boolean, soc: Int?, port: Boolean?, switch: Boolean?, nowMs: Long): ChargeSession {
        if (!parked) return ChargeSession()
        val plugged = port == true || switch == true
        // Only an explicit "closed / off" ends a session; a missing signal does not.
        val unplugged = (port == false || switch == false) && !plugged
        fun ChargeSession.with(s: Int?): ChargeSession =
            if (s == null || samples.lastOrNull()?.soc == s) this else copy(samples = samples + ChargeSample(nowMs, s))

        return when (prev.phase) {
            ChargePhase.NONE -> when {
                soc == null -> prev
                plugged -> ChargeSession(ChargePhase.READY, listOf(ChargeSample(nowMs, soc)),
                    if (port == true) "충전구 열림" else "충전 스위치 켜짐")
                else -> {
                    val base = prev.samples.firstOrNull()
                    when {
                        base == null || soc < base.soc -> ChargeSession(samples = listOf(ChargeSample(nowMs, soc)))
                        soc - base.soc >= RISE_NO_SIGNAL -> prev.with(soc).copy(phase = ChargePhase.CHARGING,
                            basis = "주차 중 배터리 ${soc - base.soc}% 상승", lastRiseMs = nowMs)
                        else -> prev.with(soc)
                    }
                }
            }
            ChargePhase.READY -> {
                val next = prev.with(soc)
                when {
                    unplugged -> ChargeSession()
                    next.gained >= RISE_TO_CONFIRM -> next.copy(phase = ChargePhase.CHARGING,
                        basis = "${prev.basis} · 배터리 ${next.gained}% 상승", lastRiseMs = nowMs)
                    else -> next
                }
            }
            ChargePhase.CHARGING -> {
                val rose = soc != null && soc > (prev.soc ?: soc)
                val next = prev.with(soc).let { if (rose) it.copy(lastRiseMs = nowMs) else it }
                val stalled = nowMs - (next.lastRiseMs ?: nowMs) >= STALL_MS
                if (unplugged || (next.soc ?: 0) >= 100 || stalled) next.copy(phase = ChargePhase.DONE, endedMs = nowMs) else next
            }
            ChargePhase.DONE -> {
                // Plugged in again and rising: carry on with the same session.
                if (soc != null && soc > (prev.soc ?: soc) && !unplugged)
                    prev.with(soc).copy(phase = ChargePhase.CHARGING, lastRiseMs = nowMs, endedMs = null)
                else prev
            }
        }
    }
}
