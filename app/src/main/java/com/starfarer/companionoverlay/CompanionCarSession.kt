package com.starfarer.companionoverlay

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import com.starfarer.companionoverlay.repository.SettingsRepository
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * A single Android Auto session. Holds car-specific conversation state
 * and shared API instances (same process as main app).
 */
class CompanionCarSession : Session(), KoinComponent {

    /** Separate conversation history for car context. */
    val conversationHistory = mutableListOf<JSONObject>()

    /** Injected dependencies. */
    private val settings: SettingsRepository by inject()

    /** Lazily initialised — carContext is only valid after onCreate. */
    lateinit var claudeAuth: ClaudeAuth
        private set
    lateinit var claudeApi: ClaudeApi
        private set

    override fun onCreateScreen(intent: Intent): Screen {
        claudeAuth = ClaudeAuth(carContext)
        claudeApi = ClaudeApi(claudeAuth, settings)
        return CompanionMainScreen(carContext, this)
    }
}
