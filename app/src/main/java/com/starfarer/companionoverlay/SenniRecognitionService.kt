package com.starfarer.companionoverlay

import android.content.Intent
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Stub RecognitionService required for the app to appear in the
 * "Default digital assistant" picker. Android won't list a
 * VoiceInteractionService without an accompanying RecognitionService.
 *
 * We don't implement our own speech recognition engine — the actual
 * recognition uses SpeechRecognizer in SpeechRecognitionManager.
 * This service exists purely to satisfy the framework's eligibility check.
 */
class SenniRecognitionService : RecognitionService() {

    override fun onStartListening(intent: Intent?, callback: Callback?) {
        DebugLog.log("RecogSvc", "onStartListening called (stub)")
        // We don't handle recognition here — SpeechRecognitionManager does.
        // If something tries to use us as a recognizer, report unavailable.
        try {
            callback?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (_: RemoteException) {}
    }

    override fun onCancel(callback: Callback?) {
        DebugLog.log("RecogSvc", "onCancel called (stub)")
    }

    override fun onStopListening(callback: Callback?) {
        DebugLog.log("RecogSvc", "onStopListening called (stub)")
    }
}
