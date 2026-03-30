package com.starfarer.companionoverlay.repository

import android.content.SharedPreferences
import com.starfarer.companionoverlay.CharacterPreset
import com.starfarer.companionoverlay.PromptSettings

/**
 * Repository layer for application settings.
 *
 * Wraps SharedPreferences to provide a testable, injectable interface.
 * All runtime settings access flows through this class. Default values
 * are defined in [PromptSettings], which is now a pure constants object.
 *
 * Security note: Sensitive values (API keys) are stored in [securePrefs],
 * which uses EncryptedSharedPreferences. Non-sensitive settings use [settingsPrefs].
 *
 * Conversation history is handled separately by [ConversationStorage] —
 * it was moved out of SharedPreferences because SP rewrites the entire
 * file on every apply(), and conversations with screenshots can be large.
 *
 * Injected via Koin as a singleton.
 */
class SettingsRepository(
    private val settingsPrefs: SharedPreferences,
    private val securePrefs: SharedPreferences,
    private val presetProvider: () -> CharacterPreset
) {


    // ══════════════════════════════════════════════════════════════════════
    // Character Preset (read-only here, mutations go through CharacterPreset)
    // ══════════════════════════════════════════════════════════════════════

    val systemPrompt: String get() = presetProvider().systemPrompt
    val userMessage: String get() = presetProvider().userMessage
    val idleSpriteUri: String? get() = presetProvider().idleSpriteUri
    val walkSpriteUri: String? get() = presetProvider().walkSpriteUri
    val idleFrameCount: Int get() = presetProvider().idleFrameCount
    val walkFrameCount: Int get() = presetProvider().walkFrameCount

    // ══════════════════════════════════════════════════════════════════════
    // Connection & API
    // ══════════════════════════════════════════════════════════════════════

    var claudeApiKey: String?
        get() = securePrefs.getString(KEY_CLAUDE_API_KEY, null)
        set(value) {
            val editor = securePrefs.edit()
            if (value.isNullOrBlank()) editor.remove(KEY_CLAUDE_API_KEY)
            else editor.putString(KEY_CLAUDE_API_KEY, value.trim())
            editor.apply()
        }

    var connectionType: String
        get() = settingsPrefs.getString(KEY_CONNECTION_TYPE, CONNECTION_API_KEY)
            ?: CONNECTION_API_KEY
        set(value) = settingsPrefs.edit().putString(KEY_CONNECTION_TYPE, value).apply()

    val isApiKeyMode: Boolean get() = connectionType == CONNECTION_API_KEY

    var advancedUnlocked: Boolean
        get() = settingsPrefs.getBoolean(KEY_ADVANCED_UNLOCKED, false)
        set(value) = settingsPrefs.edit().putBoolean(KEY_ADVANCED_UNLOCKED, value).apply()

    var model: String
        get() = settingsPrefs.getString(KEY_MODEL, PromptSettings.DEFAULT_MODEL)
            ?: PromptSettings.DEFAULT_MODEL
        set(value) = settingsPrefs.edit().putString(KEY_MODEL, value).apply()

    var webSearchEnabled: Boolean
        get() = settingsPrefs.getBoolean(KEY_WEB_SEARCH, false)
        set(value) = settingsPrefs.edit().putBoolean(KEY_WEB_SEARCH, value).apply()

    // ══════════════════════════════════════════════════════════════════════
    // Conversation
    // ══════════════════════════════════════════════════════════════════════

    var bubbleTimeoutSeconds: Int
        get() = settingsPrefs.getInt(KEY_BUBBLE_TIMEOUT, PromptSettings.DEFAULT_BUBBLE_TIMEOUT)
        set(value) = settingsPrefs.edit().putInt(KEY_BUBBLE_TIMEOUT, value).apply()

    var maxMessages: Int
        get() = settingsPrefs.getInt(KEY_MAX_MESSAGES, PromptSettings.DEFAULT_MAX_MESSAGES)
        set(value) = settingsPrefs.edit().putInt(KEY_MAX_MESSAGES, value).apply()

    var keepDialogue: Boolean
        get() = settingsPrefs.getBoolean(KEY_KEEP_DIALOGUE, false)
        set(value) = settingsPrefs.edit().putBoolean(KEY_KEEP_DIALOGUE, value).apply()

    // ══════════════════════════════════════════════════════════════════════
    // On-Device TTS
    // ══════════════════════════════════════════════════════════════════════

    var ttsEnabled: Boolean
        get() = settingsPrefs.getBoolean(KEY_TTS_ENABLED, true)
        set(value) = settingsPrefs.edit().putBoolean(KEY_TTS_ENABLED, value).apply()

    var ttsVoice: String?
        get() = settingsPrefs.getString(KEY_TTS_VOICE, null)
        set(value) = settingsPrefs.edit().putString(KEY_TTS_VOICE, value).apply()

    var ttsSpeechRate: Float
        get() = settingsPrefs.getFloat(KEY_TTS_SPEECH_RATE, 1.0f)
        set(value) = settingsPrefs.edit().putFloat(KEY_TTS_SPEECH_RATE, value).apply()

    var ttsPitch: Float
        get() = settingsPrefs.getFloat(KEY_TTS_PITCH, 1.0f)
        set(value) = settingsPrefs.edit().putFloat(KEY_TTS_PITCH, value).apply()

    // ══════════════════════════════════════════════════════════════════════
    // Gemini (STT & TTS) — API key stored in encrypted prefs
    // ══════════════════════════════════════════════════════════════════════

    var geminiApiKey: String?
        get() = securePrefs.getString(KEY_GEMINI_API_KEY, null)
        set(value) {
            val editor = securePrefs.edit()
            if (value.isNullOrBlank()) editor.remove(KEY_GEMINI_API_KEY)
            else editor.putString(KEY_GEMINI_API_KEY, value.trim())
            editor.apply()
        }

    var geminiSttEnabled: Boolean
        get() = settingsPrefs.getBoolean(KEY_GEMINI_STT, false)
        set(value) = settingsPrefs.edit().putBoolean(KEY_GEMINI_STT, value).apply()

    var geminiTtsEnabled: Boolean
        get() = settingsPrefs.getBoolean(KEY_GEMINI_TTS, false)
        set(value) = settingsPrefs.edit().putBoolean(KEY_GEMINI_TTS, value).apply()

    var geminiTtsVoice: String?
        get() = settingsPrefs.getString(KEY_GEMINI_TTS_VOICE, null)
        set(value) = settingsPrefs.edit().putString(KEY_GEMINI_TTS_VOICE, value).apply()

    var silenceTimeoutMs: Long
        get() = settingsPrefs.getLong(KEY_SILENCE_TIMEOUT, PromptSettings.DEFAULT_SILENCE_TIMEOUT_MS)
        set(value) = settingsPrefs.edit().putLong(KEY_SILENCE_TIMEOUT, value).apply()

    // ══════════════════════════════════════════════════════════════════════
    // Avatar
    // ══════════════════════════════════════════════════════════════════════

    var avatarX: Int
        get() = settingsPrefs.getInt(KEY_AVATAR_X, -1)
        set(value) = settingsPrefs.edit().putInt(KEY_AVATAR_X, value).apply()

    var avatarPosition: String?
        get() = settingsPrefs.getString(KEY_AVATAR_POSITION, null)
        set(value) = settingsPrefs.edit().putString(KEY_AVATAR_POSITION, value).apply()

    // ══════════════════════════════════════════════════════════════════════
    // Behavior
    // ══════════════════════════════════════════════════════════════════════

    var autoCopy: Boolean
        get() = settingsPrefs.getBoolean(KEY_AUTO_COPY, false)
        set(value) = settingsPrefs.edit().putBoolean(KEY_AUTO_COPY, value).apply()

    var volumeToggleEnabled: Boolean
        get() = settingsPrefs.getBoolean(KEY_VOLUME_TOGGLE, true)
        set(value) = settingsPrefs.edit().putBoolean(KEY_VOLUME_TOGGLE, value).apply()

    var beepsEnabled: Boolean
        get() = settingsPrefs.getBoolean(KEY_BEEPS_ENABLED, true)
        set(value) = settingsPrefs.edit().putBoolean(KEY_BEEPS_ENABLED, value).apply()

    var voiceScreenshotEnabled: Boolean
        get() = settingsPrefs.getBoolean(KEY_VOICE_SCREENSHOT, false)
        set(value) = settingsPrefs.edit().putBoolean(KEY_VOICE_SCREENSHOT, value).apply()

    // ══════════════════════════════════════════════════════════════════════
    // MCP
    // ══════════════════════════════════════════════════════════════════════

    var mcpEnabled: Boolean
        get() = settingsPrefs.getBoolean(KEY_MCP_ENABLED, false)
        set(value) = settingsPrefs.edit().putBoolean(KEY_MCP_ENABLED, value).apply()

    var mcpShowToolBubbles: Boolean
        get() = settingsPrefs.getBoolean(KEY_MCP_SHOW_TOOL_BUBBLES, true)
        set(value) = settingsPrefs.edit().putBoolean(KEY_MCP_SHOW_TOOL_BUBBLES, value).apply()

    var nexusEmitCounter: Int
        get() = settingsPrefs.getInt(KEY_NEXUS_EMIT_COUNTER, 0)
        set(value) = settingsPrefs.edit().putInt(KEY_NEXUS_EMIT_COUNTER, value).apply()

    var nexusIntegrationEnabled: Boolean
        get() = settingsPrefs.getBoolean(KEY_NEXUS_INTEGRATION, false)
        set(value) = settingsPrefs.edit().putBoolean(KEY_NEXUS_INTEGRATION, value).apply()

    var nexusContextCache: String?
        get() = settingsPrefs.getString(KEY_NEXUS_CONTEXT_CACHE, null)
        set(value) {
            val editor = settingsPrefs.edit()
            if (value == null) editor.remove(KEY_NEXUS_CONTEXT_CACHE)
            else editor.putString(KEY_NEXUS_CONTEXT_CACHE, value)
            editor.apply()
        }

    var nexusContextTimestamp: Long
        get() = settingsPrefs.getLong(KEY_NEXUS_CONTEXT_TIMESTAMP, 0)
        set(value) = settingsPrefs.edit().putLong(KEY_NEXUS_CONTEXT_TIMESTAMP, value).apply()

    var nexusContextPrompt: String
        get() = settingsPrefs.getString(KEY_NEXUS_CONTEXT_PROMPT, DEFAULT_CONTEXT_PROMPT)
            ?: DEFAULT_CONTEXT_PROMPT
        set(value) = settingsPrefs.edit().putString(KEY_NEXUS_CONTEXT_PROMPT, value).apply()

    var nexusContextAppendToPrompt: Boolean
        get() = settingsPrefs.getBoolean(KEY_NEXUS_CONTEXT_APPEND, true)
        set(value) = settingsPrefs.edit().putBoolean(KEY_NEXUS_CONTEXT_APPEND, value).apply()

    // ══════════════════════════════════════════════════════════════════════
    // Utilities
    // ══════════════════════════════════════════════════════════════════════

    fun resetToDefaults() {
        settingsPrefs.edit().clear().apply()
        // Don't clear secure prefs — that would wipe auth tokens too
    }

    var overlayMode: String
        get() = settingsPrefs.getString(KEY_OVERLAY_MODE, "sprite") ?: "sprite"
        set(value) = settingsPrefs.edit().putString(KEY_OVERLAY_MODE, value).apply()

    val is3dMode: Boolean
        get() = overlayMode == "godot_3d"

    companion object {
        const val CONNECTION_API_KEY = "api_key"
        const val CONNECTION_OAUTH = "oauth"

        private const val KEY_CLAUDE_API_KEY = "claude_api_key"
        private const val KEY_CONNECTION_TYPE = "claude_connection_type"
        private const val KEY_ADVANCED_UNLOCKED = "advanced_unlocked"
        private const val KEY_MODEL = "selected_model"
        private const val KEY_WEB_SEARCH = "web_search_enabled"
        private const val KEY_BUBBLE_TIMEOUT = "bubble_timeout"
        private const val KEY_MAX_MESSAGES = "max_messages"
        private const val KEY_KEEP_DIALOGUE = "keep_dialogue"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_TTS_SPEECH_RATE = "tts_speech_rate"
        private const val KEY_TTS_PITCH = "tts_pitch"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_STT = "gemini_stt_enabled"
        private const val KEY_GEMINI_TTS = "gemini_tts_enabled"
        private const val KEY_GEMINI_TTS_VOICE = "gemini_tts_voice"
        private const val KEY_SILENCE_TIMEOUT = "silence_timeout_ms"
        private const val KEY_AVATAR_X = "avatar_x"
        private const val KEY_AVATAR_POSITION = "avatar_position"
        private const val KEY_AUTO_COPY = "auto_copy"
        private const val KEY_VOLUME_TOGGLE = "volume_toggle_enabled"
        private const val KEY_BEEPS_ENABLED = "beeps_enabled"
        private const val KEY_VOICE_SCREENSHOT = "voice_screenshot_enabled"
        private const val KEY_MCP_ENABLED = "mcp_enabled"
        private const val KEY_MCP_SHOW_TOOL_BUBBLES = "mcp_show_tool_bubbles"
        private const val KEY_NEXUS_EMIT_COUNTER = "nexus_emit_counter"
        private const val KEY_NEXUS_INTEGRATION = "nexus_integration_enabled"
        private const val KEY_NEXUS_CONTEXT_CACHE = "nexus_context_cache"
        private const val KEY_NEXUS_CONTEXT_TIMESTAMP = "nexus_context_timestamp"
        private const val KEY_NEXUS_CONTEXT_APPEND = "nexus_context_append_to_prompt"
        private const val KEY_NEXUS_CONTEXT_PROMPT = "nexus_context_prompt"
        private const val DEFAULT_CONTEXT_PROMPT = "What happened recently? What are we up to? What should I know?"
        private const val KEY_OVERLAY_MODE = "overlay_mode"
    }
}
