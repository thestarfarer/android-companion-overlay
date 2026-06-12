package com.starfarer.companionoverlay.mcp

import com.starfarer.companionoverlay.DebugLog
import com.starfarer.companionoverlay.api.Tool
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.security.MessageDigest

/**
 * Transport layer for MCP server connections.
 *
 * Manages [McpClient] instances, aggregates tool definitions from all
 * connected servers, and routes tool_use calls to the correct server.
 * Does not decide when to call tools or what to do with results —
 * that's the caller's concern.
 */
class McpManager(
    private val baseClient: OkHttpClient,
    private val repository: McpRepository
) {
    companion object {
        private const val TAG = "McpMgr"
        private const val TOOL_SEPARATOR = "__"
        // Anthropic requires tool names to match ^[a-zA-Z0-9_-]{1,64}$ — a
        // longer/invalid name 400s the WHOLE request. Budget: 8-hex prefix +
        // "__" = 10 chars of overhead, leaving 54 for the sanitized tool name.
        private const val MAX_TOOL_NAME = 64
        private const val PREFIX_LEN = 8
    }

    private val clients = java.util.concurrent.ConcurrentHashMap<String, McpClient>()
    // qualifiedName -> (serverId, original MCP tool definition)
    private val toolRegistry = java.util.concurrent.ConcurrentHashMap<String, Pair<String, McpToolDefinition>>()

    private val emptyObjectSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }

    suspend fun initializeAll(): Map<String, Result<List<McpToolDefinition>>> =
        withContext(Dispatchers.IO) {
            val configs = repository.loadServers().filter { it.enabled }
            if (configs.isEmpty()) {
                DebugLog.log(TAG, "No MCP servers configured")
                disconnectAll()
                toolRegistry.clear()
                return@withContext emptyMap()
            }

            DebugLog.log(TAG, "Initializing ${configs.size} MCP servers...")

            // Build into fresh maps and connect in PARALLEL — the old code
            // cleared the registry then reconnected serially, so tools vanished
            // mid-reload and N unreachable servers blocked for N×30s.
            val newClients = configs.associate { config ->
                config.id to McpClient(
                    baseClient = baseClient,
                    config = config,
                    secretProvider = { repository.getClientSecret(config.id) }
                )
            }

            val results = configs.map { config ->
                async {
                    config.id to newClients.getValue(config.id).initialize()
                }
            }.awaitAll().toMap()

            // Build the registry off to the side, then swap in one shot.
            val newRegistry = mutableMapOf<String, Pair<String, McpToolDefinition>>()
            for (config in configs) {
                val tools = results[config.id]?.getOrNull() ?: continue
                val prefix = serverPrefix(config.id)
                for (tool in tools) {
                    val qualifiedName = uniqueQualifiedName(prefix, tool.name, newRegistry.keys)
                    newRegistry[qualifiedName] = config.id to tool
                }
            }

            val oldClients = clients.toMap()
            clients.clear()
            clients.putAll(newClients)
            toolRegistry.clear()
            toolRegistry.putAll(newRegistry)
            oldClients.values.forEach { it.disconnect() }

            DebugLog.log(TAG, "Initialization complete: " +
                "${toolRegistry.size} tools from ${clients.size} servers")

            results
        }

    fun getClaudeTools(): List<Tool> {
        return toolRegistry.map { (qualifiedName, entry) ->
            val (_, mcpTool) = entry
            Tool(
                name = qualifiedName,
                description = mcpTool.description,
                // A tool with no schema serialized without input_schema, which
                // the API rejects — fall back to an empty object schema.
                inputSchema = mcpTool.inputSchema ?: emptyObjectSchema
            )
        }
    }

    fun hasTools(): Boolean = toolRegistry.isNotEmpty()

    suspend fun executeTool(
        qualifiedToolName: String,
        arguments: JsonObject?
    ): ToolExecutionResult {
        val entry = toolRegistry[qualifiedToolName]
            ?: return ToolExecutionResult(
                content = "Error: Unknown tool '$qualifiedToolName'",
                isError = true
            )

        val (serverId, mcpTool) = entry
        val client = clients[serverId]
            ?: return ToolExecutionResult(
                content = "Error: MCP server not connected",
                isError = true
            )

        DebugLog.log(TAG, "Executing tool: ${mcpTool.name} on server $serverId")

        val result = client.callTool(mcpTool.name, arguments)

        return if (result.isSuccess) {
            val callResult = result.getOrThrow()
            ToolExecutionResult(
                content = renderToolContent(callResult),
                isError = callResult.isError ?: false
            )
        } else {
            ToolExecutionResult(
                content = "Error: ${result.exceptionOrNull()?.message}",
                isError = true
            )
        }
    }

    /**
     * Flatten an MCP tool result into text for Claude. Text blocks pass through;
     * non-text blocks (image/audio/resource) get a labeled placeholder instead
     * of being silently dropped; structured-only results surface as JSON.
     */
    private fun renderToolContent(result: McpToolCallResult): String {
        val parts = result.content.mapNotNull { block ->
            when (block.type) {
                "text" -> block.text
                "image" -> "[image${block.mimeType?.let { " ($it)" } ?: ""}]"
                "audio" -> "[audio${block.mimeType?.let { " ($it)" } ?: ""}]"
                "resource" -> block.resource?.toString() ?: "[resource]"
                else -> block.text ?: "[${block.type}]"
            }
        }
        val text = parts.joinToString("\n")
        if (text.isNotEmpty()) return text

        result.structuredContent?.let { return it.toString() }
        return "(empty result)"
    }

    suspend fun emitEventToAll(
        toolName: String,
        arguments: JsonObject
    ): List<ToolExecutionResult> = withContext(Dispatchers.IO) {
        // Match on the original MCP tool name, not a suffix of the qualified
        // name — endsWith("__name") also matched tools whose own names contain
        // "__".
        val matchingEntries = toolRegistry.filter { it.value.second.name == toolName }
        if (matchingEntries.isEmpty()) {
            DebugLog.log(TAG, "No servers have tool '$toolName'")
            return@withContext emptyList()
        }

        DebugLog.log(TAG, "Emitting '$toolName' to ${matchingEntries.size} server(s)")
        matchingEntries.keys.map { qualifiedName -> executeTool(qualifiedName, arguments) }
    }

    /** Abort in-flight tool calls on every connected server (conversation cancelled). */
    fun cancelActiveToolCalls() {
        clients.values.forEach { it.cancelInFlight() }
    }

    fun disconnectAll() {
        clients.values.forEach { it.disconnect() }
        clients.clear()
    }

    fun getServerStatuses(): Map<String, ServerStatus> {
        val configs = repository.loadServers()
        return configs.associate { config ->
            val client = clients[config.id]
            val toolCount = toolRegistry.count { it.value.first == config.id }
            config.id to ServerStatus(
                // A connected server with zero tools is still connected — keying
                // this off tools.isNotEmpty() mislabeled it as disconnected.
                connected = client != null,
                toolCount = toolCount,
                enabled = config.enabled
            )
        }
    }

    // ── Tool-name qualification ──

    /** Short, stable, regex-clean prefix derived from the (immutable) server id. */
    private fun serverPrefix(serverId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(serverId.toByteArray())
        return digest.take(PREFIX_LEN / 2).joinToString("") { "%02x".format(it) }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    /** "<prefix>__<sanitized tool>" capped at 64 chars, de-duplicated. */
    private fun uniqueQualifiedName(prefix: String, toolName: String, taken: Set<String>): String {
        val room = MAX_TOOL_NAME - prefix.length - TOOL_SEPARATOR.length
        val base = "$prefix$TOOL_SEPARATOR${sanitize(toolName).take(room)}"
        if (base !in taken) return base
        var n = 1
        while (true) {
            val suffix = "_$n"
            val candidate = "$prefix$TOOL_SEPARATOR${sanitize(toolName).take(room - suffix.length)}$suffix"
            if (candidate !in taken) return candidate
            n++
        }
    }

    data class ServerStatus(
        val connected: Boolean,
        val toolCount: Int,
        val enabled: Boolean
    )

    data class ToolExecutionResult(
        val content: String,
        val isError: Boolean
    )
}
