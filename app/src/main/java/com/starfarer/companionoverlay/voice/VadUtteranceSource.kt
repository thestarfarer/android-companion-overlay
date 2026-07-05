package com.starfarer.companionoverlay.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.starfarer.companionoverlay.AudioUtils
import com.starfarer.companionoverlay.DebugLog
import com.starfarer.companionoverlay.SileroVadDetector
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/**
 * Mic → Silero VAD → whole utterances. The device half of the server voice
 * path: reads 16kHz mono PCM off [AudioRecord] in 512-sample windows, runs
 * each through [SileroVadDetector], and cuts an utterance when the speaker
 * goes quiet. A short pre-roll ring keeps the syllable that *triggered* the
 * detector from being clipped.
 *
 * Device-only by nature (AudioRecord + ONNX runtime) — everything downstream
 * of [UtteranceSource.Callback.onUtterance] is JVM-testable instead.
 *
 * Threading: one capture thread per [start]; callbacks fire on it. [stop] is
 * asynchronous (the loop notices within one 32ms window); [release] joins
 * briefly and frees the VAD model.
 */
class VadUtteranceSource(private val context: Context) : UtteranceSource {

    companion object {
        private const val TAG = "VadSource"
        private const val WINDOW = SileroVadDetector.WINDOW_SAMPLES            // 512 samples
        private const val WINDOW_MS = WINDOW * 1000L / AudioUtils.SAMPLE_RATE  // 32ms

        /** Probability that opens an utterance / keeps it open. Hysteresis pair. */
        private const val SPEECH_START_PROB = 0.5f
        private const val SPEECH_KEEP_PROB = 0.35f

        /** Audio kept from before the trigger window (~320ms). */
        private const val PRE_ROLL_WINDOWS = 10

        /** Floor for the configurable end-of-utterance silence. */
        private const val MIN_END_SILENCE_MS = 400L

        /** Hard cap so a noisy room can't grow an unbounded buffer (~1.9MB PCM). */
        private const val MAX_UTTERANCE_MS = 60_000L

        /** No speech onset within this window → onNoSpeech (parity with local STT). */
        private const val NO_SPEECH_TIMEOUT_MS = 10_000L
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var vad: SileroVadDetector? = null   // capture-thread only (one session at a time)
    private var vadDead = false

    override fun start(callback: UtteranceSource.Callback, endOfUtteranceSilenceMs: Long) {
        synchronized(this) {
            if (running) return
            running = true
            val endSilence = endOfUtteranceSilenceMs.coerceAtLeast(MIN_END_SILENCE_MS)
            thread = Thread({ captureLoop(callback, endSilence) }, "vad-capture").apply {
                isDaemon = true
                start()
            }
        }
    }

    override fun stop() {
        running = false
    }

    override fun release() {
        running = false
        thread?.let { runCatching { it.join(750) } }
        thread = null
        vad?.close()
        vad = null
        vadDead = true
    }

    // RECORD_AUDIO is checked by the caller (VoiceInputController) before start;
    // a revocation race still lands in the SecurityException handler below.
    @SuppressLint("MissingPermission")
    private fun captureLoop(cb: UtteranceSource.Callback, endSilenceMs: Long) {
        var record: AudioRecord? = null
        try {
            val vad = obtainVad() ?: run {
                cb.onError("voice detector unavailable")
                return
            }
            vad.reset()

            val minBuf = AudioRecord.getMinBufferSize(
                AudioUtils.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AudioUtils.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, WINDOW * 2 * 8)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                cb.onError("microphone unavailable")
                return
            }
            record.startRecording()
            DebugLog.log(TAG, "Capture started (endSilence=${endSilenceMs}ms)")

            val window = ShortArray(WINDOW)
            val preRoll = ArrayDeque<ShortArray>()
            var utterance: ByteArrayOutputStream? = null
            var silenceMs = 0L
            var armedMs = 0L          // time since session start / last utterance, for no-speech
            var noSpeechReported = false

            while (running) {
                if (!readFully(record, window)) {
                    if (running) cb.onError("microphone read failed")
                    return
                }
                val prob = vad.detect(window)
                val current = utterance

                if (current == null) {
                    // Idle: keep a pre-roll ring so the trigger syllable survives.
                    preRoll.addLast(window.copyOf())
                    while (preRoll.size > PRE_ROLL_WINDOWS) preRoll.removeFirst()
                    if (prob >= SPEECH_START_PROB) {
                        val started = ByteArrayOutputStream()
                        for (w in preRoll) started.write(AudioUtils.shortsToBytes(w))
                        preRoll.clear()
                        utterance = started
                        silenceMs = 0L
                        cb.onSpeechStart()
                    } else {
                        armedMs += WINDOW_MS
                        if (armedMs >= NO_SPEECH_TIMEOUT_MS && !noSpeechReported) {
                            noSpeechReported = true
                            cb.onNoSpeech()
                        }
                    }
                } else {
                    current.write(AudioUtils.shortsToBytes(window))
                    silenceMs = if (prob >= SPEECH_KEEP_PROB) 0L else silenceMs + WINDOW_MS
                    val lengthMs = current.size() / (AudioUtils.SAMPLE_RATE * 2 / 1000)
                    if (silenceMs >= endSilenceMs || lengthMs >= MAX_UTTERANCE_MS) {
                        utterance = null
                        armedMs = 0L
                        noSpeechReported = false
                        cb.onUtterance(current.toByteArray())
                        // The controller usually stops us here; if it keeps us
                        // running (blip discarded), re-arm cleanly.
                        vad.reset()
                    }
                }
            }
            // Stopped externally mid-utterance: the user cancelled — drop the partial.
        } catch (_: SecurityException) {
            cb.onError("microphone permission missing")
        } catch (e: Exception) {
            DebugLog.log(TAG, "Capture loop died: ${e.message}")
            cb.onError(e.message ?: "capture failed")
        } finally {
            record?.let {
                runCatching { it.stop() }
                it.release()
            }
            DebugLog.log(TAG, "Capture stopped")
        }
    }

    /** @return false on a read error or shutdown mid-window. */
    private fun readFully(record: AudioRecord, out: ShortArray): Boolean {
        var off = 0
        while (off < out.size) {
            if (!running) return false
            val n = record.read(out, off, out.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun obtainVad(): SileroVadDetector? {
        if (vadDead) return null
        vad?.let { return it }
        return try {
            SileroVadDetector(context).also { vad = it }
        } catch (e: Exception) {
            DebugLog.log(TAG, "VAD init failed: ${e.message}")
            null
        }
    }
}
