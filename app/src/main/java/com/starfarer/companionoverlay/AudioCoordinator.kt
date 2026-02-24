package com.starfarer.companionoverlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.starfarer.companionoverlay.repository.SettingsRepository

class AudioCoordinator(
    private val context: Context,
    val ttsManager: TtsManager,
    val geminiTtsManager: GeminiTtsManager,
    private val settings: SettingsRepository,
    private val beepManager: BeepManager
) {

    companion object {
        private const val TAG = "Audio"
    }

    var onSpeechComplete: (() -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

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

    val isSpeaking: Boolean
        get() = ttsManager.isSpeaking || geminiTtsManager.isSpeaking

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

    fun stopSpeaking() {
        ttsManager.stop()
        geminiTtsManager.stop()
        onSpeechComplete = null
    }

    fun playBeep(beep: BeepManager.Beep) {
        if (settings.beepsEnabled) {
            beepManager.play(beep)
        }
    }

    fun release() {
        ttsManager.release()
        geminiTtsManager.release()
    }
}
