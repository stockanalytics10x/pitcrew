package com.example.washguard

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors

class SpeedTelemetryClient(private val context:Context,private val repository:LiveDriveRepository) {
    companion object {const val PERMISSION="android.car.permission.CAR_SPEED"}
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var car:Car?=null
    private var properties:CarPropertyManager?=null
    private var callback:CarPropertyManager.CarPropertyEventCallback?=null
    private var generation=0
    private var receivedAt=0L
    private var newestTimestamp=0L
    private var lastLogAt=0L
    private fun dispatch(token:Int,action:()->Unit) {main.post {if(token==generation)action()}}
    private val watchdog=object:Runnable {override fun run() {
        if(car==null)return
        if(!repository.recording) {stop();return}
        if(receivedAt>0 && SystemClock.elapsedRealtime()-receivedAt>3000) {
            repository.gap("속도 신호 지연 · 분석 일시중지");receivedAt=0L
        }
        main.postDelayed(this,1000)
    }}
    fun start() {
        if(car!=null || !repository.recording)return
        val token=++generation
        repository.gap("차량 속도 연결 중")
        try {
            car=Car.createCar(context.applicationContext,main,Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) {connected,ready ->
                if(token!=generation)return@createCar
                if(!ready) {repository.gap("차량 속도 연결 끊김");return@createCar}
                val manager=connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                properties=manager
                val listener=object:CarPropertyManager.CarPropertyEventCallback {
                    override fun onChangeEvent(value:CarPropertyValue<*>) {
                        if(value.propertyId!=VehiclePropertyIds.PERF_VEHICLE_SPEED)return
                        dispatch(token) {
                            val now=SystemClock.elapsedRealtimeNanos()
                            if(value.status!=CarPropertyValue.STATUS_AVAILABLE) {repository.gap("차량 속도 사용 불가");return@dispatch}
                            val speed=(value.value as? Number)?.toDouble()
                            if(speed==null || value.timestamp<=0 || now-value.timestamp !in 0..3_000_000_000L) {
                                repository.gap("오래되거나 유효하지 않은 속도 신호");return@dispatch
                            }
                            if(value.timestamp<=newestTimestamp)return@dispatch
                            newestTimestamp=value.timestamp
                            receivedAt=SystemClock.elapsedRealtime()
                            repository.sample(value.timestamp,speed)
                            if(receivedAt-lastLogAt>=1000) {
                                lastLogAt=receivedAt
                                Log.d("PitstopSpeed","speedMps=$speed samples=${repository.session.value.telemetry?.samples} estimatedAccel=${repository.session.value.telemetry?.acceleration}")
                            }
                        }
                    }
                    override fun onErrorEvent(propertyId:Int,areaId:Int) {dispatch(token){repository.gap("속도 수신 오류")}}
                }
                callback?.let {runCatching {manager.unregisterCallback(it)}}
                callback=listener
                worker.execute {
                    try {
                        val supported=manager.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED)
                        if(supported==null) {dispatch(token){repository.gap("현재 차량에서 속도 API 미지원")};return@execute}
                        if(!manager.registerCallback(listener,VehiclePropertyIds.PERF_VEHICLE_SPEED,10f)) {
                            dispatch(token){repository.gap("속도 업데이트 구독 실패")};return@execute
                        }
                        manager.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED,0)?.let {listener.onChangeEvent(it)}
                    } catch(_:SecurityException) {dispatch(token){repository.gap("차량 속도 권한 필요")}}
                    catch(_:IllegalArgumentException) {dispatch(token){repository.gap("현재 차량에서 속도 API 미지원")}}
                    catch(error:Exception) {Log.w("PitstopSpeed","Speed connection failed",error);dispatch(token){repository.gap("속도 연결 실패 · 다시 시도")}}
                }
            }
            main.postDelayed(watchdog,1000)
        } catch(_:Exception) {repository.gap("차량 서비스 연결 실패")}
    }
    fun stop() {
        ++generation;main.removeCallbacks(watchdog)
        val oldProperties=properties;val oldCallback=callback;val oldCar=car
        car=null;properties=null;callback=null;receivedAt=0;newestTimestamp=0
        worker.execute {oldCallback?.let {runCatching {oldProperties?.unregisterCallback(it)}};runCatching {oldCar?.disconnect()}}
        repository.gap("앱 비표시 · 수집 일시중지");repository.checkpoint()
    }
    fun close() {stop();worker.shutdown()}
}
