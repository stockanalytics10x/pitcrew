package com.example.washguard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DpBg = Color(0xEE140A04)
private val DpOrange = Color(0xFFFF7A28)
private val DpInk = Color(0xFFE6EDF1)
private val DpMuted = Color(0xFFB7A79C)

/** Presenter bar shown only while the demo runs: scene, caption, previous / next / end. */
@Composable
fun DemoPresenterBar(demo: DemoController, modifier: Modifier = Modifier) {
    val s = demo.step
    Row(
        modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(DpBg)
            .border(1.dp, DpOrange.copy(alpha = .7f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("시연 모드", color = DpOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Btn("◀", demo.index > 0) { demo.prev() }
        Column {
            Text("${demo.index + 1}/${demo.steps.size} · ${demo.say(s.title)}", color = DpInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(if (s.charging) "${demo.say(s.caption)} · 지금 ${demo.soc}%" else demo.say(s.caption), color = DpMuted, fontSize = 12.sp)
        }
        Btn("▶", demo.index < demo.steps.lastIndex) { demo.next() }
        Btn("■ 종료", true) { demo.stop() }
    }
}

@Composable
private fun Btn(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(text, color = if (enabled) DpOrange else DpMuted.copy(alpha = .4f), fontSize = 15.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 11.dp))
}
