package com.starfarer.companionoverlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.starfarer.companionoverlay.repository.SettingsRepository

/**
 * Orchestrates voice input: button press → listen → transcribe → gateway → done.
 *
 * The state machine:
 *   IDLE → LISTENING       (button press)
 *   LISTENING → PROCESSING (speech recognized, shipped to the gateway)
 *   LISTENING → IDLE       (button press to cancel, silence timeout, or error)
 *   PROCESSING → IDLE      (assistant replied)
 *
 * Every recording starts with a deliberate button press. Nothing auto-restarts.
 * The mic is either on because you asked for it, or it's off.
 *
 * STT is the on-device [SpeechRecognitionManager]. Per the presence protocol
 * this is the fallback/primary-until-the-server-grows-an-audio-backend path:
 * the transcript is sent as `text` with `source: "voice"`. When Nexus gains
 * server-side transcription, VAD-cut utterances will ship as `audio` frames
 * instead and this controller swaps its engine without changing shape.
 */
class VoiceInputController(
    private val context: Context,
    private val host: VoiceInputHost,
    private val settings: SettingsRepository,
    private val beepManager: BeepManager
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

    private var safetyTimeoutRunnable: Runnable? = null

    private val beepsEnabled: Boolean get() = settings.beepsEnabled

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

    fun destroy() {
        cancelSafetyTimeout()
        speechManager?.destroy()
        speechManager = null
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

        startOnDeviceListening()
        DebugLog.log(TAG, "Listening started (on-device)")
    }

    private fun cancelListening() {
        DebugLog.log(TAG, "Cancelling listening")
        speechManager?.cancel()
        state = State.IDLE
        btRouter.clearRouting()
        host.hideVoiceBubble()
        host.clearPendingScreenshot()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Recognition Setup
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
                state = State.IDLE
                btRouter.clearRouting()
                host.hideVoiceBubble()
            }
        }
        handler.postDelayed(safetyTimeoutRunnable!!, SAFETY_TIMEOUT_MS)
    }
}
