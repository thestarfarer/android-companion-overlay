package com.starfarer.companionoverlay

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import com.starfarer.companionoverlay.api.Message
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * A single Android Auto session. Holds car-specific conversation state
 * and shared API instances (same process as main app).
 *
 * [ClaudeAuth] and [ClaudeApi] are injected from Koin rather than created
 * locally — they're process-level singletons that share the same connection
 * pool and auth state as the main app.
 */
class CompanionCarSession : Session(), KoinComponent {

    /** Separate conversation history for car context. */
    private val _conversationHistory = mutableListOf<Message>()
    val conversationHistory: List<Message> get() = _conversationHistory

    fun addMessage(message: Message) { _conversationHistory.add(message) }
    fun trimHistory(maxSize: Int) { while (_conversationHistory.size > maxSize) _conversationHistory.removeAt(0) }

    /** Injected dependencies — shared with the main app. */
    val claudeAuth: ClaudeAuth by inject()
    val claudeApi: ClaudeApi by inject()

    override fun onCreateScreen(intent: Intent): Screen {
        return CompanionMainScreen(carContext, this)
    }
}
