package com.starfarer.companionoverlay

import android.service.voice.VoiceInteractionService

/**
 * The service Android binds when this app is set as the default digital assistant.
 * This is the persistent component — it exists so the system knows we're here.
 * The actual work happens in the SessionService.
 */
class SenniVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        DebugLog.log("VoiceIA", "Senni registered as device assistant")
    }
}
