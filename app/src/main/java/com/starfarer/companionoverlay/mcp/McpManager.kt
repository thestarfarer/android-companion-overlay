package com.starfarer.companionoverlay.mcp

import com.starfarer.companionoverlay.DebugLog
import com.starfarer.companionoverlay.api.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient

/**
 * Central orchestrator for all MCP server connections.
 *
 * Manages [McpClient] instances, aggregates tool definitions from all
 * connected servers, and routes tool_use calls to the correct server.
 *
 * Tool names are qualified as `serverId__toolName` to avoid collisions
 * when multiple servers expose tools with the same name.
 */
class McpManager(
    private val baseClient: OkHttpClient,
    private val repository: McpRepository
) {
    companion object {
        private const val TAG = "McpMgr"
        private const val TOOL_SEPARATOR = "__"
    }

    // Active clients keyed by server config ID
    private val clients = java.util.concurrent.ConcurrentHashMap<String, McpClient>()

    // Aggregated tool definitions: qualified name -> (serverId, McpToolDefinition)
    private val toolRegistry = java.util.concurrent.ConcurrentHashMap<String, Pair<String, McpToolDefinition>>()

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Initialize all enabled MCP servers.
     * Call when the overlay service starts or after config changes.
     * Safe to call multiple times — disconnects stale clients first.
     */
    suspend fun initializeAll(): Map<String, Result<List<McpToolDefinition>>> =
        withContext(Dispatchers.IO) {
            disconnectAll()
            toolRegistry.clear()

            val configs = repository.loadServers().filter { it.enabled }
            if (configs.isEmpty()) {
                DebugLog.log(TAG, "No MCP servers configured")
                return@withContext emptyMap()
            }

            DebugLog.log(TAG, "Initializing ${configs.size} MCP servers...")

            val results = mutableMapOf<String, Result<List<McpToolDefinition>>>()

            for (config in configs) {
                val client = McpClient(
                    baseClient = baseClient,
                    config = config,
                    secretProvider = { repository.getClientSecret(config.id) }
                )
                clients[config.id] = client

                val result = client.initialize()
                results[config.id] = result

                if (result.isSuccess) {
                    val tools = result.getOrDefault(emptyList())
                    for (tool in tools) {
                        val qualifiedName = "${config.id}$TOOL_SEPARATOR${tool.name}"
                        toolRegistry[qualifiedName] = config.id to tool
                    }
                }
            }

            DebugLog.log(TAG, "Initialization complete: " +
                "${toolRegistry.size} tools from ${clients.size} servers")

            results
        }

    /**
     * Get all discovered MCP tools in Claude API Tool format.
     * Returns tools with qualified names so they can be routed back
     * to the correct MCP server.
     */
    fun getClaudeTools(): List<Tool> {
        return toolRegistry.map { (qualifiedName, entry) ->
            val (_, mcpTool) = entry
            Tool(
                name = qualifiedName,
                description = mcpTool.description,
                inputSchema = mcpTool.inputSchema
            )
        }
    }

    /** Check if any MCP tools are available. */
    fun hasTools(): Boolean = toolRegistry.isNotEmpty()

    /**
     * Execute a tool call from Claude's tool_use response.
     *
     * @param qualifiedToolName The tool name as returned by Claude (serverId__toolName)
     * @param arguments The tool arguments as a JSON object
     * @return Text result to send back to Claude as tool_result
     */
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
            val textContent = callResult.content
                .filter { it.type == "text" }
                .mapNotNull { it.text }
                .joinToString("\n")

            ToolExecutionResult(
                content = textContent.ifEmpty { "(empty result)" },
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
     * Call a named tool on every connected server that has it.
     * Used for broadcast-style events like Nexus session summaries.
     */
    suspend fun emitEventToAll(
        toolName: String,
        arguments: JsonObject
    ): List<ToolExecutionResult> = withContext(Dispatchers.IO) {
        val matchingEntries = toolRegistry.filter {
            it.key.endsWith("${TOOL_SEPARATOR}$toolName")
        }
        if (matchingEntries.isEmpty()) {
            DebugLog.log(TAG, "No servers have tool '$toolName'")
            return@withContext emptyList()
        }

        DebugLog.log(TAG, "Emitting '$toolName' to ${matchingEntries.size} server(s)")
        val results = mutableListOf<ToolExecutionResult>()
        for ((qualifiedName, _) in matchingEntries) {
            val result = executeTool(qualifiedName, arguments)
            results.add(result)
        }
        results
    }

    fun disconnectAll() {
        clients.values.forEach { it.disconnect() }
        clients.clear()
    }

    /** Get human-readable status for each server (for settings UI). */
    fun getServerStatuses(): Map<String, ServerStatus> {
        val configs = repository.loadServers()
        return configs.associate { config ->
            val client = clients[config.id]
            val toolCount = toolRegistry.count { it.value.first == config.id }
            config.id to ServerStatus(
                connected = client != null && client.tools.isNotEmpty(),
                toolCount = toolCount,
                enabled = config.enabled
            )
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
