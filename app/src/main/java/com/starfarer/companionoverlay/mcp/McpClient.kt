package com.starfarer.companionoverlay.mcp

import com.starfarer.companionoverlay.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
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
        private const val PROTOCOL_VERSION = "2025-03-26"
        private const val SSE_MAX_EVENTS = 1000
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
    @Volatile private var negotiatedProtocol: String = PROTOCOL_VERSION

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0

    // Serializes the initialize handshake against concurrent callTool reinits —
    // two tool calls hitting !initialized at once used to interleave handshakes
    // on the shared sessionId/tools state.
    private val initMutex = Mutex()

    // Current in-flight HTTP call, so an external cancel can abort the blocking
    // execute() (and the server-side tool effect) instead of waiting out the
    // 120s read timeout.
    @Volatile private var activeCall: Call? = null

    @Volatile var tools: List<McpToolDefinition> = emptyList()
        private set

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    /** Full initialization: handshake + tool discovery. Call on IO dispatcher. */
    suspend fun initialize(): Result<List<McpToolDefinition>> = withContext(Dispatchers.IO) {
        initMutex.withLock { initializeLocked() }
    }

    private fun initializeLocked(): Result<List<McpToolDefinition>> {
        try {
            // A fresh handshake must not carry a stale session id from a prior
            // partial init — the server would reject it.
            sessionId = null
            initialized = false

            // Step 1: Send initialize request
            val initParams = json.encodeToJsonElement(McpInitializeParams())
            val initResponse = sendRequest("initialize", initParams as JsonObject)
            if (initResponse.error != null) {
                return Result.failure(
                    McpException("Initialize failed: ${initResponse.error.message}")
                )
            }

            val initResult = initResponse.result?.let {
                json.decodeFromJsonElement<McpInitializeResult>(it)
            } ?: return Result.failure(McpException("Empty initialize response"))

            negotiatedProtocol = initResult.protocolVersion.ifBlank { PROTOCOL_VERSION }

            DebugLog.log(TAG, "Initialized ${config.name}: " +
                "server=${initResult.serverInfo?.name}, " +
                "protocol=${initResult.protocolVersion}")

            // Step 2: Send notifications/initialized
            sendNotification("notifications/initialized")

            // Step 3: Discover tools
            val toolsResponse = sendRequest("tools/list")
            if (toolsResponse.error != null) {
                return Result.failure(
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

            return Result.success(tools)
        } catch (e: Exception) {
            DebugLog.log(TAG, "Initialize error for ${config.name}: ${e.message}")
            return Result.failure(e)
        }
    }

    /** Execute a tool call. */
    suspend fun callTool(
        toolName: String,
        arguments: JsonObject?
    ): Result<McpToolCallResult> = withContext(Dispatchers.IO) {
        // Auto-reinitialize if session was lost (mutex-guarded; double-checked
        // so concurrent callers don't each run a handshake).
        if (!initialized) {
            DebugLog.log(TAG, "Session lost, reinitializing before tool call...")
            val reinit = initMutex.withLock {
                if (initialized) Result.success(tools) else initializeLocked()
            }
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

            var response = sendRequest("tools/call", params)

            // Session expired mid-call (cleared by sendRequest on 404/400) —
            // re-handshake and retry once.
            if (response.error != null && !initialized) {
                DebugLog.log(TAG, "Session died mid-call, reinitializing and retrying...")
                val reinit = initMutex.withLock {
                    if (initialized) Result.success(tools) else initializeLocked()
                }
                if (reinit.isFailure) {
                    return@withContext Result.failure(
                        McpException("Reinit failed on retry: ${reinit.exceptionOrNull()?.message}")
                    )
                }
                response = sendRequest("tools/call", params)
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

    /** Abort any in-flight HTTP call (user cancelled the conversation). */
    fun cancelInFlight() {
        activeCall?.cancel()
    }

    fun disconnect() {
        val sid = sessionId
        sessionId = null
        initialized = false
        tools = emptyList()
        activeCall?.cancel()
        activeCall = null
        // Per spec, terminate the session server-side. Best-effort, async — a
        // blocking DELETE on every reconnect would add latency for nothing.
        if (sid != null) {
            val request = Request.Builder()
                .url(config.url)
                .delete()
                .header("Mcp-Session-Id", sid)
                .header("MCP-Protocol-Version", negotiatedProtocol)
                .apply { getAuthHeader()?.let { header("Authorization", it) } }
                .build()
            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Transport (Streamable HTTP)
    // ══════════════════════════════════════════════════════════════════════

    private fun sendRequest(
        method: String,
        params: JsonObject? = null,
        allowAuthRetry: Boolean = true
    ): JsonRpcResponse {
        val id = requestIdCounter.getAndIncrement()
        val rpcRequest = JsonRpcRequest(id = id, method = method, params = params)
        val bodyJson = json.encodeToString(rpcRequest)

        val requestBuilder = Request.Builder()
            .url(config.url)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", negotiatedProtocol)

        sessionId?.let { requestBuilder.header("Mcp-Session-Id", it) }
        getAuthHeader()?.let { requestBuilder.header("Authorization", it) }

        val call = httpClient.newCall(requestBuilder.build())
        activeCall = call
        val response = try {
            call.execute()
        } finally {
            if (activeCall === call) activeCall = null
        }

        response.use {
            response.header("Mcp-Session-Id")?.let { sessionId = it }

            // 404/400 with a session header in play = expired/invalid session.
            // The spec mandates a 404 for a terminated session and that the
            // client start a new one; treat both the same — clear and signal
            // the caller to re-handshake.
            if (response.code == 404 || response.code == 400) {
                val body = response.body?.string() ?: ""
                sessionId = null
                initialized = false
                return JsonRpcResponse(
                    id = id,
                    error = JsonRpcError(
                        code = response.code,
                        message = "Session invalid (HTTP ${response.code}): $body"
                    )
                )
            }

            // 401 — refresh the bearer and retry once. Keep the session: only
            // the token expired, not the MCP session.
            if (response.code == 401 && config.authType == McpAuthType.CLIENT_CREDENTIALS) {
                cachedToken = null
                tokenExpiresAt = 0
                if (allowAuthRetry) {
                    DebugLog.log(TAG, "401 — refreshing token and retrying")
                    return sendRequest(method, params, allowAuthRetry = false)
                }
                return JsonRpcResponse(
                    id = id,
                    error = JsonRpcError(code = 401, message = "Auth failed after retry")
                )
            }

            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                return JsonRpcResponse(
                    id = id,
                    // Negative code marks a transport (HTTP) error, distinct from
                    // a JSON-RPC error.code the server actually sent.
                    error = JsonRpcError(code = -response.code, message = "HTTP ${response.code}: $body")
                )
            }

            val contentType = response.header("Content-Type") ?: ""
            return if (contentType.contains("text/event-stream")) {
                parseSseStream(response, id)
            } else {
                val body = response.body?.string() ?: ""
                runCatching { json.decodeFromString<JsonRpcResponse>(body) }.getOrElse {
                    JsonRpcResponse(id = id, error = JsonRpcError(code = -1, message = "Malformed response: ${it.message}"))
                }
            }
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
            .header("MCP-Protocol-Version", negotiatedProtocol)

        sessionId?.let { requestBuilder.header("Mcp-Session-Id", it) }
        getAuthHeader()?.let { requestBuilder.header("Authorization", it) }

        try {
            httpClient.newCall(requestBuilder.build()).execute().close()
        } catch (e: Exception) {
            DebugLog.log(TAG, "Notification $method failed: ${e.message}")
        }
    }

    /**
     * Read the SSE stream line by line and return as soon as the JSON-RPC
     * response with our id arrives — the server may hold the stream open
     * afterward, and reading it whole used to block until the 120s timeout.
     * Multi-line `data:` fields are joined per the SSE spec.
     */
    private fun parseSseStream(response: Response, expectedId: Int): JsonRpcResponse {
        val source = response.body?.source()
            ?: return JsonRpcResponse(id = expectedId, error = JsonRpcError(code = -1, message = "Empty SSE body"))

        var lastParsed: JsonRpcResponse? = null
        val data = StringBuilder()
        var events = 0

        fun flush(): JsonRpcResponse? {
            if (data.isEmpty()) return null
            val payload = data.toString()
            data.clear()
            val parsed = runCatching { json.decodeFromString<JsonRpcResponse>(payload) }.getOrNull()
            if (parsed != null) {
                lastParsed = parsed
                if (parsed.id == expectedId) return parsed
            }
            return null
        }

        while (events < SSE_MAX_EVENTS) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    // Event boundary — dispatch.
                    flush()?.let { return it }
                    events++
                }
                line.startsWith("data:") -> {
                    val chunk = line.substring(5).removePrefix(" ")
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(chunk)
                }
                // Other SSE fields (event:, id:, retry:, comments) ignored.
            }
        }
        flush()?.let { return it }

        return lastParsed ?: JsonRpcResponse(
            id = expectedId,
            error = JsonRpcError(code = -1, message = "No matching response in SSE stream")
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

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
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
            }
        } catch (e: Exception) {
            DebugLog.log(TAG, "Token fetch error: ${e.message}")
            return null
        }
    }

    /**
     * Derive the OAuth token endpoint from the MCP server URL.
     * Takes the base URL (scheme + authority) and appends /token.
     * e.g., "https://mcp.example.com/v1/mcp" -> "https://mcp.example.com/token"
     *
     * KNOWN WEAK POINT (accepted): no OAuth metadata discovery — the endpoint
     * is assumed to live at /token on the server's authority. Contract with
     * the server.
     */
    private fun deriveTokenUrl(mcpUrl: String): String {
        val uri = java.net.URI(mcpUrl)
        return "${uri.scheme}://${uri.authority}/token"
    }
}

class McpException(message: String) : Exception(message)
