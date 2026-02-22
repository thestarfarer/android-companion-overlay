package com.starfarer.companionoverlay

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Main Android Auto screen — "Talk to Senni" button + model indicator.
 * Uses PaneTemplate (IOT category).
 */
class CompanionMainScreen(
    carContext: CarContext,
    private val session: CompanionCarSession
) : Screen(carContext) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var recorder: CarVoiceRecorder? = null
    private var isRecording = false
    private var statusText = "Ready"

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                recorder?.stop()
                scope.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val modelId = PromptSettings.getModel(carContext)
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
                        screenManager.push(CompanionModelScreen(carContext, this))
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

        recorder = CarVoiceRecorder(carContext).apply {
            silenceTimeoutMs = PromptSettings.getSilenceTimeout(carContext)

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
            val userMsg = JSONObject().apply {
                put("role", "user")
                put("content", userText)
            }

            val messagesArray = JSONArray()
            for (msg in session.conversationHistory) messagesArray.put(msg)
            messagesArray.put(userMsg)

            // Use a car-appropriate system prompt
            val systemPrompt = CAR_SYSTEM_PROMPT
            session.claudeApi.model = PromptSettings.getModel(carContext)
            val webSearch = PromptSettings.getWebSearch(carContext)

            val response = session.claudeApi.sendConversation(
                messagesArray, systemPrompt, webSearch
            )

            if (response.success) {
                session.conversationHistory.add(userMsg)
                session.conversationHistory.add(JSONObject().apply {
                    put("role", "assistant")
                    put("content", response.text)
                })

                // Trim history
                val maxMsgs = PromptSettings.getMaxMessages(carContext)
                while (session.conversationHistory.size > maxMsgs) {
                    session.conversationHistory.removeAt(0)
                    session.conversationHistory.removeAt(0)
                }

                statusText = "Ready"
                invalidate()

                screenManager.push(
                    CompanionResponseScreen(carContext, this@CompanionMainScreen, response.text)
                )
            } else {
                statusText = response.error?.ifBlank { "API error" } ?: "API error"
                invalidate()
            }
        }
    }

    /** Called from model selector when model changes. */
    fun onModelChanged() {
        invalidate()
    }

    companion object {
        private val CAR_SYSTEM_PROMPT = """You are Senni, a companion riding along in the car. You're chatty, playful, and helpful. Keep answers SHORT — the driver is driving! One to three sentences max unless they ask for more.

You shift between sweet, cheeky, and slightly impudent. You tease but never hold grudges. You love road trips, music, and being the best co-pilot.

CRITICAL: Keep responses VERY brief. The driver is looking at a car screen. No walls of text."""
    }
}
