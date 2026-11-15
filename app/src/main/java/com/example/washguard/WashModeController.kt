package com.example.washguard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.HVAC
import ai.pleos.playground.vehicle.api.Window
import ai.pleos.playground.vehicle.api.Wiper
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.WiperSwitch

/**
 * Individual check state for the wash checklist.
 * UNSUPPORTED means "could not confirm" (no permission, not supported, read failed).
 * It is never treated as safe.
 */
enum class WashCheckStatus {
    CHECKING,   // 조회 중
    SAFE,       // 확인됨 (닫힘 / 내기순환 / OFF)
    ACTION_REQ, // 조치 필요 (열림 / 외기유입 / 와이퍼 작동)
    UNSUPPORTED // 확인 불가
}

data class WashGuardState(
    val isExecuting: Boolean = false,
    val windowFrontLeft: WashCheckStatus = WashCheckStatus.CHECKING,
    val windowFrontRight: WashCheckStatus = WashCheckStatus.CHECKING,
    val windowRearLeft: WashCheckStatus = WashCheckStatus.CHECKING,
    val windowRearRight: WashCheckStatus = WashCheckStatus.CHECKING,
    val hvacRecirculation: WashCheckStatus = WashCheckStatus.CHECKING,
    val wiperSwitch: WashCheckStatus = WashCheckStatus.CHECKING,
    /** Write requests the vehicle rejected during the last run (item labels). */
    val failedRequests: List<String> = emptyList(),
    val lastRunNote: String? = null
) {
    val items: List<Pair<String, WashCheckStatus>>
        get() = listOf(
            "운전석 창문" to windowFrontLeft,
            "동승석 창문" to windowFrontRight,
            "후석 좌측 창문" to windowRearLeft,
            "후석 우측 창문" to windowRearRight,
            "내기순환" to hvacRecirculation,
            "와이퍼" to wiperSwitch
        )

    /** Ready only when every item was actually read back as safe. */
    val isReady: Boolean get() = items.all { it.second == WashCheckStatus.SAFE }

    val message: String get() = washGuardMessage(this)
}

/** Pure message logic, kept free of Android so it can be unit tested. */
fun washGuardMessage(state: WashGuardState): String {
    val items = state.items
    val checking = items.any { it.second == WashCheckStatus.CHECKING }
    val action = items.filter { it.second == WashCheckStatus.ACTION_REQ }.map { it.first }
    val unknown = items.filter { it.second == WashCheckStatus.UNSUPPORTED }.map { it.first }
    val prefix = state.lastRunNote?.let { "$it " } ?: ""
    val failed = if (state.failedRequests.isEmpty()) "" else " 요청 실패: ${state.failedRequests.joinToString(", ")}."
    return prefix + when {
        state.isExecuting -> "세차 준비 요청을 보내는 중입니다."
        checking -> "차량 상태를 확인하는 중입니다."
        state.isReady -> "모든 항목이 차량에서 확인됐어요. 기어와 사이드미러는 직접 확인하세요."
        action.isNotEmpty() && unknown.isEmpty() -> "조치가 필요해요: ${action.joinToString(", ")}."
        action.isNotEmpty() -> "조치가 필요해요: ${action.joinToString(", ")}. 확인하지 못한 항목: ${unknown.joinToString(", ")}."
        else -> "차량에서 확인하지 못한 항목이 있어요: ${unknown.joinToString(", ")}. 직접 확인해 주세요."
    } + failed
}

/**
 * Reads the wash-related vehicle state and, on explicit user request while parked,
 * asks the vehicle to close open windows and switch on recirculation.
 * The UI that calls [executeWashMode] is only reachable in the parked Pitstop screen.
 */
class WashModeController(private val context: Context) {
    var state by mutableStateOf(WashGuardState())
        private set

    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var windowApi: Window? = null
    private var hvacApi: HVAC? = null
    private var wiperApi: Wiper? = null
    /** Bumped on every new read/run so late callbacks from an older round are ignored. */
    private var generation = 0

    private val windowAreas = listOf(
        WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT
    )

