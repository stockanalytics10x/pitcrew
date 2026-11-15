package com.example.washguard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.listener.LowWasherFluidWaringListener
import ai.pleos.playground.vehicle.listener.OilLevelStateListener
import ai.pleos.playground.vehicle.listener.PowerDownWarningIndicatorListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TirePressureListener

/**
 * Reads what the pit crew checks: warning lights, tyre pressure, fluid levels, washer fluid, power-down.
 * Each item stays null until the car answers, so the crew shows "?" rather than "fine".
 * Needs pleos.car.permission.CAR_SAFETY.
 */
class VehicleHealthClient(private val context: Context) {
    var input by mutableStateOf(HealthInput(telltales = null))
        private set
    var status by mutableStateOf("연결 전")
        private set

    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var generation = 0
    private val wheels = mapOf(
        CrewSpot.TIRE_FL to WheelValue.LEFT_FRONT, CrewSpot.TIRE_FR to WheelValue.RIGHT_FRONT,
        CrewSpot.TIRE_RL to WheelValue.LEFT_REAR, CrewSpot.TIRE_RR to WheelValue.RIGHT_REAR
    )
    private val poll = object : Runnable { override fun run() { refresh(); main.postDelayed(this, 15_000) } }

    private fun post(token: Int, change: (HealthInput) -> HealthInput) { main.post { if (token == generation) input = change(input) } }
    private fun clock() = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul")).withNano(0).toString()
    /** Telltales received: show the time and which lights are on, so a missing update is visible on screen. */
    private fun gotLights(token: Int, t: Telltales, via: String) {
        val on = lights(t)
        main.post { if (token == generation) { input = input.copy(telltales = on); status = "경고등 수신 ${clock()} ($via) · " + (if (on.isEmpty()) "켜진 것 없음" else on.keys.joinToString()) } }
    }
    private fun failed(token: Int, what: String, e: Exception) {
        Log.w(TAG, what, e); main.post { if (token == generation) status = "$what 읽기 실패 ${clock()}: ${e.message ?: e.javaClass.simpleName}" }
    }

    fun start() {
        if (vehicle != null) return
        if (context.checkSelfPermission(PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            status = "차량 안전 정보 권한 필요(CAR_SAFETY)"; return
        }
        val token = ++generation
        runCatching {
            val v = Vehicle(context.applicationContext); vehicle = v; v.initialize()
            status = "차량 점검 연결됨 · 경고등 기다리는 중"
            val safety = v.getSafety()
            if (!lightsAllowed()) status = "연결됨 · 경고등은 '경고등 정보' 권한 필요(PRIVATE_CAR_SAFETY) · 공기압·워셔액 등은 수신 중"
            if (lightsAllowed()) runCatching { safety.registerTelltales(object : TelltaleInfoListener {
                override fun onTelltaleInfoUpdated(t: Telltales?) { if (t != null) gotLights(token, t, "알림") }
                override fun onFailed(e: Exception) { failed(token, "경고등", e) }
            }) }
            runCatching { safety.registerBrakeFluidLevelState(object : OilLevelStateListener {
                override fun onOilLevelUpdated(s: OilLevelState?) = post(token) { it.copy(brakeFluid = s?.name) }
                override fun onFailed(e: Exception) { Log.w(TAG, "brake fluid", e) }
            }) }
            runCatching { safety.registerEngineOilLevelState(object : OilLevelStateListener {
                override fun onOilLevelUpdated(s: OilLevelState?) = post(token) { it.copy(engineOil = s?.name) }
                override fun onFailed(e: Exception) { Log.w(TAG, "oil", e) }
            }) }
            runCatching { safety.registerLowWasherFluidWaringState(object : LowWasherFluidWaringListener {
                override fun onLowWasherFluidWaringUpdated(v: Boolean?) = post(token) { it.copy(washerLow = v) }
                override fun onFailed(e: Exception) { Log.w(TAG, "washer", e) }
            }) }
            runCatching { safety.registerPowerDownWarningIndicator(object : PowerDownWarningIndicatorListener {
                override fun onPowerDownWarningIndicatorUpdated(v: Boolean?) = post(token) { it.copy(powerDown = v) }
                override fun onFailed(e: Exception) { Log.w(TAG, "power down", e) }
            }) }
            val tire = v.getTire()
            wheels.forEach { (spot, w) ->
                runCatching { tire.registerTirePressure(WheelArea(w), object : TirePressureListener {
                    override fun onTirePressureUpdated(p: Float?) = post(token) { it.copy(tirePressure = it.tirePressure + (spot to p)) }
                    override fun onFailed(e: Exception) { Log.w(TAG, "tire $spot", e) }
                }) }
            }
            main.post(poll)
        }.onFailure { Log.w(TAG, "health start", it); status = "차량 점검 연결 실패: ${it.message ?: it.javaClass.simpleName}" }
    }

    /** One-shot reads, also a fallback for cars or emulators that do not push updates. */
    fun refresh() {
        val v = vehicle ?: return
        val token = generation
        val fail: (Exception) -> Unit = { Log.w(TAG, "read", it) }
        runCatching {
            val safety = v.getSafety()
            if (lightsAllowed()) safety.getTelltalesInfo({ t -> if (t != null) gotLights(token, t, "조회") }, { failed(token, "경고등", it) })
            safety.getBrakeFluidLevelState({ s -> post(token) { it.copy(brakeFluid = s?.name) } }, fail)
            safety.getEngineOilLevelState({ s -> post(token) { it.copy(engineOil = s?.name) } }, fail)
            safety.hasLowWasherFluidWarning({ b -> post(token) { it.copy(washerLow = b) } }, fail)
            safety.hasPowerDownWarningIndicator({ b -> post(token) { it.copy(powerDown = b) } }, fail)
            val tire = v.getTire()
            wheels.forEach { (spot, w) ->
                tire.getTirePressure(WheelArea(w), { p -> post(token) { it.copy(tirePressure = it.tirePressure + (spot to p)) } }, fail)
                tire.getLowTirePressure(WheelArea(w), { p -> post(token) { it.copy(tireLowThreshold = it.tireLowThreshold + (spot to p)) } }, fail)
            }
        }.onFailure { Log.w(TAG, "refresh", it) }
    }

    fun stop() {
        ++generation
        main.removeCallbacks(poll)
        vehicle?.let { runCatching { it.release() } }
        vehicle = null
    }

    private fun lightsAllowed() = context.checkSelfPermission(LIGHTS_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private companion object {
        /** The SDK guards telltales with a separate permission from the other safety readings. */
        const val LIGHTS_PERMISSION = "pleos.car.permission.PRIVATE_CAR_SAFETY"
        const val TAG = "PitstopHealth"
        const val PERMISSION = "pleos.car.permission.CAR_SAFETY"
        /** Lights that are on or blinking → blinking flag. */
        fun lights(t: Telltales): Map<String, Boolean> = t.telltales
            .filter { it.state == TelltaleState.ON || it.state == TelltaleState.BLINK }
            .associate { it.type.name to (it.state == TelltaleState.BLINK) }
    }
}
