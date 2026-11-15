package com.example.washguard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import android.graphics.Paint
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val Gunmetal = Color(0xFF191C1E)
private val Titanium = Color(0xFF303538)
private val InkLight = Color(0xFFF3F4F3)
private val MutedLight = Color(0xFFACB0B2)
private val Ember = Color(0xFFF87535)
private val Cyan = Color(0xFF55C9EA)
private val DisplayType = FontFamily(Font(R.font.michroma_regular))

/** Native UI, not a flattened image: every score, action and restriction remains functional. */
@Composable
fun DriveScreen(session: DriveSession, access: DriveAccess, destination: String?, onFinish: () -> Unit,
    onReport: () -> Unit, onNavigate: (String) -> Unit = {}, pitPlan: PitPlan? = null, voiceStatus: String = "",
    radio: List<BoxCallRecord> = emptyList(), deltaArrival: Double? = null,
    trackEvents: List<TrackEvent> = emptyList(), crewCheck: CrewCheck? = null, trackSpeedKmh: Double? = null, remainMinutes: Double? = null, todayTypicalKm: Double? = null, odometerKm: Double? = null, tempC: Double? = null, rainy: Boolean? = null, conditionEffect: Double? = null) {
    val parked = access == DriveAccess.PARKED
    if (!session.demo && !parked && pitPlan != null) {
        DrivePitWallScreen(pitPlan, trackSpeedKmh ?: session.telemetry?.speedKmh, voiceStatus, radio = radio, deltaArrival = deltaArrival,
            events = trackEvents, crewCheck = crewCheck, finishLabel = "FINISH" + (destination?.let { " · $it" } ?: "") + (pitPlan.remaining.value?.let { " ${it.toInt()}km" } ?: ""), remainMinutes = remainMinutes, destination = destination, todayTypicalKm = todayTypicalKm, odometerKm = odometerKm, tempC = tempC, rainy = rainy, conditionEffect = conditionEffect)
        return
    }
    if(!session.demo) {
        Column(Modifier.fillMaxSize().background(Color(0xFF050D12)).padding(28.dp),verticalArrangement=Arrangement.Center) {
            Text("PITCREW / DRIVE",color=Ember,fontSize=18.sp)
            Spacer(Modifier.height(24.dp))
            RLiveTelemetry(session.telemetry ?: SpeedFlow(),!parked,{},onFinish,onReport)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.radialGradient(
        listOf(Color(0xFF2B3033), Gunmetal, Color(0xFF111315)), radius = 1600f))) {
        // A proportional landscape composition preserves the approved specimen-sheet layout.
        val s = minOf(maxWidth.value / 1280f, maxHeight.value / 720f)
        Box(Modifier.size((1280*s).dp, (720*s).dp).align(Alignment.Center)) {
            @Composable fun Place(x: Float, y: Float, w: Float, h: Float, content: @Composable BoxScope.() -> Unit) {
                Box(Modifier.offset((x*s).dp,(y*s).dp).size((w*s).dp,(h*s).dp), content = content)
            }
            @Composable fun Label(text: String) {
                Text(text, fontFamily = DisplayType, color = InkLight, fontSize = (15*s).sp, letterSpacing = (.4*s).sp,
                    maxLines = 1, modifier = Modifier.background(Color(0xDD080A0B)).padding((7*s).dp,(3*s).dp))
            }
            Canvas(Modifier.fillMaxSize()) {
                scale(size.width / 1280f, size.height / 720f, pivot = Offset.Zero) {
                    drawRect(Ember, Offset(35f,24f), Size(8f,26f))
                    drawLine(MutedLight.copy(alpha=.45f),Offset(285f,39f),Offset(1020f,39f),1f)
                    drawLine(MutedLight.copy(alpha=.45f),Offset(1045f,24f),Offset(1045f,50f),1f)
                    drawRect(Brush.horizontalGradient(listOf(Color(0xFF363B3D), Color(0xFF232729))),Offset(36f,210f),Size(185f,75f))
                    drawRect(Ember,Offset(36f,210f),Size(24f,75f))
                    drawRect(Ember,Offset(38f,516f),Size(12f,32f))
                    drawLine(MutedLight.copy(alpha=.35f),Offset(425f,537f),Offset(1245f,537f),1f)
                    drawLine(MutedLight.copy(alpha=.4f),Offset(0f,643f),Offset(1280f,643f),1f)
                }
            }
            Place(55f,21f,225f,36f) { Text("PITCREW",fontFamily=DisplayType,fontSize=(20*s).sp,color=InkLight,letterSpacing=(.3*s).sp) }
            Place(1067f,25f,195f,30f) { Text(if(session.demo) "DEMO / NOT LIVE" else "VEHICLE / CONNECTED",fontFamily=DisplayType,fontSize=(14*s).sp,color=MutedLight) }
            Place(76f,220f,127f,58f) { TelemetryNumber(if(session.demo) "02" else "—",Modifier.fillMaxSize()) }
            Place(226f,211f,262f,36f) { Label("PITCREW") }
            Place(275f,248f,205f,35f) { Text("DRIVE MODE",fontFamily=DisplayType,fontSize=(16*s).sp,color=MutedLight) }
            listOf("SMOOTH FLOW", "STEADY INPUT", if(session.demo) "DEMO DATA" else "VEHICLE DATA").forEachIndexed { i, title ->
                Place(68f,315f+i*57f,265f,45f) {
                    Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size((45*s).dp).background(Brush.verticalGradient(listOf(Cyan,Color(0xFF267A96)))),contentAlignment=Alignment.Center) {
                            Text(listOf("∿","▥","≋")[i],fontSize=(29*s).sp,color=InkLight)
                        }
                        Column(Modifier.weight(1f)) {
                            Box(Modifier.fillMaxWidth().height((2*s).dp).background(Cyan))
                            Label(title)
                        }
                    }
                }
            }
            Place(510f,79f,200f,35f) { Label("SESSION FLOW") }
            Place(508f,108f,260f,72f) {
                Row(verticalAlignment=Alignment.Bottom) {
                    TelemetryNumber(if(session.demo) "02" else "—",Modifier.size((111*s).dp,(57*s).dp))
                    Text(if(session.demo) " / 03" else " / —",fontSize=(30*s).sp,fontFamily=DisplayType,fontWeight=FontWeight.Normal,color=MutedLight,modifier=Modifier.padding(bottom=(12*s).dp))
                }
            }
            Place(490f,180f,355f,315f) { SessionDiagram(session.demo, Modifier.fillMaxSize()) }
            val scores = listOf("SIMILARITY" to session.similarity,"SMOOTHNESS" to session.smoothness,"EFFICIENCY" to session.efficiency)
            scores.forEachIndexed { i, (label,value) ->
                val y=80f+i*151f
                Place(949f,y,285f,112f) {
                    Column {
                        Box(Modifier.width((128*s).dp).height((2*s).dp).background(Cyan))
                        Label(label)
                        Row(verticalAlignment=Alignment.Bottom) {
                            TelemetryNumber(value?.let { String.format(Locale.US,"%.1f",it.toFloat()) } ?: "—",
                                Modifier.size((222*s).dp,(67*s).dp).padding(top=(7*s).dp))
                            if(i==0) Text("/100",fontFamily=DisplayType,fontSize=(19*s).sp,color=MutedLight,modifier=Modifier.padding(bottom=(6*s).dp))
                        }
                    }
                }
                Place(954f,y+113f,290f,10f) {
                    Row(horizontalArrangement=Arrangement.spacedBy((8*s).dp)) {
                        repeat(7) { j -> Box(Modifier.weight(1f).fillMaxHeight().background(
                            if(value == null) Color(0xFF484C4E) else if(j < (value*5/100)) Ember else if(j==5) Cyan else Color(0xFF484C4E))) }
                    }
                }
            }
            Place(59f,510f,590f,48f) {
                Text(if(parked) { if(session.completed) "DRIVE REPORT  /  OPEN REPORT" else "DRIVE REPORT  /  END DEMO SESSION" }
                    else "DRIVE REPORT  /  AFTER PARKING",fontFamily=DisplayType,color=InkLight,fontSize=(14*s).sp,
                    modifier=Modifier.clickable(enabled=parked,onClick=if(session.completed) onReport else onFinish)
                        .heightIn(min=48.dp).padding(horizontal=(8*s).dp))
            }
            Place(39f,561f,57f,60f) {
                Column { listOf("FLOW","DEMO","TRACE").forEach { Text(it,fontFamily=DisplayType,color=InkLight,fontSize=(9*s).sp,lineHeight=(14*s).sp,
                    modifier=Modifier.background(Color(0xFF285467)).fillMaxWidth().height((18*s).dp).padding(horizontal=(2*s).dp)) } }
            }
            Place(101f,560f,996f,60f) {
                if(parked) FlowLine(session.flow,Modifier.fillMaxSize())
                else Text(if(access==DriveAccess.UNKNOWN) "주차 확인 대기 · 상세 잠금" else "주행 중 · 상세는 주차 후 확인",color=MutedLight,fontSize=(18*s).sp,modifier=Modifier.align(Alignment.CenterStart))
            }
            Place(1115f,562f,125f,60f) {
                if(parked && session.demo) Canvas(Modifier.fillMaxSize()) {
                    repeat(3) { r -> repeat(7) { c -> drawRect(if(c<2) Ember else if(c==3) Cyan else Color(0xFF343A3D),Offset(c*size.width/7,r*size.height/3),Size(size.width/9,size.height/4)) } }
                }
            }
            Place(24f,648f,1232f,65f) {
                Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy((18*s).dp)) {
                    listOf("GARAGE" to "GARAGE","DRIVE" to "DRIVE","PITSTOP" to "PITSTOP").forEach { (route,title) ->
                        val selected=route=="DRIVE"
                        Box(Modifier.weight(1f).fillMaxHeight().background(if(selected) Ember else Color.Transparent)
                            .clickable(enabled=parked) { onNavigate(route) },contentAlignment=Alignment.Center) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy((16*s).dp)) {
                                NavigationGlyph(route,if(selected) Color(0xFF101214) else MutedLight,Modifier.size((28*s).dp))
                                Text(title,fontFamily=DisplayType,color=if(selected) Color(0xFF101214) else if(parked) InkLight else MutedLight,fontSize=(15*s).sp,letterSpacing=(.3*s).sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationGlyph(route: String, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        scale(size.width/32f,size.height/32f,pivot=Offset.Zero) {
            val p=Path()
            when(route) {
                "HOME" -> p.apply { moveTo(3f,14f); lineTo(16f,3f); lineTo(29f,14f); lineTo(29f,29f); lineTo(20f,29f); lineTo(20f,20f); lineTo(12f,20f); lineTo(12f,29f); lineTo(3f,29f); close() }
                "DRIVE" -> { drawCircle(color,13f,Offset(16f,16f),style=Stroke(2f)); drawCircle(color,3f,Offset(16f,16f),style=Stroke(2f)); p.apply { moveTo(3f,13f); lineTo(16f,16f); lineTo(29f,13f); moveTo(16f,16f); lineTo(16f,29f) } }
                "ENERGY" -> { repeat(3) { i -> drawRect(color,Offset(3f+i*10,23f-i*7),Size(6f,7f+i*7)) } }
                "PITSTOP" -> p.apply { moveTo(6f,29f); lineTo(20f,15f); cubicTo(31f,19f,32f,6f,28f,3f); lineTo(23f,10f); lineTo(18f,7f); lineTo(23f,1f); cubicTo(12f,0f,10f,9f,14f,14f); lineTo(1f,27f); close() }
                else -> p.apply { moveTo(3f,27f); lineTo(3f,14f); lineTo(7f,5f); lineTo(25f,5f); lineTo(29f,14f); lineTo(29f,27f); moveTo(3f,15f); lineTo(29f,15f); moveTo(3f,23f); lineTo(29f,23f); moveTo(8f,19f); lineTo(11f,19f); moveTo(21f,19f); lineTo(24f,19f) }
            }
            drawPath(p,color,style=Stroke(2f,cap=StrokeCap.Round,join=StrokeJoin.Round))
        }
    }
}

/** Use the bundled font's real outlines for all digits and changing values.
 * Fit glyph ink bounds to the approved wide display area, retaining accessible text.
 */
@Composable
private fun TelemetryNumber(value: String, modifier: Modifier) {
    val context = LocalContext.current
    val paint = remember(context) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.michroma_regular)
            textSize = 100f
            color = android.graphics.Color.rgb(243,244,243)
        }
    }
    val bounds = remember(value) { android.graphics.Rect().also { paint.getTextBounds(value,0,value.length,it) } }
    Canvas(modifier.semantics { contentDescription = value }) {
        if(bounds.width() <= 0 || bounds.height() <= 0) return@Canvas
        drawContext.canvas.nativeCanvas.apply {
            val checkpoint = save()
            scale(size.width / bounds.width(),size.height / bounds.height())
            drawText(value,-bounds.left.toFloat(),-bounds.top.toFloat(),paint)
            restoreToCount(checkpoint)
        }
    }
}

