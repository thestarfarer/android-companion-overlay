package com.starfarer.companionoverlay

/**
 * Interface defining the capabilities a voice input controller needs from its host.
 *
 * This decouples [VoiceInputController] from [CompanionOverlayService], allowing:
 * - Easier unit testing (mock the interface)
 * - Clearer contract (only exposes what's actually needed)
 */
interface VoiceInputHost {

    // ══════════════════════════════════════════════════════════════════════
    // Voice Bubble
    // ══════════════════════════════════════════════════════════════════════

    /** Show the voice recording indicator bubble. */
    fun showVoiceBubble(text: String)

    /** Update the voice bubble text (for partial transcription). */
    fun updateVoiceBubble(text: String)

    /** Hide the voice bubble. */
    fun hideVoiceBubble()

    /** Show a brief notification bubble. */
    fun showBriefBubble(message: String, durationMs: Long = 3000L)

    // ══════════════════════════════════════════════════════════════════════
    // Audio
    // ══════════════════════════════════════════════════════════════════════

    /** Stop any TTS playback (the user is talking over the companion). */
    fun stopTtsAndCancel()

    /**
     * Add or drop the microphone foreground-service type around active recording.
     * Called with true when recording starts and false when it stops, so the
     * service only claims the while-in-use mic type while it's genuinely needed.
     */
    fun setMicrophoneActive(active: Boolean)

    // ══════════════════════════════════════════════════════════════════════
    // Input Handling
    // ══════════════════════════════════════════════════════════════════════

    /** Ship transcribed voice input to the Nexus gateway. */
    fun sendVoiceInput(text: String)

    /**
     * A voice utterance was shipped to the server as an `audio` message
     * (server voice path). The transcript and turn replies follow via the
     * gateway — the host should treat this like voice input in flight.
     */
    fun onVoiceAudioSent() {}

    /** Clear any pending screenshot that was waiting for voice input. */
    fun clearPendingScreenshot()

    /** True while a capture is waiting to be captioned by voice input. */
    fun hasPendingScreenshot(): Boolean = false

    /**
     * The capture waiting to ride along with the next utterance (server voice
     * path — protocol §3 `audio.image`), or null. Does NOT consume it: the
     * controller clears it via [clearPendingScreenshot] once the utterance
     * actually ships.
     */
    fun pendingCapture(): PendingCapture? = null
}

/** A capture (screenshot/camera JPEG) waiting to be voiced over. */
data class PendingCapture(val base64Jpeg: String, val kind: String)
