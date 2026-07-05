package com.starfarer.companionoverlay.voice

/**
 * A source of VAD-cut voice utterances — the seam between the device-only
 * capture stack (AudioRecord + Silero VAD in [VadUtteranceSource]) and the
 * pure-JVM pipeline logic ([ServerVoicePipeline]), so the latter can be unit
 * tested with fake utterance bytes.
 *
 * Contract: after [start], the source keeps emitting utterances until [stop].
 * One utterance = one contiguous stretch of detected speech (plus a short
 * pre-roll), delivered whole once the speaker goes quiet.
 */
interface UtteranceSource {

    interface Callback {
        /** Speech onset detected — purely advisory (UI feedback). */
        fun onSpeechStart() {}

        /**
         * One complete utterance: 16kHz mono 16-bit little-endian PCM.
         * Called from the capture thread — marshal to the main thread.
         */
        fun onUtterance(pcm16k: ByteArray)

        /** No speech began within the source's arming window. Source keeps running. */
        fun onNoSpeech() {}

        /** Capture failed (mic busy, permission revoked mid-session, …). Source stops. */
        fun onError(message: String) {}
    }

    /**
     * Begin capturing. [endOfUtteranceSilenceMs] is how much trailing silence
     * closes an utterance (the user's "silence timeout" setting; implementations
     * may clamp it to a sane minimum). Idempotent while running.
     */
    fun start(callback: Callback, endOfUtteranceSilenceMs: Long)

    /** Stop capturing. Safe to call from any thread; idempotent. */
    fun stop()

    /** Stop and free everything (VAD model, threads). The source is dead after this. */
    fun release()
}
