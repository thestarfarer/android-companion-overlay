package com.starfarer.companionoverlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.starfarer.companionoverlay.repository.SettingsRepository

/**
 * Coordinates all audio output: TTS (on-device or Gemini) and beeps.
 *
 * Responsibilities:
 * - Routing speech to the appropriate TTS engine
 * - Fallback from Gemini TTS to on-device when synthesis fails
 * - Beep playback
 * - Unified speaking state and completion callbacks
 *
 * Threading: Safe to call from any thread. Callbacks dispatch to main.
 */
class AudioCoordinator(
    private val context: Context,
    val ttsManager: TtsManager,
    val geminiTtsManager: GeminiTtsManager,
    private val settings: SettingsRepository
) {

    companion object {
        private const val TAG = "Audio"
    }

    /** Callback for speech completion. */
    var onSpeechComplete: (() -> Unit)? = null

    /** Called when Gemini TTS updates status ("Generating voice...", etc). */
    var onStatusUpdate: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val beepManager = BeepManager()

    init {
        geminiTtsManager.onStatusUpdate = { status ->
            handler.post { onStatusUpdate?.invoke(status) }
        }

        geminiTtsManager.onSpeechError = { failedText ->
            DebugLog.log(TAG, "Gemini TTS failed, falling back to on-device")
            handler.post {
                playBeep(BeepManager.Beep.ERROR)
                onStatusUpdate?.invoke("")

                ttsManager.onSpeechDone = {
                    onSpeechComplete?.invoke()
                    ttsManager.onSpeechDone = null
                }
                ttsManager.speak(failedText)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    /** Whether either TTS engine is currently speaking. */
    val isSpeaking: Boolean
        get() = ttsManager.isSpeaking || geminiTtsManager.isSpeaking

    /**
     * Speak text through the appropriate TTS engine.
     * Uses Gemini if enabled and API key is set, otherwise on-device.
     */
    fun speak(text: String) {
        val useGemini = settings.geminiTtsEnabled && !settings.geminiApiKey.isNullOrBlank()

        if (useGemini) {
            geminiTtsManager.onSpeechDone = {
                onSpeechComplete?.invoke()
                geminiTtsManager.onSpeechDone = null
            }
            geminiTtsManager.speak(text)
        } else {
            ttsManager.onSpeechDone = {
                onSpeechComplete?.invoke()
                ttsManager.onSpeechDone = null
            }
            ttsManager.speak(text)
        }
    }

    /** Stop any current TTS playback immediately. */
    fun stopSpeaking() {
        ttsManager.stop()
        geminiTtsManager.stop()
    }

    /** Play a beep sound if beeps are enabled in settings. */
    fun playBeep(beep: BeepManager.Beep) {
        if (settings.beepsEnabled) {
            beepManager.play(beep)
        }
    }

    /** Release all audio resources. Call in onDestroy. */
    fun release() {
        ttsManager.release()
        geminiTtsManager.release()
    }
}
