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

    /** Clear any pending screenshot that was waiting for voice input. */
    fun clearPendingScreenshot()
}
