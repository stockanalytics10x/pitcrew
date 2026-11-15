package com.example.washguard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Local-only bounded summary. No coordinates, server upload, or fabricated route matching. */
class LiveDriveRepository(context:Context,storeName:String="speed_flow_report"):DriveRepository {
    private val prefs=context.getSharedPreferences(storeName,Context.MODE_PRIVATE)
    private var analyzer=SpeedFlowAnalyzer()
    private val mutable=MutableStateFlow(restore())
    override val session:StateFlow<DriveSession> = mutable
    val recording get()=mutable.value.telemetry?.recording==true
    fun begin() {analyzer=SpeedFlowAnalyzer();mutable.value=DriveSession(demo=false,telemetry=analyzer.state)}
    fun sample(timeNs:Long,speedMps:Double) {if(recording) {val previous=analyzer.state;analyzer.accept(timeNs,speedMps);if(previous!=analyzer.state)publish()}}
    fun gap(status:String) {
        if(recording) {analyzer.gap(status);publish()} else if(!mutable.value.completed) mutable.value=mutable.value.copy(telemetry=SpeedFlow(status=status))
    }
    private fun publish() {mutable.value=mutable.value.copy(telemetry=analyzer.state,flow=analyzer.state.trace.map {(it/8f+.5f).coerceIn(0f,1f)})}
    override fun finish(access:DriveAccess) {
        if(access!=DriveAccess.PARKED || !recording)return
        mutable.value=mutable.value.copy(completed=true,telemetry=analyzer.state.copy(recording=false,status="기록 종료",speedKmh=null,acceleration=null))
        save()
    }
    override fun restart(access:DriveAccess) {if(access==DriveAccess.PARKED)begin()}
    fun checkpoint() {if(recording || mutable.value.completed)save()}
    private fun save() {
        val t=mutable.value.telemetry?:return
        val json=JSONObject().put("seconds",t.observedSeconds).put("average",t.averageKmh).put("max",t.maxKmh)
            .put("accel",t.accelerationEvents).put("decel",t.decelerationEvents).put("samples",t.samples)
            .put("gaps",t.gaps).put("trace",JSONArray(t.trace))
        prefs.edit().putString("latest",json.toString()).apply()
    }
    private fun restore():DriveSession = runCatching {
        val text=prefs.getString("latest",null)?:return@runCatching DriveSession(demo=false,telemetry=SpeedFlow())
        val json=JSONObject(text);val array=json.getJSONArray("trace")
        val t=SpeedFlow(status="저장된 수집 구간",observedSeconds=json.getDouble("seconds"),
            averageKmh=if(json.isNull("average"))null else json.getDouble("average"),maxKmh=if(json.isNull("max"))null else json.getDouble("max"),
            accelerationEvents=json.getInt("accel"),decelerationEvents=json.getInt("decel"),samples=json.getInt("samples"),gaps=json.optInt("gaps"),
            trace=(0 until array.length()).map {array.getDouble(it).toFloat()})
        DriveSession(demo=false,completed=true,telemetry=t,flow=t.trace.map {(it/8f+.5f).coerceIn(0f,1f)})
    }.getOrElse {DriveSession(demo=false,telemetry=SpeedFlow(status="저장 기록을 읽지 못했어요"))}
}
