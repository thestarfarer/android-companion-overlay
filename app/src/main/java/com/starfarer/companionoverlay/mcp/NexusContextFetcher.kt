package com.starfarer.companionoverlay.mcp

import com.starfarer.companionoverlay.DebugLog
import com.starfarer.companionoverlay.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Fetches context from the Nexus via [McpManager] and caches it in
 * [SettingsRepository] for reuse across conversations.
 *
 * The cached context persists until explicitly re-fetched — either from
 * the Settings UI or programmatically. It is not session-scoped.
 */
class NexusContextFetcher(
    private val mcpManager: McpManager,
    private val settings: SettingsRepository
) {
    companion object {
        private const val TAG = "NexusCtx"
        private const val TOOL_SUFFIX = "nexus_get_context"
    }

    /**
     * Calls nexus_get_context on the first MCP server that exposes it.
     * On success, caches the result in SettingsRepository.
     * Returns the context text on success, or an error message on failure.
     */
    suspend fun fetch(state: String? = null): Result<String> = withContext(Dispatchers.IO) {
        // Ensure MCP connections are live — they may not be if the overlay
        // service hasn't started (e.g. fetching from Settings directly).
        if (mcpManager.getClaudeTools().isEmpty()) {
            DebugLog.log(TAG, "Tool registry empty, initializing MCP connections...")
            mcpManager.initializeAll()
        }

        val tool = mcpManager.getClaudeTools()
            .firstOrNull { it.name.endsWith("__$TOOL_SUFFIX") || it.name == TOOL_SUFFIX }

        if (tool == null) {
            val msg = "nexus_get_context tool not found on any connected server"
            DebugLog.log(TAG, msg)
            return@withContext Result.failure(Exception(msg))
        }

        val now = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)"))
        val presetName = settings.systemPrompt.take(80)
            .let { if (it.contains("\n")) it.substringBefore("\n") else it }

        val stateText = state ?: "Waking up. Time: $now. What happened recently? What should I know?"

        val arguments = buildJsonObject {
            put("state", stateText)
        }

        DebugLog.log(TAG, "Fetching context: ${stateText.take(80)}...")

        try {
            val result = mcpManager.executeTool(tool.name, arguments)
            if (result.isError) {
                DebugLog.log(TAG, "Fetch failed: ${result.content.take(200)}")
                return@withContext Result.failure(Exception(result.content))
            }

            val context = result.content
            if (context.isBlank() || context == "(empty result)") {
                DebugLog.log(TAG, "Fetch returned empty")
                return@withContext Result.failure(Exception("Empty context returned"))
            }

            settings.nexusContextCache = context
            settings.nexusContextTimestamp = System.currentTimeMillis()
            DebugLog.log(TAG, "Cached ${context.length} chars")

            Result.success(context)
        } catch (e: Exception) {
            DebugLog.log(TAG, "Fetch error: ${e.message}")
            Result.failure(e)
        }
    }

    /** Clears the cached context. */
    fun clearCache() {
        settings.nexusContextCache = null
        settings.nexusContextTimestamp = 0
        DebugLog.log(TAG, "Cache cleared")
    }
}
