package com.example.washguard

import kotlin.math.abs

data class SpeedFlow(
    val recording:Boolean=false, val status:String="주행 기록 시작 전", val speedKmh:Double?=null,
    val acceleration:Double?=null, val feedback:String="속도 신호를 받은 뒤 흐름을 분석합니다.",
    val observedSeconds:Double=0.0, val averageKmh:Double?=null, val maxKmh:Double?=null,
    val accelerationEvents:Int=0, val decelerationEvents:Int=0, val samples:Int=0,
    val trace:List<Float> = emptyList(), val gaps:Int=0
)

/** Speed-derived longitudinal trend, not a measured accelerometer or safety score.
 * Only contiguous 0.5–2 s intervals are analysed. Timestamp unit is monotonic nanoseconds.
 * Events require a >=1 s sustained trend. No interpolation over missing intervals.
 */
class SpeedFlowAnalyzer {
    var state=SpeedFlow(recording=true,status="속도 수신 대기")
        private set
    private var lastTime:Long?=null
    private var lastSpeed=0.0
    private var integral=0.0
    private var eventKind=0
    private var eventSeconds=0.0
    private var counted=false
    private var lastFeedbackTime=Long.MIN_VALUE
    private var traceSeconds=0.0

    fun gap(status:String) {
        val hadSignal=lastTime!=null
        lastTime=null; eventKind=0;eventSeconds=0.0;counted=false
        state=state.copy(status=status,speedKmh=null,acceleration=null,feedback="수신 공백 · 새 신호를 기다립니다.",gaps=state.gaps+if(hadSignal)1 else 0)
    }
    fun accept(timeNs:Long,speedMps:Double):Boolean {
        if(!speedMps.isFinite() || abs(speedMps)>100.0 || timeNs<=0) {gap("유효하지 않은 속도 신호");return false}
        if(speedMps<0) {
            gap("후진 구간 · 가속 분석 제외")
            state=state.copy(speedKmh=abs(speedMps)*3.6,feedback="후진 구간 · 주행 흐름 평가 제외")
            return true
        }
        val previous=lastTime
        if(previous!=null && timeNs<=previous) return false
        val speed=abs(speedMps)
        if(previous==null) {
            lastTime=timeNs;lastSpeed=speed
            state=state.copy(status="속도 수신 중",speedKmh=speed*3.6,acceleration=null,samples=state.samples+1,maxKmh=maxOf(state.maxKmh?:0.0,speed*3.6))
            return true
        }
        val dt=(timeNs-previous)/1e9
        if(dt<0.5) return false
        if(dt>2.0) {gap("수신 간격 초과 · 분석 재시작");return accept(timeNs,speedMps)}
        // Derive change in speed magnitude; reversing is not interpreted as braking.
        val acceleration=(speed-lastSpeed)/dt
        if(abs(acceleration)>12.0) {gap("비연속 속도 변화 · 분석 제외");return accept(timeNs,speedMps)}
        lastTime=timeNs
        integral+=(lastSpeed+speed)*0.5*dt
        lastSpeed=speed
        val observed=state.observedSeconds+dt
        val kind=when {acceleration>=2.0->1;acceleration<=-2.5->-1;else->0}
        if(kind!=eventKind) {eventKind=kind;eventSeconds=0.0;counted=false}
        eventSeconds+=dt
        val newEvent=kind!=0 && eventSeconds>=1.0 && !counted
        if(newEvent) counted=true
        val filtered=state.acceleration?.let {it*.5+acceleration*.5}?:acceleration
        val phrase=when {
            speedMps<0 -> "후진 구간 · 주행 흐름 평가 제외"
            speed<0.3 -> "정차 중 · 다음 구간을 기다립니다."
            kind==1 && eventSeconds>=1.0 -> "최근 구간의 가속 변화가 컸어요."
            kind== -1 && eventSeconds>=1.0 -> "최근 구간의 감속 변화가 컸어요."
            abs(filtered)<0.4 -> "최근 구간의 속도 변화가 작아요."
            else -> "현재 구간의 속도 흐름을 기록하고 있어요."
        }
        val feedback=if(lastFeedbackTime==Long.MIN_VALUE || timeNs-lastFeedbackTime>=5_000_000_000L) {
            lastFeedbackTime=timeNs;phrase
        } else state.feedback
        traceSeconds+=dt
        val trace=if(traceSeconds>=1.0) {traceSeconds=0.0;(state.trace+filtered.toFloat()).takeLast(120)} else state.trace
        state=state.copy(status="속도 수신 중",speedKmh=speed*3.6,acceleration=filtered,feedback=feedback,
            observedSeconds=observed,averageKmh=integral/observed*3.6,maxKmh=maxOf(state.maxKmh?:0.0,speed*3.6),
            accelerationEvents=state.accelerationEvents+if(newEvent&&kind==1&&speedMps>=0)1 else 0,
            decelerationEvents=state.decelerationEvents+if(newEvent&&kind== -1&&speedMps>=0)1 else 0,
            samples=state.samples+1,trace=trace)
        return true
    }
}
