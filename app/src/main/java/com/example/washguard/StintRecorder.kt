package com.example.washguard

/**
 * What happens on a stint, kept outside the screens and testable without Android:
 * the arrival estimate when the stint began, the events placed on the STINT TRACK,
 * and which stop-now problems were already radioed.
 */
class StintRecorder {
    var events: List<TrackEvent> = emptyList()
        private set
    var startArrival: Double? = null
        private set

    private var wasDriving = false
    private var seenBox = 0
    private val seenFaults = mutableSetOf<String>()
    private val radioed = mutableSetOf<String>()

    /**
     * Call whenever any input changes. Returns the radio lines to say now (stop-now problems, once each while driving).
     */
    fun update(driving: Boolean, remainingKm: Double?, arrival: Double?, boxHistory: List<BoxCallRecord>, crew: CrewCheck?,
               nowMs: Long): List<String> {
        if (driving != wasDriving) {
            // A new stint starts (or ends): forget the last stint's events; earlier Box calls are not this stint's.
            events = emptyList(); seenFaults.clear(); seenBox = boxHistory.size
            startArrival = null
            wasDriving = driving
        }
        val say = mutableListOf<String>()
        if (driving) {
            if (startArrival == null) startArrival = arrival
            if (remainingKm != null) {
                if (boxHistory.size > seenBox) {
                    events = events + boxHistory.drop(seenBox).map { TrackEvent(TrackEventKind.BOX, it.atMs, remainingKm, "BOX CALL", "Box call") }
                    seenBox = boxHistory.size
                }
                crew?.cards?.filter { it.id !in seenFaults }?.forEach { c ->
                    seenFaults += c.id
                    events = events + TrackEvent(TrackEventKind.FAULT, nowMs, remainingKm, if (c.id == "tpms") "TPMS !" else "${c.title} !", "${c.title} · ${c.plan}")
                }
            }
            crew?.cards?.firstOrNull { it.level == FaultLevel.STOP_NOW && it.id !in radioed }?.let { radioed += it.id; say += FaultTriage.radioLine(it) }
        }
        if (crew?.cards?.none { it.level == FaultLevel.STOP_NOW } == true) radioed.clear()
        return say
    }
}

/**
 * Real driving spells become LAST DRIVE records: one starts when the car leaves P and is saved when it is back in P
 * (spells shorter than a minute are dropped). Demo scenes never make records.
 */
class DriveSpellRecorder(private val minMs: Long = 60_000L) {
    private var start: DriveRecord? = null

    /** Returns a finished record to save, or null. */
    fun update(access: DriveAccess, demoOn: Boolean, soc: Double?, odoKm: Double?, arrival: Double?, boxHistory: List<BoxCallRecord>, nowMs: Long,
               routeKm: Double? = null, tempC: Double? = null, rainy: Boolean? = null): DriveRecord? {
        if (demoOn) return null
        val s = start
        if (access == DriveAccess.RESTRICTED && s == null) {
            start = DriveRecord(nowMs, nowMs, soc, null, odoKm, null, arrival, boxHistory.size, 0, routeKm, tempC, rainy)
        } else if (access == DriveAccess.PARKED && s != null) {
            start = null
            if (nowMs - s.startMs >= minMs) {
                val calls = boxHistory.drop(s.boxCalls)
                return s.copy(endMs = nowMs, endSoc = soc, endOdoKm = odoKm, boxCalls = calls.size, boxAccepted = calls.count { it.outcome == BoxOutcome.ACCEPTED })
            }
        }
        return null
    }
}
