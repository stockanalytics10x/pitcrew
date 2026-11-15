package com.example.washguard

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

data class RangeState(val kilometers: Double? = null, val status: String = "접근 권한 필요")

class RangeClient(private val context: Context) {
    var state by mutableStateOf(RangeState())
        private set
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var callback: CarPropertyManager.CarPropertyEventCallback? = null
    private var generation = 0
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private fun publish(token: Int, value: RangeState) {
        main.post { if (token == generation) state = value }
    }

    fun start() {
        if (car != null) return
        val token = ++generation
        state = RangeState(status = "차량 연결 중")
        try {
            car = Car.createCar(context.applicationContext, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (token != generation) return@createCar
                if (!ready) {
                    manager = null
                    callback = null
                    state = RangeState(status = "차량 연결 끊김")
                    return@createCar
                }
                val properties = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                manager = properties
                val listener = object : CarPropertyManager.CarPropertyEventCallback {
                    override fun onChangeEvent(value: CarPropertyValue<*>) {
                        if (value.propertyId != VehiclePropertyIds.RANGE_REMAINING) return
                        val km = if (value.status == CarPropertyValue.STATUS_AVAILABLE)
                            rangeKilometers((value.value as? Number)?.toDouble()) else null
                        publish(token, RangeState(km, if (km == null) "값 없음" else "실시간 수신"))
                    }
                    override fun onErrorEvent(propertyId: Int, areaId: Int) {
                        publish(token, RangeState(status = "차량값 수신 오류"))
                    }
                }
                callback?.let { runCatching { properties.unregisterCallback(it) } }
                callback = listener
                worker.execute {
                    try {
                        val subscribed = properties.registerCallback(listener, VehiclePropertyIds.RANGE_REMAINING, CarPropertyManager.SENSOR_RATE_NORMAL)
                        val value = properties.getProperty<Float>(VehiclePropertyIds.RANGE_REMAINING, 0)
                        val km = if (value?.status == CarPropertyValue.STATUS_AVAILABLE) rangeKilometers(value.value?.toDouble()) else null
                        publish(token, RangeState(km, if (km == null) "값 없음" else if (subscribed) "조회 완료 · 업데이트 대기" else "조회 완료 · 구독 실패"))
                    } catch (error: SecurityException) {
                        publish(token, RangeState(status = "차량 에너지 정보 권한 필요"))
                    } catch (error: IllegalArgumentException) {
                        publish(token, RangeState(status = "현재 차량에서 미지원"))
                    } catch (error: Exception) {
                        Log.w("PitstopRange", "Range read failed", error)
                        publish(token, RangeState(status = "주행 가능 거리 조회 실패"))
                    }
                }
            }
        } catch (error: Exception) {
            Log.w("PitstopRange", "Car service connection failed", error)
            state = RangeState(status = "차량 서비스 연결 실패")
        }
    }

    fun stop() {
        ++generation
        val oldManager = manager
        val oldCallback = callback
        val oldCar = car
        car = null
        manager = null
        callback = null
        // Queue cleanup behind any initial read/registration, so a late registration cannot leak.
        worker.execute {
            if (oldManager != null && oldCallback != null) runCatching { oldManager.unregisterCallback(oldCallback) }
            runCatching { oldCar?.disconnect() }
        }
        state = RangeState(status = "연결 중지")
    }

    fun close() { stop(); worker.shutdown() }
}
