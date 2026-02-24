package com.starfarer.companionoverlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.starfarer.companionoverlay.api.*
import com.starfarer.companionoverlay.repository.SettingsRepository
import kotlinx.coroutines.*
import java.util.Collections

/**
 * Manages conversation state and Claude API interactions.
 *
 * Responsibilities:
 * - Conversation history (in-memory + file-based persistence via [ConversationStorage])
 * - Building API requests (text-only and with images)
 * - Sending to Claude and parsing responses
 * - Auto-copy to clipboard
 *
 * Messages are stored as typed [Message] objects from [ClaudeModels],
 * serialized to disk via kotlinx.serialization.
 *
 * Threading: The conversation history is wrapped in [Collections.synchronizedList]
 * so access is safe from any thread. All public methods are safe to call from
 * any thread. Callbacks are always dispatched to the main thread.
 *
 * Lifecycle: Created fresh by the overlay module each time the service starts.
 * Call [destroy] in the service's onDestroy to cancel in-flight work and
 * release the coroutine scope. The scope is not resurrected — a new instance
 * is created on the next service start.
 */
class ConversationManager(
    private val context: Context,
    private val claudeApi: ClaudeApi,
    private val settings: SettingsRepository,
    private val storage: ConversationStorage
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

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.Main)
    private val conversationHistory: MutableList<Message> =
        Collections.synchronizedList(mutableListOf())

    @Volatile
    private var activeJob: Job? = null

    /** The last assistant message, for bubble reopen. */
    @Volatile
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
        sendMessage(textMessage(text))
    }

    /**
     * Send a message with an attached screenshot.
     * @param imageBase64 JPEG image data, base64 encoded
     * @param text Optional voice/text overlay on the image context
     */
    fun sendWithScreenshot(imageBase64: String, text: String? = null) {
        cancelPending()
        sendMessage(screenshotMessage(imageBase64, text ?: settings.userMessage))
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
        scope.launch { storage.clear() }
        DebugLog.log(TAG, "History cleared")
    }

    /** Get conversation context for STT (helps Gemini understand domain terms). */
    fun getContextForStt(): String {
        val recent = synchronized(conversationHistory) {
            conversationHistory.takeLast(6)
        }
        if (recent.isEmpty()) return ""

        return buildString {
            for (msg in recent) {
                val text = msg.content.textContent()
                if (text.isNotBlank()) {
                    val label = if (msg.role == "assistant") "Assistant" else "User"
                    appendLine("$label: ${text.take(200)}")
                }
            }
        }.trim()
    }

    fun destroy() {
        cancelPending()
        listener = null
        supervisorJob.cancel()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Internal
    // ══════════════════════════════════════════════════════════════════════

    private fun sendMessage(userMessage: Message) {
        activeJob = scope.launch {
            try {
                val allMessages = synchronized(conversationHistory) {
                    conversationHistory.toList() + userMessage
                }
                val systemPrompt = settings.systemPrompt
                val webSearch = settings.webSearchEnabled

                DebugLog.log(TAG, "Sending (${allMessages.size} messages, webSearch=$webSearch)")

                val response = withContext(Dispatchers.IO) {
                    claudeApi.sendConversation(allMessages, systemPrompt, webSearch)
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.log(TAG, "Unexpected error: ${e::class.simpleName}: ${e.message}")
                listener?.onError(e.message ?: "Unexpected error")
            }
        }
    }

    private fun handleSuccess(userMessage: Message, responseText: String) {
        synchronized(conversationHistory) {
            conversationHistory.add(userMessage)
            conversationHistory.add(assistantMessage(responseText))

            val maxMessages = settings.maxMessages
            while (conversationHistory.size > maxMessages) {
                conversationHistory.removeAt(0)
                conversationHistory.removeAt(0)
            }
        }

        lastAssistantMessage = responseText
        DebugLog.log(TAG, "Response: ${responseText.take(60)}...")

        saveHistory()

        if (settings.autoCopy) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Senni", responseText))
        }

        listener?.onResponseReceived(responseText)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Persistence
    // ══════════════════════════════════════════════════════════════════════

    private fun saveHistory() {
        val snapshot = synchronized(conversationHistory) {
            conversationHistory.toList()
        }
        if (settings.keepDialogue) {
            scope.launch { storage.save(snapshot) }
        } else {
            scope.launch { storage.clear() }
        }
    }

    private fun restoreHistory() {
        if (!settings.keepDialogue) return

        scope.launch {
            val messages = storage.load()
            synchronized(conversationHistory) {
                conversationHistory.addAll(messages)
            }
            DebugLog.log(TAG, "Restored ${conversationHistory.size} messages")

            for (msg in messages.asReversed()) {
                if (msg.role == "assistant") {
                    lastAssistantMessage = msg.content.textContent()
                    break
                }
            }
        }
    }
}
