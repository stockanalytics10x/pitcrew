package com.example.washguard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CardBg = Color(0xFF09151C)
private val CardLineColor = Color(0xFF263C48)
private val CardTextWhite = Color(0xFFE6EDF1)
private val CardTextGray = Color(0xFF8DA4B2)
private val CardSafeGreen = Color(0xFF30CD86)
private val CardWarnOrange = Color(0xFFFF7A28)
private val CardCyan = Color(0xFF39BCE9)

@Composable
fun WashModeCard(
    state: WashGuardState,
    onExecute: () -> Unit,
    onRefresh: () -> Unit,
    onOpenWashNavi: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, if (state.isReady) CardSafeGreen.copy(alpha = 0.5f) else CardLineColor, RoundedCornerShape(5.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(5.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 헤더: 타이틀 & 상태 뱃지 & 세차장 찾기 바로가기
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "WASH GUARD",
                        color = CardWarnOrange,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "· 차량 세차 모드",
                        color = CardTextWhite,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "세차장 찾기 ›",
                        color = CardCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable(onClick = onOpenWashNavi)
                            .padding(4.dp)
                    )

                    val badgeColor by animateColorAsState(
                        targetValue = if (state.isReady) CardSafeGreen else CardWarnOrange,
                        label = "badge"
                    )
                    Surface(
                        color = badgeColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = if (state.isReady) "READY FOR WASH" else "CHECK REQUIRED",
                            color = badgeColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // 안내 메시지
            Text(
                text = state.message,
                color = if (state.isReady) CardSafeGreen else CardTextGray,
                fontSize = 12.sp
            )

            HorizontalDivider(color = CardLineColor.copy(alpha = 0.6f))

            // 4개 창문 + 공조 + 와이퍼 상태 그리드
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WashStatusItem(
                        label = "운전석 창문",
                        status = state.windowFrontLeft,
                        modifier = Modifier.weight(1f)
                    )
                    WashStatusItem(
                        label = "동승석 창문",
                        status = state.windowFrontRight,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WashStatusItem(
                        label = "후석 좌측",
                        status = state.windowRearLeft,
                        modifier = Modifier.weight(1f)
                    )
                    WashStatusItem(
                        label = "후석 우측",
                        status = state.windowRearRight,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WashStatusItem(
                        label = "공조 내기순환",
                        status = state.hvacRecirculation,
                        safeText = "차단(안전)",
                        actionText = "외기유입(위험)",
                        modifier = Modifier.weight(1f)
                    )
                    WashStatusItem(
                        label = "와이퍼 스위치",
                        status = state.wiperSwitch,
                        safeText = "OFF(안전)",
                        actionText = "AUTO/가동(위험)",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 앱이 확인·제어하지 않는 항목: 직접 확인 안내만 표시
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F1E27))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "사이드미러 접기 · 기어 N 전환",
                    color = CardTextWhite,
                    fontSize = 12.sp
                )
                Text(
                    text = "직접 확인",
                    color = CardTextGray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // 원터치 세차 준비 실행 버튼
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, CardLineColor),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CardTextGray)
                ) {
                    Text("재조회", fontSize = 12.sp)
                }

                Button(
                    onClick = onExecute,
                    enabled = !state.isExecuting,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isReady) CardSafeGreen else CardWarnOrange,
                        contentColor = Color.Black
                    )
                ) {
                    if (state.isExecuting) {
                        CircularProgressIndicator(
                            color = Color.Black,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("요청 보내는 중...", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    } else {
                        Text(
                            text = if (state.isReady) "다시 확인" else "세차 준비 (열린 창문 닫기 · 내기순환)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WashStatusItem(
    label: String,
    status: WashCheckStatus,
    safeText: String = "닫힘(안전)",
    actionText: String = "열림(조치필요)",
    modifier: Modifier = Modifier
) {
    val (text, color, icon) = when (status) {
        WashCheckStatus.SAFE -> Triple(safeText, CardSafeGreen, "✓")
        WashCheckStatus.ACTION_REQ -> Triple(actionText, CardWarnOrange, "!")
        WashCheckStatus.CHECKING -> Triple("조회중...", CardTextGray, "…")
        WashCheckStatus.UNSUPPORTED -> Triple("확인 불가", CardWarnOrange.copy(alpha = 0.75f), "?")
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0F1E27))
            .border(0.5.dp, CardLineColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = CardTextGray, fontSize = 12.sp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = icon, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(text = text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
