package com.starfarer.companionoverlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.starfarer.companionoverlay.repository.SettingsRepository
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages conversation state and Claude API interactions.
 *
 * Responsibilities:
 * - Conversation history (in-memory + persistence)
 * - Building API requests (text-only and with images)
 * - Sending to Claude and parsing responses
 * - Auto-copy to clipboard
 *
 * Threading: All public methods are safe to call from any thread.
 * Callbacks are always dispatched to the main thread.
 */
class ConversationManager(
    private val context: Context,
    private val claudeApi: ClaudeApi,
    private val settings: SettingsRepository
) {
    companion object {
        private const val TAG = "Conversation"
    }

    /** Callback interface for conversation events. */
    interface Listener {
        fun onResponseReceived(text: String)
        fun onError(message: String)
        fun onCancelled()
    }

    var listener: Listener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val conversationHistory = mutableListOf<JSONObject>()

    @Volatile
    private var activeJob: Job? = null

    /** The last assistant message, for bubble reopen. */
    var lastAssistantMessage: String? = null
        private set

    init {
        restoreHistory()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Send a text-only message to Claude.
     * Cancels any in-flight request.
     */
    fun sendText(text: String) {
        cancelPending()
        
        val userMessage = JSONObject().apply {
            put("role", "user")
            put("content", text)
        }
        
        sendMessage(userMessage)
    }

    /**
     * Send a message with an attached screenshot.
     * @param imageBase64 JPEG image data, base64 encoded
     * @param text Optional voice/text overlay on the image context
     */
    fun sendWithScreenshot(imageBase64: String, text: String? = null) {
        cancelPending()
        
        val userMessage = JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "image")
                    put("source", JSONObject().apply {
                        put("type", "base64")
                        put("media_type", "image/jpeg")
                        put("data", imageBase64)
                    })
                })
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", text ?: settings.userMessage)
                })
            })
        }
        
        sendMessage(userMessage)
    }

    /** Cancel any in-flight API request. */
    fun cancelPending() {
        activeJob?.let { job ->
            if (job.isActive) {
                DebugLog.log(TAG, "Cancelling active request")
                job.cancel()
                claudeApi.cancelPending()
            }
        }
        activeJob = null
    }

    /** Clear conversation history (both in-memory and persisted). */
    fun clearHistory() {
        conversationHistory.clear()
        lastAssistantMessage = null
        saveHistory()
        DebugLog.log(TAG, "History cleared")
    }

    /** Get conversation context for STT (helps Gemini understand domain terms). */
    fun getContextForStt(): String {
        val recent = conversationHistory.takeLast(6)
        if (recent.isEmpty()) return ""

        return buildString {
            for (msg in recent) {
                val role = msg.optString("role", "user")
                val content = msg.opt("content")
                val text = extractTextFromContent(content)
                if (text.isNotBlank()) {
                    val label = if (role == "assistant") "Assistant" else "User"
                    appendLine("$label: ${text.take(200)}")
                }
            }
        }.trim()
    }

    fun destroy() {
        cancelPending()
        scope.cancel()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Internal
    // ══════════════════════════════════════════════════════════════════════

    private fun sendMessage(userMessage: JSONObject) {
        activeJob = scope.launch {
            val messagesArray = JSONArray().apply {
                conversationHistory.forEach { put(it) }
                put(userMessage)
            }

            val systemPrompt = settings.systemPrompt
            val webSearch = settings.webSearchEnabled

            DebugLog.log(TAG, "Sending (${messagesArray.length()} messages, webSearch=$webSearch)")

            val response = withContext(Dispatchers.IO) {
                claudeApi.sendConversation(messagesArray, systemPrompt, webSearch)
            }

            when {
                response.success -> handleSuccess(userMessage, response.text)
                response.cancelled -> {
                    DebugLog.log(TAG, "Request cancelled")
                    listener?.onCancelled()
                }
                else -> {
                    DebugLog.log(TAG, "API error: ${response.error}")
                    listener?.onError(response.error ?: "Unknown error")
                }
            }
        }
    }

    private fun handleSuccess(userMessage: JSONObject, responseText: String) {
        conversationHistory.add(userMessage)
        conversationHistory.add(JSONObject().apply {
            put("role", "assistant")
            put("content", responseText)
        })

        lastAssistantMessage = responseText
        DebugLog.log(TAG, "Response: ${responseText.take(60)}...")

        val maxMessages = settings.maxMessages
        while (conversationHistory.size > maxMessages) {
            conversationHistory.removeAt(0)
            conversationHistory.removeAt(0)
        }

        saveHistory()

        if (settings.autoCopy) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Senni", responseText))
        }

        listener?.onResponseReceived(responseText)
    }

    private fun extractTextFromContent(content: Any?): String {
        return when (content) {
            is String -> content
            is JSONArray -> {
                for (j in 0 until content.length()) {
                    val block = content.getJSONObject(j)
                    if (block.optString("type") == "text") {
                        return block.optString("text", "")
                    }
                }
                ""
            }
            else -> ""
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Persistence
    // ══════════════════════════════════════════════════════════════════════

    private fun saveHistory() {
        if (settings.keepDialogue) {
            val arr = JSONArray().apply {
                conversationHistory.forEach { put(it) }
            }
            settings.conversationHistory = arr.toString()
        } else {
            settings.conversationHistory = null
        }
    }

    private fun restoreHistory() {
        if (!settings.keepDialogue) return

        val json = settings.conversationHistory ?: return

        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                conversationHistory.add(arr.getJSONObject(i))
            }
            DebugLog.log(TAG, "Restored ${conversationHistory.size} messages")

            for (i in conversationHistory.indices.reversed()) {
                val msg = conversationHistory[i]
                if (msg.optString("role") == "assistant") {
                    lastAssistantMessage = extractTextFromContent(msg.opt("content"))
                    break
                }
            }
        } catch (e: Exception) {
            DebugLog.log(TAG, "Failed to restore history: ${e.message}")
        }
    }
}
