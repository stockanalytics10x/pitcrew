package com.example.washguard

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.*

val LocalRoadTravel=staticCompositionLocalOf {TravelState()}
val LocalRoadConnect=staticCompositionLocalOf<()->Unit> {{}}
val LocalRoadFlow=staticCompositionLocalOf {SpeedFlow()}
private object RoadCache {
    @Volatile private var loaded:SeoulRoadGeometry?=null
    fun read(context:Context):SeoulRoadGeometry = loaded ?: synchronized(this) {
        loaded ?: context.assets.open("seoul_roads.pfrgz").use {SeoulRoadGeometry.read(it)}.also {loaded=it}
    }
}
private data class RoadEvent(val point:GeoPoint,val acceleration:Boolean)

@Composable
fun SeoulRoadView(modifier:Modifier=Modifier,restricted:Boolean=false) {
    val context=LocalContext.current.applicationContext
    val travel=LocalRoadTravel.current
    val connect=LocalRoadConnect.current
    val flow=LocalRoadFlow.current
    var roads by remember {mutableStateOf<SeoulRoadGeometry?>(null)}
    var failed by remember {mutableStateOf(false)}
    LaunchedEffect(Unit) {try {roads=withContext(Dispatchers.IO){RoadCache.read(context)}}catch(error:Exception){android.util.Log.w("PitstopRoads","Offline road load failed",error);failed=true}}
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit) {while(true){delay(1000);now=System.currentTimeMillis()}}
    val current=travel.current
    val fresh=current!=null && now-current.time in -1000..20_000
    val preview=current==null
    val target=remember(current){current?.let {roadProject(it.lat,it.lon)}?:roadProject(37.5665,126.9780)}
    val centerX by animateFloatAsState(target.x,tween(700),label="road-x")
    val centerY by animateFloatAsState(target.y,tween(700),label="road-y")
    var targetAngle by remember {mutableFloatStateOf(0f)}
    LaunchedEffect(travel.heading) {
        val h=travel.heading?:return@LaunchedEffect
        targetAngle+=((h-targetAngle+540f)%360f)-180f
    }
    val heading by animateFloatAsState(targetAngle,tween(700),label="road-heading")
    val geometry=roads
    val inside=remember(geometry,target){geometry?.inside(target)==true}
    val nearby=remember(geometry,floor(target.x/100),floor(target.y/100)) {geometry?.nearby(target,650f).orEmpty()}
    var showLicense by remember {mutableStateOf(false)}
    var events by remember {mutableStateOf(emptyList<RoadEvent>())}
    var previousCounts by remember {mutableStateOf(flow.accelerationEvents to flow.decelerationEvents)}
    LaunchedEffect(flow.accelerationEvents,flow.decelerationEvents,flow.samples) {
        val counts=flow.accelerationEvents to flow.decelerationEvents
        if(flow.samples<2)events=emptyList()
        if(fresh && current!=null && now-current.time<=3000 && counts!=previousCounts) {
            if(counts.first>previousCounts.first)events=(events+RoadEvent(current,true)).takeLast(40)
            if(counts.second>previousCounts.second)events=(events+RoadEvent(current,false)).takeLast(40)
        }
        previousCounts=counts
    }
    if(showLicense) AlertDialog(onDismissRequest={showLicense=false},title={Text("서울 오프라인 도로")},
        text={Text("© OpenStreetMap contributors\nODbL 1.0\nhttps://www.openstreetmap.org/copyright\nhttps://opendatacommons.org/licenses/odbl/1-0/\n\n서울 행정구역 자동차 도로를 추출한 오프라인 데이터입니다. 위치를 지도 서버로 보내지 않습니다. 도로 변경·누락이 있을 수 있으며, 경로 안내와 도로 정합은 제공하지 않습니다.")},
        confirmButton={TextButton(onClick={showLicense=false}){Text("확인")}})
    Column(modifier.background(Color(0xFF050D12)).clipToBounds()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("SEOUL / ROAD FLOW",color=Color(0xFF39BCE9),fontSize=11.sp)
            Spacer(Modifier.weight(1f))
            Text(when{failed->"데이터 오류";geometry==null->"도로 준비 중";preview->"서울 미리보기";!inside->"서울 지원 지역 밖";fresh->"위치 연결됨";else->"위치 갱신 대기"},color=Color(0xFF8DA4B2),fontSize=10.sp)
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            Canvas(Modifier.fillMaxSize()) {
                val scale=max(size.width/520f,size.height/420f)
                val angle=Math.toRadians(heading.toDouble())
                val c=cos(angle).toFloat();val s=sin(angle).toFloat()
                val origin=Offset(size.width*.5f,size.height*.62f)
                fun screen(p:RoadXY):Offset {val dx=p.x-centerX;val dy=p.y-centerY;return origin+Offset((dx*c-dy*s)*scale,-(dx*s+dy*c)*scale)}
                // Fixed-metre local projection. No fake routing or snapping to an assumed road.
                if(inside) {
                    nearby.forEach {road ->
                        val width=when(road.kind){0->6f;1->5.2f;2->4.3f;3->3.4f;else->2.1f}.dp.toPx()
                        for(i in 2 until road.xy.size step 2) {
                            val ax=road.xy[i-2];val ay=road.xy[i-1];val bx=road.xy[i];val by=road.xy[i+1]
                            val dx=bx-ax;val dy=by-ay
                            val length2=dx*dx+dy*dy
                            val fraction=if(length2>0) (((centerX-ax)*dx+(centerY-ay)*dy)/length2).coerceIn(0f,1f) else 0f
                            val distance=hypot(ax+fraction*dx-centerX,ay+fraction*dy-centerY)
                            if(distance>600f)continue
                            val fade=(1f-distance/600f).coerceIn(.06f,1f)
                            val alpha=(if(road.kind<4) .90f else .46f)*fade
                            val start=screen(RoadXY(ax,ay));val end=screen(RoadXY(bx,by))
                            drawLine(Color(0xFFCCD6DB).copy(alpha=alpha*.07f),start,end,width+5.dp.toPx(),StrokeCap.Round)
                            drawLine(Color(0xFFE1E7EA).copy(alpha=alpha),start,end,width,StrokeCap.Round)
                        }
                    }
                    travel.trail.zipWithNext().forEach {(a,b)->
                        if(b.time-a.time in 1..10_000 && distanceKm(a,b)<.3)drawLine(Color(0xFF39BCE9),screen(roadProject(a.lat,a.lon)),screen(roadProject(b.lat,b.lon)),4.dp.toPx(),StrokeCap.Round)
                    }
                    events.forEach {event ->val p=screen(roadProject(event.point.lat,event.point.lon));drawCircle(if(event.acceleration)Color(0xFFFF7A28)else Color(0xFF39BCE9),4.dp.toPx(),p)}
                    if(!preview) {
                        drawCircle(Color(0xFFFF7A28).copy(alpha=.13f),21.dp.toPx(),origin)
                        val arrow=Path().apply {moveTo(origin.x,origin.y-14.dp.toPx());lineTo(origin.x+11.dp.toPx(),origin.y+11.dp.toPx());lineTo(origin.x,origin.y+5.dp.toPx());lineTo(origin.x-11.dp.toPx(),origin.y+11.dp.toPx());close()}
                        drawPath(arrow,if(fresh)Color(0xFFFF7A28)else Color(0xFF8DA4B2))
                    }
                }
            }
            if(geometry!=null && !inside) Text("서울 지도 지원 지역 밖입니다",color=Color(0xFF8DA4B2),fontSize=13.sp,modifier=Modifier.align(Alignment.Center))
            if((preview || !fresh) && !restricted) TextButton(onClick=connect,modifier=Modifier.align(Alignment.BottomCenter)) {Text("차량 위치 연결",color=Color(0xFFFF7A28),fontSize=12.sp)}
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=4.dp)) {
            Text("도로·주행 궤적 / 경로 안내 아님",color=Color(0xFF8DA4B2),fontSize=9.sp)
            Spacer(Modifier.weight(1f))
            Text("© OpenStreetMap contributors · ODbL",color=Color(0xFF8DA4B2),fontSize=9.sp,modifier=Modifier.clickable(enabled=!restricted){showLicense=true})
        }
    }
}
