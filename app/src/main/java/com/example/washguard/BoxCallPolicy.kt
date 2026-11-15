package com.example.washguard

/**
 * When the Pit Wall should radio the driver, what it says, and how a spoken answer is read.
 * Pure Kotlin so the rules are unit tested. The call never controls the car; the only action
 * a "yes" can lead to is opening the navi's own charger search.
 */
enum class BoxLevel { NONE, ADVISED, REQUIRED }

enum class BoxAnswer { YES, NO, UNKNOWN }

enum class BoxOutcome { ACCEPTED, DECLINED, NO_ANSWER }

data class BoxCallRecord(
    val atMs: Long,
    val level: BoxLevel,
    val arrivalPercent: Double?,
    val outcome: BoxOutcome
)

/** What has been said on this trip so far. */
data class BoxCallMemory(
    val calledLevel: BoxLevel = BoxLevel.NONE,
    val lastDeclinedAtMs: Long? = null,
    val accepted: Boolean = false
)

object BoxCallPolicy {
    /** After a "no" (or no answer), the same call waits this long before repeating. */
    const val COOLDOWN_MS = 10 * 60_000L

    fun levelOf(plan: PitPlan): BoxLevel = when (plan.verdict) {
        PitVerdict.PIT_ADVISED -> BoxLevel.ADVISED
        PitVerdict.PIT_REQUIRED -> BoxLevel.REQUIRED
        else -> BoxLevel.NONE
    }

    /**
     * Call only while driving and only when a pit is needed.
     * A new or worse level always calls. The same level repeats only after a "no" plus the cooldown.
     * After a "yes" the same level stays quiet.
     */
    fun shouldCall(plan: PitPlan, driving: Boolean, memory: BoxCallMemory, nowMs: Long): Boolean {
        val level = levelOf(plan)
        if (!driving || level == BoxLevel.NONE) return false
        if (level > memory.calledLevel) return true
        if (memory.accepted) return false
        val declined = memory.lastDeclinedAtMs ?: return false
        return nowMs - declined >= COOLDOWN_MS
    }

    /** A trip that no longer needs a pit (or has no route) starts over. */
    fun settle(plan: PitPlan, memory: BoxCallMemory): BoxCallMemory =
        if (plan.verdict == PitVerdict.FINISH_OK || plan.verdict == PitVerdict.NO_DESTINATION) BoxCallMemory() else memory

    fun message(plan: PitPlan): String {
        val pt = plan.powertrain
        val arrival = plan.arrivalPercent.value
        val target = plan.chargeTargetPercent.value?.toInt()
        val targetText = if (target != null) " ${target}%까지 ${pt.refuel}하면 돼요." else ""
        return when (levelOf(plan)) {
            BoxLevel.ADVISED ->
                "Box 콜. 이대로 가면 도착 때 ${pt.energy}가 약 ${arrival?.toInt() ?: 0}% 남아요.$targetText ${pt.place}를 찾아 드릴까요?"
            BoxLevel.REQUIRED ->
                if (arrival == null || arrival < 0.0) "Box, box. 지금 잔량으로는 도착 전에 ${pt.energy}가 바닥나요.$targetText ${pt.place}를 찾아 드릴까요?"
                else "Box, box. 이대로 가면 도착 때 약 ${arrival.toInt()}%라 여유가 없어요.$targetText ${pt.place}를 찾아 드릴까요?"
            BoxLevel.NONE -> ""
        }
    }

    private val noWords = listOf("아니", "괜찮", "됐어", "다음에", "싫어", "안 해", "필요 없")
    private val yesWords = listOf("응", "네", "예", "그래", "좋아", "찾아", "부탁", "어 ", "ㅇㅇ", "오케이", "콜")

    /** "No" words win over "yes" words ("아니 괜찮아" must not read as yes). */
    fun parseAnswer(text: String?): BoxAnswer {
        val t = text?.trim()?.lowercase() ?: return BoxAnswer.UNKNOWN
        if (t.isEmpty()) return BoxAnswer.UNKNOWN
        if (noWords.any { t.contains(it) }) return BoxAnswer.NO
        if (yesWords.any { "$t ".contains(it) } || t == "어") return BoxAnswer.YES
        return BoxAnswer.UNKNOWN
    }

    fun afterCall(memory: BoxCallMemory, level: BoxLevel, outcome: BoxOutcome, nowMs: Long): BoxCallMemory = when (outcome) {
        BoxOutcome.ACCEPTED -> memory.copy(calledLevel = level, accepted = true, lastDeclinedAtMs = null)
        BoxOutcome.DECLINED, BoxOutcome.NO_ANSWER -> memory.copy(calledLevel = level, accepted = false, lastDeclinedAtMs = nowMs)
    }

    fun reply(outcome: BoxOutcome, pt: Powertrain = Powertrain.EV): String = when (outcome) {
        BoxOutcome.ACCEPTED -> "내비에 주변 ${pt.place}를 띄웠어요. 원하는 곳을 고르면 경유지로 들어가요."
        BoxOutcome.DECLINED -> "알겠어요. 상황이 바뀌면 다시 알려 드릴게요."
        BoxOutcome.NO_ANSWER -> "대답이 없어 넘어갈게요. ${pt.place}는 주차 후 PITSTOP에서 볼 수 있어요."
    }
}
