package com.starfarer.companionoverlay

import android.os.Handler
import android.os.Looper

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
 */
class VoiceInputController(private val service: CompanionOverlayService) {

    companion object {
        private const val TAG = "VoiceCtrl"
    }

    enum class State { IDLE, LISTENING, PROCESSING }

    var state: State = State.IDLE
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var speechManager: SpeechRecognitionManager? = null

    /**
     * Toggle voice input. Called from accessibility service (Shokz button).
     * Must be called on the main thread.
     */
    fun toggle() {
        DebugLog.log(TAG, "toggle() called, state=$state")
        when (state) {
            State.IDLE -> startListening()
            State.LISTENING -> cancelListening()
            State.PROCESSING -> {
                // User wants to interrupt — stop TTS, cancel everything, start fresh
                DebugLog.log(TAG, "Interrupting processing → start listening")
                service.ttsManager.stop()
                speechManager?.cancel()
                state = State.IDLE
                service.hideVoiceBubble()
                startListening()
            }
        }
    }

    fun destroy() {
        cancelSafetyTimeout()
        speechManager?.destroy()
        speechManager = null
        state = State.IDLE
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
        }
    }

    private var safetyTimeoutRunnable: Runnable? = null

    private fun startSafetyTimeout() {
        cancelSafetyTimeout()
        safetyTimeoutRunnable = Runnable {
            if (state != State.IDLE) {
                DebugLog.log(TAG, "Safety timeout! Stuck in $state, forcing IDLE")
                service.ttsManager.stop()
                speechManager?.cancel()
                state = State.IDLE
                service.hideVoiceBubble()
            }
        }
        handler.postDelayed(safetyTimeoutRunnable!!, 60_000L)
    }

    private fun cancelSafetyTimeout() {
        safetyTimeoutRunnable?.let { handler.removeCallbacks(it) }
        safetyTimeoutRunnable = null
    }

    private fun startListening() {
        if (speechManager == null) {
            speechManager = SpeechRecognitionManager(service).apply {
                onReadyForSpeech = {
                    handler.post {
                        service.showVoiceBubble("Listening...")
                    }
                }

                onPartialResult = { partial ->
                    handler.post {
                        service.updateVoiceBubble(partial)
                    }
                }

                onFinalResult = { text ->
                    handler.post {
                        DebugLog.log(TAG, "Got speech: ${text.take(80)}")
                        state = State.PROCESSING
                        service.hideVoiceBubble()
                        startSafetyTimeout()
                        service.sendVoiceInput(text)
                    }
                }

                onError = { error ->
                    handler.post {
                        DebugLog.log(TAG, "Recognition error: $error")
                        state = State.IDLE
                        service.hideVoiceBubble()
                        service.clearPendingScreenshot()
                        service.showBriefBubblePublic("Couldn't hear that~ ($error)")
                    }
                }

                onStopped = {
                    handler.post {
                        // Silence timeout or no match — just go quiet
                        if (state == State.LISTENING) {
                            DebugLog.log(TAG, "No speech detected → IDLE")
                            state = State.IDLE
                            service.hideVoiceBubble()
                            service.clearPendingScreenshot()
                        }
                    }
                }
            }
        }

        state = State.LISTENING
        service.ttsManager.stop()
        service.showVoiceBubble("Starting...")
        speechManager?.startListening()
        DebugLog.log(TAG, "Listening started")
    }

    private fun cancelListening() {
        DebugLog.log(TAG, "Cancelling listening")
        speechManager?.cancel()
        state = State.IDLE
        service.hideVoiceBubble()
        service.clearPendingScreenshot()
    }
}
