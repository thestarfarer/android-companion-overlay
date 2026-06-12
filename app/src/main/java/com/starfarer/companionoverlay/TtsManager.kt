package com.starfarer.companionoverlay

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.starfarer.companionoverlay.repository.SettingsRepository
import java.util.Locale

class TtsManager(
    private val context: Context,
    private val settings: SettingsRepository
) {

    companion object {
        private const val TAG = "TTS"
        private const val UTTERANCE_ID = "senni_reply"
    }

    var onSpeechDone: (() -> Unit)? = null
    var onReady: (() -> Unit)? = null
    /** Engine init failed — callers waiting on [onReady] should surface an error and release. */
    var onInitFailed: (() -> Unit)? = null

    var isReady: Boolean = false
        private set

    @Volatile
    var isSpeaking: Boolean = false
        private set

    private var tts: TextToSpeech? = null
    private var availableVoices: List<Voice> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private var pendingText: String? = null

    /**
     * Bumped on every initEngine() and release(). The async init callback
     * checks it — a release() racing a slow init used to let the stale
     * callback set isReady=true with tts already null, after which every
     * speak() silently no-oped forever.
     */
    private var engineGeneration = 0

    init {
        initEngine()
    }

    fun ensureReady() {
        if (!isReady && tts == null) {
            DebugLog.log(TAG, "Re-initializing TTS engine")
            initEngine()
        }
    }

    private fun initEngine() {
        val myGeneration = ++engineGeneration
        tts = TextToSpeech(context) { status ->
            if (myGeneration != engineGeneration) {
                DebugLog.log(TAG, "Init callback for a superseded engine, ignoring")
                return@TextToSpeech
            }
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                    }

                    override fun onDone(utteranceId: String?) {
                        DebugLog.log(TAG, "Chunk done: $utteranceId")
                        if (utteranceId == UTTERANCE_ID) {
                            isSpeaking = false
                            handler.post { onSpeechDone?.invoke() }
                        }
                    }

                    @Deprecated("Deprecated in API 21")
                    override fun onError(utteranceId: String?) {
                        onChunkError(utteranceId)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        DebugLog.log(TAG, "TTS error: $errorCode on $utteranceId")
                        onChunkError(utteranceId)
                    }

                    // Completion fires only for the FINAL chunk's id — an error
                    // on an intermediate chunk used to signal onSpeechDone while
                    // the remaining chunks were still queued and speaking.
                    private fun onChunkError(utteranceId: String?) {
                        DebugLog.log(TAG, "TTS error on utterance: $utteranceId")
                        if (utteranceId == UTTERANCE_ID) {
                            isSpeaking = false
                            handler.post { onSpeechDone?.invoke() }
                        }
                    }
                })

                isReady = true
                loadVoices()
                restoreSavedVoice()
                DebugLog.log(TAG, "TTS ready, ${availableVoices.size} voices available")
                pendingText?.let { text ->
                    pendingText = null
                    DebugLog.log(TAG, "Playing queued text")
                    handler.post { speak(text) }
                }
                onReady?.invoke()
            } else {
                DebugLog.log(TAG, "TTS init failed: $status")
                // Leave the engine null so ensureReady() can retry — a failed
                // init used to park here forever, and any queued utterance's
                // completion never fired (the voice flow then hung until the
                // safety watchdog).
                tts?.shutdown()
                tts = null
                isReady = false
                pendingText = null
                handler.post {
                    onSpeechDone?.invoke()
                    onInitFailed?.invoke()
                }
            }
        }
    }

    fun speak(text: String) {
        if (!isReady || tts == null) {
            DebugLog.log(TAG, "TTS not ready, queuing text")
            pendingText = text
            ensureReady()
            return
        }

        // Apply voice/rate/pitch in case settings changed since init
        applyCurrentSettings()

        val cleaned = TtsTextCleaner.clean(text)

        if (cleaned.isEmpty()) {
            onSpeechDone?.invoke()
            return
        }

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        val maxLen = TextToSpeech.getMaxSpeechInputLength()
        DebugLog.log(TAG, "Speaking: ${cleaned.length} chars (max=$maxLen): ${cleaned.take(60)}...")

        // Mark speaking at enqueue, not at the engine's async onStart — gating
        // logic (e.g. conversationManager.isTtsSpeaking) raced into the gap.
        isSpeaking = true

        if (cleaned.length <= maxLen) {
            val result = tts?.speak(cleaned, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
            if (result != TextToSpeech.SUCCESS) {
                DebugLog.log(TAG, "speak() returned error: $result")
                isSpeaking = false
            }
        } else {
            val chunks = mutableListOf<String>()
            var remaining = cleaned
            while (remaining.isNotEmpty()) {
                if (remaining.length <= maxLen) {
                    chunks.add(remaining)
                    break
                }
                val slice = remaining.substring(0, maxLen)
                val breakIdx = maxOf(
                    slice.lastIndexOf(". "),
                    slice.lastIndexOf("! "),
                    slice.lastIndexOf("? "),
                    slice.lastIndexOf("\n")
                )
                val splitAt = if (breakIdx > maxLen / 2) breakIdx + 1 else maxLen
                chunks.add(remaining.substring(0, splitAt).trim())
                remaining = remaining.substring(splitAt).trim()
            }
            DebugLog.log(TAG, "Split into ${chunks.size} chunks")
            chunks.forEachIndexed { i, chunk ->
                val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val id = if (i == chunks.size - 1) UTTERANCE_ID else "${UTTERANCE_ID}_$i"
                tts?.speak(chunk, mode, params, id)
            }
        }
    }

    fun stop() {
        tts?.stop()
        // A queued utterance must die with the stop — it used to survive and
        // blurt out whenever a slow engine init finally completed.
        pendingText = null
        isSpeaking = false
    }

    fun getAvailableVoices(): List<Pair<String, Voice>> {
        return availableVoices.map { voice ->
            val quality = when {
                voice.quality >= Voice.QUALITY_VERY_HIGH -> "\u2605\u2605\u2605"
                voice.quality >= Voice.QUALITY_HIGH -> "\u2605\u2605"
                voice.quality >= Voice.QUALITY_NORMAL -> "\u2605"
                else -> ""
            }
            val network = if (voice.isNetworkConnectionRequired) " (network)" else ""
            val label = "${voice.name}${if (quality.isNotEmpty()) " $quality" else ""}$network"
            label to voice
        }
    }

    fun setVoice(voiceName: String) {
        val voice = availableVoices.find { it.name == voiceName }
        if (voice != null) {
            tts?.voice = voice
            settings.ttsVoice = voiceName
            DebugLog.log(TAG, "Voice set: ${voice.name}")
        }
    }

    fun getCurrentVoiceName(): String? = tts?.voice?.name

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        settings.ttsSpeechRate = rate
    }

    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
        settings.ttsPitch = pitch
    }

    fun release() {
        stop()
        engineGeneration++
        tts?.shutdown()
        tts = null
        isReady = false
    }

    private fun applyCurrentSettings() {
        val savedName = settings.ttsVoice
        if (savedName != null && tts?.voice?.name != savedName) {
            val voice = availableVoices.find { it.name == savedName }
            if (voice != null) {
                tts?.voice = voice
                DebugLog.log(TAG, "Voice updated: $savedName")
            }
        }
        val rate = settings.ttsSpeechRate
        if (rate > 0f) tts?.setSpeechRate(rate)
        val pitch = settings.ttsPitch
        if (pitch > 0f) tts?.setPitch(pitch)
    }

    private fun loadVoices() {
        val allVoices = tts?.voices ?: return
        val deviceLocale = Locale.getDefault()

        availableVoices = allVoices
            .filter { voice ->
                !voice.isNetworkConnectionRequired &&
                voice.locale.language == deviceLocale.language &&
                voice.features?.contains("notInstalled") != true
            }
            .sortedByDescending { it.quality }

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
        val savedName = settings.ttsVoice
        if (savedName != null) {
            val voice = availableVoices.find { it.name == savedName }
            if (voice != null) {
                tts?.voice = voice
                DebugLog.log(TAG, "Restored voice: $savedName")
            }
        }

        val rate = settings.ttsSpeechRate
        if (rate > 0f) tts?.setSpeechRate(rate)

        val pitch = settings.ttsPitch
        if (pitch > 0f) tts?.setPitch(pitch)
    }
}
