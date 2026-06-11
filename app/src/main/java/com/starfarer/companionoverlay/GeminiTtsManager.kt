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

        /** Cap synthesis input — an unbounded request can hit model/output limits. */
        private const val MAX_TTS_CHARS = 4000
    }

    var onSpeechDone: (() -> Unit)? = null
    var onSpeechError: ((String) -> Unit)? = null

    /** Synthesis request went out — UI may show a "generating" indicator. */
    var onSynthesisStarted: (() -> Unit)? = null

    /**
     * Audio playback began (or the attempt ended) — UI should clear the
     * indicator. Replaces the old "" sentinel pushed through the status-string
     * channel and checked with isNotEmpty() at the far end.
     */
    var onSynthesisEnded: (() -> Unit)? = null

    @Volatile
    var isSpeaking: Boolean = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val audioTrackRef = AtomicReference<AudioTrack?>(null)
    private val activeCall = AtomicReference<Call?>(null)
    private var synthesisThread: Thread? = null

    // Session generation, bumped on every speak() and stop() (both main-thread).
    // The synthesis thread, posts, and HTTP completion capture their value and
    // no-op once stale. The old shared `stopped` AtomicBoolean was reset by
    // each new speak(), un-cancelling the previous in-flight synthesis — two
    // threads then played at once and the old one clobbered audioTrackRef so
    // the new track became unstoppable.
    @Volatile private var session = 0

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

        val cleaned = TtsTextCleaner.clean(text)
        if (cleaned.isEmpty()) {
            onSpeechDone?.invoke()
            return
        }
        val capped = if (cleaned.length > MAX_TTS_CHARS) {
            DebugLog.log(TAG, "Text too long for one synthesis (${cleaned.length}), capping at $MAX_TTS_CHARS")
            cleaned.take(MAX_TTS_CHARS)
        } else cleaned

        val mySession = ++session
        isSpeaking = true

        val voiceName = settings.geminiTtsVoice ?: DEFAULT_VOICE

        synthesisThread = Thread({
            synthesizeAndPlay(mySession, apiKey, capped, voiceName)
        }, "gemini-tts").also { it.start() }
    }

    private fun synthesizeAndPlay(mySession: Int, apiKey: String, text: String, voiceName: String) {
        handler.post { if (session == mySession) onSynthesisStarted?.invoke() }
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
            val call = httpClient.newCall(request)
            activeCall.set(call)
            val responseBody: String?
            val response: Response
            try {
                response = call.execute()
                responseBody = response.body?.string()
            } finally {
                // compareAndSet: plain set(null) could clear a newer session's call.
                activeCall.compareAndSet(call, null)
            }

            if (session != mySession) return

            if (!response.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBody ?: "")
                        .getJSONObject("error")
                        .getString("message")
                } catch (_: Exception) {
                    "HTTP ${response.code}"
                }
                DebugLog.log(TAG, "Gemini TTS error: $errorMsg")
                finishWithError(mySession, text, errorMsg)
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
                finishWithError(mySession, text, "parse error")
                return
            }

            if (session != mySession) return

            val pcmData = android.util.Base64.decode(base64Audio, android.util.Base64.DEFAULT)
            DebugLog.log(TAG, "Got ${pcmData.size} bytes PCM (${pcmData.size / (SAMPLE_RATE * 2.0)}s audio)")

            playPcm(mySession, text, pcmData)

        } catch (e: Exception) {
            if (session != mySession) return
            DebugLog.log(TAG, "Network error: ${e.message}")
            finishWithError(mySession, text, e.message ?: "network error")
        }
    }

    private fun playPcm(mySession: Int, text: String, pcmData: ByteArray) {
        if (session != mySession) return

        val track: AudioTrack
        try {
            val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (bufferSize <= 0) {
                finishWithError(mySession, text, "audio init failed (buffer=$bufferSize)")
                return
            }

            track = AudioTrack.Builder()
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
            // Re-check after publishing: if stop() ran in the window it may have
            // missed the ref — reclaim and release ourselves.
            if (session != mySession) {
                releaseTrack(audioTrackRef.getAndSet(null))
                return
            }

            track.play()
            DebugLog.log(TAG, "Playing audio (stream mode, ${pcmData.size} bytes)...")
            handler.post { if (session == mySession) onSynthesisEnded?.invoke() }

            val chunk = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            var offset = 0
            while (offset < pcmData.size && session == mySession) {
                val toWrite = minOf(chunk, pcmData.size - offset)
                val written = track.write(pcmData, offset, toWrite)
                if (written < 0) break
                offset += written
            }
        } catch (e: Exception) {
            DebugLog.log(TAG, "AudioTrack error: ${e.message}")
            releaseTrack(audioTrackRef.getAndSet(null))
            finishWithError(mySession, text, e.message ?: "playback error")
            return
        }

        releaseTrack(audioTrackRef.getAndSet(null))

        if (session == mySession) {
            finishSpeaking(mySession)
        }
    }

    private fun releaseTrack(track: AudioTrack?) {
        track ?: return
        try { track.stop() } catch (_: Exception) {}
        try { track.release() } catch (_: Exception) {}
    }

    fun stop() {
        // Invalidates the synthesis thread, posts, and HTTP completion of the
        // current session, and aborts the in-flight request — previously the
        // call kept running to completion (up to the 120s read timeout).
        session++
        activeCall.getAndSet(null)?.cancel()
        releaseTrack(audioTrackRef.getAndSet(null))
        isSpeaking = false
    }

    fun release() {
        stop()
        synthesisThread = null
    }

    private fun finishSpeaking(mySession: Int) {
        handler.post {
            if (session != mySession) return@post
            isSpeaking = false
            onSpeechDone?.invoke()
        }
    }

    private fun finishWithError(mySession: Int, text: String, reason: String) {
        DebugLog.log(TAG, "TTS failed ($reason), offering fallback for ${text.length} chars")
        handler.post {
            // Session check here is what stops a CANCELLED utterance from being
            // resurrected through the on-device fallback path.
            if (session != mySession) return@post
            isSpeaking = false
            onSpeechError?.invoke(text)
        }
    }
}
