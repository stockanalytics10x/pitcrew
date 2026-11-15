package com.example.washguard

import androidx.compose.runtime.*
import kotlinx.coroutines.delay



/** Wall-clock time that refreshes every [periodMs], so freshness checks re-run while the screen is open. */
@Composable
fun rememberNow(periodMs: Long = 15_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) { while (true) { delay(periodMs); now = System.currentTimeMillis() } }
    return now
}

/**
 * Navi's arrival-charge value. On the emulator it arrived as 77105 with the car at 100% (IONIQ 5 pack ≈ 77 kWh),
 * so it reads as Wh. Shown as kWh and labelled as an estimate until confirmed on a real car.
 */
fun naviArrivalText(raw: Int?): String = when {
    raw == null -> "—"
    else -> String.format(java.util.Locale.US, "%.1fkWh", raw / 1000.0)
}

/** Headline wording per verdict. Kept outside the composable so it can be unit tested. */
fun raceBriefingHeadline(plan: PitPlan): String = when (plan.verdict) {
    PitVerdict.FINISH_OK -> "이번 주행, 피트인 없이 완주 가능"
    PitVerdict.PIT_ADVISED -> "중간에 한 번 피트인을 권장해요"
    PitVerdict.PIT_REQUIRED -> if (plan.needsSecondStop) "피트인이 두 번 필요해요" else "이번 주행은 피트인이 필요해요"
    PitVerdict.NO_DESTINATION -> "목적지를 설정하면 완주 가능 여부를 판단해요"
    PitVerdict.UNKNOWN -> "판단 보류 · 차량 신호 대기"
}
