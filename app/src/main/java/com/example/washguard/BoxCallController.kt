package com.example.washguard

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class BoxPhase { SPEAKING, LISTENING, ANSWERED }

data class BoxCallUi(
    val visible: Boolean = false,
    val level: BoxLevel = BoxLevel.NONE,
    val message: String = "",
    val phase: BoxPhase = BoxPhase.SPEAKING,
    val reply: String = "",
    /** Which voice engine is speaking, or why none is; shown small on the banner. */
    val voiceStatus: String = "",
    /** Accept button: 충전소 찾기 / 주유소 찾기. */
    val acceptLabel: String = "충전소 찾기"
)

/**
 * Runs one Box call at a time: speak, listen for a short yes/no (or a button tap), act, reply.
 * The only action is [onAccept], which opens the navi's own charger search. It never controls the car.
 */
class BoxCallController(private val voice: PitVoice, private val onAccept: () -> Unit) {
    var ui by mutableStateOf(BoxCallUi())
        private set
    var history by mutableStateOf<List<BoxCallRecord>>(emptyList())
        private set

    private val main = Handler(Looper.getMainLooper())
    private var memory = BoxCallMemory()
    private var current: PitPlan? = null
    private val hide = Runnable { ui = ui.copy(visible = false) }
    /** Buttons stay up this long when no answer is heard. */
    private val answerWindowMs = 8_000L
    private val noAnswer = Runnable { finish(BoxOutcome.NO_ANSWER) }

    /** Feed every new plan. Starts a call when the policy says so. */
    fun onPlan(plan: PitPlan, driving: Boolean, nowMs: Long = System.currentTimeMillis()) {
        memory = BoxCallPolicy.settle(plan, memory)
        if (ui.visible && ui.phase != BoxPhase.ANSWERED) return
        if (!BoxCallPolicy.shouldCall(plan, driving, memory, nowMs)) return
        start(plan)
    }

    private fun start(plan: PitPlan) {
        current = plan
        val level = BoxCallPolicy.levelOf(plan)
        val message = BoxCallPolicy.message(plan)
        Log.i(TAG, "Box call $level: $message")
        main.removeCallbacks(hide); main.removeCallbacks(noAnswer)
        ui = BoxCallUi(true, acceptLabel = "${plan.powertrain.place} 찾기", level = level, message = message, phase = BoxPhase.SPEAKING, voiceStatus = voice.status)
        voice.speak(message) {
            if (ui.phase != BoxPhase.SPEAKING) return@speak
            ui = ui.copy(phase = BoxPhase.LISTENING)
            // Without a microphone the driver needs longer to reach a button.
            main.postDelayed(noAnswer, if (voice.canListen) answerWindowMs else 15_000L)
            if (voice.canListen) voice.listen { text ->
                when (BoxCallPolicy.parseAnswer(text)) {
                    BoxAnswer.YES -> finish(BoxOutcome.ACCEPTED)
                    BoxAnswer.NO -> finish(BoxOutcome.DECLINED)
                    BoxAnswer.UNKNOWN -> Unit // buttons stay until the answer window ends
                }
            }
        }
    }

    /** Button taps. */
    fun accept() = finish(BoxOutcome.ACCEPTED)
    fun decline() = finish(BoxOutcome.DECLINED)

    private fun finish(outcome: BoxOutcome) {
        val plan = current ?: return
        if (ui.phase == BoxPhase.ANSWERED) return
        main.removeCallbacks(noAnswer)
        voice.stopAll()
        val now = System.currentTimeMillis()
        val level = BoxCallPolicy.levelOf(plan)
        memory = BoxCallPolicy.afterCall(memory, level, outcome, now)
        history = history + BoxCallRecord(now, level, plan.arrivalPercent.value, outcome)
        Log.i(TAG, "Box call answered: $outcome")
        if (outcome == BoxOutcome.ACCEPTED) runCatching(onAccept).onFailure { Log.w(TAG, "accept action failed", it) }
        val reply = BoxCallPolicy.reply(outcome, current?.powertrain ?: Powertrain.EV)
        ui = ui.copy(phase = BoxPhase.ANSWERED, reply = reply)
        if (reply.isNotEmpty()) voice.speak(reply)
        main.postDelayed(hide, if (reply.isEmpty()) 500 else 4_000)
    }

    /** Forget earlier calls of this trip so the next plan can call again (demo restarts a scene). */
    fun resetMemory() { memory = BoxCallMemory(); main.removeCallbacks(noAnswer); ui = ui.copy(visible = false) }

    /** A one-way radio message with no question, e.g. "charging target reached". */
    fun announce(text: String) {
        main.removeCallbacks(hide); main.removeCallbacks(noAnswer)
        current = null
        ui = BoxCallUi(true, BoxLevel.ADVISED, text, BoxPhase.ANSWERED, reply = text, voiceStatus = voice.status)
        voice.speak(text)
        main.postDelayed(hide, 7_000)
    }

    /** Re-create the voice engines, e.g. after the voice permissions were just granted. */
    fun restartVoice() { voice.release(); voice.initialize() }

    /** Speech recognition state (ready / listening / heard / why not). */
    val listenStatus: String get() = voice.listenStatus

    /** Parked-only microphone check: listens 6 s and shows what was heard on the settings page. */
    fun testListen() { voice.listen { } }

    /** Current voice engine state for the screen. */
    val voiceStatus: String get() = voice.status

    /** Parked-only check that the radio voice actually plays. Shows the banner without asking anything. */
    fun testCall() {
        main.removeCallbacks(hide); main.removeCallbacks(noAnswer)
        current = null
        ui = BoxCallUi(true, BoxLevel.ADVISED, "무전 테스트. Pit Wall입니다. 잘 들리면 준비 완료예요.", BoxPhase.ANSWERED,
            reply = "무전 테스트. Pit Wall입니다. 잘 들리면 준비 완료예요.", voiceStatus = voice.status)
        voice.speak("무전 테스트. Pit Wall입니다. 잘 들리면 준비 완료예요.") { ui = ui.copy(voiceStatus = voice.status) }
        main.postDelayed(hide, 6_000)
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        voice.release()
    }

    private companion object { const val TAG = "BoxCall" }
}
