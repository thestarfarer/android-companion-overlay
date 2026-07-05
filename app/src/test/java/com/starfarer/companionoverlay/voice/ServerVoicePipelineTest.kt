package com.starfarer.companionoverlay.voice

import com.starfarer.companionoverlay.AudioUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Pure-JVM tests for the server voice path's decision surface: utterance
 * gating, WAV/base64 message shape, transcript correlation (incl. empty
 * text), and the unsupported/internal/timeout → local-STT fallback triggers.
 * Fake utterance bytes stand in for the device-only mic+VAD capture stack
 * ([VadUtteranceSource]), which can only be exercised on hardware.
 */
class ServerVoicePipelineTest {

    /** Fake gateway seam — records what would go on the wire. */
    private class FakeTransport : ServerVoicePipeline.Transport {
        data class Sent(val format: String, val data: String, val durationMs: Long)

        val sent = mutableListOf<Sent>()
        var connected = true
        private var nextId = 0

        override fun sendAudio(format: String, base64Data: String, durationMs: Long): String? {
            if (!connected) return null
            sent += Sent(format, base64Data, durationMs)
            return "m-${++nextId}"
        }
    }

    private class RecordingListener : ServerVoicePipeline.Listener {
        // Timeout callbacks arrive from the pipeline's scheduler thread —
        // use thread-safe collections so the polling asserts see them.
        val transcribed = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var emptyTranscripts = 0
        val failures = java.util.concurrent.CopyOnWriteArrayList<String>()

        override fun onTranscribed(text: String) { transcribed += text }
        override fun onEmptyTranscript() { emptyTranscripts++ }
        override fun onServerPathFailed(reason: String) { failures += reason }
    }

    private val transport = FakeTransport()
    private val listener = RecordingListener()

    private fun pipeline(timeoutMs: Long = 30_000L) =
        ServerVoicePipeline(transport, listener, transcriptTimeoutMs = timeoutMs)

    /** Fake 16kHz mono 16-bit PCM of the given length (32 bytes per ms). */
    private fun pcm(ms: Int): ByteArray = ByteArray(ms * 32) { (it % 251).toByte() }