@Composable
private fun SessionDiagram(demo: Boolean, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = if(demo) "데모 주행 흐름 도식. 내비게이션 지도가 아닙니다" else "주행 흐름 데이터 미연결" }) {
        if(!demo) return@Canvas
        scale(size.width/355f,size.height/315f,pivot=Offset.Zero) {
            fun line(path: Path, alpha: Float=1f, width:Float=6f) {
                drawPath(path,Brush.verticalGradient(listOf(Color.Transparent,InkLight.copy(alpha=alpha),InkLight.copy(alpha=alpha),Color.Transparent)),style=Stroke(width=width))
            }
            line(Path().apply { moveTo(54f,0f); cubicTo(22f,65f,62f,99f,38f,158f); cubicTo(15f,218f,46f,247f,9f,310f) },.35f)
            line(Path().apply { moveTo(82f,0f); cubicTo(50f,83f,79f,114f,64f,159f); cubicTo(36f,258f,165f,323f,342f,264f) })
            line(Path().apply { moveTo(197f,0f); cubicTo(198f,72f,252f,79f,342f,57f) })
            line(Path().apply { moveTo(158f,0f); lineTo(158f,315f) },1f,8f)
            listOf(39f,59f,105f,117f,186f).forEach { y ->
                drawCircle(InkLight,9f,Offset(158f,y)); drawCircle(Cyan,8f,Offset(158f,y))
            }
            val chevron=Path().apply { moveTo(144f,149f); lineTo(158f,138f); lineTo(172f,149f); lineTo(167f,154f); lineTo(158f,148f); lineTo(149f,154f); close() }
            drawPath(chevron,InkLight,style=Stroke(3f)); drawPath(chevron,Ember)
        }
    }
}

