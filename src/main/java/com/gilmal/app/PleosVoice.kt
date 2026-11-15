package com.gilmal.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale

/**
 * Pleos 차량 음성(TextToSpeech SDK) 래퍼.
 * - Pleos Connect에는 Android 기본 음성 엔진이 없으므로 차량 음성 서비스를 사용한다.
 * - 온디바이스 → 하이브리드 → 서버 순서로 연결을 시도하고, 각 단계의 결과를 lastStatus에 남긴다.
 * - Pleos 음성 서비스가 없는 기기에서는 ready=false로 남고, MainActivity가 Android 음성 → 알림음 순으로 대체한다.
 * - 원인 확인: Logcat에서 "GILMAL_TTS" 검색
 */
class PleosVoice(
    private val context: Context,
    private val onStatus: (String) -> Unit
) {
    private val tag = "GILMAL_TTS"
    private val handler = Handler(Looper.getMainLooper())
    private val modes = listOf(Mode.ON_DEVICE, Mode.HYBRID, Mode.SERVER)
    private var modeIndex = 0
    private val failures = mutableListOf<String>()
    private var tts: PleosTts? = null

    @Volatile
    var ready: Boolean = false
        private set

    // 모든 연결 방식이 실패했으면 true (그 전까지는 연결 중으로 본다)
    @Volatile
    private var gaveUp = false

    // 연결 중에 들어온 안내 문장: 연결되면 바로 읽는다 (그동안 대체 알림음을 울리지 않기 위함)
    @Volatile
    private var pending: String? = null

    @Volatile
    var lastStatus: String = "Pleos 음성 연결 중"
        private set

    private fun report(status: String) {
        lastStatus = status
        Log.i(tag, status)
        onStatus(status)
    }

    private val events = object : EventListener {
        override fun onStart() = report("Pleos 차량 음성 재생 중")
        override fun onReady() {}
        override fun onDone() = report("Pleos 차량 음성 준비됨")
        override fun onError(errorType: ErrorType) = report("Pleos 음성 재생 오류: $errorType")
        override fun onStop() = report("Pleos 차량 음성 준비됨")
        override fun onUpdatedRms(rms: Double) {}
    }

    fun start() {
        gaveUp = false
        modeIndex = 0
        failures.clear()
        tryMode()
    }

    private fun tryMode() {
        if (modeIndex >= modes.size) {
            ready = false
            gaveUp = true
            pending = null
            report("Pleos 음성 사용 불가 [" + failures.joinToString(", ") + "]")
            return
        }
        val mode = modes[modeIndex]
        report("Pleos 음성 연결 시도: $mode")
        try {
            val engine = PleosTts(context, mode)
            tts = engine
            // SDK 규칙: initialize 성공 뒤에 이벤트 리스너를 등록해야 한다
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    try { engine.addEventListener(events) } catch (e: Throwable) { Log.w(tag, "addEventListener 실패", e) }
                    ready = true
                    try { engine.setLocale(Locale.KOREA) } catch (e: Throwable) { Log.w(tag, "setLocale 실패", e) }
                    report("Pleos 차량 음성 준비됨 ($mode)")
                    pending?.let { text ->
                        pending = null
                        try { engine.speak(text) } catch (e: Throwable) { Log.e(tag, "대기 문장 재생 오류", e) }
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(tag, "initialize 실패: $mode / $errorType")
                    failNext("$mode:$errorType", engine)
                }
            })
            // 응답이 오지 않는 경우를 대비해 8초 뒤 다음 방식으로 넘어간다
            val index = modeIndex
            handler.postDelayed({
                if (!ready && modeIndex == index) failNext("$mode:응답 없음", engine)
            }, 8000)
        } catch (e: Throwable) {
            Log.e(tag, "Pleos 음성 생성 오류: $mode", e)
            failNext("$mode:${e.javaClass.simpleName}", null)
        }
    }

    private fun failNext(reason: String, engine: PleosTts?) {
        handler.post {
            if (ready) return@post
            failures += reason
            try {
                engine?.removeEventListener(events)
                engine?.release()
            } catch (e: Throwable) { }
            if (tts === engine) tts = null
            modeIndex++
            tryMode()
        }
    }

    /** 재생 요청에 성공하면 true. 실패하면 호출한 쪽에서 다른 방식으로 대체한다. */
    fun speak(text: String): Boolean {
        if (!ready) {
            if (gaveUp) return false
            pending = text   // 아직 연결 중: 연결되면 읽는다
            return true
        }
        val engine = tts ?: return false
        return try {
            engine.speak(text)
            true
        } catch (e: Throwable) {
            Log.e(tag, "speak 오류", e)
            report("Pleos 음성 재생 요청 실패: ${e.javaClass.simpleName}")
            false
        }
    }

    fun stop() {
        try { tts?.stop() } catch (e: Throwable) { }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        try {
            tts?.removeEventListener(events)
            tts?.release()
        } catch (e: Throwable) { }
        tts = null
        ready = false
    }
}
