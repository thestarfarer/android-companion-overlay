package com.starfarer.companionoverlay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.starfarer.companionoverlay.repository.SettingsRepository

/**
 * Routes speech to the active TTS engine (Gemini → on-device fallback on
 * error), plays beeps, and owns the cross-cutting audio concerns: audio focus
 * around speech/recording and the Bluetooth A2DP keep-alive.
 *
 * Singleton — outlives the overlay service. [warmUp]/[release] bracket a
 * service lifetime; release detaches every service-owned callback so a late
 * engine error can't touch a destroyed service's UI.
 *
 * STT deliberately has no analogous fallback: TTS degrading mid-conversation
 * is invisible (a different voice), but silently swapping the transcription
 * engine changes accuracy and privacy expectations — STT errors surface to
 * the user instead.
 */
class AudioCoordinator(
    private val context: Context,
    val ttsManager: TtsManager,
    val geminiTtsManager: GeminiTtsManager,
    private val settings: SettingsRepository,
    private val beepManager: BeepManager
) {
    companion object { private const val TAG = "Audio" }

    var onSpeechComplete: (() -> Unit)? = null

    /** Gemini synthesis started — show a "generating voice" indicator. */
    var onSynthesisStarted: (() -> Unit)? = null

    /** Playback began or the attempt ended — clear the indicator. */
    var onSynthesisEnded: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val keepAlive = SilenceKeepAlive()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    // Set by release(), cleared by warmUp(): late engine callbacks landing
    // between service lifetimes must not re-init TTS or invoke dead UI hooks.
    private var released = true

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = updateKeepAlive()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = updateKeepAlive()
    }

    init {
        geminiTtsManager.onSynthesisStarted = {
            handler.post { if (!released) onSynthesisStarted?.invoke() }
        }
        geminiTtsManager.onSynthesisEnded = {
            handler.post { if (!released) onSynthesisEnded?.invoke() }
        }
        geminiTtsManager.onSpeechError = { failedText ->
            handler.post {
                if (released) return@post
                DebugLog.log(TAG, "Gemini TTS failed, falling back to on-device")
                playBeep(BeepManager.Beep.ERROR)
                onSynthesisEnded?.invoke()
                ttsManager.ensureReady()
                ttsManager.onSpeechDone = { speechFinished() }
                ttsManager.speak(failedText)
            }
        }
    }

    val isSpeaking: Boolean
        get() = ttsManager.isSpeaking || geminiTtsManager.isSpeaking

    fun warmUp() {
        released = false
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        updateKeepAlive()
    }

    /**
     * The keep-alive exists to stop A2DP sinks from powering down and clipping
     * the next beep/utterance (see 82e91cc). That only matters when a Bluetooth
     * output is actually connected — it used to stream silence 24/7 even on
     * speaker-only devices, holding the audio HAL awake for nothing.
     */
    private fun updateKeepAlive() {
        val btConnected = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        if (btConnected) keepAlive.start() else keepAlive.stop()
    }

    fun speak(text: String) {
        val useGemini = settings.geminiTtsEnabled && !settings.geminiApiKey.isNullOrBlank()
        // Stop whichever engine is NOT about to speak — flipping the Gemini
        // toggle mid-speech used to let both engines talk over each other.
        if (useGemini) ttsManager.stop() else geminiTtsManager.stop()

        requestFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        if (useGemini) {
            geminiTtsManager.onSpeechDone = { speechFinished() }
            geminiTtsManager.speak(text)
        } else {
            ttsManager.ensureReady()
            ttsManager.onSpeechDone = { speechFinished() }
            ttsManager.speak(text)
        }
    }

    private fun speechFinished() {
        abandonFocus()
        onSpeechComplete?.invoke()
        ttsManager.onSpeechDone = null
        geminiTtsManager.onSpeechDone = null
    }

    fun stopSpeaking() {
        ttsManager.stop()
        geminiTtsManager.stop()
        abandonFocus()
        // Synthesis may have been cancelled before playback — clear the
        // "generating voice" indicator that would otherwise linger 30s.
        onSynthesisEnded?.invoke()
        onSpeechComplete = null
    }

    /**
     * Audio focus around active recording (driven by the mic FGS promotion in
     * the service) — without it, music kept playing under the microphone.
     */
    fun setRecordingActive(active: Boolean) {
        if (active) requestFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        else abandonFocus()
    }

    fun playBeep(beep: BeepManager.Beep) {
        if (settings.beepsEnabled) beepManager.play(beep)
    }

    private fun requestFocus(gain: Int) {
        abandonFocus()
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) {
                    // Another app took over for good — be polite and go quiet.
                    handler.post { stopSpeaking() }
                }
            }
            .build()
        audioManager.requestAudioFocus(request)
        focusRequest = request
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    fun release() {
        released = true
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        keepAlive.stop()
        abandonFocus()
        ttsManager.release()
        geminiTtsManager.release()
        // Drop service-owned hooks — this singleton outlives the service, and a
        // retained lambda would leak the whole service via its captured managers.
        onSpeechComplete = null
        onSynthesisStarted = null
        onSynthesisEnded = null
        // BeepManager is a singleton — outlives the service. Don't release it here.
    }
}
