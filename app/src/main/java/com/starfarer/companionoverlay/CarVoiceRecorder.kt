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
import java.util.concurrent.TimeUnit

/**
 * Records from the car's microphone via CarAudioRecord, detects silence
 * with Silero VAD, then transcribes via Gemini STT.
 *
 * Uses the same Silero VAD + AudioUtils pipeline as GeminiSpeechRecognizer,
 * adapted for CarAudioRecord's byte-oriented API.
 */
class CarVoiceRecorder(private val carContext: CarContext) {

    companion object {
        private const val TAG = "CarVoice"
        private const val VAD_SPEECH_THRESHOLD = 0.5f
        private const val CALIBRATION_MS = 400L
        private const val GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
    }

    var silenceTimeoutMs: Long = 1500L
    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var record: CarAudioRecord? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var vad: SileroVadDetector? = null
    @Volatile private var recording = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun getVad(): SileroVadDetector {
        return vad ?: SileroVadDetector(carContext).also { vad = it }
    }

    fun start() {
        if (recording) return

        val audioManager = carContext.getSystemService(AudioManager::class.java)
            ?: run { postError("No AudioManager"); return }

        val attrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .build()

        val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { state ->
                if (state == AudioManager.AUDIOFOCUS_LOSS) stop()
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

        Thread { recordAndProcess() }.start()
    }

    fun stop() {
        recording = false
        try { record?.stopRecording() } catch (_: Exception) {}
    }

    private fun recordAndProcess() {
        // Read in 512-sample chunks to match Silero VAD window size
        val bytesPerWindow = SileroVadDetector.WINDOW_SAMPLES * 2  // 1024 bytes
        val buffer = ByteArray(bytesPerWindow)
        val pcmData = ByteArrayOutputStream()
        var silenceStartMs = 0L
        var speechDetected = false
        val startTime = System.currentTimeMillis()

        val vadDetector = getVad()
        vadDetector.reset()
        DebugLog.log(TAG, "Silero VAD active")

        while (recording) {
            val bytesRead = try {
                record?.read(buffer, 0, buffer.size) ?: -1
            } catch (_: Exception) { -1 }

            if (bytesRead <= 0) {
                DebugLog.log(TAG, "Read returned $bytesRead, stopping")
                break
            }

            pcmData.write(buffer, 0, bytesRead)

            val now = System.currentTimeMillis()
            val elapsed = now - startTime

            // Convert bytes to shorts and run VAD
            val shorts = AudioUtils.bytesToShorts(buffer, bytesRead)
            val speechProb = if (shorts.size == SileroVadDetector.WINDOW_SAMPLES) {
                vadDetector.detect(shorts)
            } else {
                val padded = shorts.copyOf(SileroVadDetector.WINDOW_SAMPLES)
                vadDetector.detect(padded)
            }

            // Skip calibration period (beep bleed on car speakers)
            if (elapsed < CALIBRATION_MS) continue

            if (speechProb >= VAD_SPEECH_THRESHOLD) {
                speechDetected = true
                silenceStartMs = 0L
            } else {
                if (silenceStartMs == 0L) {
                    silenceStartMs = now
                } else if (speechDetected && now - silenceStartMs >= silenceTimeoutMs) {
                    DebugLog.log(TAG, "VAD silence (${now - silenceStartMs}ms)")
                    break
                }
            }

            // Safety: max 30s recording
            if (pcmData.size() > AudioUtils.SAMPLE_RATE * 2 * 30) {
                DebugLog.log(TAG, "Max recording length reached")
                break
            }
        }

        recording = false
        try { record?.stopRecording() } catch (_: Exception) {}
        releaseAudioFocus()

        val pcmBytes = pcmData.toByteArray()
        if (!speechDetected || pcmBytes.size < AudioUtils.SAMPLE_RATE) {
            DebugLog.log(TAG, "No speech detected (${pcmBytes.size} bytes)")
            postError("No speech detected")
            return
        }

        DebugLog.log(TAG, "Captured ${pcmBytes.size} bytes (${pcmBytes.size / (AudioUtils.SAMPLE_RATE * 2.0)}s)")
        val wavData = AudioUtils.pcmToWav(pcmBytes)
        transcribeWithGemini(wavData)
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let { req ->
            carContext.getSystemService(AudioManager::class.java)
                ?.abandonAudioFocusRequest(req)
        }
        audioFocusRequest = null
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
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                    .getJSONObject(0).getString("text").trim()
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

    fun destroy() {
        vad?.close()
        vad = null
    }

    private fun postError(msg: String) {
        DebugLog.log(TAG, "Error: $msg")
        handler.post { onError?.invoke(msg) }
    }
}
