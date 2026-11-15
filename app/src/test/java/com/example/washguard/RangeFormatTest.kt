package com.example.washguard

import org.junit.Assert.*
import org.junit.Test

class RangeFormatTest {
    @Test fun convertsMetersAndRetainsZero() {
        assertEquals(123.45, rangeKilometers(123450.0)!!, 0.00001)
        assertEquals(0.0, rangeKilometers(0.0)!!, 0.0)
    }
    @Test fun missingAndInvalidAreNotZero() {
        assertNull(rangeKilometers(null))
        assertNull(rangeKilometers(-1.0))
        assertNull(rangeKilometers(Double.NaN))
        assertNull(rangeKilometers(Double.POSITIVE_INFINITY))
    }
}
