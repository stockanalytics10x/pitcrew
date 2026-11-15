package com.example.washguard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.EvChargePortOpenListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import java.util.Locale

data class VehicleDataState(
    val soc: String = "—",
    val distance: String = "—",
    val socStatus: String = "연결 대기",
    val distanceStatus: String = "연결 대기",
    /** Fuel level for combustion and hybrid cars. */
    val fuel: String = "—",
    val fuelStatus: String = "연결 대기",
    /** Which energy readings the car actually sent; decides EV / combustion / hybrid. */
    val evSeen: Boolean = false,
    val fuelSeen: Boolean = false,
    /** Charge port open (EV). Null = not received. Used only to recognise charging, never shown as fact alone. */
    val chargePort: Boolean? = null,
    /** Charge switch (charging allowed). Null = not received. */
    val chargeSwitch: Boolean? = null
) {
    val socInt: Int? get() = soc.removeSuffix("%").toIntOrNull()
    val powertrain: Powertrain get() = Powertrain.detect(evSeen, fuelSeen)
    /** The level the Pit Wall plans with: battery for an EV, fuel otherwise. */
    fun energy(pt: Powertrain): String = if (pt.usesFuel) fuel else soc
}

/** One SDK session per visible Activity; callbacks from retired sessions are ignored. */
class VehicleDataClient(private val context: Context) {
    var state by mutableStateOf(VehicleDataState())
        private set
    private val main = Handler(Looper.getMainLooper())
    private var session: Vehicle? = null
    private var generation = 0
    private var socListener: EvBatteryStateOfChargeListener? = null
    private var distanceListener: DistanceDrivenListener? = null
    private var fuelListener: FuelPercentListener? = null
    private var portListener: EvChargePortOpenListener? = null

    fun start() {
        if (session != null) return
        state = VehicleDataState()
        val token = ++generation
        try {
            val vehicle = Vehicle(context.applicationContext)
            session = vehicle
            vehicle.initialize()
            connectSoc(vehicle, token, 0)
            connectDistance(vehicle, token, 0)
            connectFuel(vehicle, token)
            connectCharge(vehicle, token)
        } catch (error: Exception) {
            fail(token, true, error)
            fail(token, false, error)
        }
    }

    private fun dispatch(token: Int, action: () -> Unit) {
        main.post { if (token == generation && session != null) action() }
    }

    private fun fail(token: Int, soc: Boolean, error: Exception) {
        Log.w("PitstopVehicle", if (soc) "SOC" else "Odometer", error)
        dispatch(token) {
            val message = "조회 오류: ${error.message ?: error.javaClass.simpleName}"
            state = if (soc) state.copy(soc = "—", socStatus = message)
                else state.copy(distance = "—", distanceStatus = message)
        }
    }

    private fun retry(token: Int, attempt: Int, soc: Boolean, error: Exception, action: () -> Unit) {
        fail(token, soc, error)
        // Service binding is asynchronous. Bound retries also allow a later Activity restart.
        if (attempt < 4) main.postDelayed({
            if (generation == token && session != null) action()
        }, 2000)
    }

