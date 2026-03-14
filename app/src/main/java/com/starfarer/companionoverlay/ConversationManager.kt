package com.starfarer.companionoverlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.starfarer.companionoverlay.api.*
import com.starfarer.companionoverlay.mcp.McpManager
import com.starfarer.companionoverlay.mcp.AsyncResultPoller
import com.starfarer.companionoverlay.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import java.util.Collections
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ConversationManager(
    private val context: Context,
    private val claudeApi: ClaudeApi,
    private val settings: SettingsRepository,
    private val storage: ConversationStorage,
    private val mcpManager: McpManager
) {
    companion object {
        private const val TAG = "Conversation"
        private const val SILENCE_TIMEOUT_MS = 60_000L
        private const val WATCHER_INTERVAL_MS = 10_000L
    }

    interface Listener {
        fun onResponseReceived(text: String)
        fun onError(message: String)
        fun onCancelled()
        fun onToolUseProgress(toolNames: List<String>) {}
        fun onAsyncResultsInjecting() {}
    }

    var listener: Listener? = null

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.Main)
    private val conversationHistory: MutableList<Message> =
        Collections.synchronizedList(mutableListOf())

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var lastUserMessageTime: Long = System.currentTimeMillis()

    private var resultWatcherJob: Job? = null
    private var messagesSinceLastEmit = 0
    private var asyncPoller: AsyncResultPoller? = null

    @Volatile
    var lastAssistantMessage: String? = null
        private set

    init {
        restoreHistory()
        startResultWatcher()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    fun sendText(text: String) {
        lastUserMessageTime = System.currentTimeMillis()
        cancelPending()
        sendMessage(textMessage(text))
    }

    fun sendWithScreenshot(imageBase64: String, text: String? = null) {
        lastUserMessageTime = System.currentTimeMillis()
        cancelPending()
        sendMessage(screenshotMessage(imageBase64, text ?: settings.userMessage))
    }

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

    fun clearHistory() {
        conversationHistory.clear()
        lastAssistantMessage = null
        messagesSinceLastEmit = 0
        scope.launch { storage.clear() }
        DebugLog.log(TAG, "History cleared")
    }

    /** Called by the service after MCP initialization. Creates and starts the async result poller. */
    fun startAsyncPolling() {
        asyncPoller?.destroy()
        asyncPoller = null
        if (!settings.nexusIntegrationEnabled) return
        asyncPoller = AsyncResultPoller(mcpManager).also { it.start() }
    }

    fun getContextForStt(): String {
        val recent = synchronized(conversationHistory) {
            conversationHistory.takeLast(3)
        }
        if (recent.isEmpty()) return ""

        return buildString {
            for (msg in recent) {
                val text = msg.content.textContent()
                if (text.isNotBlank()) {
                    val label = if (msg.role == "assistant") "Assistant" else "User"
                    appendLine("$label: ${text.take(100)}")
                }
            }
        }.trim()
    }

    fun destroy() {
        cancelPending()
        resultWatcherJob?.cancel()
        asyncPoller?.destroy()
        asyncPoller = null
        resultWatcherJob = null
        listener = null
        supervisorJob.cancel()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Async Result Watcher
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Watches for pending async results (agent, round table) and injects them
     * into the conversation after a silence period. If the user hasn't sent
     * a message in [SILENCE_TIMEOUT_MS] and results are waiting, Senni speaks
     * up unprompted.
     */
    private fun startResultWatcher() {
        resultWatcherJob = scope.launch {
            while (isActive) {
                delay(WATCHER_INTERVAL_MS)
                try {
                    if (!settings.mcpEnabled || !settings.nexusIntegrationEnabled) continue
                    if (asyncPoller?.hasPendingResults() != true) continue
                    if (activeJob?.isActive == true) continue

                    val silence = System.currentTimeMillis() - lastUserMessageTime
                    if (silence < SILENCE_TIMEOUT_MS) continue

                    injectPendingResults()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.log(TAG, "Result watcher error: ${e.message}")
                }
            }
        }
    }

    /**
     * Drain pending results and send them as a synthetic turn.
     * The model sees the results as context arriving between conversation
     * turns and responds naturally — Senni sharing news she just received.
     */
    private fun injectPendingResults() {
        val results = asyncPoller?.drainResults() ?: emptyList()
        if (results.isEmpty()) return

        val resultText = results.joinToString("\n\n") { it.content }
        DebugLog.log(TAG, "Injecting ${results.size} async result(s) after silence")
        listener?.onAsyncResultsInjecting()

        // Send as a synthetic user message that the model understands
        // is system-initiated, not human-typed
        val synthetic = textMessage(
            "[System: Async job results arrived]\n$resultText\n[React to these results naturally. The user didn't type this — it arrived from a background task you dispatched earlier.]"
        )
        lastUserMessageTime = System.currentTimeMillis()
        sendMessage(synthetic)
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
                val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)"))
                val basePrompt = "[Current time: $now]\n\n" + settings.systemPrompt
                val asyncResults = if (settings.mcpEnabled && settings.nexusIntegrationEnabled) asyncPoller?.drainResults() ?: emptyList() else emptyList()
                val systemPrompt = if (asyncResults.isNotEmpty()) {
                    val resultText = asyncResults.joinToString("\n\n") { it.content }
                    DebugLog.log(TAG, "Injecting ${asyncResults.size} async result(s) into context")
                    basePrompt + "\n\n[Async results arrived while you were talking]\n" + resultText
                } else basePrompt
                val webSearch = settings.webSearchEnabled
                val mcpTools = if (settings.mcpEnabled) mcpManager.getClaudeTools() else emptyList()

                DebugLog.log(TAG, "Sending (${allMessages.size} messages, " +
                    "webSearch=$webSearch, mcpTools=${mcpTools.size})")

                var messages = sanitizeToolMessages(allMessages)
                var iterations = 0
                val maxIterations = 10

                while (iterations < maxIterations) {
                    iterations++

                    val response = withContext(Dispatchers.IO) {
                        claudeApi.sendConversation(messages, systemPrompt, webSearch, mcpTools)
                    }

                    when {
                        response.cancelled -> {
                            DebugLog.log(TAG, "Request cancelled")
                            listener?.onCancelled()
                            return@launch
                        }
                        !response.success -> {
                            DebugLog.log(TAG, "API error: ${response.error}")
                            listener?.onError(response.error ?: "Unknown error")
                            return@launch
                        }
                    }

                    val fullResponse = response.fullResponse
                    if (fullResponse != null && fullResponse.hasToolUse()) {
                        val toolUseBlocks = fullResponse.toolUseBlocks()
                        DebugLog.log(TAG, "Tool use: ${toolUseBlocks.size} calls " +
                            "(iteration $iterations)")

                        listener?.onToolUseProgress(toolUseBlocks.mapNotNull { it.name })

                        val assistantMsg = Message(
                            role = "assistant",
                            content = MessageContent.Blocks(
                                fullResponse.content.mapNotNull { block ->
                                    when (block.type) {
                                        "tool_use" -> ContentBlock.ToolUse(
                                            id = block.id!!,
                                            name = block.name!!,
                                            input = block.input ?: JsonObject(emptyMap())
                                        )
                                        "text" -> ContentBlock.Text(block.text ?: "")
                                        else -> null
                                    }
                                }
                            )
                        )

                        val toolResults = mutableListOf<ContentBlock>()
                        for (toolBlock in toolUseBlocks) {
                            val result = mcpManager.executeTool(
                                toolBlock.name!!,
                                toolBlock.input
                            )
                            toolResults.add(ContentBlock.ToolResult(
                                toolUseId = toolBlock.id!!,
                                content = result.content,
                                isError = result.isError
                            ))
                        }

                        val toolResultMsg = Message(
                            role = "user",
                            content = MessageContent.Blocks(toolResults)
                        )

                        messages = messages + assistantMsg + toolResultMsg
                    } else {
                        // Only persist user message + final text, not tool intermediates
                        val userMsg = allMessages.last()
                        val newMessages = listOf(userMsg, assistantMessage(response.text))
                        handleSuccess(newMessages, response.text)
                        return@launch
                    }
                }

                DebugLog.log(TAG, "Tool use loop exceeded $maxIterations iterations")
                listener?.onError("Tool execution limit reached")

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.log(TAG, "Unexpected error: ${e::class.simpleName}: ${e.message}")
                listener?.onError(e.message ?: "Unexpected error")
            }
        }
    }

    private fun handleSuccess(newMessages: List<Message>, responseText: String) {
        synchronized(conversationHistory) {
            conversationHistory.addAll(newMessages)

            val maxMessages = settings.maxMessages
            while (conversationHistory.size > maxMessages) {
                conversationHistory.removeAt(0)
            }
        }

        lastAssistantMessage = responseText
        DebugLog.log(TAG, "Response: ${responseText.take(60)}...")

        messagesSinceLastEmit += newMessages.size
        if (messagesSinceLastEmit >= 20) {
            messagesSinceLastEmit = 0
            maybeEmitNexus()
        }

        saveHistory()

        if (settings.autoCopy) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Senni", responseText))
        }

        listener?.onResponseReceived(responseText)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Nexus integration
    // ══════════════════════════════════════════════════════════════════════

    private fun maybeEmitNexus() {
        if (!settings.nexusIntegrationEnabled || !settings.mcpEnabled) return
        val last20 = synchronized(conversationHistory) {
            conversationHistory.takeLast(20)
        }
        scope.launch {
            try {
                val payload = buildNexusPayload(last20)
                val results = mcpManager.emitEventToAll("nexus_emit_event", payload)
                val errors = results.filter { it.isError }
                if (errors.isNotEmpty()) {
                    DebugLog.log(TAG, "Nexus emit errors: ${errors.map { it.content }}")
                }
            } catch (e: Exception) {
                DebugLog.log(TAG, "Nexus emit failed: ${e.message}")
            }
        }
    }

    private fun buildNexusPayload(messages: List<Message>): JsonObject {
        val messagesJson = messages.map { msg ->
            buildJsonObject {
                put("role", msg.role)
                put("text", msg.content.textContent())
                put("timestamp", msg.timestamp)
            }
        }
        return buildJsonObject {
            put("event_type", "session_summary")
            put("category", "conversation_history")
            put("payload", Json.encodeToString(messagesJson))
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // Message Sanitization
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Strip orphaned tool_use and tool_result blocks from the message list.
     * A tool_use is orphaned if no subsequent user message has a tool_result
     * referencing its ID. A tool_result is orphaned if no prior assistant
     * message has a tool_use with that ID. Messages left empty after
     * stripping are removed entirely.
     */
    private fun sanitizeToolMessages(messages: List<Message>): List<Message> {
        // Collect all tool_use IDs from assistant messages
        val toolUseIds = mutableSetOf<String>()
        // Collect all tool_result IDs from user messages
        val toolResultIds = mutableSetOf<String>()

        for (msg in messages) {
            val blocks = (msg.content as? MessageContent.Blocks)?.blocks ?: continue
            for (block in blocks) {
                when (block) {
                    is ContentBlock.ToolUse -> toolUseIds.add(block.id)
                    is ContentBlock.ToolResult -> toolResultIds.add(block.toolUseId)
                    else -> {}
                }
            }
        }

        // Matched = present in both sets
        val matched = toolUseIds.intersect(toolResultIds)

        if (matched.size == toolUseIds.size && matched.size == toolResultIds.size) {
            return messages  // nothing to strip
        }

        val orphanCount = (toolUseIds.size - matched.size) + (toolResultIds.size - matched.size)
        DebugLog.log(TAG, "Sanitizing: $orphanCount orphaned tool blocks removed")

        return messages.mapNotNull { msg ->
            when (val content = msg.content) {
                is MessageContent.Blocks -> {
                    val cleaned = content.blocks.filter { block ->
                        when (block) {
                            is ContentBlock.ToolUse -> block.id in matched
                            is ContentBlock.ToolResult -> block.toolUseId in matched
                            else -> true
                        }
                    }
                    if (cleaned.isEmpty()) null
                    else msg.copy(content = MessageContent.Blocks(cleaned))
                }
                else -> msg
            }
        }
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
