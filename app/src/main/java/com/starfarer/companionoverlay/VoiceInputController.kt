package com.starfarer.companionoverlay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.starfarer.companionoverlay.gateway.GatewayClient
import com.starfarer.companionoverlay.repository.SettingsRepository
import com.starfarer.companionoverlay.voice.ServerVoicePipeline
import com.starfarer.companionoverlay.voice.UtteranceSource
import com.starfarer.companionoverlay.voice.VadUtteranceSource
import java.util.concurrent.Executor

/**
 * Orchestrates voice input: button press → listen → utterance → gateway → done.
 *
 * The state machine:
 *   IDLE → LISTENING       (button press)
 *   LISTENING → PROCESSING (utterance captured and shipped to the gateway)
 *   LISTENING → IDLE       (button press to cancel, silence timeout, or error)
 *   PROCESSING → IDLE      (assistant replied)
 *
 * Every recording starts with a deliberate button press. Nothing auto-restarts.
 * The mic is either on because you asked for it, or it's off.
 *
 * Two engines behind one state machine (presence protocol §3):
 *
 * - **Server path (primary)**: mic → [VadUtteranceSource] (Silero VAD cuts
 *   utterances) → [ServerVoicePipeline] encodes each as WAV and ships it as
 *   an `audio` message; Nexus transcribes and replies. The transcript comes
 *   back via [onServerTranscript].
 * - **Local path (fallback)**: on-device [SpeechRecognitionManager] → `text`
 *   with `source: "voice"`.
 *
 * Path choice per session via the voiceMode setting ("auto" | "server" |
 * "local"): auto prefers the server while connected, and drops to local for
 * the rest of the session when the server answers an audio message with
 * `unsupported`/`internal` or goes silent for 30s ([ServerVoicePipeline]
 * tracks that). The failed utterance's audio cannot be re-fed into Android's
 * live SpeechRecognizer, so that one utterance is lost — accepted; the
 * fallback applies from the next press. A fresh `welcome` re-arms the server
 * path ([onGatewayConnected]).
 */
