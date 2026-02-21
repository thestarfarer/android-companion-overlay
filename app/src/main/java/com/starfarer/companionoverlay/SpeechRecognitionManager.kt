package com.starfarer.companionoverlay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Wraps Android's SpeechRecognizer with manual silence-timeout logic.
 *
 * Google's recognizer ignores EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS
 * on most devices, so we implement our own: when the recognizer delivers a
 * result, we don't commit immediately. Instead we restart listening and set
 * a 2-second timer. If more speech arrives, the timer resets. When the timer
 * finally expires with no new input, we commit the accumulated text.
 *
 * Must be created and used on the main thread.
 */
class SpeechRecognitionManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechRec"
        /** How long to wait after last speech before committing. */
        private const val DEFAULT_SILENCE_TIMEOUT_MS = 1000L
    }

    /** Partial transcription as the user speaks — for UI feedback. */
    var onPartialResult: ((String) -> Unit)? = null

    /** Final transcription of a complete utterance. */
    var onFinalResult: ((String) -> Unit)? = null

    /** Recognition error — human-readable. */
    var onError: ((String) -> Unit)? = null

    /** RMS level changes — for voice activity visualization. */
    var onRmsChanged: ((Float) -> Unit)? = null

    /** Called when the recognizer is ready and listening. */
    var onReadyForSpeech: (() -> Unit)? = null

    /** Called when the recognizer stops (result delivered or error). */
    var onStopped: (() -> Unit)? = null

    var isListening: Boolean = false
        private set

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())

    // --- Accumulation state for silence-timeout logic ---
    /** Segments collected across recognizer restarts. */
    private val accumulatedSegments = mutableListOf<String>()
    /** Whether we're in accumulation mode (waiting for silence timeout). */
    private var accumulating = false
    /** The pending commit runnable. */
    private var commitRunnable: Runnable? = null
    /** Guard against delivering results after explicit cancel/stop. */
    private var cancelled = false

    /** Silence timeout in ms — set from settings before startListening(). */
    var silenceTimeoutMs: Long = DEFAULT_SILENCE_TIMEOUT_MS

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            DebugLog.log(TAG, "Ready for speech")
            // Only fire the callback on the first listen, not on restarts
            if (!accumulating) {
                this@SpeechRecognitionManager.onReadyForSpeech?.invoke()
            }
        }

        override fun onBeginningOfSpeech() {
            DebugLog.log(TAG, "Speech detected")
            // New speech arrived — cancel any pending commit
            cancelCommitTimer()
        }

        override fun onRmsChanged(rmsdB: Float) {
            this@SpeechRecognitionManager.onRmsChanged?.invoke(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            DebugLog.log(TAG, "End of speech")
        }

        override fun onError(error: Int) {
            if (cancelled) return
            val msg = errorToString(error)
            DebugLog.log(TAG, "Error: $msg ($error)")

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // No speech in this segment.
                    if (accumulating && accumulatedSegments.isNotEmpty()) {
                        // We have prior segments — commit what we have
                        DebugLog.log(TAG, "Silence after accumulated speech, committing")
                        commitAccumulated()
                    } else {
                        // Nothing at all — just stop
                        isListening = false
                        accumulating = false
                        onStopped?.invoke()
                    }
                }
                SpeechRecognizer.ERROR_CLIENT -> {
                    // Often a ghost from a recognizer we just destroyed during
                    // restart. If we have accumulated segments, commit them.
                    // If not, treat it as a silent stop.
                    if (accumulating && accumulatedSegments.isNotEmpty()) {
                        DebugLog.log(TAG, "Client error during accumulation, committing")
                        commitAccumulated()
                    } else {
                        DebugLog.log(TAG, "Client error (benign), ignoring")
                        isListening = false
                        accumulating = false
                        onStopped?.invoke()
                    }
                }
                else -> {
                    isListening = false
                    accumulating = false
                    accumulatedSegments.clear()
                    cancelCommitTimer()
                    onError?.invoke(msg)
                    onStopped?.invoke()
                }
            }
        }

        override fun onResults(results: Bundle?) {
            if (cancelled) return
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = texts?.firstOrNull()?.trim()

            if (!text.isNullOrEmpty()) {
                DebugLog.log(TAG, "Segment result: ${text.take(80)}")
                accumulatedSegments.add(text)
                accumulating = true

                // Show the full accumulated text as partial feedback
                val fullText = decensor(accumulatedSegments.joinToString(" "))
                onPartialResult?.invoke(fullText)

                // Start silence timer — if it expires, commit
                startCommitTimer()

                // Restart the recognizer to catch more speech
                restartListening()
            } else if (accumulating && accumulatedSegments.isNotEmpty()) {
                // Empty result but we have accumulated text — start commit timer
                startCommitTimer()
                restartListening()
            } else {
                isListening = false
                accumulating = false
                onStopped?.invoke()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = texts?.firstOrNull()?.trim()
            if (!text.isNullOrEmpty()) {
                // Show accumulated + current partial
                val prefix = if (accumulatedSegments.isNotEmpty())
                    accumulatedSegments.joinToString(" ") + " "
                else ""
                onPartialResult?.invoke(decensor(prefix + text))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * Begin listening for speech. Creates the recognizer if needed.
     * Must be called on the main thread.
     */
    fun startListening() {
        cancelled = false
        accumulatedSegments.clear()
        accumulating = false
        cancelCommitTimer()
        ensureRecognizer()
        doStartListening()
    }

    /** Internal: (re)start the recognizer for another segment. */
    private fun restartListening() {
        // Destroy and recreate — some recognizers don't handle rapid restart well
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null

        handler.postDelayed({
            if (cancelled) return@postDelayed
            ensureRecognizer()
            doStartListening()
        }, 100) // brief delay for cleanup
    }

    private fun ensureRecognizer() {
        if (recognizer == null) {
            DebugLog.log(TAG, "Creating default recognizer")
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(listener)
        }
    }

    private fun doStartListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Disable profanity filter — undocumented but respected by some recognizers
            putExtra("android.speech.extra.OBSCENITY_LEVEL", 0)
            putExtra("android.speech.extra.BLOCK_OFFENSIVE_WORDS", false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, true)
            }
        }

        try {
            recognizer?.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            DebugLog.log(TAG, "startListening failed: ${e.message}")
            isListening = false
            if (accumulating && accumulatedSegments.isNotEmpty()) {
                commitAccumulated()
            } else {
                onError?.invoke("Failed to start: ${e.message}")
                onStopped?.invoke()
            }
        }
    }

    private fun startCommitTimer() {
        cancelCommitTimer()
        val runnable = Runnable {
            DebugLog.log(TAG, "Silence timeout — committing accumulated speech")
            commitAccumulated()
        }
        commitRunnable = runnable
        handler.postDelayed(runnable, silenceTimeoutMs)
    }

    private fun cancelCommitTimer() {
        commitRunnable?.let { handler.removeCallbacks(it) }
        commitRunnable = null
    }

    /** Deliver the accumulated segments as one final result. */
    private fun commitAccumulated() {
        cancelCommitTimer()
        // Stop any in-progress recognition
        try { recognizer?.cancel() } catch (_: Exception) {}

        val fullText = decensor(accumulatedSegments.joinToString(" ").trim())
        accumulatedSegments.clear()
        accumulating = false
        isListening = false

        if (fullText.isNotEmpty()) {
            DebugLog.log(TAG, "Final result: ${fullText.take(80)}")
            onFinalResult?.invoke(fullText)
        } else {
            onStopped?.invoke()
        }
    }

    /**
     * Stop listening. Commits any accumulated speech immediately.
     */
    fun stopListening() {
        cancelCommitTimer()
        try { recognizer?.stopListening() } catch (_: Exception) {}

        if (accumulating && accumulatedSegments.isNotEmpty()) {
            commitAccumulated()
        } else {
            isListening = false
        }
    }

    /** Cancel without delivering a result. */
    fun cancel() {
        cancelled = true
        cancelCommitTimer()
        accumulatedSegments.clear()
        accumulating = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        isListening = false
    }

    /** Tear down completely. */
    fun destroy() {
        cancel()
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    /**
     * Reverse the recognizer's profanity censorship.
     * Google replaces words like "fuck" with "f***". We put them back.
     */
    private fun decensor(text: String): String {
        // Pattern: a letter followed by asterisks, possibly with apostrophes
        // e.g. f***, s***, b*****, a**, p****, d***, etc.
        val map = mapOf(
            "f***" to "fuck",
            "f*****" to "fucked",
            "f******" to "fucking",
            "s***" to "shit",
            "s****" to "shits",
            "s*****" to "shitty",
            "b****" to "bitch",
            "b*****" to "bitchy",
            "b*******" to "bullshit",
            "a**" to "ass",
            "a*****" to "ashole",
            "a******" to "asshole",
            "d***" to "dick",
            "d**n" to "damn",
            "d****d" to "damned",
            "c***" to "cunt",
            "c**p" to "crap",
            "p***" to "piss",
            "p****" to "pussy",
            "w***e" to "whore",
            "h**l" to "hell",
            "n****" to "nigga",
            "n*****" to "nigger",
        )
        var result = text
        // Case-insensitive replacement preserving original case of first letter
        for ((censored, replacement) in map) {
            val regex = Regex(Regex.escape(censored), RegexOption.IGNORE_CASE)
            result = regex.replace(result) { match ->
                val firstChar = match.value.first()
                if (firstChar.isUpperCase()) {
                    replacement.replaceFirstChar { it.uppercase() }
                } else {
                    replacement
                }
            }
        }
        // Catch-all: any remaining single letter + 2+ asterisks — strip the asterisks
        // This won't perfectly reconstruct but removes the visual censorship
        result = result.replace(Regex("""\b([a-zA-Z])\*{2,}\b""")) { match ->
            match.groupValues[1] + "[?]"
        }
        return result
    }

    private fun errorToString(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
        else -> "Unknown error ($error)"
    }
}
