package com.example.washguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WashGuardStateTest {
    private val s = WashCheckStatus.SAFE
    private val u = WashCheckStatus.UNSUPPORTED
    private val a = WashCheckStatus.ACTION_REQ

    private fun state(vararg v: WashCheckStatus) =
        WashGuardState(windowFrontLeft = v[0], windowFrontRight = v[1], windowRearLeft = v[2],
            windowRearRight = v[3], hvacRecirculation = v[4], wiperSwitch = v[5])

    @Test fun unknownIsNeverReady() {
        val all = state(u, u, u, u, u, u)
        assertFalse(all.isReady)
        assertFalse(all.message.contains("완료"))
        assertTrue(all.message.contains("직접 확인"))
        assertFalse(state(s, s, s, s, s, u).isReady)
    }

    @Test fun readyOnlyWhenEveryItemReadBackSafe() {
        assertTrue(state(s, s, s, s, s, s).isReady)
    }

    @Test fun actionItemsAreNamed() {
        val st = state(a, s, s, s, a, u)
        assertFalse(st.isReady)
        assertTrue(st.message.contains("운전석 창문"))
        assertTrue(st.message.contains("내기순환"))
        assertTrue(st.message.contains("와이퍼"))
    }

    @Test fun checkingIsNotReady() {
        val st = WashGuardState()
        assertFalse(st.isReady)
        assertTrue(st.message.contains("확인하는 중"))
    }

    @Test fun failedRequestsAreReported() {
        val st = state(a, s, s, s, s, s).copy(failedRequests = listOf("운전석 창문"))
        assertTrue(st.message.contains("요청 실패: 운전석 창문"))
    }
}
