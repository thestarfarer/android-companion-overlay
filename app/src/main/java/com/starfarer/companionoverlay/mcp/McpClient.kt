package com.starfarer.companionoverlay.mcp

import com.starfarer.companionoverlay.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP client for a single server connection using Streamable HTTP transport.
 *
 * Handles the full lifecycle: initialization handshake, tool discovery,
 * tool execution, session management, and optional client credentials auth.
 *
 * Reuses the shared [OkHttpClient] via [newBuilder] for connection pool sharing,
 * following the same pattern as [ClaudeApi] and [ClaudeAuth].
 */
class McpClient(
    baseClient: OkHttpClient,
    private val config: McpServerConfig,
    private val secretProvider: () -> String?
) {
    companion object {
        private const val TAG = "MCP"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val httpClient = baseClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val requestIdCounter = AtomicInteger(1)
    @Volatile private var sessionId: String? = null
    @Volatile private var initialized = false

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0

    @Volatile var tools: List<McpToolDefinition> = emptyList()
        private set

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    /** Full initialization: handshake + tool discovery. Call on IO dispatcher. */
    suspend fun initialize(): Result<List<McpToolDefinition>> = withContext(Dispatchers.IO) {
        try {
            // Step 1: Send initialize request
            val initParams = json.encodeToJsonElement(McpInitializeParams())
            val initResponse = sendRequest("initialize", initParams as JsonObject)
            if (initResponse.error != null) {
                return@withContext Result.failure(
                    McpException("Initialize failed: ${initResponse.error.message}")
                )
            }

            val initResult = initResponse.result?.let {
                json.decodeFromJsonElement<McpInitializeResult>(it)
            } ?: return@withContext Result.failure(McpException("Empty initialize response"))

            DebugLog.log(TAG, "Initialized ${config.name}: " +
                "server=${initResult.serverInfo?.name}, " +
                "protocol=${initResult.protocolVersion}")

            // Step 2: Send notifications/initialized
            sendNotification("notifications/initialized")

            // Step 3: Discover tools
            val toolsResponse = sendRequest("tools/list")
            if (toolsResponse.error != null) {
                return@withContext Result.failure(
                    McpException("tools/list failed: ${toolsResponse.error.message}")
                )
            }

            val toolsResult = toolsResponse.result?.let {
                json.decodeFromJsonElement<McpToolsListResult>(it)
            } ?: McpToolsListResult()

            tools = toolsResult.tools
            initialized = true

            DebugLog.log(TAG, "Discovered ${tools.size} tools from ${config.name}: " +
                tools.joinToString { it.name })

            Result.success(tools)
        } catch (e: Exception) {
            DebugLog.log(TAG, "Initialize error for ${config.name}: ${e.message}")
            Result.failure(e)
        }
    }

    /** Execute a tool call. */
    suspend fun callTool(
        toolName: String,
        arguments: JsonObject?
    ): Result<McpToolCallResult> = withContext(Dispatchers.IO) {
        // Auto-reinitialize if session was lost
        if (!initialized) {
            DebugLog.log(TAG, "Session lost, reinitializing before tool call...")
            val reinit = initialize()
            if (reinit.isFailure) {
                return@withContext Result.failure(
                    McpException("Reinit failed: ${reinit.exceptionOrNull()?.message}")
                )
            }
        }

        try {
            val params = buildJsonObject {
                put("name", toolName)
                if (arguments != null) put("arguments", arguments)
            }

            val response = sendRequest("tools/call", params)

            // If we got a session error during the call, reinit and retry once
            if (response.error != null && response.error.code == 400 && !initialized) {
                DebugLog.log(TAG, "Session died mid-call, reinitializing and retrying...")
                val reinit = initialize()
                if (reinit.isFailure) {
                    return@withContext Result.failure(
                        McpException("Reinit failed on retry: ${reinit.exceptionOrNull()?.message}")
                    )
                }
                val retryResponse = sendRequest("tools/call", params)
                if (retryResponse.error != null) {
                    return@withContext Result.failure(
                        McpException("tools/call error after retry: ${retryResponse.error.message}")
                    )
                }
                val retryResult = retryResponse.result?.let {
                    json.decodeFromJsonElement<McpToolCallResult>(it)
                } ?: McpToolCallResult()
                return@withContext Result.success(retryResult)
            }

            if (response.error != null) {
                return@withContext Result.failure(
                    McpException("tools/call error: ${response.error.message}")
                )
            }

            val result = response.result?.let {
                json.decodeFromJsonElement<McpToolCallResult>(it)
            } ?: McpToolCallResult()

            DebugLog.log(TAG, "Tool $toolName result: " +
                "${result.content.size} content blocks, isError=${result.isError}")

            Result.success(result)
        } catch (e: Exception) {
            DebugLog.log(TAG, "Tool call error for $toolName: ${e.message}")
            Result.failure(e)
        }
    }
    fun disconnect() {
        sessionId = null
        initialized = false
        tools = emptyList()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Transport (Streamable HTTP)
    // ══════════════════════════════════════════════════════════════════════

    private fun sendRequest(
        method: String,
        params: JsonObject? = null
    ): JsonRpcResponse {
        val id = requestIdCounter.getAndIncrement()
        val rpcRequest = JsonRpcRequest(
            id = id,
            method = method,
            params = params
        )

        val bodyJson = json.encodeToString(rpcRequest)

        val requestBuilder = Request.Builder()
            .url(config.url)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")

        sessionId?.let { requestBuilder.header("Mcp-Session-Id", it) }
        getAuthHeader()?.let { requestBuilder.header("Authorization", it) }

        val request = requestBuilder.build()
        val response = httpClient.newCall(request).execute()

        // On 400 (invalid session) — clear session so next call re-initializes
        if (response.code == 400) {
            val errorBody = response.body?.string() ?: ""
            response.close()
            sessionId = null
            initialized = false
            return JsonRpcResponse(
                id = id,
                error = JsonRpcError(code = 400, message = "Session invalid: $errorBody")
            )
        }

        // On 401 — clear token and session, retry once with fresh credentials
        if (response.code == 401 && config.authType == McpAuthType.CLIENT_CREDENTIALS) {
            response.close()
            cachedToken = null
            tokenExpiresAt = 0
            sessionId = null
            initialized = false

            val freshAuth = getAuthHeader()
            if (freshAuth != null) {
                val retryBuilder = Request.Builder()
                    .url(config.url)
                    .post(bodyJson.toRequestBody("application/json".toMediaType()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("Authorization", freshAuth)
                // Don't send old session ID on retry

                val retryResponse = httpClient.newCall(retryBuilder.build()).execute()
                retryResponse.header("Mcp-Session-Id")?.let { sessionId = it }
                val retryBody = retryResponse.body?.string() ?: ""

                if (!retryResponse.isSuccessful) {
                    return JsonRpcResponse(
                        id = id,
                        error = JsonRpcError(code = retryResponse.code, message = "HTTP ${retryResponse.code}: $retryBody")
                    )
                }

                val contentType = retryResponse.header("Content-Type") ?: ""
                return if (contentType.contains("text/event-stream")) {
                    parseSseResponse(retryBody, id)
                } else {
                    json.decodeFromString<JsonRpcResponse>(retryBody)
                }
            }

            return JsonRpcResponse(
                id = id,
                error = JsonRpcError(code = 401, message = "Auth failed after retry")
            )
        }

        response.header("Mcp-Session-Id")?.let { sessionId = it }

        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            return JsonRpcResponse(
                id = id,
                error = JsonRpcError(
                    code = response.code,
                    message = "HTTP ${response.code}: $responseBody"
                )
            )
        }

        val contentType = response.header("Content-Type") ?: ""

        return if (contentType.contains("text/event-stream")) {
            parseSseResponse(responseBody, id)
        } else {
            json.decodeFromString<JsonRpcResponse>(responseBody)
        }
    }
    private fun sendNotification(method: String, params: JsonObject? = null) {
        val notification = JsonRpcNotification(method = method, params = params)
        val bodyJson = json.encodeToString(notification)

        val requestBuilder = Request.Builder()
            .url(config.url)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")

        sessionId?.let { requestBuilder.header("Mcp-Session-Id", it) }
        getAuthHeader()?.let { requestBuilder.header("Authorization", it) }

        try {
            httpClient.newCall(requestBuilder.build()).execute().close()
        } catch (e: Exception) {
            DebugLog.log(TAG, "Notification $method failed: ${e.message}")
        }
    }

    /**
     * Parse SSE stream to extract the JSON-RPC response matching our request ID.
     * SSE format: lines starting with "data: " contain JSON payloads.
     */
    private fun parseSseResponse(sseBody: String, expectedId: Int): JsonRpcResponse {
        var matched: JsonRpcResponse? = null
        var lastParsed: JsonRpcResponse? = null

        for (line in sseBody.lines()) {
            if (line.startsWith("data: ")) {
                val data = line.removePrefix("data: ").trim()
                try {
                    val parsed = json.decodeFromString<JsonRpcResponse>(data)
                    lastParsed = parsed
                    if (parsed.id == expectedId) {
                        matched = parsed
                    }
                } catch (_: Exception) { /* skip malformed lines */ }
            }
        }

        return matched
            ?: lastParsed
            ?: JsonRpcResponse(
                error = JsonRpcError(code = -1, message = "No data in SSE response")
            )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Authentication
    // ══════════════════════════════════════════════════════════════════════

    private fun getAuthHeader(): String? {
        if (config.authType != McpAuthType.CLIENT_CREDENTIALS) return null

        val now = System.currentTimeMillis()
        if (cachedToken != null && now < tokenExpiresAt - 60_000) {
            return "Bearer $cachedToken"
        }

        return fetchClientCredentialsToken()?.let { "Bearer $it" }
    }

    private fun fetchClientCredentialsToken(): String? {
        val clientId = config.clientId ?: return null
        val clientSecret = secretProvider() ?: return null

        try {
            val formBody = "grant_type=client_credentials" +
                "&client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}" +
                "&client_secret=${java.net.URLEncoder.encode(clientSecret, "UTF-8")}"

            val tokenUrl = deriveTokenUrl(config.url)

            val request = Request.Builder()
                .url(tokenUrl)
                .post(formBody.toRequestBody(
                    "application/x-www-form-urlencoded".toMediaType()
                ))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                DebugLog.log(TAG, "Token fetch failed: ${response.code}")
                return null
            }

            val tokenResponse = json.decodeFromString<OAuthTokenResponse>(
                response.body?.string() ?: ""
            )

            cachedToken = tokenResponse.accessToken
            tokenExpiresAt = System.currentTimeMillis() + (tokenResponse.expiresIn * 1000)

            DebugLog.log(TAG, "Got token for ${config.name}, expires in ${tokenResponse.expiresIn}s")
            return cachedToken
        } catch (e: Exception) {
            DebugLog.log(TAG, "Token fetch error: ${e.message}")
            return null
        }
    }

    /**
     * Derive the OAuth token endpoint from the MCP server URL.
     * Takes the base URL (scheme + authority) and appends /token.
     * e.g., "https://mcp.example.com/v1/mcp" -> "https://mcp.example.com/token"
     */
    private fun deriveTokenUrl(mcpUrl: String): String {
        val uri = java.net.URI(mcpUrl)
        return "${uri.scheme}://${uri.authority}/token"
    }
}

class McpException(message: String) : Exception(message)
