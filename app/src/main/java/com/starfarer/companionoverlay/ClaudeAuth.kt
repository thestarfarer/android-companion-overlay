package com.starfarer.companionoverlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * OAuth 2.0 PKCE authentication for Claude API.
 *
 * Flow:
 * 1. Generate PKCE verifier/challenge
 * 2. Open browser to authorize URL
 * 3. Listen on localhost for callback
 * 4. Exchange code for tokens
 * 5. Store tokens in encrypted preferences
 */
class ClaudeAuth(private val context: Context) {

    companion object {
        private const val TAG = "Auth"

        // OAuth endpoints
        private const val OAUTH_AUTHORIZE_URL = "https://claude.ai/oauth/authorize"
        private const val OAUTH_TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
        private const val PROFILE_URL = "https://api.anthropic.com/api/oauth/profile"

        // OAuth client config
        private const val OAUTH_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        private const val OAUTH_AUTHORIZE_SCOPES = "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers"
        private const val OAUTH_REFRESH_SCOPES = "user:profile user:inference user:sessions:claude_code user:mcp_servers"
        private const val OAUTH_BETA = "oauth-2025-04-20"

        // Callback server
        private const val CALLBACK_PORT = 8765
        private const val REDIRECT_URI = "http://localhost:$CALLBACK_PORT/callback"

        // Storage keys
        private const val PREFS_NAME = "companion_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ACCOUNT_UUID = "account_uuid"

        // DNS fallback IPs (updated 2026-02)
        private val FALLBACK_IPS = mapOf(
            "platform.claude.com" to listOf("160.79.104.10")
        )
    }

