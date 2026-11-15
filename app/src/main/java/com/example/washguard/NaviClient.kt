package com.example.washguard

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.constants.NaviErrorCode

data class NaviStop(val name:String,val lat:Double,val lon:Double,val address:String)
data class NaviSnapshot(
    val connected:Boolean=false,val status:String="내비 연결 대기",val destination:TripDestination?=null,
    val remainKm:Double?=null,val remainMinutes:Double?=null,val active:Boolean?=null,
    val updated:Long=0,val waypoints:Int=0,val busy:Boolean=false,val searchStatus:String="",
    /** Navi's own predicted charge at the destination, raw value as received (unit to be confirmed on the emulator). */
    val naviArrivalChargeRaw:Int?=null,
    /** Raw isRouting from the navi; [active] also counts a fresh remaining distance. */
    val routing:Boolean?=null,
    /** What the navi last sent, shown on screen while no route is recognised. */
    val diag:String="아직 받은 내비 정보 없음",
    /** Car position from the navi, used to rank nearby PIT SERVICE places. */
    val here:GeoPoint?=null
)
/** A route counts as active when the navi says it is routing, or when it reports a distance still to go. */
fun naviActive(routing:Boolean?,remainKm:Double?):Boolean = routing==true || (remainKm!=null && remainKm>0.0)
private fun clock(ms:Long)=java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalTime().withNano(0).toString()
fun provinceCode(address:String):String = listOf(
    "서울" to "11","부산" to "26","대구" to "27","인천" to "28","광주" to "29","대전" to "30","울산" to "31",
    "세종" to "36","경기" to "41","강원" to "51","충청북" to "43","충북" to "43","충청남" to "44","충남" to "44",
    "전북" to "52","전라북" to "52","전라남" to "46","전남" to "46","경상북" to "47","경북" to "47","경상남" to "48","경남" to "48","제주" to "50"
).firstOrNull{address.startsWith(it.first)}?.second ?: ""
fun sameDestination(a:TripDestination?,b:TripDestination?):Boolean = a!=null&&b!=null&&distanceKm(GeoPoint(0,a.lat,a.lon),GeoPoint(0,b.lat,b.lon))<0.1

