package com.starfarer.companionoverlay

import android.media.AudioAttributes
import android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Records from the car's microphone via CarAudioRecord, detects silence,
 * then transcribes via Gemini STT. Adapted from GeminiSpeechRecognizer.
 */
class CarVoiceRecorder(private val carContext: CarContext) {

    companion object {
        private const val TAG = "CarVoice"
        private const val SAMPLE_RATE = 16000 // CarAudioRecord fixed rate
        private const val SILENCE_THRESHOLD = 500.0
        private const val GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
    }

    var silenceTimeoutMs: Long = 1500L
    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var record: CarAudioRecord? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile private var recording = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun start() {
        if (recording) return

        val audioManager = carContext.getSystemService(AudioManager::class.java)
            ?: run { postError("No AudioManager"); return }

        // Acquire exclusive audio focus (suppresses media)
        val attrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .build()

        val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { state ->
                if (state == AudioManager.AUDIOFOCUS_LOSS) {
                    stop()
                }
            }
            .build()
        audioFocusRequest = focusReq

        if (audioManager.requestAudioFocus(focusReq) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            postError("Audio focus denied")
            return
        }

        try {
            record = CarAudioRecord.create(carContext)
            record!!.startRecording()
            recording = true
            DebugLog.log(TAG, "Car mic recording started")
        } catch (e: Exception) {
            DebugLog.log(TAG, "CarAudioRecord failed: ${e.message}")
            audioManager.abandonAudioFocusRequest(focusReq)
            postError("Mic unavailable: ${e.message}")
            return
        }

        // Record on background thread
        Thread {
            recordAndProcess()
        }.start()
    }

    fun stop() {
        recording = false
        try {
            record?.stopRecording()
        } catch (_: Exception) {}
    }

    private fun recordAndProcess() {
        val bufferSize = SAMPLE_RATE // 0.5s at 16-bit mono = 32000 bytes, but read in 16000-byte chunks
        val buffer = ByteArray(bufferSize)
        val pcmData = ByteArrayOutputStream()
        var silenceStartMs = 0L
        var speechDetected = false

        while (recording) {
            val bytesRead = try {
                record?.read(buffer, 0, buffer.size) ?: -1
            } catch (_: Exception) { -1 }

            if (bytesRead <= 0) {
                DebugLog.log(TAG, "Read returned $bytesRead, stopping")
                break
            }

            pcmData.write(buffer, 0, bytesRead)

            // Compute RMS
            var sum = 0.0
            val samples = bytesRead / 2
            for (i in 0 until samples) {
                val lo = buffer[i * 2].toInt() and 0xFF
                val hi = buffer[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo
                sum += (sample * sample).toDouble()
            }
            val rms = sqrt(sum / samples)

            val now = System.currentTimeMillis()
            if (rms > SILENCE_THRESHOLD) {
                speechDetected = true
                silenceStartMs = 0L
            } else {
                if (silenceStartMs == 0L) {
                    silenceStartMs = now
                } else if (speechDetected && now - silenceStartMs >= silenceTimeoutMs) {
                    DebugLog.log(TAG, "Silence detected after speech")
                    break
                }
            }

            // Safety: max 30s recording
            if (pcmData.size() > SAMPLE_RATE * 2 * 30) {
                DebugLog.log(TAG, "Max recording length reached")
                break
            }
        }

        recording = false
        try { record?.stopRecording() } catch (_: Exception) {}
        releaseAudioFocus()

        val pcmBytes = pcmData.toByteArray()
        if (!speechDetected || pcmBytes.size < SAMPLE_RATE) {
            DebugLog.log(TAG, "No speech detected (${pcmBytes.size} bytes)")
            postError("No speech detected")
            return
        }

        DebugLog.log(TAG, "Captured ${pcmBytes.size} bytes (${pcmBytes.size / (SAMPLE_RATE * 2.0)}s)")
        val wavData = pcmToWav(pcmBytes)
        transcribeWithGemini(wavData)
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let { req ->
            carContext.getSystemService(AudioManager::class.java)
                ?.abandonAudioFocusRequest(req)
        }
        audioFocusRequest = null
    }

    private fun pcmToWav(pcmData: ByteArray): ByteArray {
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = pcmData.size
        val headerSize = 44

        val out = ByteArrayOutputStream(headerSize + dataSize)
        val dos = DataOutputStream(out)

        // RIFF header
        dos.writeBytes("RIFF")
        dos.writeIntLE(dataSize + 36)
        dos.writeBytes("WAVE")
        // fmt chunk
        dos.writeBytes("fmt ")
        dos.writeIntLE(16) // chunk size
        dos.writeShortLE(1) // PCM
        dos.writeShortLE(numChannels)
        dos.writeIntLE(SAMPLE_RATE)
        dos.writeIntLE(byteRate)
        dos.writeShortLE(blockAlign)
        dos.writeShortLE(bitsPerSample)
        // data chunk
        dos.writeBytes("data")
        dos.writeIntLE(dataSize)
        dos.write(pcmData)

        return out.toByteArray()
    }

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

    private fun transcribeWithGemini(wavData: ByteArray) {
        val apiKey = PromptSettings.getGeminiApiKey(carContext)
        if (apiKey.isNullOrBlank()) {
            postError("No Gemini API key")
            return
        }

        val base64Audio = android.util.Base64.encodeToString(wavData, android.util.Base64.NO_WRAP)
        DebugLog.log(TAG, "WAV ${wavData.size}B → base64 ${base64Audio.length} chars")

        val requestJson = JSONObject().apply {
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

        val url = "$GEMINI_URL?key=$apiKey"
        val body = requestJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBody ?: "").getJSONObject("error").getString("message")
                } catch (_: Exception) { "HTTP ${response.code}" }
                postError("Gemini: $errorMsg")
                return
            }

            val text = try {
                JSONObject(responseBody ?: "{}")
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()
            } catch (_: Exception) { "" }

            DebugLog.log(TAG, "Transcription: ${text.take(80)}")

            if (text.isNotBlank()) {
                handler.post { onResult?.invoke(text) }
            } else {
                postError("Empty transcription")
            }

        } catch (e: Exception) {
            postError("Network: ${e.message}")
        }
    }

    private fun postError(msg: String) {
        DebugLog.log(TAG, "Error: $msg")
        handler.post { onError?.invoke(msg) }
    }
}
