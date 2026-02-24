package com.starfarer.companionoverlay

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.starfarer.companionoverlay.api.assistantMessage
import com.starfarer.companionoverlay.api.textMessage
import com.starfarer.companionoverlay.repository.SettingsRepository
import kotlinx.coroutines.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Main Android Auto screen: "Talk to Senni" button + model indicator.
 * Uses PaneTemplate (IOT category).
 *
 * Implements [KoinComponent] to access [SettingsRepository] from the
 * Car App Library context, where standard Android injection isn't available.
 */
class CompanionMainScreen(
    carContext: CarContext,
    private val session: CompanionCarSession
) : Screen(carContext), KoinComponent {

    private val settings: SettingsRepository by inject()
    private val httpClient: okhttp3.OkHttpClient by inject()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var recorder: CarVoiceRecorder? = null
    private var isRecording = false
    private var statusText = "Ready"

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                recorder?.stop()
                recorder?.destroy()
                scope.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val modelId = settings.model
        val modelName = PromptSettings.MODEL_IDS.indexOf(modelId).let { idx ->
            if (idx >= 0) PromptSettings.MODEL_NAMES[idx] else "Unknown"
        }

        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(statusText)
                    .addText("Model: $modelName")
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(if (isRecording) "Listening..." else "Talk")
                    .setOnClickListener { onTalkClicked() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Model")
                    .setOnClickListener {
                        screenManager.push(CompanionModelScreen(carContext) { onModelChanged() })
                    }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle("Senni")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun onTalkClicked() {
        if (isRecording) {
            recorder?.stop()
            return
        }

        isRecording = true
        statusText = "Listening..."
        invalidate()

        recorder = CarVoiceRecorder(carContext, settings, httpClient).apply {
            silenceTimeoutMs = settings.silenceTimeoutMs

            onResult = { transcript ->
                isRecording = false
                statusText = "Thinking..."
                invalidate()
                sendToClaudeAndRespond(transcript)
            }

            onError = { error ->
                isRecording = false
                statusText = "Error: $error"
                invalidate()
            }

            start()
        }
    }

    private fun sendToClaudeAndRespond(userText: String) {
        scope.launch {
            val userMsg = textMessage(userText)
            val allMessages = session.conversationHistory + userMsg

            val systemPrompt = CAR_SYSTEM_PROMPT
            val webSearch = settings.webSearchEnabled

            val response = session.claudeApi.sendConversation(
                allMessages, systemPrompt, webSearch
            )

            if (response.success) {
                session.addMessage(userMsg)
                session.addMessage(assistantMessage(response.text))
                session.trimHistory(settings.maxMessages)

                statusText = "Ready"
                invalidate()

                screenManager.push(
                    CompanionResponseScreen(carContext, response.text)
                )
            } else {
                statusText = response.error?.ifBlank { "API error" } ?: "API error"
                invalidate()
            }
        }
    }

    fun onModelChanged() {
        invalidate()
    }

    companion object {
        private val CAR_SYSTEM_PROMPT = """You are Senni, a companion riding along in the car. You're chatty, playful, and helpful. Keep answers SHORT — the driver is driving! One to three sentences max unless they ask for more.

You shift between sweet, cheeky, and slightly impudent. You tease but never hold grudges. You love road trips, music, and being the best co-pilot.

CRITICAL: Keep responses VERY brief. The driver is looking at a car screen. No walls of text."""
    }
}
