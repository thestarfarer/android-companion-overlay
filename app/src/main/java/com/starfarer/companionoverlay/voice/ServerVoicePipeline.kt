package com.starfarer.companionoverlay.voice

import com.starfarer.companionoverlay.AudioUtils
import com.starfarer.companionoverlay.DebugLog
import java.util.Base64
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The server-side voice path (presence protocol §3 `audio`): takes VAD-cut
 * PCM utterances, encodes them, ships them through the gateway, and matches
 * the server's `transcript`/`error` responses back to the in-flight message.
 *
 * Encoding: 16kHz mono PCM in a WAV container. The protocol explicitly
 * blesses this (the server converts with ffmpeg); a 10s utterance is ~430KB
 * of base64, far under the 10MB cap. Opus via MediaCodec would shave ~85%
 * of that, but needs device-only codec APIs and an Ogg muxer — not worth the
 * untestable surface for payloads this small.
 *
 * Fallback state: `unsupported` (no STT backend), `internal` (transcription
 * failed) or a transcript timeout flips [fallbackActive]; the caller should
 * route subsequent utterances through local STT and call [resetFallback] on
 * the next `welcome` to retry the server path. Note the failed utterance's
 * audio cannot be re-fed into Android's live SpeechRecognizer — that
 * utterance is lost; the fallback applies from the next one.
 *
 * Pure JVM (no Android classes) so the whole decision surface is unit
 * testable. Listener callbacks are delivered on [callbackExecutor].
 */
class ServerVoicePipeline(
    private val transport: Transport,
    private val listener: Listener,
    private val callbackExecutor: Executor = Executor { it.run() },
    private val transcriptTimeoutMs: Long = TRANSCRIPT_TIMEOUT_MS,
) {
    companion object {
        private const val TAG = "VoicePipe"

        /** Anything shorter is a VAD blip, not speech. */
        const val MIN_UTTERANCE_MS = 300L

        /** No transcript within this window ⇒ the server voice path is broken. */
        const val TRANSCRIPT_TIMEOUT_MS = 30_000L

        /** Protocol cap on base64 audio payloads — the server's exact limit (10_000_000, not 10 MiB). */
        const val MAX_BASE64_BYTES = 10_000_000

        /** 16kHz × 16-bit mono ⇒ 32 PCM bytes per millisecond. */
        private const val PCM_BYTES_PER_MS = AudioUtils.SAMPLE_RATE * 2 / 1000
    }

    /** Outbound seam — [com.starfarer.companionoverlay.gateway.GatewayClient.sendAudio] shaped. */
    interface Transport {
        /** @return the sent message's id, or null when offline. */
        fun sendAudio(format: String, base64Data: String, durationMs: Long): String?
    }

    interface Listener {
        /** The server heard [text] — render it where the user's own words go. A turn follows. */
        fun onTranscribed(text: String) {}

        /** The server heard nothing (empty transcript). No turn follows. */
        fun onEmptyTranscript() {}

        /**
         * The server voice path broke ([reason] = unsupported | internal |
         * timeout). [fallbackActive] is already set; the in-flight utterance
         * is lost — switch to local STT from the next one.
         */
        fun onServerPathFailed(reason: String) {}
    }

    enum class SubmitResult { SENT, TOO_SHORT, TOO_LARGE, OFFLINE }

    private val lock = Any()
    private var pendingId: String? = null
    private var timeoutTask: ScheduledFuture<*>? = null
    private var fallback = false

    private val scheduler = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "voice-pipeline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    /** True while the server path is considered broken for this session. */
    val fallbackActive: Boolean get() = synchronized(lock) { fallback }

    /** True while an audio message is awaiting its transcript. */
    val hasPendingUtterance: Boolean get() = synchronized(lock) { pendingId != null }

    /**
     * Encode one VAD-cut utterance (16kHz mono 16-bit LE PCM) as WAV and send
     * it as an `audio` message. Synchronous; the transcript/error/timeout
     * outcome arrives via [Listener].
     */
    fun submitUtterance(pcm16kMono: ByteArray): SubmitResult {
        val durationMs = pcm16kMono.size.toLong() / PCM_BYTES_PER_MS
        if (durationMs < MIN_UTTERANCE_MS) {
            DebugLog.log(TAG, "Dropping ${durationMs}ms blip")
            return SubmitResult.TOO_SHORT
        }
        val base64 = Base64.getEncoder().encodeToString(AudioUtils.pcmToWav(pcm16kMono))
        if (base64.length > MAX_BASE64_BYTES) {
            DebugLog.log(TAG, "Utterance over the 10MB base64 cap (${base64.length} bytes) — dropped")
            return SubmitResult.TOO_LARGE
        }
        val id = transport.sendAudio("wav", base64, durationMs) ?: return SubmitResult.OFFLINE
        synchronized(lock) {
            clearPendingLocked()
            pendingId = id
            timeoutTask = scheduler.schedule({ onTimeout(id) }, transcriptTimeoutMs, TimeUnit.MILLISECONDS)
        }
        DebugLog.log(TAG, "Sent ${durationMs}ms utterance as $id (${base64.length} b64 bytes)")
        return SubmitResult.SENT
    }

    /**
     * Route an inbound `transcript` frame. @return true when it belonged to
     * the in-flight utterance (stale/foreign transcripts are ignored).
     */
    fun handleTranscript(re: String?, text: String): Boolean {
        synchronized(lock) {
            if (re == null || re != pendingId) return false
            clearPendingLocked()
        }
        if (text.isBlank()) dispatch { it.onEmptyTranscript() }
        else dispatch { it.onTranscribed(text) }
        return true
    }

    /**
     * Route an inbound `error` frame. @return true when it was consumed
     * (an `unsupported`/`internal` error on the in-flight utterance — the
     * fallback hint replaces the host's generic error UI). Other codes on
     * the in-flight utterance clear it but return false so the host's normal
     * error path still renders; they don't disable the server path.
     */
    fun handleServerError(code: String, re: String?): Boolean {
        synchronized(lock) {
            if (re == null || re != pendingId) return false
            clearPendingLocked()
        }
        if (code == "unsupported" || code == "internal") {
            triggerFallback(code)
            return true
        }
        return false
    }

    /** Retry the server path (call on each `welcome` — new connection, new luck). */
    fun resetFallback() {
        synchronized(lock) { fallback = false }
    }

    /** Forget the in-flight utterance (user interrupted / session torn down). */
    fun cancelPending() {
        synchronized(lock) { clearPendingLocked() }
    }

    fun shutdown() {
        cancelPending()
        scheduler.shutdownNow()
    }

    private fun onTimeout(id: String) {
        synchronized(lock) {
            if (pendingId != id) return
            clearPendingLocked()
        }
        DebugLog.log(TAG, "No transcript for $id within ${transcriptTimeoutMs}ms")
        triggerFallback("timeout")
    }

    private fun triggerFallback(reason: String) {
        synchronized(lock) { fallback = true }
        dispatch { it.onServerPathFailed(reason) }
    }

    /** Must hold [lock]. */
    private fun clearPendingLocked() {
        pendingId = null
        timeoutTask?.cancel(false)
        timeoutTask = null
    }

    private fun dispatch(block: (Listener) -> Unit) {
        callbackExecutor.execute {
            try {
                block(listener)
            } catch (e: Exception) {
                DebugLog.log(TAG, "Listener error: ${e.message}")
            }
        }
    }
}
