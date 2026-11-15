package com.example.washguard

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

data class SeoulForecast(val date: String, val probability: Int, val precipitationMm: Double?)
data class WeatherState(
    val forecast: SeoulForecast? = null, val loading: Boolean = false, val status: String = "기상청 서버 연결 대기",
    val region: String = "서울 수동 기준", val nearRain: String? = null, val nearStatus: String = "아직 조회하지 않음",
    val history: String = "아직 비교할 위치 기록 없음", val dirtRisk: String = "자료 부족", val historyWetHours: Int = 0, val nearKnown: Boolean = false, val nearClearLabel: String = "확인 필요",
    val days: List<DayForecast> = emptyList(),
    /** Forecast temperature for now, °C (null until a forecast arrived). */
    val nowTempC: Double? = null
)
class WeatherClient(context: Context, private val store: TravelStore, private val keys: ApiKeyStore, private val washTime: () -> Long) {
    private val preferences = context.getSharedPreferences("weather_mode", Context.MODE_PRIVATE)
    var state by mutableStateOf(WeatherState())
        private set
    private val api = KmaApi(context, store)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var point: GeoPoint? = null
    private var manual = preferences.getBoolean("manual", true)
    private var generation = 0
    private var closed = false
    private var lastGrid: WeatherGrid? = null
    private var lastFetch = 0L
    private val periodic = object : Runnable { override fun run() { refresh(); main.postDelayed(this, 30 * 60_000L) } }
    fun start() { main.removeCallbacks(periodic); if (System.currentTimeMillis() - lastFetch >= 30 * 60_000) refresh(); main.postDelayed(periodic, 30 * 60_000L) }
    fun stop() { main.removeCallbacks(periodic) }
    fun useLocation(value: GeoPoint) {
        if (manual) return
        val grid = weatherGrid(value.lat, value.lon)
        point = value
        if (grid != lastGrid && System.currentTimeMillis() - lastFetch >= 10 * 60_000) refresh()
    }
    fun useSeoul() { ++generation; manual = true; preferences.edit().putBoolean("manual", true).apply(); point = null; lastGrid = null; state = WeatherState(); refresh() }
    fun useVehicle(value: GeoPoint?) {
        ++generation; manual = false; point = value; lastGrid = null; lastFetch = 0
        preferences.edit().putBoolean("manual", false).apply()
        state = WeatherState(status = "새 차량 위치 수신 필요", region = "차량 위치 대기")
        if (value != null) refresh()
    }
    fun invalidate() { ++generation; lastFetch = 0; state = WeatherState(region = state.region) }
    fun refresh() {
        if (closed || state.loading) return
        if (!manual && point == null) { state = WeatherState(status = "새 차량 위치 수신 필요", region = "차량 위치 대기"); return }
        val selected = point?.takeIf { validFreshPoint(it, System.currentTimeMillis()) }
        if (point != null && selected == null) { state = WeatherState(status = "현재 위치가 오래됨 · 새 위치 수신 또는 서울 수동 선택 필요", region = "위치 확인 필요"); return }
        val target = selected ?: GeoPoint(System.currentTimeMillis(), 37.5665, 126.9780)
        val grid = weatherGrid(target.lat, target.lon)
        if (grid == null) { state = WeatherState(status = "기상청 국내 예보 범위 밖", region = "위치 확인 필요"); return }
        val region = if (selected == null) "서울 수동 기준" else "차량 위치 · ${api.nearest(target)?.name?.plus(" 관측소 인근") ?: "격자 ${grid.x},${grid.y}"}"
        val token = ++generation
        state = WeatherState(loading = true, status = "$region 예보 조회 중", region = region)
        worker.execute {
            val now = LocalDateTime.now(KST)
            val result = runCatching { api.forecast(target, now) }
            main.post {
                if (closed || token != generation) return@post
                state = result.fold(onSuccess = {
                    lastGrid = grid; lastFetch = System.currentTimeMillis()
                    WeatherState(it.tomorrow, false, "기상청 · ${it.issued} 발표 · ${now.format(DateTimeFormatter.ofPattern("HH:mm"))} 조회(KST)\n내일 예보: ${it.tomorrow?.date ?: "확인 필요"}", region, it.nearRain, it.nearStatus, "과거 관측 비교 중", nearKnown = it.nearKnown, nearClearLabel = it.nearClearLabel, days = it.days, nowTempC = it.nowTempC)
                }, onFailure = { WeatherState(status = "기상청 조회 실패 · 서버/활용 승인/인터넷 확인", region = region) })
            }
            val history = runCatching { api.history(now, washTime()) }.getOrDefault(HistorySummary("과거 관측 비교 실패 · ASOS 시간자료 활용 승인 확인"))
            main.post { if (!closed && token == generation) state = state.copy(history = history.text, historyWetHours = history.wetHours, dirtRisk = when {
                history.wetHours > 0 -> "강수 노출·오염 가능성 있음"
                history.compared > 0 -> "비교 표본에 강수 관측 없음 · 오염은 미확인"
                else -> "자료 부족"
            }) }
        }
    }
    fun close() { closed = true; ++generation; stop(); worker.shutdownNow() }
}