@Composable
private fun TelemetryLabel(text: String) {
    Text(text,fontFamily=DisplayType,color=MutedLight,fontSize=14.sp,modifier=Modifier.background(Color(0xFF101315)).padding(8.dp,4.dp))
}

@Composable
private fun MetricPanel(session: DriveSession, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MetricRow("SIMILARITY / 주행 유사도", session.similarity)
        MetricRow("SMOOTHNESS / 가감속 부드러움", session.smoothness)
        MetricRow("EFFICIENCY / 주행 효율", session.efficiency)
    }
}
@Composable
private fun MetricRow(label: String, value: Int?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Box(Modifier.width(72.dp).height(2.dp).background(Ember))
        TelemetryLabel(label)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(value?.toString()?.padStart(2, '0') ?: "—", color = InkLight,
                fontSize = 48.sp, lineHeight = 52.sp, fontWeight = FontWeight.Light,
                fontFamily = DisplayType, letterSpacing = (-2).sp)
            Text("/ 100", color = MutedLight, fontSize = 17.sp, modifier = Modifier.padding(bottom = 7.dp))
        }
    }
}
@Composable
private fun FlowLine(values: List<Float>, modifier: Modifier) {
    Canvas(modifier.heightIn(min = 48.dp).semantics { contentDescription = "예시 주행 흐름 선 그래프. 실제 측정값 아님" }) {
        repeat(3) { row -> val y = size.height * (row + 1) / 4; drawLine(MutedLight.copy(alpha = .16f), Offset(0f,y), Offset(size.width,y), 1f) }
        if (values.size > 1) {
            val path = Path()
            values.forEachIndexed { i, value ->
                val x = i * size.width / (values.size - 1)
                val y = (1f - value.coerceIn(0f,1f)) * size.height
                if (i == 0) path.moveTo(x,y) else path.lineTo(x,y)
            }
            drawPath(path, Cyan, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
        }
    }
}

@Composable
fun DriveReportScreen(session: DriveSession, onRestart: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Gunmetal).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("DRIVE REPORT", color = Ember, fontSize = 20.sp)
        Text("일상을 돌아보는 피트 브리핑", color = InkLight, fontSize = 32.sp)
        Text("DEMO · 모든 점수·구간은 예시입니다. 차량 성능 또는 안전 평가가 아닙니다.", color = MutedLight, fontSize = 20.sp)
        MetricPanel(session, Modifier.fillMaxWidth())
        Text("구간 비교  /  유사도 · 부드러움 · 효율", color = InkLight, fontSize = 22.sp)
        session.segments.forEach { segment ->
            Surface(color = Titanium, shape = RoundedCornerShape(2.dp)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(segment.name, color = InkLight, fontSize = 22.sp)
                    Text("${segment.similarity}   /   ${segment.smoothness}   /   ${segment.efficiency}", color = Ember, fontSize = 22.sp)
                }
            }
        }
        FlowLine(session.flow, Modifier.fillMaxWidth().height(100.dp))
        Text("유사도: 예시 기준 패턴과의 비교 · 부드러움: 예시 가감속 변화 · 효율: 예시 정규화 지표\n실제 산식·단위·기준 경로는 차량 신호 검증 후 정의합니다.", color = MutedLight, fontSize = 18.sp)
        Button(onClick = onRestart, modifier = Modifier.heightIn(min = 64.dp)) { Text("새 데모 운행", fontSize = 20.sp) }
    }
}

@Preview(widthDp = 1280, heightDp = 720, name = "Drive / Parked")
@Composable
private fun DriveParkedPreview() { MaterialTheme { DriveScreen(DemoDriveRepository().session.value, DriveAccess.PARKED, null, {}, {}) } }
@Preview(widthDp = 960, heightDp = 600, name = "Drive / Restricted")
@Composable
private fun DriveRestrictedPreview() { MaterialTheme { DriveScreen(DemoDriveRepository().session.value, DriveAccess.RESTRICTED, null, {}, {}) } }
@Preview(widthDp = 1280, heightDp = 720, name = "Drive Report")
@Composable
private fun DriveReportPreview() { MaterialTheme { DriveReportScreen(DemoDriveRepository().session.value.copy(completed = true), {}) } }