class NaviClient(private val context:Context){
    var state by mutableStateOf(NaviSnapshot());private set
    private val main=Handler(Looper.getMainLooper())
    private var sdk:NaviHelper?=null
    private var listener:NaviHelperEventListener?=null
    private var generation=0
    private var pending:NaviStop?=null
    private var expected:TripDestination?=null
    private var sending=false
    private var gotDestination=false;private var gotRoute=false;private var gotWaypoints=false
    private var waypointRows:List<WaypointInfo> = emptyList()
    private val timeout=Runnable{if(pending!=null){pending=null;sending=false;state=state.copy(busy=false,status="경유지 결과를 확인하지 못했어요. 내비 화면에서 확인해 주세요.")}}
    private val poll=object:Runnable{override fun run(){refresh();main.postDelayed(this,30_000)}}
    fun start(){
        if(sdk!=null)return
        val token=++generation
        val api=NaviHelper(context.applicationContext);sdk=api
        state=NaviSnapshot(status="현대차 내비 연결 중")
        val callback=object:NaviHelperEventListener{
            override fun onCurrentLocationInfo(currentLocationInfo:CurrentLocationInfo){main.post{
                if(token==generation&&(currentLocationInfo.latitude!=0.0||currentLocationInfo.longitude!=0.0))
                    state=state.copy(here=GeoPoint(System.currentTimeMillis(),currentLocationInfo.latitude,currentLocationInfo.longitude))
            }}
            override fun onDestinationInfo(destinationInfo:DestinationInfo){main.post{
                if(token!=generation)return@post
                val d=destinationInfo
                android.util.Log.i("PitstopNavi","destination name=${d.name.isNotBlank()} remainDistance=${d.remainDistance} remainTime=${d.remainTime}")
                val point=weatherGrid(d.latitude,d.longitude)
                val target=if(point!=null&&d.name.isNotBlank())TripDestination(d.name,d.latitude,d.longitude,provinceCode(d.address))else null
                val km=if(d.remainDistance>0)d.remainDistance/1000.0 else null
                val now=System.currentTimeMillis()
                state=state.copy(destination=target,remainKm=km,
                    remainMinutes=if(km!=null&&d.remainTime>=0)d.remainTime/60.0 else null,updated=now,
                    active=naviActive(state.routing,km),
                    diag="목적지 ${if(d.name.isNotBlank()) "있음" else "이름 없음"} · 남은 ${d.remainDistance}m · 경로 ${state.routing ?: "?"} · ${clock(now)}",
                    status=if(km!=null)"내비 목적지 수신${if(d.name.isNotBlank()) ": ${d.name}" else ""}" else "내비에서 목적지를 설정해 주세요.")
                gotDestination=true;maybeSend()
            }}
            override fun onRouteStateInfo(routeStateInfo:RouteStateInfo){main.post{
                if(token!=generation)return@post
                android.util.Log.i("PitstopNavi","route routing=${routeStateInfo.isRouting} arrivalCharge=${routeStateInfo.remainDestinationChargingInt} waypointCharge=${routeStateInfo.remainWaypointChargingInt}")
                val routing=routeStateInfo.isRouting
                state=state.copy(routing=routing,active=naviActive(routing,state.remainKm),
                    naviArrivalChargeRaw=routeStateInfo.remainDestinationChargingInt.takeIf{naviActive(routing,state.remainKm)},
                    diag=state.diag.replace(Regex("경로 [^·]+"),"경로 $routing "))
                gotRoute=true;maybeSend()
            }}
            override fun onWayPointInfo(waypointInfoList:List<WaypointInfo>){main.post{
                if(token!=generation)return@post
                waypointRows=waypointInfoList;state=state.copy(waypoints=waypointInfoList.size)
                val stop=pending
                if(sending&&stop!=null&&waypointInfoList.any{distanceKm(GeoPoint(0,it.latitude,it.longitude),GeoPoint(0,stop.lat,stop.lon))<0.15}){
                    pending=null;sending=false;main.removeCallbacks(timeout);state=state.copy(busy=false,status="내비 경유지에 반영됐어요: ${stop.name}")
                }else{gotWaypoints=true;maybeSend()}
            }}
            override fun onWaypointChanged(waypointChangedInfo:WaypointChangedInfo){main.post{if(token==generation)refresh()}}
            override fun onDestinationChanged(){main.post{if(token==generation)refresh()}}
            override fun onRouteCancelled(){main.post{if(token==generation){pending=null;sending=false;state=state.copy(destination=null,active=false,routing=false,remainKm=null,remainMinutes=null,naviArrivalChargeRaw=null,busy=false,status="내비 경로가 취소됐어요.")}}}
            override fun onRoutingCompleted(){main.post{if(token==generation)refresh()}}
            override fun onError(errorCode:NaviErrorCode){main.post{if(token==generation){pending=null;sending=false;state=state.copy(busy=false,status="내비 요청을 처리하지 못했어요: ${errorCode.name}",searchStatus="내비 요청 오류: ${errorCode.name}")}}}
        }
        listener=callback
        try{
            api.initialize(onConnected={main.post{if(token==generation){api.addListener(callback);state=state.copy(connected=true,status="내비 연결됨 · 목적지 확인 중");refresh();main.removeCallbacks(poll);main.postDelayed(poll,30_000)}}},
                onFailed={e->main.post{if(token==generation)state=state.copy(status="내비 서비스 연결 실패 · 설정에서 재시도해 주세요.",diag="연결 실패: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())};android.util.Log.w("PitstopNavi","initialize failed",e)})
        }catch(e:Exception){android.util.Log.w("PitstopNavi","initialize threw",e);stop();state=state.copy(status="내비 서비스 연결 실패",diag="연결 오류: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())}
    }
    /** Requests an OEM-owned search UI; never queries or exports location to an external POI service. */
    fun openSearch(kind: NativeSearchKind) {
        val api=sdk
        if(api==null || !state.connected) {state=state.copy(searchStatus="내비 연결 후 다시 검색해 주세요.");return}
        if(state.busy) {state=state.copy(searchStatus="경유지 처리가 끝난 뒤 검색해 주세요.");return}
        if(context.checkSelfPermission("pleos.car.permission.NAVI_CUSTOM_MAP") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            state=state.copy(searchStatus="기본 내비 화면 요청 권한이 없습니다. 앱 업데이트·권한을 확인해 주세요.");return
        }
        runCatching { api.requestNavigationScreen(NavigationScreenRequest.Foreground); api.requestNavigationScreen(nativeSearchRequest(kind)) }
            .onSuccess {state=state.copy(searchStatus="${kind.label} 검색 화면 요청을 전달했습니다. 내비 화면에서 결과를 확인해 주세요.");android.util.Log.i("PitstopNaviSearch","request sent: ${kind.name}")}
            .onFailure {state=state.copy(searchStatus="검색 화면을 열지 못했어요. 내비 연결을 확인하고 다시 시도해 주세요.");android.util.Log.w("PitstopNaviSearch","request failed",it)}
    }
    fun retry(){ stop(); start() }
    fun refresh(){
        val api=sdk?:return
        if(!state.connected)return
        val missing=PERMISSIONS.filter{context.checkSelfPermission(it)!=android.content.pm.PackageManager.PERMISSION_GRANTED}
        if(missing.isNotEmpty()){
            state=state.copy(status="내비 권한이 허용되지 않았어요",diag="권한 없음: ${missing.joinToString{it.substringAfterLast('.')}}")
            android.util.Log.w("PitstopNavi","missing permissions $missing")
            return
        }
        runCatching{api.getRouteStateInfo();api.getDestinationInfo();api.getWaypointInfo();runCatching{api.getCurrentLocationInfo()}}
            .onFailure{state=state.copy(status="내비 정보 조회 실패",diag="조회 오류: ${it.javaClass.simpleName} ${it.message ?: ""}".trim());android.util.Log.w("PitstopNavi","refresh failed",it)}
    }
    /**
     * PIT SERVICE "안내": with a route running the place becomes a waypoint (destination kept);
     * with no route it becomes the destination. Only called from the driver's own tap.
     */
    fun goTo(stop:NaviStop){
        val dest=state.destination
        if(state.active==true&&dest!=null){addStop(stop,dest);return}
        val api=sdk
        if(api==null||!state.connected){state=state.copy(status="내비가 연결되지 않았어요. SETTINGS에서 내비 권한을 확인해 주세요.");return}
        // Only the route is sent: the map in the driving view (left) shows it, and our app stays on screen.
        // The full navi screen would cover the app area, so it opens only from the driver's "내비 화면 열기" tap.
        runCatching{api.requestRoute(RouteInfo(stop.lat,stop.lon,null,null,stop.name,"",stop.address,"",ai.pleos.playground.navi.constants.RouteOption.RECOMMENDED,"",false))}
            .onSuccess{state=state.copy(status="${stop.name}(으)로 경로를 보냈어요. 왼쪽 지도에서 안내가 시작돼요.");android.util.Log.i("PitstopNavi","route requested")}
            .onFailure{state=state.copy(status="내비 안내 요청 실패: ${it.javaClass.simpleName}");android.util.Log.w("PitstopNavi","route request failed",it)}
    }
    /** Brings the built-in navi screen to the front (driver's tap only). */
    fun showNavi(delayMs:Long=0){
        val api=sdk?:return
        main.postDelayed({runCatching{api.requestNavigationScreen(NavigationScreenRequest.Foreground)}
            .onSuccess{android.util.Log.i("PitstopNavi","foreground requested")}
            .onFailure{state=state.copy(status="내비 화면을 열지 못했어요: ${it.javaClass.simpleName}");android.util.Log.w("PitstopNavi","foreground failed",it)}},delayMs)
    }
    /** Called only after the user confirms adding the selected place. Never replaces the destination. */
    fun addStop(stop:NaviStop,destination:TripDestination){
        if(state.busy)return
        if(!state.connected){state=state.copy(status="내비가 연결되지 않았어요.");return}
        pending=stop;expected=destination;sending=false;gotDestination=false;gotRoute=false;gotWaypoints=false
        state=state.copy(busy=true,status="현재 내비 목적지와 경유지를 확인하고 있어요.")
        main.removeCallbacks(timeout);main.postDelayed(timeout,15_000);refresh()
    }
    private fun maybeSend(){
        val stop=pending?:return
        if(sending||!gotDestination||!gotRoute||!gotWaypoints)return
        if(state.active!=true||!sameDestination(state.destination,expected)||waypointRows.size>=2){
            pending=null;main.removeCallbacks(timeout)
            state=state.copy(busy=false,status=if(waypointRows.size>=2)"내비 경유지가 이미 2개예요. 기존 경유지를 정리한 뒤 다시 선택해 주세요." else "현재 내비 목적지가 비교한 목적지와 달라요. 내비 목적지를 다시 불러와 주세요.")
            return
        }
        if(waypointRows.any{distanceKm(GeoPoint(0,it.latitude,it.longitude),GeoPoint(0,stop.lat,stop.lon))<0.15}){
            pending=null;main.removeCallbacks(timeout);state=state.copy(busy=false,status="이미 내비 경유지에 포함돼 있어요.");return
        }
        sending=true;state=state.copy(status="내비에 경유지 추가 요청 중")
        runCatching{sdk?.addWaypoint(RequestWaypointInfo(latitude=stop.lat,longitude=stop.lon,poiName=stop.name,
            waypointIndex=if(waypointRows.isEmpty())WaypointIndex.FIRST else WaypointIndex.SECOND,
            poiId="",address=stop.address,poiSubId=""))}
            .onFailure{pending=null;sending=false;state=state.copy(busy=false,status="내비 경유지 추가 요청 실패")}
    }
    companion object {
        /** Runtime permissions the navi service checks before answering. */
        val PERMISSIONS = listOf("pleos.car.permission.NAVI_ROUTE","pleos.car.permission.NAVI_ROUTE_SEARCH","pleos.car.permission.NAVI_CUSTOM_MAP")
    }
    fun stop(){
        ++generation;main.removeCallbacks(poll);main.removeCallbacks(timeout);pending=null;sending=false
        val old=sdk;val callback=listener;sdk=null;listener=null
        if(old!=null){if(callback!=null)runCatching{old.removeListener(callback)};runCatching{old.release()}}
        state=state.copy(connected=false,busy=false,destination=null,remainKm=null,remainMinutes=null,active=null,routing=null,naviArrivalChargeRaw=null,status="내비 연결 대기")
    }
}


