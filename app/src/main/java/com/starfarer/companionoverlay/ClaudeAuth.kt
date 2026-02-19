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

class ClaudeAuth(private val context: Context) {

    companion object {
        private const val TAG = "Auth"
        private const val OAUTH_AUTHORIZE_URL = "https://claude.ai/oauth/authorize"
        private const val OAUTH_TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
        private const val OAUTH_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        private const val OAUTH_AUTHORIZE_SCOPES = "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers"
        private const val OAUTH_REFRESH_SCOPES = "user:profile user:inference user:sessions:claude_code user:mcp_servers"
        private const val CALLBACK_PORT = 8765
        private const val REDIRECT_URI = "http://localhost:$CALLBACK_PORT/callback"
        
        private const val PREFS_NAME = "companion_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ACCOUNT_UUID = "account_uuid"

        private const val PROFILE_URL = "https://api.anthropic.com/api/oauth/profile"
        private const val OAUTH_BETA = "oauth-2025-04-20"

        // Hardcoded IP for platform.claude.com (from Hetzner DNS)
        private const val PLATFORM_CLAUDE_IP = "160.79.104.10"
    }

    private fun log(msg: String) = DebugLog.log(TAG, msg)

    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Custom DNS that uses Google DNS for lookups, with fallback to hardcoded IP
    private val customDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            log("DNS lookup for: $hostname")
            
            // For platform.claude.com, use hardcoded IP
            if (hostname == "platform.claude.com") {
                log("Using hardcoded IP $PLATFORM_CLAUDE_IP for $hostname")
                return listOf(InetAddress.getByName(PLATFORM_CLAUDE_IP))
            }
            
