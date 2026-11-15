package com.example.washguard

/** RANGE_REMAINING is meters; zero is valid, invalid readings must not become zero. */
fun rangeKilometers(meters: Double?): Double? = meters?.takeIf { it.isFinite() && it >= 0 }?.div(1000.0)
