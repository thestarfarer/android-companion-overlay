package com.starfarer.companionoverlay

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.starfarer.companionoverlay.repository.SettingsRepository
import java.util.concurrent.TimeUnit

/**
 * Records audio via AudioRecord, detects silence, then sends to
 * Gemini Flash for context-aware transcription.
 *
 * Advantages over SpeechRecognizer:
 * - No audio focus grab (YouTube keeps playing)
 * - No profanity filter
 * - Context-aware: sends conversation history so it knows domain terms
 * - No Google STT quirks (ghost errors, ignored extras)
 *
 * Audio format: 16kHz mono 16-bit PCM, packaged as WAV for Gemini.
 * Silence detection: amplitude RMS below threshold for silenceDurationMs.
 */
class GeminiSpeechRecognizer(
    private val context: Context,
    baseClient: OkHttpClient,
    private val settings: SettingsRepository
) {

    companion object {
        private const val TAG = "GeminiSTT"

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** Silero VAD speech probability threshold. */
        private const val VAD_SPEECH_THRESHOLD = 0.5f
        /** Ignore first N ms to skip beep bleed from bone conduction headsets. */
        private const val CALIBRATION_MS = 400L

        /** Default silence duration — overridden by getSilenceTimeoutMs(). */
        private const val DEFAULT_silenceDurationMs = 1500L

        /** Maximum recording duration to prevent huge payloads (ms). */
        private const val MAX_RECORD_MS = 60_000L

        /** Gemini API endpoint. */
        private const val GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent"
    }

    // Callbacks — same shape as SpeechRecognitionManager for drop-in swap
    var onPartialResult: ((String) -> Unit)? = null
    var onFinalResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onRmsChanged: ((Float) -> Unit)? = null
    var onReadyForSpeech: (() -> Unit)? = null
    var onStopped: (() -> Unit)? = null

    var isListening: Boolean = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var recording = false
    @Volatile private var cancelled = false
    @Volatile private var activeHttpCall: Call? = null

    /** Conversation context to inject into the transcription prompt. */
    var conversationContext: String = ""


    /** Silence duration in ms — set from settings before startListening(). */
    var silenceDurationMs: Long = DEFAULT_silenceDurationMs

    private val httpClient = baseClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var vad: SileroVadDetector? = null

    private fun getVad(): SileroVadDetector {
        return vad ?: SileroVadDetector(context).also { vad = it }
    }

    fun startListening() {
        if (isListening) {
            DebugLog.log(TAG, "Still listening from previous session, force-stopping")
            cancel()
        }
        cancelled = false

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING),
            SAMPLE_RATE * 2 // At least 1 second buffer
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferSize
            )
        } catch (e: SecurityException) {
            DebugLog.log(TAG, "Mic permission denied: ${e.message}")
            onError?.invoke("Microphone permission denied")
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            DebugLog.log(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            onError?.invoke("Failed to initialize microphone")
            return
        }

        isListening = true
        recording = true
        audioRecord?.startRecording()

        DebugLog.log(TAG, "Recording started (16kHz mono PCM)")
        handler.post {
            onReadyForSpeech?.invoke()
            // Immediately show recording state — we ARE recording, not "listening"
            onPartialResult?.invoke("🎤 Recording...")
        }

        recordingThread = Thread({
            recordAndDetectSilence()
        }, "gemini-stt-record").also { it.start() }
    }

    /**
     * Recording loop: reads PCM in 512-sample chunks, runs Silero VAD for silence detection.
     * When silence persists for silenceDurationMs, stops and transcribes.
     */
    private fun recordAndDetectSilence() {
        val pcmOutput = ByteArrayOutputStream()
        // Read in 512-sample chunks — Silero VAD's native window size at 16kHz
        val readBuffer = ShortArray(SileroVadDetector.WINDOW_SAMPLES)
        var silenceStartMs = 0L
        var speechDetected = false
        val recordStartMs = System.currentTimeMillis()

        val vadDetector = getVad()
        vadDetector.reset()
        DebugLog.log(TAG, "Silero VAD active (window=${SileroVadDetector.WINDOW_SAMPLES}, thr=$VAD_SPEECH_THRESHOLD)")

        while (recording && !cancelled) {
            val shortsRead = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: -1
            if (shortsRead <= 0) continue

            // Write PCM bytes (always — we want complete audio for transcription)
            val byteBuffer = ByteBuffer.allocate(shortsRead * 2)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until shortsRead) {
                byteBuffer.putShort(readBuffer[i])
            }
            pcmOutput.write(byteBuffer.array())

            val now = System.currentTimeMillis()
            val elapsed = now - recordStartMs

            // Skip calibration period (beep bleed from bone conduction headsets)
            if (elapsed < CALIBRATION_MS) continue

            // Run Silero VAD on this 512-sample window
            val speechProb = if (shortsRead == SileroVadDetector.WINDOW_SAMPLES) {
                vadDetector.detect(readBuffer)
            } else {
                // Partial chunk at end — pad with zeros
                val padded = readBuffer.copyOf(SileroVadDetector.WINDOW_SAMPLES)
                vadDetector.detect(padded)
            }

            // Report probability for bubble animation (scale 0-1 → RMS-like range)
            handler.post { onRmsChanged?.invoke(speechProb * 2000f) }

            if (speechProb >= VAD_SPEECH_THRESHOLD) {
                speechDetected = true
                silenceStartMs = 0L
                handler.post { onPartialResult?.invoke("🎤 Recording...") }
            } else if (speechDetected) {
                // Below threshold \u2014 start or continue silence timer
                if (silenceStartMs == 0L) {
                    silenceStartMs = now
                } else if (now - silenceStartMs >= silenceDurationMs) {
                    DebugLog.log(TAG, "VAD silence (${now - silenceStartMs}ms, lastProb=${"%.2f".format(speechProb)})")
                    break
                }
            }

            // Hard cap on recording time
            if (now - recordStartMs >= MAX_RECORD_MS) {
                DebugLog.log(TAG, "Max recording time reached")
                break
            }
        }

        // Stop recording
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        recording = false

        if (cancelled) {
            DebugLog.log(TAG, "Recording cancelled")
            handler.post {
                isListening = false
                onStopped?.invoke()
            }
            return
        }

        val pcmData = pcmOutput.toByteArray()
        if (!speechDetected || pcmData.size < SAMPLE_RATE) {
            // Less than 0.5s of audio or no speech at all
            DebugLog.log(TAG, "No speech detected (${pcmData.size} bytes PCM)")
            handler.post {
                isListening = false
                onStopped?.invoke()
            }
            return
        }

        DebugLog.log(TAG, "Captured ${pcmData.size} bytes PCM (${pcmData.size / (SAMPLE_RATE * 2.0)}s)")
        handler.post { onPartialResult?.invoke("✒️ Transcribing...") }

        // Convert to WAV and send to Gemini
        transcribeWithGemini(AudioUtils.pcmToWav(pcmData))
    }

    /** Build a WAV file from raw PCM data. */

    /** Build the Gemini API request JSON, optionally including conversation context. */
    private fun buildGeminiRequestJson(base64Audio: String, includeContext: Boolean): JSONObject {
        return JSONObject().apply {
            if (includeContext && conversationContext.isNotBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(
                        JSONObject().put("text",
                            "You are a speech-to-text transcriber. The user is in an ongoing conversation. " +
                            "Use the following context ONLY to resolve ambiguous words, names, and terms. " +
                            "Do NOT include any of this context in your output.\n\n$conversationContext")
                    ))
                })
            }
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().apply {
                    put(JSONObject().put("text", "Transcribe this audio. Return ONLY the spoken words, nothing else."))
                    put(JSONObject().apply {
                        put("inline_data", JSONObject().apply {
                            put("mime_type", "audio/wav")
                            put("data", base64Audio)
                        })
                    })
                })
            ))
        }
    }

    /** Check if a Gemini response was blocked by safety/content filters. */
    private fun isContentBlocked(responseBody: String?, httpCode: Int): Boolean {
        if (responseBody == null) return false
        val json = runCatching { JSONObject(responseBody) }.getOrNull() ?: return false

        // HTTP error — check error message for safety-related keywords
        if (httpCode !in 200..299) {
            val msg = json.optJSONObject("error")?.optString("message", "") ?: ""
            val blocked = listOf("content", "blocked", "safety", "prohibited")
            return blocked.any { msg.contains(it, ignoreCase = true) }
        }

        // Prompt-level block: promptFeedback.blockReason present
        val blockReason = json.optJSONObject("promptFeedback")?.optString("blockReason", "") ?: ""
        if (blockReason.isNotBlank()) return true

        // Candidate-level block: finishReason indicates safety
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
        if (candidate != null) {
            val reason = candidate.optString("finishReason", "")
            if (reason in listOf("SAFETY", "RECITATION", "OTHER")) return true
        }

        // No candidates at all (but 200) — also a block
        if (!json.has("candidates") || json.optJSONArray("candidates")?.length() == 0) return true

        return false
    }

    /** Execute a single Gemini transcription request. Returns (responseBody, httpCode). */
    private fun executeGeminiRequest(requestJson: JSONObject, apiKey: String): Pair<String?, Int> {
        val body = requestJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(GEMINI_URL)
            .post(body)
            .header("x-goog-api-key", apiKey)
            .build()
        val hasContext = requestJson.has("systemInstruction")
        DebugLog.log(TAG, "Sending to Gemini (context=$hasContext, ${requestJson.toString().length} chars)...")
        val call = httpClient.newCall(request)
        activeHttpCall = call
        val response = call.execute()
        activeHttpCall = null
        return Pair(response.body?.string(), response.code)
    }

    /** Send WAV audio to Gemini for transcription with conversation context. */
    private fun transcribeWithGemini(wavData: ByteArray) {
        val apiKey = settings.geminiApiKey
        if (apiKey.isNullOrBlank()) {
            DebugLog.log(TAG, "No Gemini API key configured")
            handler.post {
                isListening = false
                onError?.invoke("No Gemini API key — set it in settings")
            }
            return
        }

        val base64Audio = android.util.Base64.encodeToString(wavData, android.util.Base64.NO_WRAP)
        DebugLog.log(TAG, "WAV size: ${wavData.size} bytes, base64: ${base64Audio.length} chars")

        try {
            var requestJson = buildGeminiRequestJson(base64Audio, includeContext = true)
            var (responseBody, httpCode) = executeGeminiRequest(requestJson, apiKey)

            if (cancelled) return

            // Retry without conversation context if safety filters blocked the request
            if (isContentBlocked(responseBody, httpCode)) {
                DebugLog.log(TAG, "Content blocked with context, retrying without")
                handler.post { onPartialResult?.invoke("Retrying transcription...") }

                if (cancelled) return

                requestJson = buildGeminiRequestJson(base64Audio, includeContext = false)
                val retry = executeGeminiRequest(requestJson, apiKey)
                responseBody = retry.first
                httpCode = retry.second

                if (cancelled) return
            }

            if (httpCode !in 200..299) {
                val errorMsg = try {
                    JSONObject(responseBody ?: "")
                        .getJSONObject("error")
                        .getString("message")
                } catch (_: Exception) {
                    "HTTP $httpCode"
                }
                DebugLog.log(TAG, "Gemini error: $errorMsg")
                handler.post {
                    isListening = false
                    onError?.invoke("Gemini: $errorMsg")
                }
                return
            }

            // Parse response: candidates[0].content.parts[0].text
            val text = try {
                JSONObject(responseBody ?: "{}")
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()
            } catch (e: Exception) {
                DebugLog.log(TAG, "Failed to parse response: ${e.message}")
                DebugLog.log(TAG, "Raw response: ${responseBody?.take(500)}")
                ""
            }

            DebugLog.log(TAG, "Transcription: ${text.take(80)}")

            if (cancelled) return

            handler.post {
                isListening = false
                if (text.isNotBlank()) {
                    onFinalResult?.invoke(text)
                } else {
                    onStopped?.invoke()
                }
            }

        } catch (e: Exception) {
            activeHttpCall = null
            if (cancelled) return
            DebugLog.log(TAG, "Network error: ${e.message}")
            handler.post {
                isListening = false
                onError?.invoke("Network error: ${e.message}")
            }
        }
    }

    fun stopListening() {
        recording = false
        // Thread will detect this and stop, then transcribe
    }

    fun cancel() {
        cancelled = true
        recording = false
        activeHttpCall?.cancel()
        activeHttpCall = null
        isListening = false
    }

    fun destroy() {
        cancel()
        recordingThread = null
        vad?.close()
        vad = null
    }
}