    private fun connectSoc(vehicle: Vehicle, token: Int, attempt: Int) {
        val failed: (Exception) -> Unit = { error ->
            retry(token, attempt, true, error) { connectSoc(vehicle, token, attempt + 1) }
        }
        try {
            val battery = vehicle.getEvBattery()
            // Read and subscribe straight away. After a cold boot the emulator sometimes never answers the
            // capability check, which left the charge "waiting" for good; a car without the property fails here instead.
            battery.getEvBatteryStateOfCharge(
                onComplete = { value -> dispatch(token) {
                    state = state.copy(soc = value?.let { "$it%" } ?: "—", evSeen = state.evSeen || value != null,
                        socStatus = if (value == null) "값 없음" else "조회 완료")
                } },
                onFailed = failed
            )
            val listener = object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(value: Int?) = dispatch(token) {
                    state = state.copy(soc = value?.let { "$it%" } ?: "—", evSeen = state.evSeen || value != null,
                        socStatus = if (value == null) "값 없음" else "실시간 수신")
                }
                override fun onFailed(e: Exception) { fail(token, true, e) }
            }
            // A retry only reads again: the listener is registered once.
            if (socListener == null) { socListener = listener; battery.registerEvBatteryStateOfCharge(listener) }
        } catch (error: Exception) { failed(error) }
    }

    private fun connectDistance(vehicle: Vehicle, token: Int, attempt: Int) {
        val failed: (Exception) -> Unit = { error ->
            retry(token, attempt, false, error) { connectDistance(vehicle, token, attempt + 1) }
        }
        fun distance(value: Float?) = value?.let { String.format(Locale.KOREA, "%,.1f km", it) } ?: "—"
        try {
            val odometer = vehicle.getOdometer()
            odometer.checkCurrentDistanceDrivenCapabilities(
                onComplete = { _ -> dispatch(token) {
                    try {
                        odometer.getCurrentDistanceDriven(
                            onComplete = { value -> dispatch(token) {
                                state = state.copy(distance = distance(value),
                                    distanceStatus = if (value == null) "값 없음" else "조회 완료")
                            } }, onFailed = { fail(token, false, it) }
                        )
                        val listener = object : DistanceDrivenListener {
                            override fun onDistanceDrivenUpdated(value: Float?) = dispatch(token) {
                                state = state.copy(distance = distance(value),
                                    distanceStatus = if (value == null) "값 없음" else "실시간 수신")
                            }
                            override fun onFailed(e: Exception) { fail(token, false, e) }
                        }
                        distanceListener = listener
                        odometer.registerDistanceDriven(listener)
                    } catch (error: Exception) { fail(token, false, error) }
                } }, onFailed = failed
            )
        } catch (error: Exception) { failed(error) }
    }

    /** Fuel level, when the car has a tank. Unsupported (EV) is quiet: no retries, no error on screen. */
    private fun connectFuel(vehicle: Vehicle, token: Int) {
        try {
            val fuel = vehicle.getFuel()
            fuel.checkFuelPercentCapabilities(
                onComplete = { cap -> dispatch(token) {
                    if (cap?.accessType == null || cap.accessType == ai.pleos.playground.vehicle.api.model.capability.AccessType.NONE) {
                        state = state.copy(fuelStatus = "지원 안 함"); return@dispatch
                    }
                    try {
                        fuel.getFuelPercent(
                            onComplete = { value -> dispatch(token) {
                                state = state.copy(fuel = value?.let { "$it%" } ?: "—", fuelSeen = state.fuelSeen || value != null,
                                    fuelStatus = if (value == null) "값 없음" else "조회 완료")
                            } },
                            onFailed = { e -> dispatch(token) { state = state.copy(fuelStatus = "조회 오류: ${e.message ?: e.javaClass.simpleName}") } }
                        )
                        val listener = object : FuelPercentListener {
                            override fun onFuelPercentUpdated(value: Int?) = dispatch(token) {
                                state = state.copy(fuel = value?.let { "$it%" } ?: "—", fuelSeen = state.fuelSeen || value != null,
                                    fuelStatus = if (value == null) "값 없음" else "실시간 수신")
                            }
                            override fun onFailed(e: Exception) { Log.w("PitstopVehicle", "Fuel", e) }
                        }
                        fuelListener = listener
                        fuel.registerFuelPercent(listener)
                    } catch (error: Exception) { Log.w("PitstopVehicle", "Fuel", error) }
                } },
                onFailed = { e -> dispatch(token) { state = state.copy(fuelStatus = "지원 안 함") }; Log.i("PitstopVehicle", "Fuel capability: ${e.message}") }
            )
        } catch (error: Exception) { Log.i("PitstopVehicle", "Fuel unavailable", error) }
    }

    /**
     * Charge port (listener) and charge switch (polled: the SDK has no listener for it). Both are quiet when unsupported:
     * the charging screen then falls back to the battery rising while parked.
     */
    private fun connectCharge(vehicle: Vehicle, token: Int) {
        try {
            val battery = vehicle.getEvBattery()
            battery.isEvChargePortOpen(
                onComplete = { v -> dispatch(token) { state = state.copy(chargePort = v) } },
                onFailed = { Log.i("PitstopVehicle", "Charge port: ${it.message}") })
            val listener = object : EvChargePortOpenListener {
                override fun onEvChargePortOpenUpdated(value: Boolean?) = dispatch(token) { state = state.copy(chargePort = value) }
                override fun onFailed(e: Exception) { Log.i("PitstopVehicle", "Charge port listener: ${e.message}") }
            }
            portListener = listener
            battery.registerEvChargePortOpen(listener)
        } catch (error: Exception) { Log.i("PitstopVehicle", "Charge port unavailable", error) }
        val poll = object : Runnable {
            override fun run() {
                if (generation != token || session == null) return
                try {
                    vehicle.getEvBattery().getEvChargeSwitchState(
                        onComplete = { v -> dispatch(token) { state = state.copy(chargeSwitch = v) } },
                        onFailed = { Log.i("PitstopVehicle", "Charge switch: ${it.message}") })
                } catch (error: Exception) { Log.i("PitstopVehicle", "Charge switch unavailable", error); return }
                main.postDelayed(this, 10_000)
            }
        }
        main.postDelayed(poll, 1500)
    }

    fun stop() {
        ++generation
        main.removeCallbacksAndMessages(null)
        val vehicle = session
        session = null
        if (vehicle != null) {
            socListener?.let { listener ->
                runCatching { vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(listener) }
                    .onFailure { Log.w("PitstopVehicle", "SOC unsubscribe", it) }
            }
            fuelListener?.let { listener -> runCatching { vehicle.getFuel().unregisterFuelPercent(listener) } }
            portListener?.let { listener -> runCatching { vehicle.getEvBattery().unregisterEvChargePortOpen(listener) } }
            distanceListener?.let { listener ->
                runCatching { vehicle.getOdometer().unregisterDistanceDriven(listener) }
                    .onFailure { Log.w("PitstopVehicle", "Odometer unsubscribe", it) }
            }
            runCatching { vehicle.release() }.onFailure { Log.w("PitstopVehicle", "Release", it) }
        }
        socListener = null
        distanceListener = null
        portListener = null
        state = VehicleDataState(socStatus = "연결 중지", distanceStatus = "연결 중지")
    }
}
