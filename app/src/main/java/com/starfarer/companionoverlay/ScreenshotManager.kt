package com.starfarer.companionoverlay

import android.content.Context
import com.starfarer.companionoverlay.event.OverlayCoordinator

/**
 * Handles screenshot requests by coordinating with the accessibility service
 * through [OverlayCoordinator].
 *
 * The actual screenshot is taken by [CompanionAccessibilityService] which has
 * the necessary permissions. This class just provides a clean API surface.
 */
class ScreenshotManager(
    private val context: Context,
    private val coordinator: OverlayCoordinator
) {

    companion object {
        private const val TAG = "Screenshot"
    }

    /**
     * Request a screenshot. The callback receives base64-encoded JPEG data,
     * or null if the screenshot failed.
     *
     * Requires the accessibility service to be running.
     */
    fun takeScreenshot(callback: (String?) -> Unit) {
        if (!coordinator.accessibilityRunning.value) {
            DebugLog.log(TAG, "Accessibility service not running")
            callback(null)
            return
        }

        DebugLog.log(TAG, "Requesting screenshot via coordinator")
        coordinator.requestScreenshot(callback)
    }
}
