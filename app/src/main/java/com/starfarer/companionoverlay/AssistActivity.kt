package com.starfarer.companionoverlay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
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

        // Without overlay permission the service would crash adding its window.
        // Send the user to grant it instead.
        if (!OverlayController.canStart(this)) {
            DebugLog.log("Assist", "No overlay permission — opening settings")
            Toast.makeText(this, getString(R.string.assist_toast_overlay_permission_needed), Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
            return
        }

        OverlayController.ensureRunning(this, coordinator, thenStartVoice = true)
        finish()
    }
}
