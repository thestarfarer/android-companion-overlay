package com.starfarer.companionoverlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.starfarer.companionoverlay.repository.SettingsRepository
import okhttp3.OkHttpClient

/**
 * Orchestrates voice input: button press → listen → transcribe → Claude → done.
 *
 * The state machine:
 *   IDLE → LISTENING       (button press)
 *   LISTENING → PROCESSING (speech recognized, sending to Claude)
 *   LISTENING → IDLE       (button press to cancel, silence timeout, or error)
 *   PROCESSING → IDLE      (Claude responded)
 *
 * Every recording starts with a deliberate button press. Nothing auto-restarts.
 * The mic is either on because you asked for it, or it's off.
 *
 * Dependencies are injected rather than reaching through the service:
 * - [VoiceInputHost] for UI and input handling
 * - [SettingsRepository] for configuration
 * - [BeepManager] for audio feedback
 */
class VoiceInputController(
    private val context: Context,
    private val host: VoiceInputHost,
    private val settings: SettingsRepository,
    private val beepManager: BeepManager,
    private val httpClient: OkHttpClient
) {

    companion object {
        private const val TAG = "VoiceCtrl"
        private const val SAFETY_TIMEOUT_MS = 300_000L
        private const val BT_CLEAR_DELAY_MS = 300L
    }

    enum class State { IDLE, LISTENING, PROCESSING }

    var state: State = State.IDLE
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var speechManager: SpeechRecognitionManager? = null
    private var geminiRecognizer: GeminiSpeechRecognizer? = null
    private val btRouter = BluetoothAudioRouter(context)

    private var safetyTimeoutRunnable: Runnable? = null

    private val beepsEnabled: Boolean get() = settings.beepsEnabled
    private val useGemini: Boolean get() = settings.geminiSttEnabled

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

    /** Cancel safety timeout when API response arrives — TTS handles its own completion. */
    fun cancelSafetyTimeout() {
        safetyTimeoutRunnable?.let { handler.removeCallbacks(it) }
        safetyTimeoutRunnable = null
    }

    /**
     * Called by the service after Claude's response has been shown.
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
        geminiRecognizer?.destroy()
        geminiRecognizer = null
        btRouter.clearRouting()
        state = State.IDLE
    }

    // ══════════════════════════════════════════════════════════════════════
    // State Transitions
    // ══════════════════════════════════════════════════════════════════════

    private fun interruptAndRestart() {
        DebugLog.log(TAG, "Interrupting processing → start listening")
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

        if (useGemini) {
            startGeminiListening()
        } else {
            startOnDeviceListening()
        }
        DebugLog.log(TAG, "Listening started (${if (useGemini) "Gemini" else "on-device"})")
    }

    private fun cancelListening() {
        DebugLog.log(TAG, "Cancelling listening")
        speechManager?.cancel()
        geminiRecognizer?.cancel()
        state = State.IDLE
        btRouter.clearRouting()
        host.hideVoiceBubble()
        host.clearPendingScreenshot()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Recognition Setup
    // ══════════════════════════════════════════════════════════════════════

    private fun wireCallbacks(
        setOnReady: (() -> Unit) -> Unit,
        setOnPartial: ((String) -> Unit) -> Unit,
        setOnFinal: ((String) -> Unit) -> Unit,
        setOnError: ((String) -> Unit) -> Unit,
        setOnStopped: (() -> Unit) -> Unit
    ) {
        setOnReady {
            val label = if (useGemini) "🎙 Recording..." else "🎙 Listening..."
            handler.post {
                host.showVoiceBubble(label)
                if (beepsEnabled) beepManager.play(BeepManager.Beep.READY)
            }
        }

        setOnPartial { partial ->
            handler.post {
                host.updateVoiceBubble(partial)
                if (partial == "✒️ Transcribing...") {
                    btRouter.clearRouting()
                    if (beepsEnabled) handler.postDelayed({ beepManager.play(BeepManager.Beep.STEP) }, BT_CLEAR_DELAY_MS)
                }
            }
        }

        setOnFinal { text ->
            handler.post {
                DebugLog.log(TAG, "Got speech: ${text.take(80)}")
                state = State.PROCESSING
                host.hideVoiceBubble()
                startSafetyTimeout()
                host.sendVoiceInput(text)
                btRouter.clearRouting()
            }
        }

        setOnError { error ->
            handler.post {
                DebugLog.log(TAG, "Recognition error: $error")
                if (beepsEnabled) beepManager.play(BeepManager.Beep.ERROR)
                state = State.IDLE
                btRouter.clearRouting()
                host.hideVoiceBubble()
                host.clearPendingScreenshot()
                host.showBriefBubble("Couldn't hear that~ ($error)")
            }
        }

        setOnStopped {
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
            speechManager = SpeechRecognitionManager(context).also { mgr ->
                wireCallbacks(
                    { mgr.onReadyForSpeech = it },
                    { mgr.onPartialResult = it },
                    { mgr.onFinalResult = it },
                    { mgr.onError = it },
                    { mgr.onStopped = it }
                )
            }
        }
        speechManager?.silenceTimeoutMs = settings.silenceTimeoutMs
        speechManager?.startListening()
    }

    private fun startGeminiListening() {
        if (geminiRecognizer == null) {
            geminiRecognizer = GeminiSpeechRecognizer(context, httpClient, settings).also { mgr ->
                wireCallbacks(
                    { mgr.onReadyForSpeech = it },
                    { mgr.onPartialResult = it },
                    { mgr.onFinalResult = it },
                    { mgr.onError = it },
                    { mgr.onStopped = it }
                )
            }
        }
        geminiRecognizer?.silenceDurationMs = settings.silenceTimeoutMs
        geminiRecognizer?.conversationContext = host.getConversationContextForStt()
        geminiRecognizer?.startListening()
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
