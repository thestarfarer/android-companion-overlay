package com.starfarer.companionoverlay.event

/**
 * Events that flow between components without requiring static instance references.
 *
 * The overlay service, accessibility service, and activities communicate through
 * these events rather than reaching directly into each other's state.
 *
 * Events are pure data — no callbacks, no lambdas. The coordinator manages
 * callback matching internally via [OverlayCoordinator.pendingScreenshotCallback].
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

    /**
     * Request a screenshot from the accessibility service.
     *
     * The callback is held by [OverlayCoordinator], not carried in this event.
     * The accessibility service takes the screenshot and calls
     * [OverlayCoordinator.onScreenshotComplete] with the result.
     */
    data object ScreenshotRequest : OverlayEvent()

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
