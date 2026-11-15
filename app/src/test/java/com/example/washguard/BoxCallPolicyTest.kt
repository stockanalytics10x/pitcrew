package com.example.washguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxCallPolicyTest {
    private fun plan(soc: Double, range: Double, remaining: Double?) = PitWallEngine.plan(PitWallInput(soc, range, remaining))
    private val ok = plan(80.0, 400.0, 100.0)
    private val advised = plan(52.0, 260.0, 170.0)   // ~18%
    private val required = plan(52.0, 260.0, 230.0)  // ~6%
    private val empty = plan(20.0, 80.0, 400.0)      // runs out
    private val now = 1_000_000L

    @Test fun onlyWhileDrivingAndOnlyWhenPitNeeded() {
        assertFalse(BoxCallPolicy.shouldCall(required, driving = false, memory = BoxCallMemory(), nowMs = now))
        assertFalse(BoxCallPolicy.shouldCall(ok, true, BoxCallMemory(), now))
        assertTrue(BoxCallPolicy.shouldCall(advised, true, BoxCallMemory(), now))
        assertTrue(BoxCallPolicy.shouldCall(required, true, BoxCallMemory(), now))
    }

    @Test fun sameLevelDoesNotRepeatUntilCooldownAfterNo() {
        val m = BoxCallPolicy.afterCall(BoxCallMemory(), BoxLevel.ADVISED, BoxOutcome.DECLINED, now)
        assertFalse(BoxCallPolicy.shouldCall(advised, true, m, now + 60_000))
        assertTrue(BoxCallPolicy.shouldCall(advised, true, m, now + BoxCallPolicy.COOLDOWN_MS))
    }

    @Test fun escalationCallsEvenInsideCooldown() {
        val m = BoxCallPolicy.afterCall(BoxCallMemory(), BoxLevel.ADVISED, BoxOutcome.DECLINED, now)
        assertTrue(BoxCallPolicy.shouldCall(required, true, m, now + 1_000))
    }

    @Test fun acceptedStaysQuietAtSameLevel() {
        val m = BoxCallPolicy.afterCall(BoxCallMemory(), BoxLevel.REQUIRED, BoxOutcome.ACCEPTED, now)
        assertFalse(BoxCallPolicy.shouldCall(required, true, m, now + 3 * BoxCallPolicy.COOLDOWN_MS))
    }

    @Test fun finishingOrNoRouteResetsTheTrip() {
        val m = BoxCallPolicy.afterCall(BoxCallMemory(), BoxLevel.REQUIRED, BoxOutcome.ACCEPTED, now)
        assertEquals(BoxCallMemory(), BoxCallPolicy.settle(ok, m))
        assertEquals(BoxCallMemory(), BoxCallPolicy.settle(plan(50.0, 250.0, null), m))
        assertEquals(m, BoxCallPolicy.settle(required, m))
    }

    @Test fun messagesUseEngineNumbersAndNeverNegative() {
        assertTrue(BoxCallPolicy.message(advised).startsWith("Box 콜."))
        assertTrue(BoxCallPolicy.message(required).startsWith("Box, box."))
        assertTrue(BoxCallPolicy.message(required).contains("6%"))
        val e = BoxCallPolicy.message(empty)
        assertTrue(e.contains("바닥"))
        assertFalse(e.contains("-"))
        listOf(advised, required, empty).forEach { p ->
            listOf("원", "절감", "저렴", "최저").forEach { w -> assertFalse(BoxCallPolicy.message(p).contains(w)) }
        }
    }

    @Test fun answersAreRead() {
        listOf("응", "네", "예 찾아줘", "그래", "좋아요", "어").forEach { assertEquals(it, BoxAnswer.YES, BoxCallPolicy.parseAnswer(it)) }
        listOf("아니", "아니 괜찮아", "다음에", "필요 없어", "됐어").forEach { assertEquals(it, BoxAnswer.NO, BoxCallPolicy.parseAnswer(it)) }
        listOf(null, "", "음악 틀어줘").forEach { assertEquals(BoxAnswer.UNKNOWN, BoxCallPolicy.parseAnswer(it)) }
    }
}
