package com.starfarer.companionoverlay

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
import java.util.concurrent.atomic.AtomicReference

import com.starfarer.companionoverlay.repository.SettingsRepository

class GeminiTtsManager(
    private val context: android.content.Context,
    baseClient: OkHttpClient,
    private val settings: SettingsRepository
) {

    companion object {
        private const val TAG = "GeminiTTS"

        private const val TTS_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent"

        private const val SAMPLE_RATE = 24000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private const val DEFAULT_VOICE = "Kore"
    }

    var onSpeechDone: (() -> Unit)? = null
    var onSpeechError: ((String) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null

    private var pendingText: String? = null

    var isSpeaking: Boolean = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val audioTrackRef = AtomicReference<AudioTrack?>(null)
    private var synthesisThread: Thread? = null
    private val stopped = AtomicBoolean(false)

    private val httpClient = baseClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun speak(text: String) {
        stop()

        val apiKey = settings.geminiApiKey
        if (apiKey.isNullOrBlank()) {
            DebugLog.log(TAG, "No Gemini API key, falling back to silence")
            onSpeechDone?.invoke()
            return
        }

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
        pendingText = cleaned

        val voiceName = settings.geminiTtsVoice ?: DEFAULT_VOICE

        synthesisThread = Thread({
            synthesizeAndPlay(apiKey, cleaned, voiceName)
        }, "gemini-tts").also { it.start() }
    }

    private fun synthesizeAndPlay(apiKey: String, text: String, voiceName: String) {
        handler.post { onStatusUpdate?.invoke("\uD83D\uDD0A Generating voice...") }
        DebugLog.log(TAG, "Synthesizing: ${text.length} chars, voice=$voiceName")

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

        val body = requestJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(TTS_URL)
            .post(body)
            .header("x-goog-api-key", apiKey)
            .build()

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
                finishWithError(errorMsg)
                return
            }

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
                finishWithError("parse error")
                return
            }

            if (stopped.get()) return

            val pcmData = android.util.Base64.decode(base64Audio, android.util.Base64.DEFAULT)
            DebugLog.log(TAG, "Got ${pcmData.size} bytes PCM (${pcmData.size / (SAMPLE_RATE * 2.0)}s audio)")

            playPcm(pcmData)

        } catch (e: Exception) {
            DebugLog.log(TAG, "Network error: ${e.message}")
            finishWithError(e.message ?: "network error")
        }
    }

    private fun playPcm(pcmData: ByteArray) {
        if (stopped.get()) {
            finishSpeaking()
            return
        }

        val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

        val track = AudioTrack.Builder()
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
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrackRef.set(track)

        try {
            if (stopped.get()) {
                releaseTrack(track)
                return
            }

            track.play()
            DebugLog.log(TAG, "Playing audio (stream mode, ${pcmData.size} bytes)...")
            handler.post { onStatusUpdate?.invoke("") }

            var offset = 0
            while (offset < pcmData.size && !stopped.get()) {
                val toWrite = minOf(bufferSize, pcmData.size - offset)
                val written = track.write(pcmData, offset, toWrite)
                if (written < 0) break
                offset += written
            }
        } catch (e: Exception) {
            DebugLog.log(TAG, "AudioTrack error: ${e.message}")
            releaseTrack(audioTrackRef.getAndSet(null))
            finishWithError(e.message ?: "playback error")
            return
        }

        releaseTrack(audioTrackRef.getAndSet(null))

        if (!stopped.get()) {
            finishSpeaking()
        }
    }

    private fun releaseTrack(track: AudioTrack?) {
        track ?: return
        try { track.stop() } catch (_: Exception) {}
        try { track.release() } catch (_: Exception) {}
    }

    fun stop() {
        stopped.set(true)
        releaseTrack(audioTrackRef.getAndSet(null))
        isSpeaking = false
    }

    fun release() {
        stop()
        synthesisThread = null
    }

    private fun finishSpeaking() {
        isSpeaking = false
        pendingText = null
        handler.post { onSpeechDone?.invoke() }
    }

    private fun finishWithError(reason: String) {
        isSpeaking = false
        val text = pendingText
        pendingText = null
        DebugLog.log(TAG, "TTS failed ($reason), offering fallback for ${text?.length ?: 0} chars")
        handler.post {
            if (text != null) {
                onSpeechError?.invoke(text)
            } else {
                onSpeechDone?.invoke()
            }
        }
    }
}