    private fun readIntLE(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

    // ── Utterance gating & message shape ──

    @Test
    fun `utterances shorter than 300ms are discarded as VAD blips`() {
        val p = pipeline()
        assertEquals(ServerVoicePipeline.SubmitResult.TOO_SHORT, p.submitUtterance(pcm(200)))
        assertTrue(transport.sent.isEmpty())
        assertFalse(p.hasPendingUtterance)
    }

    @Test
    fun `utterance goes up as base64 WAV with the right duration`() {
        val raw = pcm(1000)
        assertEquals(ServerVoicePipeline.SubmitResult.SENT, pipeline().submitUtterance(raw))

        val sent = transport.sent.single()
        assertEquals("wav", sent.format)
        assertEquals(1000L, sent.durationMs)

        // The payload must round-trip base64 into a WAV ffmpeg can sniff:
        // RIFF/WAVE magic, 16kHz mono PCM, and the exact sample bytes.
        val wav = Base64.getDecoder().decode(sent.data)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals(AudioUtils.SAMPLE_RATE, readIntLE(wav, 24))         // sample rate
        assertEquals(raw.size, readIntLE(wav, 40))                       // data chunk size
        assertEquals(44 + raw.size, wav.size)
        assertTrue(wav.copyOfRange(44, wav.size).contentEquals(raw))
    }

    @Test
    fun `utterances over the 10MB base64 cap are dropped, not sent`() {
        // 8MB of PCM → ~10.7MB of base64 — over the protocol cap.
        val p = pipeline()
        assertEquals(ServerVoicePipeline.SubmitResult.TOO_LARGE, p.submitUtterance(ByteArray(8 * 1024 * 1024)))
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `offline transport reports OFFLINE and never engages fallback`() {
        transport.connected = false
        val p = pipeline()
        assertEquals(ServerVoicePipeline.SubmitResult.OFFLINE, p.submitUtterance(pcm(1000)))
        assertFalse(p.fallbackActive)   // offline ≠ broken server path
        assertTrue(listener.failures.isEmpty())
    }

    // ── Transcript routing ──

    @Test
    fun `transcript matching the in-flight id delivers the heard text`() {
        val p = pipeline()
        p.submitUtterance(pcm(1000))                       // in flight as m-1
        assertTrue(p.handleTranscript("m-1", "hello there"))
        assertEquals(listOf("hello there"), listener.transcribed)
        assertFalse(p.hasPendingUtterance)
    }

    @Test
    fun `empty transcript means the server heard nothing`() {
        val p = pipeline()
        p.submitUtterance(pcm(1000))
        assertTrue(p.handleTranscript("m-1", ""))
        assertEquals(1, listener.emptyTranscripts)
        assertTrue(listener.transcribed.isEmpty())
        assertFalse(p.fallbackActive)                      // heard-nothing is not a failure
    }

    @Test
    fun `stale or foreign transcripts are ignored`() {
        val p = pipeline()
        p.submitUtterance(pcm(1000))
        assertFalse(p.handleTranscript("m-999", "someone else's words"))
        assertFalse(p.handleTranscript(null, "no correlation"))
        assertTrue(listener.transcribed.isEmpty())
        assertTrue(p.hasPendingUtterance)                  // still waiting for m-1
    }

    // ── Fallback triggers ──

    @Test
    fun `unsupported error on the in-flight audio engages local-STT fallback`() {
        val p = pipeline()
        p.submitUtterance(pcm(1000))
        assertTrue(p.handleServerError("unsupported", "m-1"))
        assertTrue(p.fallbackActive)
        assertEquals(listOf("unsupported"), listener.failures)
    }

    @Test
    fun `internal error (whisper box down) engages local-STT fallback`() {
        val p = pipeline()
        p.submitUtterance(pcm(1000))
        assertTrue(p.handleServerError("internal", "m-1"))
        assertTrue(p.fallbackActive)
        assertEquals(listOf("internal"), listener.failures)
    }

    @Test
    fun `errors for other messages are not consumed and do not fallback`() {
        val p = pipeline()
        p.submitUtterance(pcm(1000))
        assertFalse(p.handleServerError("internal", "m-other"))
        assertFalse(p.handleServerError("unsupported", null))
        assertFalse(p.fallbackActive)
        assertTrue(p.hasPendingUtterance)
    }

    @Test
    fun `non-STT error codes clear the utterance but leave the server path armed`() {
        val p = pipeline()
        p.submitUtterance(pcm(1000))
        // e.g. overloaded: the utterance failed, but the path isn't broken —
        // the host's generic error UI renders it (hence not consumed).
        assertFalse(p.handleServerError("overloaded", "m-1"))
        assertFalse(p.fallbackActive)
        assertFalse(p.hasPendingUtterance)
        // A late transcript for it must now be ignored.
        assertFalse(p.handleTranscript("m-1", "too late"))
    }

    @Test
    fun `transcript timeout engages fallback`() {
        val p = pipeline(timeoutMs = 100)
        p.submitUtterance(pcm(1000))
        awaitTrue { listener.failures.isNotEmpty() }
        assertEquals(listOf("timeout"), listener.failures)
        assertTrue(p.fallbackActive)
        assertFalse(p.hasPendingUtterance)
    }

    @Test
    fun `transcript arriving in time defuses the timeout`() {
        val p = pipeline(timeoutMs = 200)
        p.submitUtterance(pcm(1000))
        assertTrue(p.handleTranscript("m-1", "made it"))
        Thread.sleep(400)
        assertTrue(listener.failures.isEmpty())
        assertFalse(p.fallbackActive)
    }

    @Test
    fun `resetFallback re-arms the server path on the next welcome`() {
        val p = pipeline()
        p.submitUtterance(pcm(1000))
        p.handleServerError("unsupported", "m-1")
        assertTrue(p.fallbackActive)

        p.resetFallback()
        assertFalse(p.fallbackActive)
        // And the path genuinely works again:
        assertEquals(ServerVoicePipeline.SubmitResult.SENT, p.submitUtterance(pcm(500)))
    }

    @Test
    fun `cancelPending forgets the in-flight utterance`() {
        val p = pipeline()
        p.submitUtterance(pcm(1000))
        p.cancelPending()
        assertFalse(p.hasPendingUtterance)
        assertFalse(p.handleTranscript("m-1", "interrupted"))
    }

    private fun awaitTrue(timeoutSec: Long = 5, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("condition not met within ${timeoutSec}s")
    }
}