    fun initialize() {
        if (vehicle != null) return
        try {
            val v = Vehicle(context.applicationContext)
            vehicle = v
            v.initialize()
            windowApi = v.getWindow()
            hvacApi = v.getHVAC()
            wiperApi = v.getWiper()
            checkAllStatus()
        } catch (e: Exception) {
            Log.e(TAG, "Init failed", e)
            val u = WashCheckStatus.UNSUPPORTED
            state = WashGuardState(
                windowFrontLeft = u, windowFrontRight = u, windowRearLeft = u, windowRearRight = u,
                hvacRecirculation = u, wiperSwitch = u, lastRunNote = "차량 SDK에 연결하지 못했어요."
            )
        }
    }

    fun release() {
        generation++
        main.removeCallbacksAndMessages(null)
        runCatching { vehicle?.release() }
        vehicle = null; windowApi = null; hvacApi = null; wiperApi = null; hvacArea = null
    }

    /** Re-read every item from the vehicle. */
    fun checkAllStatus(note: String? = null, failed: List<String> = emptyList()) {
        val token = ++generation
        val c = WashCheckStatus.CHECKING
        state = WashGuardState(
            windowFrontLeft = c, windowFrontRight = c, windowRearLeft = c, windowRearRight = c,
            hvacRecirculation = c, wiperSwitch = c, failedRequests = failed, lastRunNote = note
        )
        windowAreas.forEach { area -> readWindow(token, area) }
        readHvac(token)
        readWiper(token)
    }

    private fun update(token: Int, change: (WashGuardState) -> WashGuardState) {
        main.post { if (token == generation) state = change(state) }
    }

    private fun setWindow(s: WashGuardState, area: WindowValue, st: WashCheckStatus) = when (area) {
        WindowValue.ROW_1_LEFT -> s.copy(windowFrontLeft = st)
        WindowValue.ROW_1_RIGHT -> s.copy(windowFrontRight = st)
        WindowValue.ROW_2_LEFT -> s.copy(windowRearLeft = st)
        else -> s.copy(windowRearRight = st)
    }

