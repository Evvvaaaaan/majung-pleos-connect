package io.majung.pleos.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

class AndroidVehicleAudioPlatform(context: Context) : VehicleAudioPlatform {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var currentRequest: AudioFocusRequest? = null

    override fun requestTransientMayDuckFocus(onFocusChanged: (FocusChangeEvent) -> Unit): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> onFocusChanged(FocusChangeEvent.GAIN)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onFocusChanged(FocusChangeEvent.LOSS_TRANSIENT)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                    onFocusChanged(FocusChangeEvent.LOSS_TRANSIENT_CAN_DUCK)
                AudioManager.AUDIOFOCUS_LOSS -> onFocusChanged(FocusChangeEvent.LOSS_PERMANENT)
            }
        }

        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener)
            .build()

        currentRequest = req
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandonAudioFocus() {
        currentRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        currentRequest = null
    }
}
