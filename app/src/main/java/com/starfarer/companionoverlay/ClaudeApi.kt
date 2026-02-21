package com.starfarer.companionoverlay

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ClaudeApi(private val auth: ClaudeAuth) {

    companion object {
        private const val TAG = "API"

        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private const val OAUTH_BETA = "oauth-2025-04-20"
        const val DEFAULT_MODEL = "claude-sonnet-4-5-20250929"
        private const val IDENTITY_AGENT = "You are a Claude agent, built on Anthropic's Claude Agent SDK."
        private const val BILLING_SALT = "59cf53e54c78"
        private const val BILLING_VERSION = "2.1.45"
        private const val USER_AGENT = "claude-cli/2.1.45 (external, cli)"

        private fun computeBillingHeader(messages: JSONArray): String {
            var text = ""
            for (i in 0 until messages.length()) {
                val msg = messages.getJSONObject(i)
                if (msg.getString("role") == "user") {
                    val content = msg.get("content")
                    text = when (content) {
                        is String -> content
                        is JSONArray -> {
                            var found = ""
                            for (j in 0 until content.length()) {
                                val block = content.getJSONObject(j)
                                if (block.optString("type") == "text") {
                                    found = block.getString("text")
                                    break
                                }
                            }
                            found
                        }
                        else -> ""
                    }
                    break
                }
            }
            val c4 = if (text.length > 4) text[4] else '0'
            val c7 = if (text.length > 7) text[7] else '0'
            val c20 = if (text.length > 20) text[20] else '0'
            val hash = MessageDigest.getInstance("SHA-256")
                .digest("$BILLING_SALT$c4$c7$c20$BILLING_VERSION".toByteArray())
                .joinToString("") { "%02x".format(it) }
            return "x-anthropic-billing-header: cc_version=$BILLING_VERSION.${hash.take(3)}; cc_entrypoint=cli; cch=00000;"
        }
    }

    var model: String = DEFAULT_MODEL

    private fun log(msg: String) = DebugLog.log(TAG, msg)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeCall: Call? = null

    /** Cancel any in-flight API request. Safe to call from any thread. */
    fun cancelPending() {
        activeCall?.let { call ->
            if (!call.isCanceled()) {
                log("Cancelling in-flight request")
                call.cancel()
            }
        }
        activeCall = null
    }

    data class ApiResponse(
        val success: Boolean,
        val text: String,
        val error: String? = null
    )

    /**
     * Send a multi-turn conversation to Claude.
     * @param messagesArray Pre-built JSONArray of {role, content} messages
     * @param systemPrompt Optional system prompt
     */
    suspend fun sendConversation(messagesArray: JSONArray, systemPrompt: String? = null, webSearch: Boolean = false): ApiResponse = withContext(Dispatchers.IO) {
        try {
            val token = auth.getValidToken()
            if (token == null) {
                log("No valid token!")
                return@withContext ApiResponse(false, "", "Not authenticated")
            }

            log("Sending conversation (${messagesArray.length()} messages)...")

            // Build system array: billing block, identity block, then user prompt
            val systemArray = JSONArray()
            systemArray.put(JSONObject().apply {
                put("type", "text")
                put("text", computeBillingHeader(messagesArray))
            })
            systemArray.put(JSONObject().apply {
                put("type", "text")
                put("text", IDENTITY_AGENT)
            })
            if (systemPrompt != null) {
                systemArray.put(JSONObject().apply {
                    put("type", "text")
                    put("text", systemPrompt)
                })
            }

            // Build request body
            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", if (webSearch) 4096 else 512)
                put("stream", false)
                put("system", systemArray)
                put("messages", messagesArray)
                put("metadata", JSONObject().apply {
                    put("user_id", auth.buildMetadataUserId())
                })
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

            log("Request body length: ${body.toString().length}")

            val request = Request.Builder()
                .url(API_URL)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .header("anthropic-version", API_VERSION)
                .header("Authorization", "Bearer $token")
                .header("anthropic-beta", OAUTH_BETA)
                .header("User-Agent", USER_AGENT)
                .header("x-app", "cli")
                .build()

            log("Executing request...")
            val call = httpClient.newCall(request)
            activeCall = call
            val response = call.execute()
            activeCall = null

            log("Response code: ${response.code}")

            val responseBody = response.body?.string() ?: ""
            log("Response body: ${responseBody.take(500)}")

            if (!response.isSuccessful) {
                return@withContext ApiResponse(false, "", "API error ${response.code}: $responseBody")
            }

            val json = JSONObject(responseBody)
            val content = json.getJSONArray("content")
            val textBuilder = StringBuilder()
            
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.getString("type") == "text") {
                    textBuilder.append(block.getString("text"))
                }
            }

            val responseText = textBuilder.toString()
            log("Response text: ${responseText.take(100)}...")

            ApiResponse(true, responseText)
        } catch (e: CancellationException) {
            log("Request cancelled")
            activeCall = null
            throw e  // Re-throw so coroutine machinery handles it
        } catch (e: java.io.IOException) {
            activeCall = null
            if (e.message?.contains("Canceled") == true || e.message?.contains("canceled") == true) {
                log("Request cancelled (HTTP)")
                // Return a silent cancellation — not a real error
                ApiResponse(false, "", "Cancelled")
            } else {
                log("API IO exception: ${e.message}")
                ApiResponse(false, "", e.message ?: "Network error")
            }
        } catch (e: Exception) {
            activeCall = null
            log("API exception: ${e::class.simpleName}: ${e.message}")
            ApiResponse(false, "", e.message ?: "Unknown error")
        }
    }

    /** Convenience: single-turn text chat */
    suspend fun chat(userMessage: String, systemPrompt: String? = null): ApiResponse {
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userMessage)
        })
        return sendConversation(messages, systemPrompt)
    }
}
