package com.starfarer.companionoverlay.repository

import android.content.SharedPreferences
import com.starfarer.companionoverlay.CharacterPreset
import com.starfarer.companionoverlay.PromptSettings

/**
 * Repository layer for application settings.
 *
 * Wraps SharedPreferences to provide a testable, injectable interface.
 * All settings access should flow through this class rather than
 * calling PromptSettings directly from business logic.
 *
 * Security note: Sensitive values (API keys) are stored in [securePrefs],
 * which uses EncryptedSharedPreferences. Non-sensitive settings use [settingsPrefs].
 *
 * Injected via Koin as a singleton.
 */
class SettingsRepository(
    private val settingsPrefs: SharedPreferences,
    private val securePrefs: SharedPreferences,
    private val presetProvider: () -> CharacterPreset
) {

    init {
        migrateInsecureKeys()
    }

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
    // Model & API
    // ══════════════════════════════════════════════════════════════════════

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

    var conversationHistory: String?
        get() = settingsPrefs.getString(KEY_CONVERSATION_HISTORY, null)
        set(value) {
            val editor = settingsPrefs.edit()
            if (value == null) editor.remove(KEY_CONVERSATION_HISTORY)
            else editor.putString(KEY_CONVERSATION_HISTORY, value)
            editor.apply()
        }

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
    // Utilities
    // ══════════════════════════════════════════════════════════════════════

    fun resetToDefaults() {
        settingsPrefs.edit().clear().apply()
        // Don't clear secure prefs — that would wipe auth tokens too
    }

    // ══════════════════════════════════════════════════════════════════════
    // Migration
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Migrate sensitive keys from plaintext storage to encrypted storage.
     * Runs once on init; idempotent if already migrated or nothing to migrate.
     */
    private fun migrateInsecureKeys() {
        // Check for Gemini API key in old location
        val oldKey = settingsPrefs.getString(KEY_GEMINI_API_KEY, null)
        if (oldKey != null && securePrefs.getString(KEY_GEMINI_API_KEY, null) == null) {
            // Move to encrypted storage
            securePrefs.edit().putString(KEY_GEMINI_API_KEY, oldKey).apply()
            // Wipe from plaintext storage
            settingsPrefs.edit().remove(KEY_GEMINI_API_KEY).apply()
        }
    }

    companion object {
        private const val KEY_MODEL = "selected_model"
        private const val KEY_WEB_SEARCH = "web_search_enabled"
        private const val KEY_BUBBLE_TIMEOUT = "bubble_timeout"
        private const val KEY_MAX_MESSAGES = "max_messages"
        private const val KEY_KEEP_DIALOGUE = "keep_dialogue"
        private const val KEY_CONVERSATION_HISTORY = "conversation_history"
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
    }
}
