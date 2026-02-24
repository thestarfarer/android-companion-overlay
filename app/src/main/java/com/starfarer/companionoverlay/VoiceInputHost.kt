package com.starfarer.companionoverlay

/**
 * Interface defining the capabilities a voice input controller needs from its host.
 *
 * This decouples [VoiceInputController] from [CompanionOverlayService], allowing:
 * - Easier unit testing (mock the interface)
 * - Clearer contract (only exposes what's actually needed)
 * - Potential reuse in other contexts (Android Auto, etc.)
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
    
    /** Stop any TTS playback and cancel pending API requests. */
    fun stopTtsAndCancel()
    
    // ══════════════════════════════════════════════════════════════════════
    // Input Handling
    // ══════════════════════════════════════════════════════════════════════
    
    /** Send voice input text to Claude. */
    fun sendVoiceInput(text: String)
    
    /** Clear any pending screenshot that was waiting for voice input. */
    fun clearPendingScreenshot()
    
    /** Get conversation context to help STT understand domain terms. */
    fun getConversationContextForStt(): String
}
