package com.example.washguard

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import java.util.concurrent.Executors

data class TravelState(
    val enabled: Boolean = false, val status: String = "기록 꺼짐", val points: List<GeoPoint> = emptyList(),
    val current: GeoPoint? = null, val heading:Float?=null, val trail:List<GeoPoint> = emptyList(), val lastWash: Long = 0, val keySaved: Boolean = false
)

class TravelClient(private val context: Context, val store: TravelStore, val keys: ApiKeyStore) {
    private val prefs = context.getSharedPreferences("travel_settings", Context.MODE_PRIVATE)
    var state by mutableStateOf(TravelState(enabled = prefs.getBoolean("enabled", false), lastWash = prefs.getLong("wash", 0), keySaved = keys.read().isNotBlank()))
        private set
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var sdk: FusedLocation? = null
    private var callback: FusedLocationListener? = null
    private var generation = 0
    private var lastSaved: GeoPoint? = null
    var onLocation: (GeoPoint) -> Unit = {}
    /** Other on-device logs (energy log) that "기록 삭제" must also clear. */
    var onClearAll: () -> Unit = {}
    init { reload() }
    private fun reload() { worker.execute { val rows = store.points(); main.post { state = state.copy(points = rows) } } }
    fun supported(): Boolean = FusedLocation(context).isSupported()
    fun enable() { prefs.edit().putBoolean("enabled", true).apply(); state = state.copy(enabled = true); start() }
    fun start() {
        if (!state.enabled || sdk != null) return
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) { state = state.copy(status = "위치 권한 필요"); return }
        val location = FusedLocation(context.applicationContext)
        if (!location.isSupported()) { state = state.copy(status = "Pleos 위치 서비스 미지원"); return }
        val token = ++generation
        sdk = location
        state = state.copy(status = "위치 수신 대기")
        val listener = object : FusedLocationListener {
            override fun onChanged(locationData: LocationData?) {
                main.post {
                    if (token != generation) return@post
                    if (locationData == null) { state = state.copy(status = "현재 위치 없음"); return@post }
                    val point = GeoPoint(locationData.timestampMillis, locationData.latitude, locationData.longitude)
                    if (!validFreshPoint(point, System.currentTimeMillis())) { state = state.copy(status = "오래되거나 유효하지 않은 위치 · 새 위치 대기"); return@post }
                                        val lastTrail=state.trail.lastOrNull()
                    if(lastTrail!=null && point.time<=lastTrail.time)return@post
                    val trail=if(lastTrail==null || point.time-lastTrail.time>=1000) (state.trail+point).takeLast(300) else state.trail
                    state = state.copy(current = point, heading=locationData.heading.takeIf {it.isFinite() && it>=0 && it<360},trail=trail,status = "위치 수신 중 · 앱 표시 중에만 기록")
                    onLocation(point)
                    val previous = lastSaved
                    if (previous == null || (point.time - previous.time >= 30_000 && distanceKm(previous, point) >= 0.1) || point.time - previous.time >= 300_000) {
                        lastSaved = point
                        worker.execute {
                            store.record(point)
                            val rows = store.points()
                            main.post { if (token == generation) state = state.copy(points = rows) }
                        }
                    }
                }
            }
            override fun onError(exception: Exception) { main.post { if (token == generation) state = state.copy(status = "위치 수신 오류 · 권한 및 차량 위치 공유 설정 확인") } }
        }
        callback = listener
        try { location.initialize(); location.registerFusedLocationCallback(listener); location.getLastKnownLocation(listener) }
        catch (_: Exception) { state = state.copy(status = "위치 연결 실패 · 다시 기록 시작으로 재시도"); stop() }
    }
    fun stop() {
        ++generation
        val old = sdk
        val listener = callback
        sdk = null; callback = null; lastSaved = null
        if (old != null) { if (listener != null) runCatching { old.unregisterFusedLocationCallback(listener) }; runCatching { old.release() } }
    }
    fun disable() { stop(); prefs.edit().putBoolean("enabled", false).apply(); state = state.copy(enabled = false, status = "기록 꺼짐", current = null, heading=null, trail=emptyList()) }
    fun washed() { val time = System.currentTimeMillis(); prefs.edit().putLong("wash", time).apply(); state = state.copy(lastWash = time) }
    fun saveKey(value: String) { keys.save(value); state = state.copy(keySaved = keys.read().isNotBlank()) }
    fun clear(onCleared: () -> Unit = {}) {
        disable()
        onClearAll()
        worker.execute { store.clearAll(); prefs.edit().remove("wash").commit(); main.post { state = state.copy(points = emptyList(), current = null, lastWash = 0, status = "기록 삭제 완료"); onCleared() } }
    }
    fun close() { stop(); worker.shutdown() }
    companion object { const val PERMISSION = "pleos.car.permission.FUSED_LOCATION" }
}