    private fun log(msg: String) = DebugLog.log(TAG, msg)

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Custom DNS that tries system DNS first, falls back to hardcoded IPs.
     * Some networks (corporate proxies, certain ISPs) have trouble resolving
     * Anthropic's domains.
     */
    private val customDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            // Try system DNS first
            return try {
                val addresses = InetAddress.getAllByName(hostname).toList()
                log("DNS: $hostname → ${addresses.map { it.hostAddress }}")
                addresses
            } catch (e: Exception) {
                // Fall back to hardcoded IPs if available
                val fallback = FALLBACK_IPS[hostname]
                if (fallback != null) {
                    log("DNS failed for $hostname, using fallback: $fallback")
                    fallback.map { InetAddress.getByName(it) }
                } else {
                    log("DNS failed for $hostname, no fallback available")
                    throw e
                }
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .dns(customDns)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val prefs by lazy {
        log("Initializing EncryptedSharedPreferences...")
        try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            log("ERROR creating prefs: ${e.message}, deleting and retrying...")
            try { context.deleteSharedPreferences(PREFS_NAME) } catch (_: Exception) {}
            try {
                createEncryptedPrefs()
            } catch (e2: Exception) {
                log("Retry also failed: ${e2.message}, using plain prefs")
                context.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    private fun createEncryptedPrefs(): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context, PREFS_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Public interface
    // ══════════════════════════════════════════════════════════════════════

    interface AuthCallback {
        fun onAuthProgress(message: String)
        fun onAuthSuccess()
        fun onAuthFailure(error: String)
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var isWaitingForCallback = false

    fun isAuthenticated(): Boolean {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        return token != null
    }

    fun isWaitingForCallback(): Boolean = isWaitingForCallback

    fun getExpiresAt(): Long = prefs.getLong(KEY_EXPIRES_AT, 0)

    fun logout() {
        log("Logging out")
        prefs.edit().clear().commit()
    }

    fun cancelAuth() {
        log("Cancelling auth")
        isWaitingForCallback = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    // ══════════════════════════════════════════════════════════════════════
    // OAuth flow
    // ══════════════════════════════════════════════════════════════════════

    suspend fun startAuthWithCallback(activityContext: Context, callback: AuthCallback) = withContext(Dispatchers.IO) {
        try {
            log("=== Starting OAuth flow ===")

            val codeVerifier = generateCodeVerifier()
            val codeChallenge = generateCodeChallenge(codeVerifier)
            val state = generateState()

            log("Generated PKCE - verifier: ${codeVerifier.take(10)}..., state: ${state.take(10)}...")

            val authUrl = Uri.parse(OAUTH_AUTHORIZE_URL).buildUpon()
                .appendQueryParameter("code", "true")
                .appendQueryParameter("client_id", OAUTH_CLIENT_ID)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("scope", OAUTH_AUTHORIZE_SCOPES)
                .appendQueryParameter("code_challenge", codeChallenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("state", state)
                .build()

            postProgress(callback, "Starting callback server...")

            try {
                serverSocket = ServerSocket(CALLBACK_PORT)
                log("Server socket created on port $CALLBACK_PORT")
            } catch (e: Exception) {
                log("ERROR: Can't bind port $CALLBACK_PORT: ${e.message}")
                postFailure(callback, "Port $CALLBACK_PORT busy - close other apps?")
                return@withContext
            }

            serverSocket?.soTimeout = 300000
            isWaitingForCallback = true

            // Open browser
            mainHandler.post {
                log("Opening browser...")
                try {
                    CustomTabsIntent.Builder().build().launchUrl(activityContext, authUrl)
                } catch (e: Exception) {
                    log("CustomTabs failed, using Intent: ${e.message}")
                    activityContext.startActivity(Intent(Intent.ACTION_VIEW, authUrl).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }

            postProgress(callback, "Waiting for authorization...")

            // Wait for callback
            val socket = try {
                serverSocket?.accept()
            } catch (e: Exception) {
                log("ERROR: Accept failed: ${e.message}")
                isWaitingForCallback = false
                postFailure(callback, "Timeout or cancelled")
                return@withContext
            }

            if (socket == null) {
                isWaitingForCallback = false
                postFailure(callback, "Server closed")
                return@withContext
            }

            // Parse callback
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: ""
            val pathPart = requestLine.split(" ").getOrNull(1).orEmpty()
            val uri = Uri.parse("http://localhost$pathPart")

            val code = uri.getQueryParameter("code")
            val returnedState = uri.getQueryParameter("state")
            val error = uri.getQueryParameter("error")

            log("Callback - code: ${code?.take(10)}..., state match: ${returnedState == state}, error: $error")

            val writer = PrintWriter(socket.getOutputStream(), true)

            if (error != null || code == null || returnedState != state) {
                val errorMsg = when {
                    error != null -> "OAuth error: $error"
                    code == null -> "No code in callback"
                    else -> "State mismatch (CSRF?)"
                }
                writer.println("HTTP/1.1 400 Bad Request\r\nContent-Type: text/html\r\n\r\n<h1>Failed</h1><p>$errorMsg</p>")
                writer.flush()
                socket.close()
                serverSocket?.close()
                serverSocket = null
                isWaitingForCallback = false
                postFailure(callback, errorMsg)
                return@withContext
            }

            // Success response
            writer.println("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<h1>Success!</h1><p>Return to Senni~</p>")
            writer.flush()
            socket.close()
            serverSocket?.close()
            serverSocket = null
            isWaitingForCallback = false

            // Exchange code for tokens
            postProgress(callback, "Exchanging code for token...")
            val result = exchangeCodeForTokens(code, codeVerifier, state)

            if (result.isSuccess) {
                log("=== Auth completed ===")
                postSuccess(callback)
            } else {
                postFailure(callback, result.exceptionOrNull()?.message ?: "Token exchange failed")
            }

        } catch (e: Exception) {
            log("Auth exception: ${e.message}")
            serverSocket?.close()
            serverSocket = null
            isWaitingForCallback = false
            postFailure(callback, e.message ?: "Unknown error")
        }
    }

    private suspend fun exchangeCodeForTokens(code: String, codeVerifier: String, state: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("grant_type", "authorization_code")
                put("code", code)
                put("redirect_uri", REDIRECT_URI)
                put("client_id", OAUTH_CLIENT_ID)
                put("code_verifier", codeVerifier)
                put("state", state)
            }

            val request = Request.Builder()
                .url(OAUTH_TOKEN_URL)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                log("Token ERROR: $errorBody")
                return@withContext Result.failure(Exception("HTTP ${response.code}: $errorBody"))
            }

            val json = JSONObject(response.body?.string() ?: "{}")
            val accessToken = json.getString("access_token")
            val refreshToken = json.optString("refresh_token", "")
            val expiresIn = json.optLong("expires_in", 3600)
            val expiresAt = System.currentTimeMillis() + (expiresIn * 1000)

            log("Got tokens - expires_in: $expiresIn")

            prefs.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putLong(KEY_EXPIRES_AT, expiresAt)
                .commit()

            Result.success(Unit)
        } catch (e: Exception) {
            log("Token exchange exception: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun refreshToken(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
                ?: return@withContext Result.failure(Exception("No refresh token"))

            log("Refreshing token...")

            val body = JSONObject().apply {
                put("grant_type", "refresh_token")
                put("refresh_token", refreshToken)
                put("client_id", OAUTH_CLIENT_ID)
                put("scope", OAUTH_REFRESH_SCOPES)
            }

            val request = Request.Builder()
                .url(OAUTH_TOKEN_URL)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                log("Refresh failed: ${response.code}")
                return@withContext Result.failure(Exception("Refresh failed"))
            }

            val json = JSONObject(response.body?.string() ?: "{}")
            val newAccessToken = json.getString("access_token")
            val newRefreshToken = json.optString("refresh_token", refreshToken)
            val expiresIn = json.optLong("expires_in", 3600)
            val expiresAt = System.currentTimeMillis() + (expiresIn * 1000)

            prefs.edit()
                .putString(KEY_ACCESS_TOKEN, newAccessToken)
                .putString(KEY_REFRESH_TOKEN, newRefreshToken)
                .putLong(KEY_EXPIRES_AT, expiresAt)
                .commit()

            log("Token refreshed")
            Result.success(Unit)
        } catch (e: Exception) {
            log("Refresh exception: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getValidToken(): String? {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0)

        val validToken = if (System.currentTimeMillis() > expiresAt - 300000) {
            val result = refreshToken()
            if (result.isFailure) return null
            prefs.getString(KEY_ACCESS_TOKEN, null)
        } else {
            token
        }

        if (validToken != null) {
            getOrCreateUserId()
            fetchProfileIfNeeded(validToken)
        }

        return validToken
    }

    // ══════════════════════════════════════════════════════════════════════
    // User identity
    // ══════════════════════════════════════════════════════════════════════

    fun getOrCreateUserId(): String {
        val existing = prefs.getString(KEY_USER_ID, null)
        if (existing != null) return existing

        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val userId = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_USER_ID, userId).commit()
        log("Generated userId: ${userId.take(10)}...")
        return userId
    }

    fun getAccountUuid(): String? = prefs.getString(KEY_ACCOUNT_UUID, null)

    suspend fun fetchProfileIfNeeded(accessToken: String) = withContext(Dispatchers.IO) {
        if (prefs.getString(KEY_ACCOUNT_UUID, null) != null) return@withContext
        try {
            log("Fetching OAuth profile...")
            val request = Request.Builder()
                .url(PROFILE_URL)
                .header("Authorization", "Bearer $accessToken")
                .header("anthropic-beta", OAUTH_BETA)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                val uuid = json.optJSONObject("account")?.optString("uuid", "")?.ifEmpty { null }
                if (uuid != null) {
                    prefs.edit().putString(KEY_ACCOUNT_UUID, uuid).commit()
                    log("Saved accountUuid: $uuid")
                }
            }
        } catch (e: Exception) {
            log("Profile fetch failed: ${e.message}")
        }
    }

    fun buildMetadataUserId(): String {
        val userId = getOrCreateUserId()
        val accountUuid = getAccountUuid() ?: ""
        val sessionId = UUID.randomUUID().toString()
        return "user_${userId}_account_${accountUuid}_session_${sessionId}"
    }

    // ══════════════════════════════════════════════════════════════════════
    // PKCE helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun generateRandomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun sha256(input: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))

    private fun generateCodeVerifier(): String = base64UrlEncode(generateRandomBytes(32))
    private fun generateCodeChallenge(verifier: String): String = base64UrlEncode(sha256(verifier))
    private fun generateState(): String = base64UrlEncode(generateRandomBytes(32))

    // ══════════════════════════════════════════════════════════════════════
    // Callback helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun postProgress(callback: AuthCallback, message: String) =
        mainHandler.post { callback.onAuthProgress(message) }

    private fun postSuccess(callback: AuthCallback) =
        mainHandler.post { callback.onAuthSuccess() }

    private fun postFailure(callback: AuthCallback, error: String) =
        mainHandler.post { callback.onAuthFailure(error) }
}
