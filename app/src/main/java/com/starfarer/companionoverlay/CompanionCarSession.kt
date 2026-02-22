package com.starfarer.companionoverlay

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import org.json.JSONObject

/**
 * A single Android Auto session. Holds car-specific conversation state
 * and shared API instances (same process as main app).
 */
class CompanionCarSession : Session() {

    /** Separate conversation history for car context. */
    val conversationHistory = mutableListOf<JSONObject>()

    /** Lazily initialised — carContext is only valid after onCreate. */
    lateinit var claudeAuth: ClaudeAuth
        private set
    lateinit var claudeApi: ClaudeApi
        private set

    override fun onCreateScreen(intent: Intent): Screen {
        claudeAuth = ClaudeAuth(carContext)
        claudeApi = ClaudeApi(claudeAuth)
        return CompanionMainScreen(carContext, this)
    }
}
