package com.example.washguard

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Demo mode, switched on from SETTINGS. Steps through [DemoScenario.roadToDaegu].
 * When the emulator's vehicle console (Pleos Connect Virtual Console, 127.0.0.1:8088 inside the emulator)
 * is reachable, each scene is also sent to it, so the car's own signals (gear, speed, charge) follow the demo.
 * The app still uses the scene values directly and labels them "시연", so it works without the console too.
 */
class DemoController(
    private val onAnnounce: (String) -> Unit = {},
    private val onDrivingScene: () -> Unit = {},
    /** EV or combustion words for scene text and radio (set by the runtime). */
    var powertrainOf: () -> Powertrain = { Powertrain.EV }
) {
    fun say(text: String): String = DemoScenario.words(text, powertrainOf())
    val steps = DemoScenario.roadToDaegu
    var active by mutableStateOf(false)
        private set
    var index by mutableIntStateOf(0)
        private set
    /** Charge shown now; rises during a charging scene. */
    var soc by mutableIntStateOf(steps.first().soc)
        private set
    var consoleStatus by mutableStateOf("확인 전")
        private set

    val step: DemoStep get() = steps[index]

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var consoleOk = false
    private var chargeStart = 0L
    private var announced = false
    private val tick = object : Runnable {
        override fun run() {
            if (!active || !step.charging) return
            val next = DemoScenario.chargingSoc(step, SystemClock.elapsedRealtime() - chargeStart)
            if (next != soc) {
                soc = next
                sendCharge(next.toDouble(), charging = next < (step.socEnd ?: next))
            }
            if (next >= (step.socEnd ?: next)) {
                if (!announced) { announced = true; onAnnounce("${powertrainOf().refuel} 목표 ${next}%에 도달했어요. 출발해도 돼요.") }
                return
            }
            main.postDelayed(this, 350)
        }
    }

    fun start() {
        active = true
        probeConsole()
        go(0)
    }

    fun stop() {
        main.removeCallbacks(tick)
        if (active && consoleOk) send("/vehicle/controls?speed_kph=0&accelerator_pct=0", "/vehicle/gear?gear=Park",
            "/vehicle/ev-battery?field=charge_switch&value=false",
            "/vehicle/telltales/simulator?type=TPMS&state=Off&color=Red")
        active = false
    }

    fun next() { if (index < steps.lastIndex) go(index + 1) }
    fun prev() { if (index > 0) go(index - 1) }

    private fun go(i: Int) {
        main.removeCallbacks(tick)
        index = i
        val s = steps[i]
        soc = s.soc
        announced = false
        if (s.driving && (i == 0 || !steps[i - 1].driving)) onDrivingScene()
        sendScene(s)
        s.radio?.let { main.postDelayed({ if (active && index == i) onAnnounce(say(it)) }, 1500) }
        if (s.charging) { chargeStart = SystemClock.elapsedRealtime(); main.postDelayed(tick, 600) }
    }

    private fun sendScene(s: DemoStep) {
        // The car stays in P during the demo: on the emulator the system covers side-loaded apps in D
        // ("주행 중에는 사용할 수 없습니다"), which would hide the driving scenes. The app takes drive/park
        // from the scene itself while the demo runs, and every value on screen is labelled "시연".
        send(
            "/vehicle/controls?speed_kph=0&accelerator_pct=0",
            "/vehicle/gear?gear=Park",
            "/vehicle/ev-battery?field=charge_switch&value=${s.charging}",
            "/vehicle/ev-battery?field=soc&value=${s.soc}",
            "/vehicle/odometer?remaining_range_m=${(DemoScenario.rangeKm(s.soc.toDouble()) * 1000).toInt()}",
            "/vehicle/telltales/simulator?type=TPMS&state=${if (DemoScenario.TPMS in s.faults) "On" else "Off"}&color=Red"
        )
    }

    private fun sendCharge(soc: Double, charging: Boolean) = send(
        "/vehicle/ev-battery?field=soc&value=${soc.toInt()}",
        "/vehicle/odometer?remaining_range_m=${(DemoScenario.rangeKm(soc) * 1000).toInt()}",
        "/vehicle/ev-battery?field=charge_switch&value=$charging"
    )

    private fun probeConsole() {
        consoleStatus = "차량 콘솔 확인 중"
        io.execute {
            val ok = runCatching { request("GET", "/vehicle/state") in 200..299 }.getOrDefault(false)
            main.post {
                consoleOk = ok
                consoleStatus = if (ok) "차량 콘솔 연결됨 · 실제 차량 신호도 함께 바뀌어요" else "차량 콘솔 없음 · 앱 안에서만 시연 값 사용"
                if (ok && active) sendScene(step)
            }
        }
    }

    private fun send(vararg paths: String) {
        if (!consoleOk) return
        io.execute { paths.forEach { p -> runCatching { request("PUT", p) }.onFailure { Log.w(TAG, "console $p failed", it) } } }
    }

    private fun request(method: String, path: String): Int {
        val c = URL("http://127.0.0.1:8088$path").openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 1500; c.readTimeout = 2500
        return try { c.responseCode.also { runCatching { c.inputStream.close() } } } finally { c.disconnect() }
    }

    fun release() { main.removeCallbacksAndMessages(null); io.shutdownNow() }

    private companion object { const val TAG = "PitstopDemo" }
}
