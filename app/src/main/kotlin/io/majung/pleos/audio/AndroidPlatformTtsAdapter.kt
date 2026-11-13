package io.majung.pleos.audio

import android.content.Context
import android.speech.tts.TextToSpeech as AndroidTts
import java.util.Locale

/**
 * Android 플랫폼 TTS 어댑터.
 * - sim 플레이버: 기본 음성 엔진
 * - pleos 플레이버: Pleos TextToSpeech(ON_DEVICE)가 미지원·실패인 트림에서의 폴백 (FallbackTtsEngine 참고)
 */
class AndroidPlatformTtsAdapter(context: Context) : PleosTtsEngine {
    private var tts: AndroidTts? = null

    init {
        runCatching {
            tts = AndroidTts(context.applicationContext) { status ->
                if (status == AndroidTts.SUCCESS) {
                    tts?.language = Locale.KOREAN
                }
            }
        }
    }

    override fun initializeAndSetKoreanLocale(onReady: () -> Unit) {
        onReady()
    }

    override fun speak(text: String, onCompleted: () -> Unit, onError: (String) -> Unit) {
        runCatching {
            val r = tts?.speak(text, AndroidTts.QUEUE_ADD, null, "majung_${System.nanoTime()}") ?: AndroidTts.ERROR
            if (r == AndroidTts.SUCCESS) onCompleted() else onError("Android TTS speak() returned $r")
        }.onFailure { e ->
            onError(e.message ?: "Android TTS unavailable")
        }
    }

    override fun stop() {
        runCatching { tts?.stop() }
    }

    override fun release() {
        runCatching { tts?.shutdown() }
        tts = null
    }
}

/**
 * 1차 엔진(Pleos TTS)이 실패하면 2차 엔진(Android TTS)으로 같은 문장을 재생한다.
 * 공식 호환성 안내가 "차종에 따라 다름"인 SDK를 핵심 경험(환대 인사·안전 경고)에 쓰기 위한 안전망.
 */
class FallbackTtsEngine(
    private val primary: PleosTtsEngine,
    private val secondary: PleosTtsEngine,
    private val onFallback: (String) -> Unit = {}
) : PleosTtsEngine {
    override fun initializeAndSetKoreanLocale(onReady: () -> Unit) {
        secondary.initializeAndSetKoreanLocale {}
        primary.initializeAndSetKoreanLocale(onReady)
    }

    override fun speak(text: String, onCompleted: () -> Unit, onError: (String) -> Unit) {
        primary.speak(
            text,
            onCompleted = onCompleted,
            onError = { reason ->
                onFallback("Pleos TTS 실패($reason) → Android TTS 폴백")
                secondary.speak(text, onCompleted, onError)
            }
        )
    }

    override fun stop() {
        primary.stop()
        secondary.stop()
    }

    override fun release() {
        primary.release()
        secondary.release()
    }
}
