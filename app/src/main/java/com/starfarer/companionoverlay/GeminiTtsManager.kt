package com.starfarer.companionoverlay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Text-to-speech via Gemini's TTS model.
 *
 * Sends text to gemini-2.5-flash-preview-tts, gets back raw 24kHz
 * 16-bit mono PCM audio, plays it through AudioTrack.
 *
 * Advantages over on-device TTS:
 * - Multilingual (30 voices, 24 languages, handles code-switching)
 * - No voice pack installs needed
 * - Higher quality HD voices
 * - Handles mixed Russian/English naturally
 *
 * Disadvantages:
 * - Network latency per utterance
 * - Costs tokens (cheap on flash-lite pricing)
 */
class GeminiTtsManager(private val context: Context) {

    companion object {
        private const val TAG = "GeminiTTS"

        private const val TTS_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent"

        /** Gemini TTS outputs 24kHz 16-bit mono PCM. */
        private const val SAMPLE_RATE = 24000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** Default voice — Kore is a clear, natural female voice. */
        private const val DEFAULT_VOICE = "Kore"
    }

    var onSpeechDone: (() -> Unit)? = null

    var isSpeaking: Boolean = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var audioTrack: AudioTrack? = null
    private var synthesisThread: Thread? = null
    private val stopped = AtomicBoolean(false)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // TTS can take a while for long text
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Synthesize and play speech for the given text.
     * Runs network + playback on a background thread.
     */
    fun speak(text: String) {
        stop() // Kill any current playback

        val apiKey = PromptSettings.getGeminiApiKey(context)
        if (apiKey.isNullOrBlank()) {
            DebugLog.log(TAG, "No Gemini API key, falling back to silence")
            onSpeechDone?.invoke()
            return
        }

        // Strip markdown formatting
        val cleaned = text
            .replace(Regex("\\*+"), "")
            .replace(Regex("~+"), "")
            .replace(Regex("#+\\s*"), "")
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("`[^`]*`"), "")
            .replace(Regex("---+"), "")
            .trim()

        if (cleaned.isEmpty()) {
            onSpeechDone?.invoke()
            return
        }

        stopped.set(false)
        isSpeaking = true

        val voiceName = PromptSettings.getGeminiTtsVoice(context) ?: DEFAULT_VOICE

        synthesisThread = Thread({
            synthesizeAndPlay(apiKey, cleaned, voiceName)
        }, "gemini-tts").also { it.start() }
    }

    private fun synthesizeAndPlay(apiKey: String, text: String, voiceName: String) {
        DebugLog.log(TAG, "Synthesizing: ${text.length} chars, voice=$voiceName")

        // Build request per Gemini TTS API docs
        val requestJson = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(
                        JSONObject().put("text", text)
                    ))
                }
            ))
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", voiceName)
                        })
                    })
                })
            })
        }

        val url = "$TTS_URL?key=$apiKey"
        val body = requestJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (stopped.get()) return

            if (!response.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBody ?: "")
                        .getJSONObject("error")
                        .getString("message")
                } catch (_: Exception) {
                    "HTTP ${response.code}"
                }
                DebugLog.log(TAG, "Gemini TTS error: $errorMsg")
                finishSpeaking()
                return
            }

            // Parse response: candidates[0].content.parts[0].inlineData.data
            val base64Audio = try {
                JSONObject(responseBody ?: "{}")
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getJSONObject("inlineData")
                    .getString("data")
            } catch (e: Exception) {
                DebugLog.log(TAG, "Failed to parse TTS response: ${e.message}")
                DebugLog.log(TAG, "Raw: ${responseBody?.take(300)}")
                finishSpeaking()
                return
            }

            if (stopped.get()) return

            // Decode base64 → raw PCM bytes
            val pcmData = android.util.Base64.decode(base64Audio, android.util.Base64.DEFAULT)
            DebugLog.log(TAG, "Got ${pcmData.size} bytes PCM (${pcmData.size / (SAMPLE_RATE * 2.0)}s audio)")

            playPcm(pcmData)

        } catch (e: Exception) {
            DebugLog.log(TAG, "Network error: ${e.message}")
            finishSpeaking()
        }
    }

    /** Play raw PCM audio through AudioTrack. */
    private fun playPcm(pcmData: ByteArray) {
        if (stopped.get()) {
            finishSpeaking()
            return
        }

        val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bufferSize, pcmData.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            audioTrack?.write(pcmData, 0, pcmData.size)
            audioTrack?.setNotificationMarkerPosition(pcmData.size / 2) // frames = bytes / 2 for 16-bit
            audioTrack?.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack?) {
                    DebugLog.log(TAG, "Playback complete")
                    finishSpeaking()
                }
                override fun onPeriodicNotification(track: AudioTrack?) {}
            })

            if (!stopped.get()) {
                audioTrack?.play()
                DebugLog.log(TAG, "Playing audio...")
            } else {
                finishSpeaking()
            }
        } catch (e: Exception) {
            DebugLog.log(TAG, "AudioTrack error: ${e.message}")
            finishSpeaking()
        }
    }

    fun stop() {
        stopped.set(true)
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        isSpeaking = false
    }

    fun release() {
        stop()
        synthesisThread = null
    }

    private fun finishSpeaking() {
        try {
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        isSpeaking = false
        handler.post { onSpeechDone?.invoke() }
    }
}
