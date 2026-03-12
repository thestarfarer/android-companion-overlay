package com.starfarer.companionoverlay.mcp

import kotlinx.serialization.*
import kotlinx.serialization.json.*

// ══════════════════════════════════════════════════════════════════════════
// JSON-RPC 2.0 Base Types
// ══════════════════════════════════════════════════════════════════════════

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JsonObject? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

@Serializable
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonObject? = null
)

// ══════════════════════════════════════════════════════════════════════════
// MCP Initialize
// ══════════════════════════════════════════════════════════════════════════

@Serializable
data class McpInitializeParams(
    val protocolVersion: String = "2025-03-26",
    val capabilities: McpClientCapabilities = McpClientCapabilities(),
    val clientInfo: McpClientInfo = McpClientInfo()
)

@Serializable
data class McpClientCapabilities(
    val roots: JsonObject? = null
)

@Serializable
data class McpClientInfo(
    val name: String = "companion-overlay",
    val version: String = "1.0"
)

@Serializable
data class McpInitializeResult(
    val protocolVersion: String,
    val capabilities: JsonObject = JsonObject(emptyMap()),
    val serverInfo: McpServerInfo? = null
)

@Serializable
data class McpServerInfo(
    val name: String,
    val version: String? = null
)

// ══════════════════════════════════════════════════════════════════════════
// MCP Tools
// ══════════════════════════════════════════════════════════════════════════

@Serializable
data class McpToolsListResult(
    val tools: List<McpToolDefinition> = emptyList()
)

@Serializable
data class McpToolDefinition(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject? = null
)

@Serializable
data class McpToolCallResult(
    val content: List<McpToolContent> = emptyList(),
    val isError: Boolean? = null
)

@Serializable
data class McpToolContent(
    val type: String,
    val text: String? = null,
    val data: String? = null,
    val mimeType: String? = null
)

// ══════════════════════════════════════════════════════════════════════════
// MCP Server Configuration (persisted)
// ══════════════════════════════════════════════════════════════════════════

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val authType: McpAuthType = McpAuthType.NONE,
    val clientId: String? = null,
    val enabled: Boolean = true
)

@Serializable
enum class McpAuthType {
    @SerialName("none") NONE,
    @SerialName("client_credentials") CLIENT_CREDENTIALS
}

// ══════════════════════════════════════════════════════════════════════════
// OAuth Token Response (client_credentials flow)
// ══════════════════════════════════════════════════════════════════════════

@Serializable
data class OAuthTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long = 3600
)
