package com.starfarer.companionoverlay

import com.starfarer.companionoverlay.api.ClaudeBilling
import com.starfarer.companionoverlay.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Claude API client for sending messages and managing conversations.
 *
 * Handles:
 * - Building API requests with proper headers and billing
 * - Sending single and multi-turn conversations
 * - Response parsing and error handling
 * - Request cancellation
 *
 * @param auth Authentication manager for obtaining valid tokens
 * @param settings Settings repository for model configuration
 */
class ClaudeApi(
    private val auth: ClaudeAuth,
    private val settings: SettingsRepository
) {

    companion object {
        private const val TAG = "API"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private const val OAUTH_BETA = "oauth-2025-04-20"
        private const val IDENTITY_AGENT = "You are a Claude agent, built on Anthropic's Claude Agent SDK."
        private const val USER_AGENT = "claude-cli/2.1.45 (external, cli)"

        const val DEFAULT_MODEL = "claude-sonnet-4-5-20250929"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeCall: Call? = null

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    /** Cancel any in-flight API request. Safe to call from any thread. */
    fun cancelPending() {
        activeCall?.let { call ->
            if (!call.isCanceled()) {
                DebugLog.log(TAG, "Cancelling in-flight request")
                call.cancel()
            }
        }
        activeCall = null
    }

    /**
     * Send a multi-turn conversation to Claude.
     *
     * @param messagesArray Pre-built JSONArray of {role, content} messages
     * @param systemPrompt Optional system prompt
     * @param webSearch Enable web search tool
     * @return API response with success/failure status and text or error
     */
    suspend fun sendConversation(
        messagesArray: JSONArray,
        systemPrompt: String? = null,
        webSearch: Boolean = false
    ): ApiResponse = withContext(Dispatchers.IO) {
        try {
            val token = auth.getValidToken()
                ?: return@withContext ApiResponse.error("Not authenticated")

            val model = settings.model
            DebugLog.log(TAG, "Sending conversation (${messagesArray.length()} messages, model=$model)...")

            val billingHeader = ClaudeBilling.computeHeader(extractFirstUserText(messagesArray))
            val body = buildRequestBody(messagesArray, systemPrompt, model, webSearch, billingHeader)

            DebugLog.log(TAG, "Request body length: ${body.toString().length}")

            val request = buildRequest(token, body)

            DebugLog.log(TAG, "Executing request...")
            val call = httpClient.newCall(request)
            activeCall = call
            val response = call.execute()
            activeCall = null

            DebugLog.log(TAG, "Response code: ${response.code}")

            val responseBody = response.body?.string() ?: ""
            DebugLog.log(TAG, "Response body: ${responseBody.take(500)}")

            if (!response.isSuccessful) {
                return@withContext ApiResponse.error("API error ${response.code}: $responseBody")
            }

            val responseText = parseResponseText(responseBody)
            DebugLog.log(TAG, "Response text: ${responseText.take(100)}...")

            ApiResponse.success(responseText)

        } catch (e: CancellationException) {
            DebugLog.log(TAG, "Request cancelled")
            activeCall = null
            throw e
        } catch (e: java.io.IOException) {
            activeCall = null
            handleIoException(e)
        } catch (e: Exception) {
            activeCall = null
            DebugLog.log(TAG, "API exception: ${e::class.simpleName}: ${e.message}")
            ApiResponse.error(e.message ?: "Unknown error")
        }
    }

    /** Convenience: single-turn text chat */
    suspend fun chat(userMessage: String, systemPrompt: String? = null): ApiResponse {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }
        return sendConversation(messages, systemPrompt)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Request Building
    // ══════════════════════════════════════════════════════════════════════

    private fun buildRequestBody(
        messages: JSONArray,
        systemPrompt: String?,
        model: String,
        webSearch: Boolean,
        billingHeader: String
    ): JSONObject {
        val systemArray = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", billingHeader))
            put(JSONObject().put("type", "text").put("text", IDENTITY_AGENT))
            if (systemPrompt != null) {
                put(JSONObject().put("type", "text").put("text", systemPrompt))
            }
        }

        return JSONObject().apply {
            put("model", model)
            put("max_tokens", if (webSearch) 4096 else 512)
            put("stream", false)
            put("system", systemArray)
            put("messages", messages)
            put("metadata", JSONObject().put("user_id", auth.buildMetadataUserId()))
            if (webSearch) {
                put("tools", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "web_search_20250305")
                        put("name", "web_search")
                        put("max_uses", 5)
                    })
                })
            }
        }
    }

    private fun buildRequest(token: String, body: JSONObject): Request {
        return Request.Builder()
            .url(API_URL)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("anthropic-version", API_VERSION)
            .header("Authorization", "Bearer $token")
            .header("anthropic-beta", OAUTH_BETA)
            .header("User-Agent", USER_AGENT)
            .header("x-app", "cli")
            .build()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Response Parsing
    // ══════════════════════════════════════════════════════════════════════

    private fun parseResponseText(responseBody: String): String {
        val json = JSONObject(responseBody)
        val content = json.getJSONArray("content")
        val textBuilder = StringBuilder()

        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.getString("type") == "text") {
                textBuilder.append(block.getString("text"))
            }
        }

        return textBuilder.toString()
    }

    private fun extractFirstUserText(messages: JSONArray): String {
        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            if (msg.getString("role") == "user") {
                val content = msg.get("content")
                return when (content) {
                    is String -> content
                    is JSONArray -> {
                        for (j in 0 until content.length()) {
                            val block = content.getJSONObject(j)
                            if (block.optString("type") == "text") {
                                return block.getString("text")
                            }
                        }
                        ""
                    }
                    else -> ""
                }
            }
        }
        return ""
    }

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
