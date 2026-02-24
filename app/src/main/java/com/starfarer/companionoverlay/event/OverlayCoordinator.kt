package com.starfarer.companionoverlay.event

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central coordinator for overlay state and cross-component communication.
 *
 * Replaces the static `instance` pattern used by CompanionOverlayService and
 * CompanionAccessibilityService. Components subscribe to state flows and emit
 * events rather than reaching into each other's internals.
 *
 * Events are pure data — no callbacks or lambdas flow through [events].
 * Screenshot callbacks are held in [pendingScreenshotCallback] and matched
 * when [onScreenshotComplete] is called.
 *
 * Lifecycle: Singleton scoped to the Application. Survives configuration changes
 * and service restarts. Injected via Koin.
 */
class OverlayCoordinator {

    // ══════════════════════════════════════════════════════════════════════
    // State (observable by UI)
    // ══════════════════════════════════════════════════════════════════════

    private val _overlayRunning = MutableStateFlow(false)
    val overlayRunning: StateFlow<Boolean> = _overlayRunning.asStateFlow()

    private val _accessibilityRunning = MutableStateFlow(false)
    val accessibilityRunning: StateFlow<Boolean> = _accessibilityRunning.asStateFlow()

    // ══════════════════════════════════════════════════════════════════════
    // Events (fire-and-forget commands, pure data only)
    // ══════════════════════════════════════════════════════════════════════

    private val _events = MutableSharedFlow<OverlayEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<OverlayEvent> = _events.asSharedFlow()

    // ══════════════════════════════════════════════════════════════════════
    // Screenshot coordination (callback held here, not in the event)
    // ══════════════════════════════════════════════════════════════════════

    private val pendingScreenshotCallback = AtomicReference<((String?) -> Unit)?>(null)

    // ══════════════════════════════════════════════════════════════════════
    // Service registration
    // ══════════════════════════════════════════════════════════════════════

    fun onOverlayServiceStarted() {
        _overlayRunning.value = true
        _events.tryEmit(OverlayEvent.ServiceStarted)
    }

    fun onOverlayServiceStopped() {
        _overlayRunning.value = false
        _events.tryEmit(OverlayEvent.ServiceStopping)
    }

    fun onAccessibilityServiceConnected() {
        _accessibilityRunning.value = true
    }

    fun onAccessibilityServiceDisconnected() {
        _accessibilityRunning.value = false
        pendingScreenshotCallback.set(null)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Commands
    // ══════════════════════════════════════════════════════════════════════

    fun toggleVoice() {
        if (_overlayRunning.value) {
            _events.tryEmit(OverlayEvent.ToggleVoice)
        }
    }

    fun dismissOverlay() {
        if (_overlayRunning.value) {
            _events.tryEmit(OverlayEvent.DismissOverlay)
        }
    }

    fun notifyKeyboardVisibility(visible: Boolean) {
        if (_overlayRunning.value) {
            _events.tryEmit(OverlayEvent.KeyboardVisibility(visible))
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Screenshot flow
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Request a screenshot from the accessibility service.
     *
     * The callback is stored internally and invoked when the accessibility
     * service calls [onScreenshotComplete]. Only a data event flows through
     * the SharedFlow — no lambdas leak into the event stream.
     */
    fun requestScreenshot(callback: (String?) -> Unit) {
        if (!_accessibilityRunning.value) {
            callback(null)
            return
        }
        pendingScreenshotCallback.getAndSet(callback)?.invoke(null)
        _events.tryEmit(OverlayEvent.ScreenshotRequest)
    }

    /**
     * Called by the accessibility service when a screenshot is complete.
     */
    fun onScreenshotComplete(base64: String?) {
        pendingScreenshotCallback.getAndSet(null)?.invoke(base64)
    }
}
