package com.starfarer.companionoverlay.gateway

import com.starfarer.companionoverlay.DebugLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Local cache of the Nexus-mandated avatar sprites (PRESENCE_PROTOCOL.md §7).
 *
 * `welcome` carries `avatar_version`; on mismatch with the cached version the
 * host calls [syncIfNeeded], which fetches `GET /avatar/manifest`, downloads
 * only files whose sha256 differs, verifies hashes, and swaps the cache
 * directory atomically (tmp dir + rename). Endpoints ship bundled sprites as
 * the first-boot default and render from this cache indefinitely when Nexus
 * is unreachable — presence never depends on connectivity.
 *
 * Layout: `<baseDir>/manifest.json` + the sprite sheet files it names.
 * Frame counts come from the manifest, never from compile-time constants.
 *
 * Pure JVM (files + OkHttp) so it is unit-testable off-device. All methods
 * are blocking — call from Dispatchers.IO.
 */
class AvatarRepository(
    private val baseDir: File,
    private val httpClient: OkHttpClient,
    private val config: GatewayConfig,
) {
    companion object {
        private const val TAG = "Avatar"
        private const val MANIFEST_FILE = "manifest.json"
        private const val MAX_ASSET_BYTES = 20L * 1024 * 1024
    }

    data class Sprite(val file: File, val frames: Int)

    private val lock = Any()

    init {
        // Crash-recovery: a swap that renamed the live dir away but died
        // before renaming the new one in leaves only "<base>.old" behind.
        val old = siblingDir(".old")
        if (!baseDir.exists() && old.isDirectory) {
            old.renameTo(baseDir)
            DebugLog.log(TAG, "Recovered avatar cache from interrupted swap")
        }
    }

    /** Version of the cached sprite set, or null when no valid cache exists. */
    fun cachedVersion(): String? = synchronized(lock) {
        readManifest()?.str("version")
    }

    /**
     * Cached sprite sheet by animation name ("idle"/"walk"), or null when the
     * cache has no (valid) entry — callers then fall back to bundled assets.
     */
    fun sprite(name: String): Sprite? = synchronized(lock) {
        val manifest = readManifest() ?: return null
        val entry = (manifest["sprites"] as? JsonObject)?.get(name) as? JsonObject ?: return null
        val fileName = entry.str("file") ?: return null
        val frames = (entry["frames"] as? JsonPrimitive)?.intOrNull ?: return null
        if (frames <= 0) return null
        val file = File(baseDir, File(fileName).name)
        if (!file.isFile || file.length() == 0L) return null
        Sprite(file, frames)
    }

    /**
     * Bring the cache up to [serverVersion]. No-op (false) when the version
     * already matches, the server is unconfigured, or the sync fails — the
     * old cache stays intact in every failure path. Returns true when the
     * cache changed and sprites should be reloaded.
     */
    fun syncIfNeeded(serverVersion: String?): Boolean {
        if (serverVersion.isNullOrBlank()) return false
        if (cachedVersion() == serverVersion) return false
        val base = config.serverUrl?.takeIf { it.isNotBlank() } ?: return false
        val httpBase = GatewayClient.normalizeBaseUrl(base)

        return try {
            doSync(httpBase, serverVersion)
        } catch (e: Exception) {
            DebugLog.log(TAG, "Avatar sync failed: ${e.message}")
            false
        }
    }

    private fun doSync(httpBase: String, expectedVersion: String): Boolean {
        val manifestRaw = fetch("$httpBase/avatar/manifest")
            ?: return false
        val manifest = Json.parseToJsonElement(manifestRaw.decodeToString()).jsonObject
        val version = manifest.str("version")
        if (version == null) {
            DebugLog.log(TAG, "Manifest missing version — aborting sync")
            return false
        }
        if (version != expectedVersion) {
            // Server restarted between welcome and now — the manifest is still
            // authoritative; sync to what it actually serves.
            DebugLog.log(TAG, "Manifest version $version != welcome $expectedVersion — using manifest")
        }
        if (cachedVersion() == version) return false

        val sprites = manifest["sprites"] as? JsonObject ?: return false
        val tmpDir = siblingDir(".tmp")
        tmpDir.deleteRecursively()
        if (!tmpDir.mkdirs()) return false

        try {
            for ((anim, entryEl) in sprites) {
                val entry = entryEl as? JsonObject ?: continue
                val fileName = File(entry.str("file") ?: continue).name
                val wantSha = entry.str("sha256") ?: ""

                val cached = File(baseDir, fileName)
                val target = File(tmpDir, fileName)
                if (cached.isFile && wantSha.isNotEmpty() && sha256(cached.readBytes()) == wantSha) {
                    cached.copyTo(target)  // unchanged — reuse local bytes
                    continue
                }

                val bytes = fetch("$httpBase/avatar/asset/$fileName")
                    ?: throw IllegalStateException("download failed for $fileName")
                if (wantSha.isNotEmpty() && sha256(bytes) != wantSha) {
                    throw IllegalStateException("sha256 mismatch for $fileName")
                }
                target.writeBytes(bytes)
                DebugLog.log(TAG, "Downloaded $anim sheet: $fileName (${bytes.size / 1024}K)")
            }

            File(tmpDir, MANIFEST_FILE).writeBytes(manifestRaw)

            synchronized(lock) {
                val oldDir = siblingDir(".old")
                oldDir.deleteRecursively()
                if (baseDir.exists() && !baseDir.renameTo(oldDir)) {
                    throw IllegalStateException("could not move old cache aside")
                }
                if (!tmpDir.renameTo(baseDir)) {
                    // Roll back so we're never left without a cache dir.
                    oldDir.renameTo(baseDir)
                    throw IllegalStateException("could not activate new cache")
                }
                oldDir.deleteRecursively()
            }
            DebugLog.log(TAG, "Avatar cache updated to $version")
            return true
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private fun fetch(url: String): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.token ?: ""}")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                DebugLog.log(TAG, "GET $url -> ${response.code}")
                return null
            }
            val body = response.body ?: return null
            if (body.contentLength() > MAX_ASSET_BYTES) return null
            return body.bytes()
        }
    }

    private fun readManifest(): JsonObject? {
        val file = File(baseDir, MANIFEST_FILE)
        if (!file.isFile) return null
        return try {
            Json.parseToJsonElement(file.readText()).jsonObject
        } catch (e: Exception) {
            DebugLog.log(TAG, "Corrupt cached manifest: ${e.message}")
            null
        }
    }

    private fun siblingDir(suffix: String) = File(baseDir.parentFile, baseDir.name + suffix)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
}
