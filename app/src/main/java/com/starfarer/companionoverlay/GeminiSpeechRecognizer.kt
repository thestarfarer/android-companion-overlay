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
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

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
class GeminiSpeechRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "GeminiSTT"

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** RMS threshold below which audio is considered silence. */
        private const val SILENCE_THRESHOLD = 500.0

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

    /** Conversation context to inject into the transcription prompt. */
    var conversationContext: String = ""

    /** Silence duration in ms — set from settings before startListening(). */
    var silenceDurationMs: Long = DEFAULT_silenceDurationMs

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

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
                MediaRecorder.AudioSource.MIC,
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
            recordAndDetectSilence(bufferSize)
        }, "gemini-stt-record").also { it.start() }
    }

    /**
     * Recording loop: reads PCM buffers, computes RMS, detects silence.
     * When silence persists for silenceDurationMs, stops and transcribes.
     */
    private fun recordAndDetectSilence(bufferSize: Int) {
        val pcmOutput = ByteArrayOutputStream()
        val readBuffer = ShortArray(bufferSize / 2)
        var silenceStartMs = 0L
        var speechDetected = false
        val recordStartMs = System.currentTimeMillis()

        while (recording && !cancelled) {
            val shortsRead = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: -1
            if (shortsRead <= 0) continue

            // Write PCM bytes
            val byteBuffer = ByteBuffer.allocate(shortsRead * 2)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until shortsRead) {
                byteBuffer.putShort(readBuffer[i])
            }
            pcmOutput.write(byteBuffer.array())

            // Compute RMS
            var sumSquares = 0.0
            for (i in 0 until shortsRead) {
                val sample = readBuffer[i].toDouble()
                sumSquares += sample * sample
            }
            val rms = sqrt(sumSquares / shortsRead)

            handler.post { onRmsChanged?.invoke(rms.toFloat()) }

            val now = System.currentTimeMillis()

            if (rms > SILENCE_THRESHOLD) {
                speechDetected = true
                silenceStartMs = 0L
                // Show a visual indicator that speech is being captured
                handler.post { onPartialResult?.invoke("🎤 Recording...") }
            } else if (speechDetected) {
                // Below threshold — start or continue silence timer
                if (silenceStartMs == 0L) {
                    silenceStartMs = now
                } else if (now - silenceStartMs >= silenceDurationMs) {
                    DebugLog.log(TAG, "Silence detected after speech, stopping")
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
        handler.post { onPartialResult?.invoke("Transcribing...") }

        // Convert to WAV and send to Gemini
        transcribeWithGemini(pcmToWav(pcmData))
    }

    /** Build a WAV file from raw PCM data. */
    private fun pcmToWav(pcmData: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)

        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = pcmData.size
        val chunkSize = 36 + dataSize

        // RIFF header
        dos.writeBytes("RIFF")
        dos.writeIntLE(chunkSize)
        dos.writeBytes("WAVE")

        // fmt subchunk
        dos.writeBytes("fmt ")
        dos.writeIntLE(16) // subchunk1 size
        dos.writeShortLE(1) // PCM format
        dos.writeShortLE(numChannels)
        dos.writeIntLE(SAMPLE_RATE)
        dos.writeIntLE(byteRate)
        dos.writeShortLE(blockAlign)
        dos.writeShortLE(bitsPerSample)

        // data subchunk
        dos.writeBytes("data")
        dos.writeIntLE(dataSize)
        dos.write(pcmData)

        dos.flush()
        return bos.toByteArray()
    }

    /** Little-endian write helpers for WAV header. */
    private fun DataOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    /** Send WAV audio to Gemini for transcription with conversation context. */
    private fun transcribeWithGemini(wavData: ByteArray) {
        val apiKey = PromptSettings.getGeminiApiKey(context)
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

        // Build the prompt with conversation context
        val contextBlock = if (conversationContext.isNotBlank()) {
            "\n\nConversation context (for understanding references and domain terms):\n$conversationContext"
        } else ""

        val prompt = """Transcribe this audio recording verbatim. Return ONLY the transcribed text, nothing else. No timestamps, no speaker labels, no formatting — just the exact words spoken. If the audio is unclear or empty, return an empty string.$contextBlock"""

        // Build Gemini API request body
        val requestJson = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().apply {
                    put(JSONObject().put("text", prompt))
                    put(JSONObject().apply {
                        put("inline_data", JSONObject().apply {
                            put("mime_type", "audio/wav")
                            put("data", base64Audio)
                        })
                    })
                })
            ))
        }

        val url = "$GEMINI_URL?key=$apiKey"
        val body = requestJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        DebugLog.log(TAG, "Sending to Gemini (${requestJson.toString().length} chars request)...")

        try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBody ?: "")
                        .getJSONObject("error")
                        .getString("message")
                } catch (_: Exception) {
                    "HTTP ${response.code}"
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

            handler.post {
                isListening = false
                if (text.isNotBlank()) {
                    onFinalResult?.invoke(text)
                } else {
                    onStopped?.invoke()
                }
            }

        } catch (e: Exception) {
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
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        isListening = false
    }

    fun destroy() {
        cancel()
        recordingThread = null
    }
}
