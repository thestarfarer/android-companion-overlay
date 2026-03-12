package com.starfarer.companionoverlay.mcp

import android.content.SharedPreferences
import com.starfarer.companionoverlay.DebugLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistence for MCP server configurations.
 *
 * Server configs (name, URL, auth type, client ID) are stored in regular
 * SharedPreferences as a JSON list, following the same pattern as
 * [PresetRepository].
 *
 * Client secrets are stored separately in EncryptedSharedPreferences,
 * keyed by server ID.
 */
class McpRepository(
    private val settingsPrefs: SharedPreferences,
    private val securePrefs: SharedPreferences
) {
    companion object {
        private const val TAG = "McpRepo"
        private const val KEY_SERVERS = "mcp_servers"
        private const val SECRET_KEY_PREFIX = "mcp_secret_"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cachedServers: List<McpServerConfig>? = null

    fun loadServers(): List<McpServerConfig> {
        cachedServers?.let { return it }

        val stored = settingsPrefs.getString(KEY_SERVERS, null)
            ?: return emptyList<McpServerConfig>().also { cachedServers = it }

        return try {
            json.decodeFromString<List<McpServerConfig>>(stored)
                .also { cachedServers = it }
        } catch (e: Exception) {
            DebugLog.log(TAG, "Failed to parse MCP servers: ${e.message}")
            emptyList<McpServerConfig>().also { cachedServers = it }
        }
    }

    fun saveServers(servers: List<McpServerConfig>) {
        val encoded = json.encodeToString(servers)
        settingsPrefs.edit().putString(KEY_SERVERS, encoded).apply()
        cachedServers = servers.toList()
    }

    fun addServer(config: McpServerConfig, clientSecret: String? = null) {
        val servers = loadServers().toMutableList()
        servers.add(config)
        saveServers(servers)
        if (clientSecret != null) {
            setClientSecret(config.id, clientSecret)
        }
    }

    fun updateServer(config: McpServerConfig, clientSecret: String? = null) {
        val servers = loadServers().toMutableList()
        val idx = servers.indexOfFirst { it.id == config.id }
        if (idx >= 0) servers[idx] = config else servers.add(config)
        saveServers(servers)
        if (clientSecret != null) {
            setClientSecret(config.id, clientSecret)
        }
    }

    fun removeServer(serverId: String) {
        val servers = loadServers().filter { it.id != serverId }
        saveServers(servers)
        removeClientSecret(serverId)
    }

    fun getClientSecret(serverId: String): String? {
        return securePrefs.getString("$SECRET_KEY_PREFIX$serverId", null)
    }

    fun setClientSecret(serverId: String, secret: String) {
        securePrefs.edit()
            .putString("$SECRET_KEY_PREFIX$serverId", secret)
            .apply()
    }

    private fun removeClientSecret(serverId: String) {
        securePrefs.edit()
            .remove("$SECRET_KEY_PREFIX$serverId")
            .apply()
    }

    fun invalidateCache() {
        cachedServers = null
    }
}