            // For everything else, try system DNS first, then Google DNS
            return try {
                val addresses = InetAddress.getAllByName(hostname).toList()
                log("System DNS resolved $hostname to ${addresses.map { it.hostAddress }}")
                addresses
            } catch (e: Exception) {
                log("System DNS failed for $hostname, trying Google DNS...")
                try {
                    // Use Google's DNS-over-HTTPS would be better but this is simpler
                    val addresses = Dns.SYSTEM.lookup(hostname)
                    log("Fallback resolved $hostname to ${addresses.map { it.hostAddress }}")
                    addresses
                } catch (e2: Exception) {
                    log("All DNS failed for $hostname: ${e2.message}")
                    throw e2
                }
            }
        }
    }
    
    // OkHttp client with custom DNS
    private val httpClient = OkHttpClient.Builder()
        .dns(customDns)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val prefs by lazy {
        log("Initializing EncryptedSharedPreferences...")
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            log("MasterKey created")
            val p = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            log("EncryptedSharedPreferences created successfully")
            p
        } catch (e: Exception) {
            log("ERROR creating prefs: ${e.message}")
            throw e
        }
    }

    interface AuthCallback {
        fun onAuthProgress(message: String)
        fun onAuthSuccess()
        fun onAuthFailure(error: String)
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var isWaitingForCallback = false

    private fun generateRandomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun sha256(input: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.US_ASCII))
    }

    private fun generateCodeVerifier(): String = base64UrlEncode(generateRandomBytes(32))
    private fun generateCodeChallenge(verifier: String): String = base64UrlEncode(sha256(verifier))
    private fun generateState(): String = base64UrlEncode(generateRandomBytes(32))

    private fun postProgress(callback: AuthCallback, message: String) {
        mainHandler.post { callback.onAuthProgress(message) }
    }

    private fun postSuccess(callback: AuthCallback) {
        mainHandler.post { callback.onAuthSuccess() }
    }

    private fun postFailure(callback: AuthCallback, error: String) {
        mainHandler.post { callback.onAuthFailure(error) }
    }

    suspend fun startAuthWithCallback(activityContext: Context, callback: AuthCallback) = withContext(Dispatchers.IO) {
        try {
            log("=== Starting OAuth flow ===")
            
            val codeVerifier = generateCodeVerifier()
            val codeChallenge = generateCodeChallenge(codeVerifier)
            val state = generateState()
            
            log("Generated PKCE - verifier: ${codeVerifier.take(10)}..., challenge: ${codeChallenge.take(10)}..., state: ${state.take(10)}...")

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

            log("Auth URL: $authUrl")
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

            mainHandler.post {
                log("Opening browser...")
                try {
                    val customTabsIntent = CustomTabsIntent.Builder().build()
                    customTabsIntent.launchUrl(activityContext, authUrl)
                } catch (e: Exception) {
                    log("CustomTabs failed, using Intent: ${e.message}")
                    val intent = Intent(Intent.ACTION_VIEW, authUrl)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activityContext.startActivity(intent)
                }
            }

            postProgress(callback, "Waiting for authorization...")
            log("Waiting for callback connection...")

            val socket = try {
                serverSocket?.accept()
            } catch (e: Exception) {
                log("ERROR: Accept failed: ${e.message}")
                isWaitingForCallback = false
                postFailure(callback, "Timeout or cancelled")
                return@withContext
            }
            
            if (socket == null) {
                log("ERROR: Socket is null")
                isWaitingForCallback = false
                postFailure(callback, "Server closed")
                return@withContext
            }

            log("Connection received from ${socket.inetAddress}")
            
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: ""
            log("Request line: $requestLine")

            val pathPart = requestLine.split(" ").getOrNull(1).orEmpty()
            log("Path part: $pathPart")
            
            val uri = Uri.parse("http://localhost$pathPart")
            val code = uri.getQueryParameter("code")
            val returnedState = uri.getQueryParameter("state")
            val error = uri.getQueryParameter("error")

            log("Parsed - code: ${code?.take(10)}..., state: ${returnedState?.take(10)}..., error: $error")
            log("State match: ${returnedState == state}")

            val writer = PrintWriter(socket.getOutputStream(), true)
            if (error != null || code == null || returnedState != state) {
                val errorMsg = when {
                    error != null -> "OAuth error: $error"
                    code == null -> "No code in callback"
                    returnedState != state -> "State mismatch (CSRF?)"
                    else -> "Unknown error"
                }
                log("ERROR: $errorMsg")
                writer.println("HTTP/1.1 400 Bad Request")
                writer.println("Content-Type: text/html; charset=utf-8")
                writer.println()
                writer.println("<html><body><h1>Authentication Failed</h1><p>$errorMsg</p></body></html>")
                writer.flush()
                socket.close()
                serverSocket?.close()
                serverSocket = null
                isWaitingForCallback = false
                postFailure(callback, errorMsg)
                return@withContext
            }

            log("Sending success response to browser")
            writer.println("HTTP/1.1 200 OK")
            writer.println("Content-Type: text/html; charset=utf-8")
            writer.println()
            writer.println("<html><body><h1>Success!</h1><p>You can close this tab and return to Senni~</p></body></html>")
            writer.flush()
            socket.close()
            serverSocket?.close()
            serverSocket = null
            isWaitingForCallback = false

            postProgress(callback, "Exchanging code for token...")
            log("Starting token exchange with hardcoded DNS...")
            
            val result = exchangeCodeForTokens(code, codeVerifier, state)
            
            if (result.isSuccess) {
                log("=== Auth completed successfully! ===")
                postSuccess(callback)
            } else {
                log("=== Auth failed: ${result.exceptionOrNull()?.message} ===")
                postFailure(callback, result.exceptionOrNull()?.message ?: "Token exchange failed")
            }
        } catch (e: Exception) {
            log("=== Auth exception: ${e.message} ===")
            e.printStackTrace()
            serverSocket?.close()
            serverSocket = null
            isWaitingForCallback = false
            postFailure(callback, e.message ?: "Unknown error")
        }
    }

    fun cancelAuth() {
        log("Cancelling auth")
        isWaitingForCallback = false
        try { serverSocket?.close() } catch (e: Exception) { }
        serverSocket = null
    }

    fun isWaitingForCallback(): Boolean = isWaitingForCallback

    private suspend fun exchangeCodeForTokens(code: String, codeVerifier: String, state: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            log("Token exchange URL: $OAUTH_TOKEN_URL")
            
            val body = JSONObject().apply {
                put("grant_type", "authorization_code")
                put("code", code)
                put("redirect_uri", REDIRECT_URI)
                put("client_id", OAUTH_CLIENT_ID)
                put("code_verifier", codeVerifier)
                put("state", state)
            }

            log("Token request body: $body")
            
            val request = Request.Builder()
                .url(OAUTH_TOKEN_URL)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            log("Executing OkHttp request with custom DNS...")
            val response = httpClient.newCall(request).execute()
            
            log("Response code: ${response.code}")
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                log("Token ERROR response: $errorBody")
                return@withContext Result.failure(Exception("HTTP ${response.code}: $errorBody"))
            }

            val responseBody = response.body?.string() ?: "{}"
            log("Token response (truncated): ${responseBody.take(200)}...")
            
            val json = JSONObject(responseBody)

            val accessToken = json.getString("access_token")
            val refreshToken = json.optString("refresh_token", "")
            val expiresIn = json.optLong("expires_in", 3600)
            val expiresAt = System.currentTimeMillis() + (expiresIn * 1000)

            log("Got tokens - access: ${accessToken.take(10)}..., refresh: ${refreshToken.take(10)}..., expires_in: $expiresIn")
            
            log("Saving to prefs...")
            prefs.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putLong(KEY_EXPIRES_AT, expiresAt)
                .commit()

            val savedToken = prefs.getString(KEY_ACCESS_TOKEN, null)
            val savedExpires = prefs.getLong(KEY_EXPIRES_AT, 0)
            log("Verified save - token present: ${savedToken != null}, token length: ${savedToken?.length ?: 0}, expires: $savedExpires")

            if (savedToken == null) {
                log("ERROR: Token not saved!")
                return@withContext Result.failure(Exception("Failed to save token"))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            log("Token exchange exception: ${e::class.simpleName}: ${e.message}")
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

            log("Token refreshed successfully")
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

    fun isAuthenticated(): Boolean {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        val expires = prefs.getLong(KEY_EXPIRES_AT, 0)
        log("isAuthenticated check - token: ${token != null}, expires: $expires")
        return token != null
    }
    
    fun getExpiresAt(): Long = prefs.getLong(KEY_EXPIRES_AT, 0)

    fun getOrCreateUserId(): String {
        val existing = prefs.getString(KEY_USER_ID, null)
        if (existing != null) return existing
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val userId = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_USER_ID, userId).commit()
        log("Generated new userId: ${userId.take(10)}...")
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
            } else {
                log("Profile fetch failed: ${response.code}")
            }
        } catch (e: Exception) {
            log("Profile fetch exception: ${e.message}")
        }
    }

    fun buildMetadataUserId(): String {
        val userId = getOrCreateUserId()
        val accountUuid = getAccountUuid() ?: ""
        val sessionId = UUID.randomUUID().toString()
        return "user_${userId}_account_${accountUuid}_session_${sessionId}"
    }

    fun logout() {
        log("Logging out")
        prefs.edit().clear().commit()
    }
}
