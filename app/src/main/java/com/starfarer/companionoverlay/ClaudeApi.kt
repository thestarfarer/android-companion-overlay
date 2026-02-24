package com.starfarer.companionoverlay

import com.starfarer.companionoverlay.api.*
import com.starfarer.companionoverlay.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    /** Cancel any in-flight API request. Safe to call from any thread. */
    fun cancelPending() {
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
        webSearch: Boolean = false
    ): ApiResponse = withContext(Dispatchers.IO) {
        try {
            val token = auth.getValidToken()
                ?: return@withContext ApiResponse.error("Not authenticated")

            val model = settings.model
            DebugLog.log(TAG, "Sending conversation (${messages.size} messages, model=$model)...")

            val firstUserText = messages.firstOrNull { it.role == "user" }
                ?.content?.textContent() ?: ""
            val billingHeader = ClaudeBilling.computeHeader(firstUserText)

            val request = buildRequest(token, messages, systemPrompt, model, webSearch, billingHeader)

            DebugLog.log(TAG, "Executing request...")
            val call = httpClient.newCall(request)
            activeCall.set(call)
            val response = call.execute()
            activeCall.compareAndSet(call, null)

            DebugLog.log(TAG, "Response code: ${response.code}")

            val responseBody = response.body?.string() ?: ""
            DebugLog.log(TAG, "Response body: ${responseBody.take(500)}")

            if (!response.isSuccessful) {
                return@withContext ApiResponse.error("API error ${response.code}: $responseBody")
            }

            val claudeResponse = json.decodeFromString<ClaudeResponse>(responseBody)
            val responseText = claudeResponse.text()
            DebugLog.log(TAG, "Response text: ${responseText.take(100)}...")

            ApiResponse.success(responseText)

        } catch (e: CancellationException) {
            DebugLog.log(TAG, "Request cancelled")
            activeCall.set(null)
            throw e
        } catch (e: java.io.IOException) {
            activeCall.set(null)
            handleIoException(e)
        } catch (e: Exception) {
            activeCall.set(null)
            DebugLog.log(TAG, "API exception: ${e::class.simpleName}: ${e.message}")
            ApiResponse.error(e.message ?: "Unknown error")
        }
    }

    /** Convenience: single-turn text chat */
    suspend fun chat(userMessage: String, systemPrompt: String? = null): ApiResponse {
        return sendConversation(listOf(textMessage(userMessage)), systemPrompt)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Request Building
    // ══════════════════════════════════════════════════════════════════════

    private fun buildRequest(
        token: String,
        messages: List<Message>,
        systemPrompt: String?,
        model: String,
        webSearch: Boolean,
        billingHeader: String
    ): Request {
        val systemBlocks = buildList {
            add(SystemBlock(text = billingHeader))
            add(SystemBlock(text = IDENTITY_AGENT))
            if (systemPrompt != null) {
                add(SystemBlock(text = systemPrompt))
            }
        }

        val tools = if (webSearch) listOf(
            Tool(type = "web_search_20250305", name = "web_search", maxUses = 5)
        ) else null

        val requestBody = ClaudeRequest(
            model = model,
            maxTokens = if (webSearch) 4096 else 512,
            system = systemBlocks,
            messages = messages,
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
        val cancelled: Boolean = false
    ) {
        companion object {
            fun success(text: String) = ApiResponse(true, text)
            fun error(message: String) = ApiResponse(false, "", message)
            fun cancelled() = ApiResponse(false, "", "Cancelled", cancelled = true)
        }
    }
}
