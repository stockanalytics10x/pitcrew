package com.example.washguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.listener.EventListener as TtsEvents
import android.speech.tts.TextToSpeech as AndroidTts
import android.speech.tts.UtteranceProgressListener

/**
 * Voice for the Pit Wall radio.
 * Speech: Pleos TextToSpeech on-device (no keys), falling back to Android's TTS if it fails.
 * Listening: Pleos SpeechToText on-device for a short yes/no window, only with RECORD_AUDIO.
 * Everything is reported back on the main thread.
 */
class PitVoice(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var pleosTts: PleosTts? = null
    private var pleosReady = false
    private var androidTts: AndroidTts? = null
    private var androidReady = false
    private var stt: SpeechToText? = null
    private var sttReady = false
    private var onSpoken: (() -> Unit)? = null
    private var onHeard: ((String?) -> Unit)? = null
    private val listenTimeout = Runnable { finishListening(null) }

    /** Short human-readable status for the debrief / logs. */
    var status by androidx.compose.runtime.mutableStateOf("음성 준비 전")
        private set
    /** Speech recognition state for the screen: ready, listening, what was heard, or why it cannot listen. */
    var listenStatus by androidx.compose.runtime.mutableStateOf("음성 인식 준비 전")
        private set
    /** Why Pleos TTS is not used, kept so the fallback status can still show it. */
    private var pleosProblem: String? = null

    val canListen: Boolean
        get() = sttReady && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun initialize() {
        // The Pleos SDKs create their engine inside initialize(); listeners and settings must come after onSuccess,
        // otherwise they throw "lateinit property tts has not been initialized".
        if (pleosTts == null) runCatching {
            val tts = PleosTts(context.applicationContext, ai.pleos.playground.tts.constant.Mode.ON_DEVICE)
            pleosTts = tts
            status = "Pleos 음성 연결 중"
            tts.initialize(object : ai.pleos.playground.tts.listener.OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        runCatching {
                            tts.addEventListener(object : TtsEvents {
                                override fun onStart() {}
                                override fun onReady() {}
                                override fun onDone() { main.post { spokenDone() } }
                                override fun onError(error: ai.pleos.playground.tts.constant.ErrorType) {
                                    Log.w(TAG, "Pleos TTS error: $error"); main.post { status = "Pleos 말하기 오류($error)"; spokenDone() }
                                }
                                override fun onStop() { main.post { spokenDone() } }
                                override fun onUpdatedRms(rms: Double) {}
                            })
                            tts.setLocale(Locale.KOREA)
                            tts.setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        }.onSuccess {
                            pleosReady = true; pleosProblem = null; status = "Pleos 음성(온디바이스)"
                            Log.i(TAG, "Pleos TTS ready")
                        }.onFailure {
                            Log.w(TAG, "Pleos TTS setup failed", it)
                            pleosProblem = "Pleos 설정 실패: ${it.message ?: it.javaClass.simpleName}"; status = pleosProblem!!; startAndroidTts()
                        }
                    }
                }
                override fun onFailure(error: ai.pleos.playground.tts.constant.ErrorType) {
                    Log.w(TAG, "Pleos TTS init failed: $error"); main.post { pleosProblem = "Pleos 연결 실패($error)"; status = pleosProblem!!; startAndroidTts() }
                }
            })
        }.onFailure { Log.w(TAG, "Pleos TTS unavailable", it); pleosProblem = "Pleos 없음: ${it.message ?: it.javaClass.simpleName}"; status = pleosProblem!!; startAndroidTts() }

        if (stt == null) runCatching {
            val s = SpeechToText(context.applicationContext, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
            stt = s
            s.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        runCatching {
                            s.addListener(object : ResultListener {
                                override fun onUpdated(text: String, isFinal: Boolean) {
                                    if (isFinal || BoxCallPolicy.parseAnswer(text) != BoxAnswer.UNKNOWN) main.post { finishListening(text) }
                                }
                                override fun onUpdatedEpdData(start: Long, end: Long) {}
                                override fun onStartedRecognition() { Log.i(TAG, "STT started") }
                                override fun onEndedRecognition() {}
                                override fun onError(error: ai.pleos.playground.stt.constant.ErrorType) {
                                    Log.w(TAG, "STT error: $error"); main.post { listenStatus = "인식 오류($error)"; finishListening(null, keepStatus = true) }
                                }
                                override fun onReady() {}
                                override fun onUpdatedRms(rms: Double) {}
                            })
                            s.setLocale(Locale.KOREA)
                        }.onSuccess { sttReady = true; listenStatus = "음성 인식 준비됨"; Log.i(TAG, "STT ready") }
                            .onFailure { Log.w(TAG, "STT setup failed", it); listenStatus = "인식 설정 실패: ${it.message ?: it.javaClass.simpleName}" }
                    }
                }
                override fun onFailure(error: ai.pleos.playground.stt.constant.ErrorType) { Log.w(TAG, "STT init failed: $error"); main.post { listenStatus = "인식 연결 실패($error)" } }
            })
        }.onFailure { Log.w(TAG, "STT unavailable", it); listenStatus = "인식 없음: ${it.message ?: it.javaClass.simpleName}" }
    }

    private fun startAndroidTts() {
        if (androidTts != null) return
        androidTts = AndroidTts(context.applicationContext) { code ->
            main.post {
                androidReady = code == AndroidTts.SUCCESS
                if (androidReady) {
                    val lang = androidTts?.setLanguage(Locale.KOREA) ?: AndroidTts.LANG_NOT_SUPPORTED
                    if (lang == AndroidTts.LANG_MISSING_DATA || lang == AndroidTts.LANG_NOT_SUPPORTED) Log.w(TAG, "Android TTS has no Korean voice: $lang")
                    androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { main.post { spokenDone() } }
                        @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { spokenDone() } }
                    })
                    val base = if (lang == AndroidTts.LANG_MISSING_DATA || lang == AndroidTts.LANG_NOT_SUPPORTED) "기본 음성 · 한국어 음성 없음" else "안드로이드 기본 음성"
                    status = listOfNotNull(pleosProblem, base).joinToString(" → ")
                } else status = listOfNotNull(pleosProblem, "기본 음성도 시작 실패(코드 $code)").joinToString(" → ")
            }
        }
    }

    /** Speaks [text]; [done] runs once when speech ends, fails, or no engine is ready. */
    fun speak(text: String, done: () -> Unit = {}) {
        onSpoken = done
        when {
            pleosReady -> runCatching { pleosTts?.speak(text, true) }.onFailure {
                Log.w(TAG, "Pleos speak failed", it)
                pleosProblem = "Pleos 말하기 실패: ${it.message ?: it.javaClass.simpleName}"
                status = pleosProblem!!
                pleosReady = false
                if (androidReady) androidTts?.speak(text, AndroidTts.QUEUE_FLUSH, null, "pitwall") else { startAndroidTts(); spokenDone() }
            }
            androidReady -> androidTts?.speak(text, AndroidTts.QUEUE_FLUSH, null, "pitwall") ?: spokenDone()
            else -> { Log.w(TAG, "No TTS ready, skipping speech"); status = "음성 엔진 준비 안 됨 · 소리 없이 표시"; spokenDone() }
        }
        // Some engines never report completion; never block the call flow on it.
        main.postDelayed({ if (onSpoken === done) spokenDone() }, 12_000)
    }

    private fun spokenDone() {
        val cb = onSpoken ?: return
        onSpoken = null
        cb()
    }

    /** Listens for up to [timeoutMs]; [heard] gets the recognized text, or null for silence / no microphone. */
    fun listen(timeoutMs: Long = 6_000, heard: (String?) -> Unit) {
        if (!canListen) {
            if (sttReady) listenStatus = "마이크 권한 없음 · 설정에서 허용"
            heard(null); return
        }
        onHeard = heard
        listenStatus = "듣는 중…"
        main.removeCallbacks(listenTimeout)
        main.postDelayed(listenTimeout, timeoutMs)
        runCatching { stt?.request() }.onFailure { Log.w(TAG, "STT request failed", it); finishListening(null) }
    }

    private fun finishListening(text: String?, keepStatus: Boolean = false) {
        val cb = onHeard ?: return
        onHeard = null
        if (!keepStatus) listenStatus = if (text.isNullOrBlank()) "들은 말 없음 · 마이크 입력을 확인하세요" else "들은 말: \"$text\""
        main.removeCallbacks(listenTimeout)
        runCatching { stt?.stop() }
        cb(text)
    }

    fun stopAll() {
        runCatching { pleosTts?.stop() }
        runCatching { androidTts?.stop() }
        onSpoken = null
        finishListening(null)
    }

    fun release() {
        pleosProblem = null
        listenStatus = "음성 인식 준비 전"
        stopAll()
        main.removeCallbacksAndMessages(null)
        runCatching { pleosTts?.release() }
        runCatching { stt?.release() }
        runCatching { androidTts?.shutdown() }
        pleosTts = null; stt = null; androidTts = null
        pleosReady = false; sttReady = false; androidReady = false
    }

    private companion object { const val TAG = "PitVoice" }
}
