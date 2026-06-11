package com.starfarer.companionoverlay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import java.util.concurrent.atomic.AtomicReference

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
    var onReadyForSpeech: (() -> Unit)? = null
    var onStopped: (() -> Unit)? = null

    /** Typed lifecycle events (recording/transcribing/retrying) — see [VoiceStatus]. */
    var onStatus: ((VoiceStatus) -> Unit)? = null

    var isListening: Boolean = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var recordingThread: Thread? = null

    // Session generation, bumped on every startListening() and cancel() (both
    // main-thread). The recording thread, delayed posts, and HTTP completions
    // capture the value they were started with and no-op once it goes stale —
    // unlike the old resettable `cancelled`/`recording` booleans, a stale
    // session can observe that it lost but can never affect the live one
    // (double-recording, killing the new session, or delivering cancelled
    // audio to Claude).
    @Volatile private var session = 0

    private val activeHttpCall = AtomicReference<Call?>(null)

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

        // Fail fast on missing prerequisites. The key used to be checked only
        // AFTER a full utterance was recorded; a missing mic permission
        // surfaced as a misleading "failed to initialize" (AudioRecord doesn't
        // throw for it — it just reports STATE_UNINITIALIZED).
        if (settings.geminiApiKey.isNullOrBlank()) {
            DebugLog.log(TAG, "No Gemini API key configured")
            onError?.invoke("No Gemini API key — set it in settings")
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            DebugLog.log(TAG, "Mic permission not granted")
            onError?.invoke("Microphone permission denied")
            return
        }

        val mySession = ++session

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING),
            SAMPLE_RATE * 2 // At least 1 second buffer
        )

        // Owned by the recording thread from here on — never a shared field, so
        // an old session's teardown can't release the new session's recorder.
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferSize
            )
        } catch (e: Exception) {
            DebugLog.log(TAG, "AudioRecord create failed: ${e.message}")
            onError?.invoke("Failed to initialize microphone")
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            DebugLog.log(TAG, "AudioRecord failed to initialize")
            record.release()
            onError?.invoke("Failed to initialize microphone")
            return
        }

        isListening = true
        record.startRecording()

        DebugLog.log(TAG, "Recording started (16kHz mono PCM)")
        handler.post {
            if (session != mySession) return@post
            onReadyForSpeech?.invoke()
            onStatus?.invoke(VoiceStatus.Recording)
        }

        recordingThread = Thread({
            runSession(mySession, record)
        }, "gemini-stt-record").also { it.start() }
    }

    /** Post a terminal callback for [mySession] unless a newer session took over. */
    private fun finishSession(mySession: Int, callback: () -> Unit) {
        handler.post {
            if (session != mySession) return@post
            isListening = false
            callback()
        }
    }

    /**
     * Recording session, owned by its thread: reads PCM in 512-sample chunks,
     * runs Silero VAD for silence detection, then transcribes. [record] belongs
     * to this call and is released in the finally block on every path. The
     * whole body is guarded — an uncaught error here (e.g. ONNX throwing after
     * destroy() closed the VAD session mid-detect) used to kill the process.
     */
    private fun runSession(mySession: Int, record: AudioRecord) {
        try {
            recordAndTranscribe(mySession, record)
        } catch (e: Exception) {
            DebugLog.log(TAG, "Recording session failed: ${e.message}")
            finishSession(mySession) { onError?.invoke("Recording failed: ${e.message}") }
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
    }

    private fun recordAndTranscribe(mySession: Int, record: AudioRecord) {
        val pcmOutput = ByteArrayOutputStream()
        // Read in 512-sample chunks — Silero VAD's native window size at 16kHz
        val readBuffer = ShortArray(SileroVadDetector.WINDOW_SAMPLES)
        var silenceStartMs = 0L
        var speechDetected = false
        var emptyReads = 0
        val recordStartMs = System.currentTimeMillis()

        val vadDetector = getVad()
        vadDetector.reset()
        DebugLog.log(TAG, "Silero VAD active (window=${SileroVadDetector.WINDOW_SAMPLES}, thr=$VAD_SPEECH_THRESHOLD)")

        while (session == mySession) {
            val shortsRead = record.read(readBuffer, 0, readBuffer.size)
            if (shortsRead <= 0) {
                // A dead AudioRecord returns 0/negative forever — busy-spinning
                // here held the mic and never reached the duration cap.
                if (++emptyReads >= 50) {
                    DebugLog.log(TAG, "AudioRecord stopped delivering data (err=$shortsRead)")
                    finishSession(mySession) { onError?.invoke("Microphone stopped delivering audio") }
                    return
                }
                Thread.sleep(10)
                continue
            }
            emptyReads = 0

            // Write PCM bytes (always — we want complete audio for transcription)
            val byteBuffer = ByteBuffer.allocate(shortsRead * 2)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until shortsRead) {
                byteBuffer.putShort(readBuffer[i])
            }
            pcmOutput.write(byteBuffer.array(), 0, shortsRead * 2)

            val now = System.currentTimeMillis()
            val elapsed = now - recordStartMs

            // Skip calibration period (beep bleed from bone conduction headsets)
            if (elapsed < CALIBRATION_MS) continue

            // Run Silero VAD on this 512-sample window
            val speechProb = if (shortsRead == SileroVadDetector.WINDOW_SAMPLES) {
                vadDetector.detect(readBuffer)
            } else {
                // True zero-pad for a partial read — copyOf() on the full-size
                // buffer kept stale samples from the previous read in the tail.
                val padded = ShortArray(SileroVadDetector.WINDOW_SAMPLES)
                System.arraycopy(readBuffer, 0, padded, 0, shortsRead)
                vadDetector.detect(padded)
            }

            if (speechProb >= VAD_SPEECH_THRESHOLD) {
                speechDetected = true
                silenceStartMs = 0L
            } else if (speechDetected) {
                // Below threshold — start or continue silence timer
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

        if (session != mySession) {
            // Cancelled or superseded — the live session (if any) owns the UI.
            DebugLog.log(TAG, "Recording session superseded/cancelled")
            return
        }

        val pcmData = pcmOutput.toByteArray()
        if (!speechDetected || pcmData.size < SAMPLE_RATE) {
            // Less than 0.5s of audio or no speech at all
            DebugLog.log(TAG, "No speech detected (${pcmData.size} bytes PCM)")
            finishSession(mySession) { onStopped?.invoke() }
            return
        }

        DebugLog.log(TAG, "Captured ${pcmData.size} bytes PCM (${pcmData.size / (SAMPLE_RATE * 2.0)}s)")
        handler.post { if (session == mySession) onStatus?.invoke(VoiceStatus.Transcribing) }

        // Convert to WAV and send to Gemini
        transcribeWithGemini(mySession, AudioUtils.pcmToWav(pcmData))
    }

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
        activeHttpCall.set(call)
        try {
            val response = call.execute()
            return Pair(response.body?.string(), response.code)
        } finally {
            // compareAndSet: a plain set(null) here could clear a NEWER session's
            // call reference, making it uncancellable.
            activeHttpCall.compareAndSet(call, null)
        }
    }

    /** Send WAV audio to Gemini for transcription with conversation context. */
    private fun transcribeWithGemini(mySession: Int, wavData: ByteArray) {
        // Presence was validated in startListening; a blank here means the key
        // was removed mid-session — treat as an ordinary error.
        val apiKey = settings.geminiApiKey
        if (apiKey.isNullOrBlank()) {
            DebugLog.log(TAG, "Gemini API key disappeared mid-session")
            finishSession(mySession) { onError?.invoke("No Gemini API key — set it in settings") }
            return
        }

        val base64Audio = android.util.Base64.encodeToString(wavData, android.util.Base64.NO_WRAP)
        DebugLog.log(TAG, "WAV size: ${wavData.size} bytes, base64: ${base64Audio.length} chars")

        try {
            var requestJson = buildGeminiRequestJson(base64Audio, includeContext = true)
            var (responseBody, httpCode) = executeGeminiRequest(requestJson, apiKey)

            if (session != mySession) return

            // Retry without conversation context if safety filters blocked the request
            if (isContentBlocked(responseBody, httpCode)) {
                DebugLog.log(TAG, "Content blocked with context, retrying without")
                handler.post { if (session == mySession) onStatus?.invoke(VoiceStatus.Retrying) }

                requestJson = buildGeminiRequestJson(base64Audio, includeContext = false)
                val retry = executeGeminiRequest(requestJson, apiKey)
                responseBody = retry.first
                httpCode = retry.second

                if (session != mySession) return

                // Still blocked after dropping the context: say so — silently
                // treating it as "no speech" gave the user zero feedback.
                if (isContentBlocked(responseBody, httpCode)) {
                    DebugLog.log(TAG, "Content blocked even without context")
                    finishSession(mySession) { onError?.invoke("Transcription blocked by content filters") }
                    return
                }
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
                finishSession(mySession) { onError?.invoke("Gemini: $errorMsg") }
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

            finishSession(mySession) {
                if (text.isNotBlank()) {
                    onFinalResult?.invoke(text)
                } else {
                    onStopped?.invoke()
                }
            }

        } catch (e: Exception) {
            if (session != mySession) return
            DebugLog.log(TAG, "Network error: ${e.message}")
            finishSession(mySession) { onError?.invoke("Network error: ${e.message}") }
        }
    }

    fun cancel() {
        // Invalidates the recording thread, pending posts, and HTTP completions
        // of the current session. Main-thread only (like startListening).
        session++
        activeHttpCall.getAndSet(null)?.cancel()
        isListening = false
    }

    fun destroy() {
        cancel()
        recordingThread = null
        vad?.close()
        vad = null
    }
}
