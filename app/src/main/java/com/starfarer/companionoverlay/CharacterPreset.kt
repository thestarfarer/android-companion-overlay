package com.starfarer.companionoverlay

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A character preset bundles everything that defines a companion's identity:
 * her name, how she speaks (prompts), and how she looks (sprites).
 *
 * Stored as JSON in SharedPreferences using kotlinx.serialization.
 * The overlay service reads through PromptSettings, which routes to the
 * active preset transparently.
 */
@Serializable
data class CharacterPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Senni",
    val systemPrompt: String = PromptSettings.DEFAULT_SYSTEM_PROMPT,
    val userMessage: String = PromptSettings.DEFAULT_USER_MESSAGE,
    val idleSpriteUri: String? = null,
    val walkSpriteUri: String? = null,
    val idleFrameCount: Int = PromptSettings.DEFAULT_IDLE_FRAME_COUNT,
    val walkFrameCount: Int = PromptSettings.DEFAULT_WALK_FRAME_COUNT,
    val createdAt: Long = System.currentTimeMillis()
) {

    companion object {
        private const val PREFS_NAME = "companion_prompts"
        private const val KEY_PRESETS = "character_presets"
        private const val KEY_ACTIVE_ID = "active_preset_id"

        private val json = Json { 
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private fun getPrefs(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /**
         * Load all presets. Creates a default preset if none exist.
         */
        fun loadAll(context: Context): MutableList<CharacterPreset> {
            val prefs = getPrefs(context)
            val stored = prefs.getString(KEY_PRESETS, null)
            
            if (stored == null) {
                // No presets yet — create default
                val default = createDefault(context)
                return mutableListOf(default).also { saveAll(context, it) }
            }
            
            return try {
                json.decodeFromString<List<CharacterPreset>>(stored).toMutableList()
            } catch (e: Exception) {
                DebugLog.log("Preset", "Failed to parse presets: ${e.message}")
                mutableListOf(createDefault(context))
            }
        }

        fun saveAll(context: Context, presets: List<CharacterPreset>) {
            val encoded = json.encodeToString(presets)
            getPrefs(context).edit().putString(KEY_PRESETS, encoded).apply()
        }

        fun getActiveId(context: Context): String? =
            getPrefs(context).getString(KEY_ACTIVE_ID, null)

        fun setActiveId(context: Context, id: String) =
            getPrefs(context).edit().putString(KEY_ACTIVE_ID, id).apply()

        /** Returns the active preset, falling back to first available. */
        fun getActive(context: Context): CharacterPreset {
            val all = loadAll(context)
            val activeId = getActiveId(context)
            return all.find { it.id == activeId } 
                ?: all.first().also { setActiveId(context, it.id) }
        }

        fun save(context: Context, preset: CharacterPreset) {
            val all = loadAll(context)
            val idx = all.indexOfFirst { it.id == preset.id }
            if (idx >= 0) all[idx] = preset else all.add(preset)
            saveAll(context, all)
        }

        fun delete(context: Context, presetId: String) {
            val all = loadAll(context)
            all.removeAll { it.id == presetId }
            if (all.isEmpty()) all.add(createDefault(context))
            if (getActiveId(context) == presetId) setActiveId(context, all.first().id)
            saveAll(context, all)
        }

        private fun createDefault(context: Context): CharacterPreset {
            val preset = CharacterPreset()
            setActiveId(context, preset.id)
            return preset
        }
    }
}
