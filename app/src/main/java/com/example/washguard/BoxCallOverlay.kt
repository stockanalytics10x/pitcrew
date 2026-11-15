package com.example.washguard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BxBg = Color(0xF2081219)
private val BxInk = Color(0xFFE6EDF1)
private val BxMuted = Color(0xFF8DA4B2)
private val BxOrange = Color(0xFFFF7A28)
private val BxRed = Color(0xFFFF4B4B)
private val BxCyan = Color(0xFF39BCE9)

/**
 * Pit Wall radio banner. Voice first; two large buttons are the fallback when no answer is heard.
 * Kept short so it can be glanced at while driving.
 */
@Composable
fun BoxCallOverlay(ui: BoxCallUi, onAccept: () -> Unit, onDecline: () -> Unit, modifier: Modifier = Modifier, listenStatus: String = "") {
    if (!ui.visible) return
    val accent = if (ui.level == BoxLevel.REQUIRED) BxRed else BxOrange
    Column(
        modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(18.dp)).background(BxBg)
            .border(2.dp, accent.copy(alpha = .7f), RoundedCornerShape(18.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(accent))
            Text(if (ui.level == BoxLevel.REQUIRED) "BOX, BOX · PIT WALL" else "BOX CALL · PIT WALL",
                color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text(when (ui.phase) {
                BoxPhase.SPEAKING -> "무전 중"
                BoxPhase.LISTENING -> "대답을 듣고 있어요"
                BoxPhase.ANSWERED -> ""
            }, color = BxMuted, fontSize = 13.sp)
        }
        Text(if (ui.phase == BoxPhase.ANSWERED && ui.reply.isNotEmpty()) ui.reply else ui.message,
            color = BxInk, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        if (ui.phase != BoxPhase.ANSWERED) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Button(onClick = onAccept, modifier = Modifier.weight(1f).heightIn(min = 72.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black)) {
                Text(ui.acceptLabel, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f).heightIn(min = 72.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = BxCyan)) {
                Text("다음에", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
