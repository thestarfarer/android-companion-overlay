package com.starfarer.companionoverlay

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Text-to-speech manager for reading Claude's replies aloud.
 *
 * Only speaks when the request came through voice input — typed
 * conversations stay silent. Uses Android's built-in TTS engine,
 * which means quality depends on whatever engine the device has
 * installed. Modern Pixel/Samsung neural voices are decent.
 * The ElevenLabs upgrade is a future problem.
 *
 * Exposes available voices so the user can pick one they like
 * from settings. Voice preference persists across sessions.
 */
class TtsManager(private val context: Context) {

    companion object {
        private const val TAG = "TTS"
        private const val UTTERANCE_ID = "senni_reply"
    }

    /** Called when speech finishes or is interrupted. */
    var onSpeechDone: (() -> Unit)? = null

    /** Called when TTS engine is initialized and ready. */
    var onReady: (() -> Unit)? = null

    var isReady: Boolean = false
        private set

    var isSpeaking: Boolean = false
        private set

    private var tts: TextToSpeech? = null
    private var availableVoices: List<Voice> = emptyList()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                loadVoices()
                restoreSavedVoice()
                DebugLog.log(TAG, "TTS ready, ${availableVoices.size} voices available")
                onReady?.invoke()
            } else {
                DebugLog.log(TAG, "TTS init failed: $status")
            }
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
            }

            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                onSpeechDone?.invoke()
            }

            @Deprecated("Deprecated in API 21")
            override fun onError(utteranceId: String?) {
                isSpeaking = false
                DebugLog.log(TAG, "TTS error on utterance")
                onSpeechDone?.invoke()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                isSpeaking = false
                DebugLog.log(TAG, "TTS error: $errorCode")
                onSpeechDone?.invoke()
            }
        })
    }

    /**
     * Speak the given text. Interrupts any current speech.
     */
    fun speak(text: String) {
        if (!isReady) {
            DebugLog.log(TAG, "TTS not ready, skipping speak")
            onSpeechDone?.invoke()
            return
        }

        // Strip markdown-ish formatting that sounds terrible read aloud
        val cleaned = text
            .replace(Regex("\\*+"), "")     // bold/italic markers
            .replace(Regex("~+"), "")        // tildes
            .replace(Regex("#+\\s*"), "")    // headers
            .replace(Regex("```[\\s\\S]*?```"), "") // code blocks
            .replace(Regex("`[^`]*`"), "")   // inline code
            .trim()

        if (cleaned.isEmpty()) {
            onSpeechDone?.invoke()
            return
        }

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        tts?.speak(cleaned, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
        DebugLog.log(TAG, "Speaking: ${cleaned.take(60)}...")
    }

    /** Stop any current speech immediately. */
    fun stop() {
        tts?.stop()
        isSpeaking = false
    }

    /**
     * Returns available voices, filtered to the device's current locale
     * and quality. Each entry: display name → Voice object.
     */
    fun getAvailableVoices(): List<Pair<String, Voice>> {
        return availableVoices.map { voice ->
            val quality = when {
                voice.quality >= Voice.QUALITY_VERY_HIGH -> "★★★"
                voice.quality >= Voice.QUALITY_HIGH -> "★★"
                voice.quality >= Voice.QUALITY_NORMAL -> "★"
                else -> ""
            }
            val network = if (voice.isNetworkConnectionRequired) " (network)" else ""
            val label = "${voice.name}${if (quality.isNotEmpty()) " $quality" else ""}$network"
            label to voice
        }
    }

    /** Set a specific voice by name. Persists the choice. */
    fun setVoice(voiceName: String) {
        val voice = availableVoices.find { it.name == voiceName }
        if (voice != null) {
            tts?.voice = voice
            PromptSettings.setTtsVoice(context, voiceName)
            DebugLog.log(TAG, "Voice set: ${voice.name}")
        }
    }

    /** Get the currently active voice name. */
    fun getCurrentVoiceName(): String? {
        return tts?.voice?.name
    }

    /** Set speech rate. 1.0 = normal, 0.75 = slower, 1.25 = faster. */
    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        PromptSettings.setTtsSpeechRate(context, rate)
    }

    /** Set pitch. 1.0 = normal, 0.8 = deeper, 1.2 = higher. */
    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
        PromptSettings.setTtsPitch(context, pitch)
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    // --- Private ---

    private fun loadVoices() {
        val allVoices = tts?.voices ?: return
        val deviceLocale = Locale.getDefault()

        // Filter to voices that match device language, aren't broken,
        // and sort by quality descending
        availableVoices = allVoices
            .filter { voice ->
                !voice.isNetworkConnectionRequired &&
                voice.locale.language == deviceLocale.language &&
                voice.features?.contains("notInstalled") != true
            }
            .sortedByDescending { it.quality }

        // If no locale-matched voices, show all offline voices
        if (availableVoices.isEmpty()) {
            availableVoices = allVoices
                .filter { voice ->
                    !voice.isNetworkConnectionRequired &&
                    voice.features?.contains("notInstalled") != true
                }
                .sortedByDescending { it.quality }
        }

        DebugLog.log(TAG, "Filtered ${availableVoices.size} voices for ${deviceLocale.language}")
        availableVoices.take(5).forEach { v ->
            DebugLog.log(TAG, "  ${v.name} q=${v.quality} locale=${v.locale}")
        }
    }

    private fun restoreSavedVoice() {
        val savedName = PromptSettings.getTtsVoice(context)
        if (savedName != null) {
            val voice = availableVoices.find { it.name == savedName }
            if (voice != null) {
                tts?.voice = voice
                DebugLog.log(TAG, "Restored voice: $savedName")
            }
        }

        val rate = PromptSettings.getTtsSpeechRate(context)
        if (rate > 0f) tts?.setSpeechRate(rate)

        val pitch = PromptSettings.getTtsPitch(context)
        if (pitch > 0f) tts?.setPitch(pitch)
    }
}
