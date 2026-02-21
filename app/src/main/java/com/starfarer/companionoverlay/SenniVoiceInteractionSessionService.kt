package com.starfarer.companionoverlay

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Creates sessions when the assistant is invoked.
 * Each invocation (long-press, swipe, headset button) creates a new session.
 */
class SenniVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return SenniVoiceInteractionSession(this)
    }
}
