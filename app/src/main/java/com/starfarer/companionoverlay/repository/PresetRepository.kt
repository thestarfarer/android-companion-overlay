package com.starfarer.companionoverlay.repository

import android.content.SharedPreferences
import com.starfarer.companionoverlay.CharacterPreset
import com.starfarer.companionoverlay.DebugLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Repository for character preset persistence.
 *
 * Replaces the static companion methods on [CharacterPreset], giving us
 * proper lifecycle, testability, and thread safety. The old pattern passed
 * Context through every call and held mutable global caches in @Volatile
 * fields — correct by accident rather than by design. Here the cache is
 * instance state, naturally scoped to the repository's lifetime.
 *
 * Injected via Koin as a singleton.
 */
class PresetRepository(
    private val prefs: SharedPreferences
) {

    companion object {
        private const val TAG = "PresetRepo"
        private const val KEY_PRESETS = "character_presets"
        private const val KEY_ACTIVE_ID = "active_preset_id"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ── In-memory cache ──────────────────────────────────────────────────

    @Volatile
    private var cachedPresets: List<CharacterPreset>? = null

    @Volatile
    private var cachedActiveId: String? = null

    /** Force the next read to go through SharedPreferences. */
    fun invalidateCache() {
        cachedPresets = null
        cachedActiveId = null
    }

    // ── Read ─────────────────────────────────────────────────────────────

    /**
     * Load all presets. Creates a default preset if none exist.
     * Returns from cache if available; hits disk only on first call
     * or after [invalidateCache].
     *
     * Returns an immutable list. Mutations go through [save] and [delete].
     */
    fun loadAll(): List<CharacterPreset> {
        cachedPresets?.let { return it }

        val stored = prefs.getString(KEY_PRESETS, null)

        if (stored == null) {
            val default = createDefault()
            val list = listOf(default)
            saveAll(list)
            cachedPresets = list
            return list
        }

        return try {
            val list = json.decodeFromString<List<CharacterPreset>>(stored)
            cachedPresets = list
            list
        } catch (e: Exception) {
            DebugLog.log(TAG, "Failed to parse presets: ${e.message}")
            listOf(createDefault())
        }
    }

    fun getActiveId(): String? {
        cachedActiveId?.let { return it }
        return prefs.getString(KEY_ACTIVE_ID, null).also {
            cachedActiveId = it
        }
    }

    /** Returns the active preset, falling back to first available. */
    fun getActive(): CharacterPreset {
        val all = loadAll()
        val activeId = getActiveId()
        return all.find { it.id == activeId }
            ?: all.first().also { setActiveId(it.id) }
    }

    // ── Write ────────────────────────────────────────────────────────────

    fun saveAll(presets: List<CharacterPreset>) {
        val encoded = json.encodeToString(presets)
        prefs.edit().putString(KEY_PRESETS, encoded).apply()
        cachedPresets = presets.toList()
    }

    fun setActiveId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
        cachedActiveId = id
    }

    fun save(preset: CharacterPreset) {
        val all = loadAll().toMutableList()
        val idx = all.indexOfFirst { it.id == preset.id }
        if (idx >= 0) all[idx] = preset else all.add(preset)
        saveAll(all)
    }

    fun delete(presetId: String) {
        val remaining = loadAll().filter { it.id != presetId }.toMutableList()
        if (remaining.isEmpty()) remaining.add(createDefault())
        if (getActiveId() == presetId) setActiveId(remaining.first().id)
        saveAll(remaining)
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private fun createDefault(): CharacterPreset {
        val preset = CharacterPreset()
        setActiveId(preset.id)
        return preset
    }
}
