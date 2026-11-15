package com.example.washguard

/**
 * Made-up past drives for demo mode only (labelled 시연), so the learning can be shown on an emulator that has
 * never been driven. The Pit Wall learns from them through the same [EfficiencyModel] it uses for real records.
 *
 * Pattern built in: weekday commutes of ~25 km, some weekend highway trips; dry mild days are the most
 * efficient, rain and cold cost more; past arrival predictions were a little optimistic.
 */
object DemoHistory {
    /** The demo trip to Daegu leaves on a cold, rainy evening. */
    const val TEMP_C = 3.0
    const val RAINY = true

    /** A fixed "now" (Fri 2026-11-13 18:00 KST) so the demo learning is the same on every run. */
    val NOW_MS: Long = java.time.LocalDateTime.of(2026, 11, 13, 18, 0).atZone(KST_ZONE).toInstant().toEpochMilli()

    fun conditions(nowMs: Long) = EfficiencyModel.Conditions(TEMP_C, RAINY, nowMs)

    /** Odometer and charge readings at the start and end of each example drive, for the daily-distance forecast. */
    fun samples(): List<EnergySample> = records(NOW_MS).flatMap { r ->
        listOf(EnergySample(r.startMs, r.startSoc, r.startOdoKm, null), EnergySample(r.endMs, r.endSoc, r.endOdoKm, null))
    }

    fun records(nowMs: Long): List<DriveRecord> {
        var seed = 7L
        fun noise(): Double { seed = (seed * 1103515245 + 12345) and 0x7fffffff; return (seed % 1000) / 1000.0 - 0.5 } // -0.5..0.5
        val day = 86_400_000L
        val out = mutableListOf<DriveRecord>()
        var odo = 18_000.0
        for (d in 60 downTo 1) {
            val start = nowMs - d * day
            val dow = java.time.Instant.ofEpochMilli(start).atZone(KST_ZONE).dayOfWeek
            val weekend = EfficiencyModel.weekend(dow)
            if (!weekend && d % 7 == 3) continue // a day off now and then
            if (weekend && d % 3 != 0) continue
            val cold = d % 5 == 0 || d % 11 == 0
            val rain = d % 4 == 1
            val temp = if (cold) -1.0 + noise() * 4 else 14.0 + noise() * 8
            val km = if (weekend) 170.0 + noise() * 60 else 25.0 + noise() * 6
            // km per %: dry mild 5.0; rain −8%; cold −14%; highway −9%.
            var kpp = 5.0 * (if (rain) 0.92 else 1.0) * (if (cold) 0.86 else 1.0) * (if (weekend) 0.91 else 1.0) * (1 + noise() * 0.04)
            val used = km / kpp
            val startSoc = if (weekend) 85.0 else 70.0 + noise() * 10
            // About every third trip had a route: its prediction was ~2 points optimistic, give or take.
            val routed = d % 3 == 0
            val predicted = if (routed) startSoc - used + 2.0 + noise() * 8 else null
            val startMs = start + (if (weekend) 10 else 8) * 3_600_000L
            out += DriveRecord(startMs, startMs + (km / (if (weekend) 90.0 else 35.0) * 3_600_000).toLong(), startSoc, startSoc - used, odo, odo + km,
                predicted, 0, 0, if (routed) km else null, temp, rain)
            odo += km
        }
        return out
    }

    /** What the Pit Wall learned from the demo history, with the note marked as demo. */
    fun learned(carKmPerPercent: Double, powertrain: Powertrain): EfficiencyModel.Learned =
        EfficiencyModel.learn(records(NOW_MS), conditions(NOW_MS), carKmPerPercent, powertrain).let { it.copy(note = it.note?.let { n -> "시연 기록 · $n" }) }
}
