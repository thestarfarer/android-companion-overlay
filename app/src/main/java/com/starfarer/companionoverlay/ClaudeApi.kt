package com.starfarer.companionoverlay

import com.starfarer.companionoverlay.api.*
import com.starfarer.companionoverlay.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Claude API client for sending messages and managing conversations.
 *
 * Handles:
 * - Building API requests with proper headers and billing
 * - Sending single and multi-turn conversations
 * - Response parsing and error handling
 * - Request cancellation
 *
 * Request and response bodies use the typed models in [com.starfarer.companionoverlay.api]
 * with kotlinx.serialization. No more manual JSONObject construction.
 *
 * The shared [OkHttpClient] is injected via Koin. API-specific timeouts
 * are applied via [OkHttpClient.newBuilder], which shares the parent's
 * connection pool and thread pool.
 *
 * @param baseClient Shared OkHttpClient from DI — connection pool reused
 * @param auth Authentication manager for obtaining valid tokens
 * @param settings Settings repository for model configuration
 */
class ClaudeApi(
    baseClient: OkHttpClient,
    private val auth: ClaudeAuth,
    private val settings: SettingsRepository
) {

    companion object {
        private const val TAG = "API"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private const val OAUTH_BETA = "oauth-2025-04-20"
        private const val IDENTITY_AGENT = "You are a Claude agent, built on Anthropic's Claude Agent SDK."
        private val USER_AGENT = "claude-cli/${ClaudeBilling.CLIENT_VERSION} (external, cli)"

        /** Transient statuses worth an automatic retry with backoff. */
        private val RETRYABLE_CODES = setOf(429, 500, 502, 503, 504, 529)
        private const val MAX_RETRIES = 2
        /** Bound on seamless pause_turn resumes (server-side web-search loops). */
        private const val MAX_CONTINUATIONS = 5
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val httpClient = baseClient.newBuilder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val activeCall = AtomicReference<Call?>(null)

    // Lets cancelPending() abort a retry/resume loop that is BETWEEN HTTP
    // calls (sleeping on backoff, or about to re-issue a pause_turn resume) —
    // cancelling the OkHttp Call alone can't reach those gaps.
    @Volatile private var cancelRequested = false

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    /** Cancel any in-flight API request. Safe to call from any thread. */
    fun cancelPending() {
        cancelRequested = true
        activeCall.getAndSet(null)?.let { call ->
            if (!call.isCanceled()) {
                DebugLog.log(TAG, "Cancelling in-flight request")
                call.cancel()
            }
        }
    }

    /**
     * Send a multi-turn conversation to Claude.
     *
     * @param messages Typed message list (text and multimodal)
     * @param systemPrompt Optional system prompt
     * @param webSearch Enable web search tool
     * @return API response with success/failure status and text or error
     */
    suspend fun sendConversation(
        messages: List<Message>,
        systemPrompt: String? = null,
        webSearch: Boolean = false,
        mcpTools: List<Tool> = emptyList()
    ): ApiResponse = withContext(Dispatchers.IO) {
        try {
            cancelRequested = false
            val model = settings.model

            val baseRequest = if (settings.isApiKeyMode) {
                val apiKey = settings.claudeApiKey
                if (apiKey.isNullOrBlank()) {
                    return@withContext ApiResponse.error("Claude API key not set")
                }
                DebugLog.log(TAG, "Sending conversation (${messages.size} messages, model=$model, mode=api_key)...")
                buildApiKeyRequest(apiKey, messages, systemPrompt, model, webSearch, mcpTools)
            } else {
                val token = auth.getValidToken()
                    ?: return@withContext ApiResponse.error("Not authenticated")
                DebugLog.log(TAG, "Sending conversation (${messages.size} messages, model=$model, mode=oauth)...")
                val firstUserText = messages.firstOrNull { it.role == "user" }
                    ?.content?.textContent() ?: ""
                val billingHeader = ClaudeBilling.computeHeader(firstUserText)
                buildOAuthRequest(token, messages, systemPrompt, model, webSearch, billingHeader, mcpTools)
            }

            var request = baseRequest
            var continuations = 0
            // A server-side web-search loop can park mid-turn (stop_reason
            // "pause_turn"); the turn is resumed seamlessly below. Text and raw
            // content blocks from paused rounds accumulate across resumes.
            val priorText = StringBuilder()
            val pausedContent = mutableListOf<JsonElement>()

            while (true) {
                DebugLog.log(TAG, "Executing request...")
                val result = executeWithRetry(request)
                if (result.cancelled) return@withContext ApiResponse.cancelled()

                DebugLog.log(TAG, "Response code: ${result.code}")
                DebugLog.log(TAG, "Response body: ${result.body.take(500)}")

                if (result.code == 401) {
                    // Distinct surface — a dead credential isn't a transient
                    // failure, and the fix differs per auth mode.
                    val hint = if (settings.isApiKeyMode)
                        "Claude API key rejected — check it in Settings"
                    else
                        "Claude login expired — sign in again in Settings"
                    return@withContext ApiResponse.error(hint)
                }
                if (result.code !in 200..299) {
                    return@withContext ApiResponse.error(apiErrorMessage(result.code, result.body))
                }

                val claudeResponse = json.decodeFromString<ClaudeResponse>(result.body)

                if (claudeResponse.stopReason == "pause_turn" && continuations < MAX_CONTINUATIONS) {
                    continuations++
                    DebugLog.log(TAG, "pause_turn — resuming ($continuations/$MAX_CONTINUATIONS)")
                    priorText.append(claudeResponse.text())
                    val rawContent = json.parseToJsonElement(result.body)
                        .jsonObject["content"]?.jsonArray ?: JsonArray(emptyList())
                    pausedContent.addAll(rawContent)
                    request = buildResumeRequest(baseRequest, pausedContent)
                    continue
                }

                var responseText = priorText.toString() + claudeResponse.text()
                if (claudeResponse.stopReason == "max_tokens") {
                    // The reply hit the output cap mid-generation — mark it so a
                    // cut-off sentence doesn't masquerade as a complete reply.
                    DebugLog.log(TAG, "Reply truncated by max_tokens cap")
                    responseText += "…"
                }
                DebugLog.log(TAG, "Response text: ${responseText.take(100)}...")

                return@withContext ApiResponse.success(responseText, claudeResponse)
            }
            @Suppress("UNREACHABLE_CODE")
            ApiResponse.error("unreachable")

        } catch (e: CancellationException) {
            DebugLog.log(TAG, "Request cancelled")
            throw e
        } catch (e: java.io.IOException) {
            handleIoException(e)
        } catch (e: Exception) {
            DebugLog.log(TAG, "API exception: ${e::class.simpleName}: ${e.message}")
            ApiResponse.error(e.message ?: "Unknown error")
        }
    }

    /**
     * One logical request with bounded retry on transient failures. Honors
     * Retry-After when present, exponential backoff (1s, 2s) otherwise.
     * activeCall is cleared per attempt with compareAndSet — the old
     * unconditional set(null) on failure paths could wipe a NEWER request's
     * call reference, making it uncancellable.
     */
    private suspend fun executeWithRetry(request: Request): HttpResult {
        var attempt = 0
        while (true) {
            if (cancelRequested) return HttpResult(0, "", cancelled = true)

            val call = httpClient.newCall(request)
            activeCall.set(call)
            val code: Int
            val body: String
            val retryAfterHeader: String?
            try {
                val response = call.execute()
                body = response.body?.string() ?: ""
                code = response.code
                retryAfterHeader = response.header("retry-after")
            } finally {
                activeCall.compareAndSet(call, null)
            }

            if (code in RETRYABLE_CODES && attempt < MAX_RETRIES) {
                attempt++
                val delayMs = (retryAfterHeader?.toLongOrNull()?.times(1000))
                    ?.coerceAtMost(30_000)
                    ?: (1000L shl (attempt - 1))
                DebugLog.log(TAG, "HTTP $code — retrying in ${delayMs}ms (attempt $attempt/$MAX_RETRIES)")
                delay(delayMs)
                continue
            }
            return HttpResult(code, body)
        }
    }

    private data class HttpResult(val code: Int, val body: String, val cancelled: Boolean = false)

    /**
     * Re-issue [base] with the paused turn's content appended as ONE assistant
     * message. Raw JSON splice on purpose: the typed models drop server-tool
     * fields the API needs back verbatim, and consecutive paused rounds must
     * merge into a single assistant message (roles have to alternate).
     */
    private fun buildResumeRequest(base: Request, pausedContent: List<JsonElement>): Request {
        val buffer = okio.Buffer()
        base.body?.writeTo(buffer)
        val bodyObj = json.parseToJsonElement(buffer.readUtf8()).jsonObject

        val baseMessages = bodyObj["messages"]?.jsonArray ?: JsonArray(emptyList())
        val resumedMessages = JsonArray(baseMessages + buildJsonObject {
            put("role", "assistant")
            put("content", JsonArray(pausedContent))
        })
        val newBody = JsonObject(bodyObj.toMutableMap().apply { put("messages", resumedMessages) })

        return base.newBuilder()
            .post(newBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    /** Prefer the API's own error message over dumping the raw body at the user. */
    private fun apiErrorMessage(code: Int, body: String): String {
        val apiMessage = runCatching {
            json.decodeFromString<ClaudeResponse>(body).error?.message
        }.getOrNull()
        return "API error $code: ${apiMessage ?: body.take(200)}"
    }

    /** Convenience: single-turn text chat */
    suspend fun chat(userMessage: String, systemPrompt: String? = null): ApiResponse {
        return sendConversation(listOf(textMessage(userMessage)), systemPrompt)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Request Building
    // ══════════════════════════════════════════════════════════════════════

    private fun buildApiKeyRequest(
        apiKey: String,
        messages: List<Message>,
        systemPrompt: String?,
        model: String,
        webSearch: Boolean,
        mcpTools: List<Tool> = emptyList()
    ): Request {
        val systemBlocks = if (systemPrompt != null) {
            listOf(SystemBlock(text = systemPrompt))
        } else {
            emptyList()
        }

        val tools = buildList {
            if (webSearch) add(Tool(type = "web_search_20260209", name = "web_search", maxUses = 5))
            addAll(mcpTools)
        }.ifEmpty { null }

        val hasTools = webSearch || mcpTools.isNotEmpty()
        val cleanMessages = messages.map { it.copy(timestamp = 0L) }
        val requestBody = ClaudeRequest(
            model = model,
            maxTokens = if (hasTools) 4096 else 512,
            system = systemBlocks,
            messages = cleanMessages,
            tools = tools
        )

        val bodyJson = json.encodeToString(requestBody)
        DebugLog.log(TAG, "Request body length: ${bodyJson.length}")

        return Request.Builder()
            .url(API_URL)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("anthropic-version", API_VERSION)
            .header("x-api-key", apiKey)
            .build()
    }

    private fun buildOAuthRequest(
        token: String,
        messages: List<Message>,
        systemPrompt: String?,
        model: String,
        webSearch: Boolean,
        billingHeader: String,
        mcpTools: List<Tool> = emptyList()
    ): Request {
        val systemBlocks = buildList {
            add(SystemBlock(text = billingHeader))
            add(SystemBlock(text = IDENTITY_AGENT))
            if (systemPrompt != null) {
                add(SystemBlock(text = systemPrompt))
            }
        }

        val tools = buildList {
            if (webSearch) add(Tool(type = "web_search_20260209", name = "web_search", maxUses = 5))
            addAll(mcpTools)
        }.ifEmpty { null }

        val hasTools = webSearch || mcpTools.isNotEmpty()
        val cleanMessages = messages.map { it.copy(timestamp = 0L) }
        val requestBody = ClaudeRequest(
            model = model,
            maxTokens = if (hasTools) 4096 else 512,
            system = systemBlocks,
            messages = cleanMessages,
            metadata = RequestMetadata(userId = auth.buildMetadataUserId()),
            tools = tools
        )

        val bodyJson = json.encodeToString(requestBody)
        DebugLog.log(TAG, "Request body length: ${bodyJson.length}")

        return Request.Builder()
            .url(API_URL)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("anthropic-version", API_VERSION)
            .header("Authorization", "Bearer $token")
            .header("anthropic-beta", OAUTH_BETA)
            .header("User-Agent", USER_AGENT)
            .header("x-app", "cli")
            .header("X-Claude-Code-Session-Id", auth.getOrCreateSessionId())
            .build()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Error Handling
    // ══════════════════════════════════════════════════════════════════════

    private fun handleIoException(e: java.io.IOException): ApiResponse {
        return if (e.message?.contains("Canceled", ignoreCase = true) == true) {
            DebugLog.log(TAG, "Request cancelled (HTTP)")
            ApiResponse.cancelled()
        } else {
            DebugLog.log(TAG, "API IO exception: ${e.message}")
            ApiResponse.error(e.message ?: "Network error")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Response Model
    // ══════════════════════════════════════════════════════════════════════

    data class ApiResponse(
        val success: Boolean,
        val text: String,
        val error: String? = null,
        val cancelled: Boolean = false,
        val fullResponse: ClaudeResponse? = null
    ) {
        companion object {
            fun success(text: String, response: ClaudeResponse? = null) =
                ApiResponse(true, text, fullResponse = response)
            fun error(message: String) = ApiResponse(false, "", message)
            fun cancelled() = ApiResponse(false, "", "Cancelled", cancelled = true)
        }
    }
}
