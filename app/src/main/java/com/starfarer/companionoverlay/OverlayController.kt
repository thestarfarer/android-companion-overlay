package com.starfarer.companionoverlay

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.starfarer.companionoverlay.event.OverlayCoordinator

/**
 * Single entry point for bringing the overlay service up.
 *
 * Several independent OS surfaces want the overlay running — the app's toggle
 * button (MainActivity), volume-key gestures (CompanionAccessibilityService),
 * and the system assistant trigger (AssistActivity). Each is a separate
 * component the OS can invoke cold, so each must be able to start the service.
 * This object holds the one copy of the "start it, wait for init, optionally
 * kick off voice" ceremony so the callers can't drift out of sync — which is
 * exactly how the triple-press path ended up missing a permission check.
 *
 * Permission *policy* stays with the caller: the prompt differs per surface
 * (open settings vs. an ActivityResult launcher vs. a toast), so callers gate
 * on [canStart] themselves. The service also self-guards in onCreate as a
 * backstop, so a missing permission can never crash here.
 */
object OverlayController {

    private const val INIT_DELAY_MS = 800L
    private val handler = Handler(Looper.getMainLooper())

    /** Whether the overlay can be shown — i.e. "Display over other apps" is granted. */
    fun canStart(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * Ensure the overlay service is running. If [thenStartVoice] is set, voice
     * input is toggled once the service is up — immediately if it was already
     * running, or after a short init delay if it had to be started. Safe to call
     * when already running.
     */
    fun ensureRunning(
        context: Context,
        coordinator: OverlayCoordinator,
        thenStartVoice: Boolean = false
    ) {
        if (coordinator.overlayRunning.value) {
            if (thenStartVoice) coordinator.toggleVoice()
            return
        }
        context.startForegroundService(Intent(context, CompanionOverlayService::class.java))
        if (thenStartVoice) {
            handler.postDelayed({ coordinator.toggleVoice() }, INIT_DELAY_MS)
        }
    }
}
