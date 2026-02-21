package com.starfarer.companionoverlay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession

/**
 * The actual session that fires when the user triggers the assistant.
 * 
 * We don't show our own UI here — we start the overlay service if needed
 * and toggle voice input, then get out of the way. The overlay IS our UI.
 */
class SenniVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        DebugLog.log("VoiceIA", "Assistant invoked! showFlags=$showFlags")

        val ctx = context

        // Start overlay if not running
        if (!CompanionOverlayService.isRunning) {
            DebugLog.log("VoiceIA", "Overlay not running, starting it")
            val intent = Intent(ctx, CompanionOverlayService::class.java)
            ctx.startForegroundService(intent)
            // Give the service a moment to initialize before toggling voice
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                CompanionOverlayService.instance?.voiceController?.toggle()
                DebugLog.log("VoiceIA", "Voice toggled (delayed start)")
                hide()
            }, 800)
        } else {
            // Overlay already running — just toggle voice
            CompanionOverlayService.instance?.voiceController?.toggle()
            DebugLog.log("VoiceIA", "Voice toggled (overlay was running)")
            hide()
        }
    }

    override fun onHide() {
        super.onHide()
        DebugLog.log("VoiceIA", "Session hidden")
    }
}