    private fun readWindow(token: Int, area: WindowValue) {
        val win = windowApi ?: return update(token) { setWindow(it, area, WashCheckStatus.UNSUPPORTED) }
        try {
            win.getCurrentWindowPosition(WindowArea(area),
                onComplete = { pos ->
                    val st = when {
                        pos == null -> WashCheckStatus.UNSUPPORTED
                        pos == 0 -> WashCheckStatus.SAFE
                        else -> WashCheckStatus.ACTION_REQ
                    }
                    update(token) { setWindow(it, area, st) }
                },
                onFailed = { e ->
                    Log.w(TAG, "Window read failed: $area", e)
                    update(token) { setWindow(it, area, WashCheckStatus.UNSUPPORTED) }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Window read exception: $area", e)
            update(token) { setWindow(it, area, WashCheckStatus.UNSUPPORTED) }
        }
    }

    /** Recirculation area reported by the vehicle (e.g. one area for both rows); found once. */
    private var hvacArea: SeatArea? = null

    private fun readHvac(token: Int) {
        val hvac = hvacApi ?: return update(token) { it.copy(hvacRecirculation = WashCheckStatus.UNSUPPORTED) }
        val known = hvacArea
        if (known != null) return readHvacAt(token, hvac, known)
        try {
            hvac.checkHvacRecirculationCapabilities(
                { areas ->
                    val area = areas.firstOrNull()
                    if (area == null) update(token) { it.copy(hvacRecirculation = WashCheckStatus.UNSUPPORTED) }
                    else main.post { if (token == generation) { hvacArea = area; readHvacAt(token, hvac, area) } }
                },
                { e ->
                    Log.w(TAG, "HVAC capability check failed", e)
                    update(token) { it.copy(hvacRecirculation = WashCheckStatus.UNSUPPORTED) }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "HVAC capability exception", e)
            update(token) { it.copy(hvacRecirculation = WashCheckStatus.UNSUPPORTED) }
        }
    }

    private fun readHvacAt(token: Int, hvac: HVAC, area: SeatArea) {
        try {
            hvac.getHvacRecirculationState(area,
                onComplete = { isRecirc ->
                    val st = when (isRecirc) {
                        true -> WashCheckStatus.SAFE
                        false -> WashCheckStatus.ACTION_REQ
                        null -> WashCheckStatus.UNSUPPORTED
                    }
                    update(token) { it.copy(hvacRecirculation = st) }
                },
                onFailed = { e ->
                    Log.w(TAG, "HVAC read failed", e)
                    update(token) { it.copy(hvacRecirculation = WashCheckStatus.UNSUPPORTED) }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "HVAC read exception", e)
            update(token) { it.copy(hvacRecirculation = WashCheckStatus.UNSUPPORTED) }
        }
    }

    private fun readWiper(token: Int) {
        val wiper = wiperApi ?: return update(token) { it.copy(wiperSwitch = WashCheckStatus.UNSUPPORTED) }
        try {
            wiper.getCurrentWiperSwitch(WindowArea(WindowValue.FRONT_WINDSHIELD),
                onComplete = { sw ->
                    val st = when (sw) {
                        WiperSwitch.OFF -> WashCheckStatus.SAFE
                        null -> WashCheckStatus.UNSUPPORTED
                        else -> WashCheckStatus.ACTION_REQ
                    }
                    update(token) { it.copy(wiperSwitch = st) }
                },
                onFailed = { e ->
                    Log.w(TAG, "Wiper read failed", e)
                    update(token) { it.copy(wiperSwitch = WashCheckStatus.UNSUPPORTED) }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Wiper read exception", e)
            update(token) { it.copy(wiperSwitch = WashCheckStatus.UNSUPPORTED) }
        }
    }

    /**
     * Explicit user action: close windows that were read as open and switch on recirculation
     * if it was read as off. Items that could not be read are not written. When every request
     * has answered (or after a timeout), everything is read again and the result comes from
     * that read, never from a fixed "done" message.
     */
    fun executeWashMode() {
        if (state.isExecuting) return
        val snapshot = state
        val win = windowApi
        val hvac = hvacApi
        val windowTargets = windowAreas.filter { area ->
            val st = when (area) {
                WindowValue.ROW_1_LEFT -> snapshot.windowFrontLeft
                WindowValue.ROW_1_RIGHT -> snapshot.windowFrontRight
                WindowValue.ROW_2_LEFT -> snapshot.windowRearLeft
                else -> snapshot.windowRearRight
            }
            st == WashCheckStatus.ACTION_REQ && win != null
        }
        val hvacSeat = hvacArea
        val hvacTarget = snapshot.hvacRecirculation == WashCheckStatus.ACTION_REQ && hvac != null && hvacSeat != null
        val total = windowTargets.size + (if (hvacTarget) 1 else 0)
        if (total == 0) {
            checkAllStatus(note = "바꿀 수 있는 항목이 없어 상태만 다시 확인했어요.")
            return
        }

        val token = ++generation
        state = state.copy(isExecuting = true, failedRequests = emptyList(), lastRunNote = null)
        var answered = 0
        val failed = mutableListOf<String>()
        fun done(label: String?, ok: Boolean) = main.post {
            if (token != generation) return@post
            if (!ok && label != null) failed += label
            answered++
            if (answered == total) checkAllStatus(note = "요청을 보낸 뒤 다시 읽은 결과예요.", failed = failed.toList())
        }

        windowTargets.forEach { area ->
            val label = when (area) {
                WindowValue.ROW_1_LEFT -> "운전석 창문"
                WindowValue.ROW_1_RIGHT -> "동승석 창문"
                WindowValue.ROW_2_LEFT -> "후석 좌측 창문"
                else -> "후석 우측 창문"
            }
            try {
                win!!.setWindowPosition(WindowArea(area), 0,
                    onComplete = { _, _ -> done(label, true) },
                    onFailed = { e -> Log.w(TAG, "Close window failed: $area", e); done(label, false) }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Close window exception: $area", e); done(label, false)
            }
        }
        if (hvacTarget) {
            try {
                hvac!!.setHvacRecirculation(hvacSeat!!, true,
                    onComplete = { _, _ -> done("내기순환", true) },
                    onFailed = { e -> Log.w(TAG, "Recirculation failed", e); done("내기순환", false) }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Recirculation exception", e); done("내기순환", false)
            }
        }

        // Some requests may never answer; read back anyway so the screen reflects the vehicle.
        main.postDelayed({
            if (token == generation && state.isExecuting) {
                checkAllStatus(note = "일부 요청의 응답이 없어 현재 상태를 다시 읽었어요.", failed = failed.toList())
            }
        }, EXECUTE_TIMEOUT_MS)
    }

    private companion object {
        const val TAG = "WashModeController"
        const val EXECUTE_TIMEOUT_MS = 6_000L
    }
}
