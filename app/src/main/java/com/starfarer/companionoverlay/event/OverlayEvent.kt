package com.starfarer.companionoverlay.event

/**
 * Events that flow between components without requiring static instance references.
 * 
 * The overlay service, accessibility service, and activities communicate through
 * these events rather than reaching directly into each other's state.
 */
sealed class OverlayEvent {
    
    // ══════════════════════════════════════════════════════════════════════
    // Service Lifecycle
    // ══════════════════════════════════════════════════════════════════════
    
    /** Overlay service has started and is ready to receive commands. */
    data object ServiceStarted : OverlayEvent()
    
    /** Overlay service is stopping. */
    data object ServiceStopping : OverlayEvent()
    
    // ══════════════════════════════════════════════════════════════════════
    // Voice Input
    // ══════════════════════════════════════════════════════════════════════
    
    /** Toggle voice input (from headset button, volume key, etc). */
    data object ToggleVoice : OverlayEvent()
    
    // ══════════════════════════════════════════════════════════════════════
    // Screenshot
    // ══════════════════════════════════════════════════════════════════════
    
    /** Request a screenshot from the accessibility service. */
    data class ScreenshotRequest(val callback: (String?) -> Unit) : OverlayEvent()
    
    /** Screenshot result (base64 JPEG or null on failure). */
    data class ScreenshotResult(val base64: String?) : OverlayEvent()
    
    // ══════════════════════════════════════════════════════════════════════
    // Keyboard Detection
    // ══════════════════════════════════════════════════════════════════════
    
    /** Keyboard visibility changed. */
    data class KeyboardVisibility(val visible: Boolean) : OverlayEvent()
    
    // ══════════════════════════════════════════════════════════════════════
    // Overlay Control
    // ══════════════════════════════════════════════════════════════════════
    
    /** Request overlay dismissal with animation. */
    data object DismissOverlay : OverlayEvent()
}