class VoiceInputController(
    private val context: Context,
    private val host: VoiceInputHost,
    private val settings: SettingsRepository,
    private val beepManager: BeepManager,
    private val gateway: GatewayClient,
    utteranceSourceFactory: (Context) -> UtteranceSource = { VadUtteranceSource(it) },
) {

    companion object {
        private const val TAG = "VoiceCtrl"
        private const val SAFETY_TIMEOUT_MS = 300_000L
    }

    enum class State { IDLE, LISTENING, PROCESSING }

    var state: State = State.IDLE
        private set(value) {
            field = value
            // The mic is live only while LISTENING. Promote/drop the service's
            // microphone FGS type to match, so it's never claimed at idle — claiming
            // it unconditionally crashed the service start on Android 14+/17.
            host.setMicrophoneActive(value == State.LISTENING)
        }

    private val handler = Handler(Looper.getMainLooper())
    private var speechManager: SpeechRecognitionManager? = null
    private val btRouter = BluetoothAudioRouter(context)

    private val sourceFactory = utteranceSourceFactory
    private var utteranceSource: UtteranceSource? = null

    // One-time-per-connection hint when the server voice path breaks.
    private var fallbackHintShown = false

    private var safetyTimeoutRunnable: Runnable? = null

    private val beepsEnabled: Boolean get() = settings.beepsEnabled

    // ══════════════════════════════════════════════════════════════════════
    // Server voice pipeline (pure-JVM logic; this class is the Android glue)
    // ══════════════════════════════════════════════════════════════════════

    private val serverPipeline = ServerVoicePipeline(
        transport = object : ServerVoicePipeline.Transport {
            override fun sendAudio(
                format: String,
                base64Data: String,
                durationMs: Long,
                image: ServerVoicePipeline.ImagePayload?,
            ): String? = gateway.sendAudio(format, base64Data, durationMs, image)
        },
        listener = object : ServerVoicePipeline.Listener {
            override fun onTranscribed(text: String) {
                if (state != State.PROCESSING) return
                DebugLog.log(TAG, "Server heard: ${text.take(80)}")
                // Render what was heard where the user's own words show up;
                // the turn replies (status/message/speak) follow on their own.
                host.showBriefBubble(context.getString(R.string.svc_bubble_heard, text), 5000L)
            }

            override fun onEmptyTranscript() {
                if (state != State.PROCESSING) return
                DebugLog.log(TAG, "Server heard nothing — no turn follows")
                cancelSafetyTimeout()
                state = State.IDLE
                host.hideVoiceBubble()
                host.clearPendingScreenshot()
                host.showBriefBubble(context.getString(R.string.svc_bubble_didnt_catch))
            }

            override fun onServerPathFailed(reason: String) {
                // The utterance's audio can't be replayed into Android's live
                // SpeechRecognizer — it is lost. Local STT takes over from the
                // next press; the next welcome re-arms the server path.
                DebugLog.log(TAG, "Server voice path failed ($reason) — local STT from next utterance")
                if (state == State.PROCESSING) {
                    cancelSafetyTimeout()
                    if (beepsEnabled) beepManager.play(BeepManager.Beep.ERROR)
                    state = State.IDLE
                    host.hideVoiceBubble()
                    host.clearPendingScreenshot()
                }
                if (!fallbackHintShown) {
                    fallbackHintShown = true
                    host.showBriefBubble(context.getString(R.string.svc_bubble_voice_fallback), 4000L)
                }
            }
        },
        callbackExecutor = Executor { handler.post(it) },
    )

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Toggle voice input. Called from accessibility service (Shokz button).
     * Must be called on the main thread.
     */
    fun toggle() {
        DebugLog.log(TAG, "toggle() called, state=$state")
        when (state) {
            State.IDLE -> startListening()
            State.LISTENING -> cancelListening()
            State.PROCESSING -> interruptAndRestart()
        }
    }

    /** Cancel safety timeout when the reply arrives — TTS handles its own completion. */
    fun cancelSafetyTimeout() {
        safetyTimeoutRunnable?.let { handler.removeCallbacks(it) }
        safetyTimeoutRunnable = null
    }

    /**
     * Called by the service after the assistant's response has been shown.
     * Returns to IDLE. The next recording starts only when the user
     * presses the button again.
     */
    fun onVoiceResponseComplete() {
        cancelSafetyTimeout()
        if (state == State.PROCESSING) {
            DebugLog.log(TAG, "Response complete → IDLE")
            state = State.IDLE
            btRouter.clearRouting()
        }
    }

    /** Gateway `transcript` frame (main thread) — routed to the server pipeline. */
    fun onServerTranscript(re: String?, text: String) {
        serverPipeline.handleTranscript(re, text)
    }

    /**
     * Gateway `error` frame. @return true when it was consumed by the voice
     * pipeline (fallback engaged and rendered here) — the host should skip
     * its generic error UI in that case.
     */
    fun onGatewayError(code: String, re: String?): Boolean =
        serverPipeline.handleServerError(code, re)

    /** Fresh `welcome` — retry the server voice path on this connection. */
    fun onGatewayConnected() {
        serverPipeline.resetFallback()
        fallbackHintShown = false
    }

    fun destroy() {
        cancelSafetyTimeout()
        speechManager?.destroy()
        speechManager = null
        serverPipeline.shutdown()
        utteranceSource?.release()
        utteranceSource = null
        btRouter.clearRouting()
        state = State.IDLE
    }

    // ══════════════════════════════════════════════════════════════════════
    // State Transitions
    // ══════════════════════════════════════════════════════════════════════

    private fun interruptAndRestart() {
        DebugLog.log(TAG, "Interrupting processing → start listening")
        // The PROCESSING-watchdog timeout belongs to the session being
        // interrupted — left armed, it fired into the next healthy session.
        cancelSafetyTimeout()
        host.stopTtsAndCancel()
        speechManager?.cancel()
        // A transcript for the interrupted utterance must not render into the
        // new session.
        serverPipeline.cancelPending()
        state = State.IDLE
        host.hideVoiceBubble()
        startListening()
    }

    private fun startListening() {
        state = State.LISTENING
        host.stopTtsAndCancel()
        host.hideVoiceBubble()

        val routed = btRouter.routeToBluetoothHeadset()
        DebugLog.log(TAG, "BT audio routing: ${if (routed) "active" else "using built-in mic"}")

        if (useServerPath()) {
            startServerListening()
        } else {
            startOnDeviceListening()
            DebugLog.log(TAG, "Listening started (on-device)")
        }
    }

    /**
     * Which engine handles this session. A capture waiting to be voiced
     * rides the audio message itself (protocol §3 `audio.image`) — the
     * transcript becomes its caption server-side.
     */
    private fun useServerPath(): Boolean {
        return when (settings.voiceMode) {
            SettingsRepository.VOICE_MODE_SERVER -> true
            SettingsRepository.VOICE_MODE_LOCAL -> false
            // auto: server while connected and not in session-fallback. While
            // offline, local STT is the natural fallback — its `text` output
            // queues for reconnect, whereas `audio` frames never queue.
            else -> gateway.isConnected && !serverPipeline.fallbackActive
        }
    }

    private fun cancelListening() {
        DebugLog.log(TAG, "Cancelling listening")
        speechManager?.cancel()
        utteranceSource?.stop()
        state = State.IDLE
        btRouter.clearRouting()
        host.hideVoiceBubble()
        host.clearPendingScreenshot()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Server path (VAD → audio message)
    // ══════════════════════════════════════════════════════════════════════

    private fun startServerListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            state = State.IDLE
            btRouter.clearRouting()
            host.showBriefBubble(context.getString(R.string.settings_voice_needs_mic))
            return
        }
        val source = utteranceSource ?: sourceFactory(context).also { utteranceSource = it }
        host.showVoiceBubble(context.getString(R.string.status_listening))
        if (beepsEnabled) beepManager.play(BeepManager.Beep.READY)
        source.start(utteranceCallback, settings.silenceTimeoutMs)
        DebugLog.log(TAG, "Listening started (server VAD path)")
    }

    private val utteranceCallback = object : UtteranceSource.Callback {
        // All handlers are gated on LISTENING — a capture-thread callback that
        // lands after cancel/timeout must not touch the next session's state.

        override fun onUtterance(pcm16k: ByteArray) {
            handler.post {
                if (state != State.LISTENING) return@post
                // A waiting capture (upper-body gesture) rides along; the
                // server captions it with the transcript. Consumed only once
                // the utterance actually ships.
                val capture = host.pendingCapture()
                val image = capture?.let {
                    ServerVoicePipeline.ImagePayload("image/jpeg", it.base64Jpeg, it.kind)
                }
                when (serverPipeline.submitUtterance(pcm16k, image)) {
                    ServerVoicePipeline.SubmitResult.SENT -> {
                        // Push-to-talk parity with the local path: one utterance
                        // per press, then wait for the reply.
                        host.clearPendingScreenshot()
                        utteranceSource?.stop()
                        state = State.PROCESSING
                        host.hideVoiceBubble()
                        startSafetyTimeout()
                        host.onVoiceAudioSent()
                        btRouter.clearRouting()
                    }
                    ServerVoicePipeline.SubmitResult.TOO_SHORT -> {
                        // VAD blip — keep listening for real speech.
                    }
                    ServerVoicePipeline.SubmitResult.OFFLINE,
                    ServerVoicePipeline.SubmitResult.TOO_LARGE -> {
                        // Audio is never queued offline (protocol §1) — drop
                        // with a hint, like images.
                        utteranceSource?.stop()
                        if (beepsEnabled) beepManager.play(BeepManager.Beep.ERROR)
                        state = State.IDLE
                        btRouter.clearRouting()
                        host.hideVoiceBubble()
                        host.clearPendingScreenshot()
                        host.showBriefBubble(context.getString(R.string.svc_bubble_offline_audio), 4000L)
                    }
                }
            }
        }

        override fun onNoSpeech() {
            handler.post {
                if (state != State.LISTENING) return@post
                DebugLog.log(TAG, "No speech detected (VAD) → IDLE")
                utteranceSource?.stop()
                state = State.IDLE
                btRouter.clearRouting()
                host.hideVoiceBubble()
                host.clearPendingScreenshot()
            }
        }

        override fun onError(message: String) {
            handler.post {
                if (state != State.LISTENING) return@post
                DebugLog.log(TAG, "VAD capture error: $message")
                utteranceSource?.stop()
                if (beepsEnabled) beepManager.play(BeepManager.Beep.ERROR)
                state = State.IDLE
                btRouter.clearRouting()
                host.hideVoiceBubble()
                host.clearPendingScreenshot()
                host.showBriefBubble(context.getString(R.string.status_couldnt_hear, message))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Local path (on-device SpeechRecognizer → text source:"voice")
    // ══════════════════════════════════════════════════════════════════════

    private fun wireCallbacks(mgr: SpeechRecognitionManager) {
        // All wrappers are gated on LISTENING: the engine guards its own
        // sessions, but a callback that slipped through after a state change
        // (cancel, timeout, response shown) could otherwise wedge the UI or
        // hijack the next session's state.
        mgr.onReadyForSpeech = {
            handler.post {
                if (state != State.LISTENING) return@post
                host.showVoiceBubble(context.getString(R.string.status_listening))
                if (beepsEnabled) beepManager.play(BeepManager.Beep.READY)
            }
        }

        mgr.onPartialResult = { partial ->
            handler.post {
                if (state != State.LISTENING) return@post
                host.updateVoiceBubble(partial)
            }
        }

        mgr.onFinalResult = { text ->
            handler.post {
                if (state != State.LISTENING) return@post
                DebugLog.log(TAG, "Got speech: ${text.take(80)}")
                state = State.PROCESSING
                host.hideVoiceBubble()
                startSafetyTimeout()
                host.sendVoiceInput(text)
                btRouter.clearRouting()
            }
        }

        mgr.onError = { error ->
            handler.post {
                if (state != State.LISTENING) return@post
                DebugLog.log(TAG, "Recognition error: $error")
                if (beepsEnabled) beepManager.play(BeepManager.Beep.ERROR)
                state = State.IDLE
                btRouter.clearRouting()
                host.hideVoiceBubble()
                host.clearPendingScreenshot()
                host.showBriefBubble(context.getString(R.string.status_couldnt_hear, error))
            }
        }

        mgr.onStopped = {
            handler.post {
                if (state == State.LISTENING) {
                    DebugLog.log(TAG, "No speech detected → IDLE")
                    state = State.IDLE
                    btRouter.clearRouting()
                    host.hideVoiceBubble()
                    host.clearPendingScreenshot()
                }
            }
        }
    }

    private fun startOnDeviceListening() {
        if (speechManager == null) {
            speechManager = SpeechRecognitionManager(context).also { wireCallbacks(it) }
        }
        speechManager?.silenceTimeoutMs = settings.silenceTimeoutMs
        speechManager?.startListening()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Safety
    // ══════════════════════════════════════════════════════════════════════

    private fun startSafetyTimeout() {
        cancelSafetyTimeout()
        safetyTimeoutRunnable = Runnable {
            if (state != State.IDLE) {
                DebugLog.log(TAG, "Safety timeout! Stuck in $state, forcing IDLE")
                host.stopTtsAndCancel()
                speechManager?.cancel()
                utteranceSource?.stop()
                serverPipeline.cancelPending()
                state = State.IDLE
                btRouter.clearRouting()
                host.hideVoiceBubble()
            }
        }
        handler.postDelayed(safetyTimeoutRunnable!!, SAFETY_TIMEOUT_MS)
    }
}
