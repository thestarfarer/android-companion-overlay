package com.starfarer.companionoverlay

/**
 * Engine-side status events from the STT engines, decoupled from display text.
 *
 * These used to travel through the partial-result channel as exact-match emoji
 * strings ("✒️ Transcribing...") that doubled as protocol tokens — the
 * controller string-compared the display label to drive Bluetooth teardown and
 * beeps, so retouching the label silently broke audio routing. Engines now
 * emit typed events; the display mapping lives in one place
 * ([VoiceInputController.handleVoiceStatus]).
 */
sealed interface VoiceStatus {
    /** Mic is open and capturing. */
    data object Recording : VoiceStatus

    /** Capture finished; transcription in flight — mic and BT route can be released. */
    data object Transcribing : VoiceStatus

    /** Transcription was blocked by content filters; retrying without context. */
    data object Retrying : VoiceStatus
}
