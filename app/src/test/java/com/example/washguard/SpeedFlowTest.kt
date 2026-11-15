package com.example.washguard

import org.junit.Assert.*
import org.junit.Test

class SpeedFlowTest {
    private fun stamp(seconds:Double)=(seconds*1e9).toLong()+1_000_000_000L
    @Test fun constantSpeedHasZeroAccelerationAndCorrectAverage() {
        val a=SpeedFlowAnalyzer();repeat(10){a.accept(stamp(it.toDouble()),10.0)}
        assertEquals(0.0,a.state.acceleration!!,1e-6)
        assertEquals(36.0,a.state.averageKmh!!,1e-6)
        assertEquals(9.0,a.state.observedSeconds,1e-6)
        assertEquals(0,a.state.accelerationEvents)
    }
    @Test fun sustainedAccelerationCountsOncePerEpisode() {
        val a=SpeedFlowAnalyzer();repeat(5){a.accept(stamp(it.toDouble()),it*3.0)}
        assertEquals(1,a.state.accelerationEvents)
        a.accept(stamp(5.0),12.0);a.accept(stamp(6.0),15.0)
        assertEquals(2,a.state.accelerationEvents)
    }
    @Test fun halfSecondSpikeDoesNotCountAsEvent() {
        val a=SpeedFlowAnalyzer();a.accept(stamp(0.0),10.0);a.accept(stamp(.5),11.5);a.accept(stamp(1.0),11.5)
        assertEquals(0,a.state.accelerationEvents)
    }
    @Test fun decelerationIsNotCalledBrakePedalUse() {
        val a=SpeedFlowAnalyzer();a.accept(stamp(0.0),15.0);a.accept(stamp(1.0),12.0);a.accept(stamp(2.0),9.0)
        assertEquals(1,a.state.decelerationEvents)
        assertTrue(a.state.acceleration!!<0)
    }
    @Test fun gapDoesNotInventAccelerationOrElapsedTime() {
        val a=SpeedFlowAnalyzer();a.accept(stamp(0.0),0.0);a.accept(stamp(1.0),0.0);a.accept(stamp(10.0),20.0)
        assertNull(a.state.acceleration);assertEquals(1,a.state.gaps)
        assertEquals(1.0,a.state.observedSeconds,1e-6);assertEquals(0,a.state.accelerationEvents)
    }
    @Test fun timestampsCannotGoBackwardsOrDuplicate() {
        val a=SpeedFlowAnalyzer();a.accept(stamp(2.0),10.0)
        assertFalse(a.accept(stamp(2.0),20.0));assertFalse(a.accept(stamp(1.0),20.0))
        assertEquals(1,a.state.samples)
    }
    @Test fun reverseAndInvalidSamplesDoNotProduceDrivingEvents() {
        val a=SpeedFlowAnalyzer();a.accept(stamp(0.0),0.0);a.accept(stamp(1.0),-5.0)
        assertNull(a.state.acceleration);assertEquals(0,a.state.accelerationEvents)
        assertFalse(a.accept(stamp(2.0),Double.NaN));assertNull(a.state.speedKmh)
        a.accept(stamp(3.0),10.0);assertNull(a.state.acceleration)
    }
    @Test fun traceIsBoundedForLongSessions() {
        val a=SpeedFlowAnalyzer();repeat(1000){a.accept(stamp(it.toDouble()),10.0)}
        assertEquals(120,a.state.trace.size)
        assertEquals(999.0,a.state.observedSeconds,1e-6)
    }
}
