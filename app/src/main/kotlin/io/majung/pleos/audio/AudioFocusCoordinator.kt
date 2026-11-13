package io.majung.pleos.audio

import java.util.ArrayDeque

enum class FocusChangeEvent {
    GAIN,
    LOSS_TRANSIENT,
    LOSS_TRANSIENT_CAN_DUCK,
    LOSS_PERMANENT
}

interface VehicleAudioPlatform {
    fun requestTransientMayDuckFocus(onFocusChanged: (FocusChangeEvent) -> Unit): Boolean
    fun abandonAudioFocus()
}

interface PleosTtsEngine {
    fun initializeAndSetKoreanLocale(onReady: () -> Unit)
    fun speak(text: String, onCompleted: () -> Unit, onError: (String) -> Unit)
    fun stop()
    fun release()
}

class AudioFocusCoordinator(
    private val audioPlatform: VehicleAudioPlatform,
    private val ttsEngine: PleosTtsEngine,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    data class SpeechItem(
        val utteranceId: String,
        val text: String,
        val isCriticalSafety: Boolean,
        val enqueuedAtMs: Long,
        val maxResumeAgeMs: Long = 20_000L
    )

    private val speechQueue = ArrayDeque<SpeechItem>()
    private var activeItem: SpeechItem? = null
    private var pausedByTransientInterrupt: Boolean = false
    private var isEngineReady: Boolean = false

    fun initialize() {
        ttsEngine.initializeAndSetKoreanLocale {
            isEngineReady = true
            playNextIfIdle()
        }
    }

    fun speakWithDucking(
        utteranceId: String,
        text: String,
        isCriticalSafety: Boolean = false
    ) {
        val now = clockMs()
        val item = SpeechItem(
            utteranceId = utteranceId,
            text = text,
            isCriticalSafety = isCriticalSafety,
            enqueuedAtMs = now
        )

        if (isCriticalSafety) {
            if (activeItem != null && activeItem?.isCriticalSafety == false) {
                ttsEngine.stop()
                activeItem = null
            }
            speechQueue.addFirst(item)
        } else {
            speechQueue.addLast(item)
        }
        playNextIfIdle()
    }

    fun onAudioFocusChanged(event: FocusChangeEvent) {
        when (event) {
            FocusChangeEvent.LOSS_TRANSIENT -> {
                val interrupted = activeItem
                if (interrupted != null) {
                    pausedByTransientInterrupt = true
                    ttsEngine.stop()
                    speechQueue.addFirst(interrupted)
                    activeItem = null
                }
            }
            FocusChangeEvent.GAIN -> {
                if (pausedByTransientInterrupt) {
                    pausedByTransientInterrupt = false
                    pruneStaleItems()
                    playNextIfIdle()
                }
            }
            FocusChangeEvent.LOSS_TRANSIENT_CAN_DUCK -> Unit
            FocusChangeEvent.LOSS_PERMANENT -> stopAndAbandonFocus()
        }
    }

    fun stopAndAbandonFocus() {
        pausedByTransientInterrupt = false
        speechQueue.clear()
        activeItem = null
        ttsEngine.stop()
        audioPlatform.abandonAudioFocus()
    }

    fun release() {
        stopAndAbandonFocus()
        ttsEngine.release()
    }

    private fun playNextIfIdle() {
        if (!isEngineReady || activeItem != null || pausedByTransientInterrupt) return
        pruneStaleItems()
        val next = speechQueue.pollFirst() ?: run {
            audioPlatform.abandonAudioFocus()
            return
        }

        val focusGranted = audioPlatform.requestTransientMayDuckFocus(::onAudioFocusChanged)
        if (!focusGranted && !next.isCriticalSafety) {
            speechQueue.addFirst(next)
            return
        }

        activeItem = next
        ttsEngine.speak(
            text = next.text,
            onCompleted = {
                activeItem = null
                if (speechQueue.isEmpty()) {
                    audioPlatform.abandonAudioFocus()
                } else {
                    playNextIfIdle()
                }
            },
            onError = {
                activeItem = null
                audioPlatform.abandonAudioFocus()
                playNextIfIdle()
            }
        )
    }

    private fun pruneStaleItems() {
        val now = clockMs()
        val iter = speechQueue.iterator()
        while (iter.hasNext()) {
            val candidate = iter.next()
            if (!candidate.isCriticalSafety && (now - candidate.enqueuedAtMs) > candidate.maxResumeAgeMs) {
                iter.remove()
            }
        }
    }

    fun queuedCount(): Int = speechQueue.size
}
