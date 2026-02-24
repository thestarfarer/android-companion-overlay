package com.starfarer.companionoverlay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.starfarer.companionoverlay.event.OverlayCoordinator
import org.koin.android.ext.android.inject

/**
 * Transparent activity that catches ACTION_ASSIST and ACTION_VOICE_ASSIST.
 *
 * This is what the system launches when the user invokes the assistant —
 * long-press home, Shokz button, swipe gesture, whatever the device maps
 * to the assistant trigger. We start the overlay if needed, toggle voice,
 * and finish immediately. No UI of our own.
 *
 * Known limitation: launching any Activity steals foreground focus, which
 * makes YouTube exit fullscreen into PiP. No workaround exists for
 * ACTION_VOICE_COMMAND on stock Android — the intent requires an Activity
 * target. Use triple-tap volume down instead when watching fullscreen video.
 */
class AssistActivity : Activity() {

    private val coordinator: OverlayCoordinator by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.log("Assist", "AssistActivity launched, action=${intent?.action}")

        // Start overlay if not running
        if (!coordinator.overlayRunning.value) {
            DebugLog.log("Assist", "Overlay not running, starting it")
            val svcIntent = Intent(this, CompanionOverlayService::class.java)
            startForegroundService(svcIntent)
            // Delay for service init, then toggle voice
            android.os.Handler(mainLooper).postDelayed({
                coordinator.toggleVoice()
                DebugLog.log("Assist", "Voice toggled (delayed)")
            }, 800)
        } else {
            coordinator.toggleVoice()
            DebugLog.log("Assist", "Voice toggled (overlay was running)")
        }

        finish()
    }
}
