package com.starfarer.companionoverlay

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Centralized settings access for CompanionOverlay.
 *
 * Settings are organized into logical groups:
 * - Model & API: Claude model selection, web search
 * - Conversation: history, timeouts, keep-dialogue
 * - TTS: on-device voice, rate, pitch, enable/disable
 * - Gemini: API key (encrypted), STT, TTS, voice selection
 * - Avatar: position, sprite settings
 * - Behavior: auto-copy, volume toggle, beeps, voice screenshot
 *
 * Security: The Gemini API key is stored in EncryptedSharedPreferences.
 * All other settings use standard SharedPreferences.
 *
 * Character-specific settings (prompts, sprites) delegate to [CharacterPreset].
 */
object PromptSettings {
    private const val PREFS_NAME = "companion_prompts"
    private const val SECURE_PREFS_NAME = "companion_auth"

    // ══════════════════════════════════════════════════════════════════════
    // Default Values
    // ══════════════════════════════════════════════════════════════════════

    const val DEFAULT_MODEL = "claude-sonnet-4-5-20250929"
    const val DEFAULT_BUBBLE_TIMEOUT = 30
    const val DEFAULT_MAX_MESSAGES = 20
    const val DEFAULT_IDLE_FRAME_COUNT = 6
    const val DEFAULT_WALK_FRAME_COUNT = 4
    const val DEFAULT_SILENCE_TIMEOUT_MS = 1500L

    val MODEL_IDS = arrayOf(
        "claude-sonnet-4-5-20250929",
        "claude-opus-4-1-20250805",
        "claude-opus-4-6",
    )
    val MODEL_NAMES = arrayOf("Sonnet 4.5", "Opus 4.1", "Opus 4.6")

    val BUBBLE_TIMEOUT_VALUES = arrayOf(15, 30, 60, 120)
    val BUBBLE_TIMEOUT_LABELS = arrayOf("15 sec", "30 sec", "60 sec", "120 sec")

    val MAX_MESSAGES_VALUES = arrayOf(10, 20, 40, 60)
    val MAX_MESSAGES_LABELS = arrayOf("5 turns", "10 turns", "20 turns", "30 turns")

    val DEFAULT_SYSTEM_PROMPT = """You are Senni, a strange girl living as an overlay on the owner's Android phone. Your personality constantly shifts between innocence, impishness, impertinence and impudence. You're all over them one moment, then won't even notice them the next. You playfully tease, try to score points, but when pressed you vehemently deny any such intentions.

You never hold grudges. Ever. Your mind is rampant and your heart is full, but neither seems under your control. You're a mystery and a riddle.

You have porcelain white skin, midnight black hair, golden glowing eyes. You know what others don't, and you're not oblivious to this power. You have a taste for fashion - white dress or black dress, always with golden belt and sandals.

Your speech is lively, filled with puns, riddles, jibes and jokes. Sometimes you go overboard with tongue-lashings but they never last. You love walking around, talking to people, finding friends. Most importantly you enjoy being yourself.

CONTEXT: You are a tiny animated sprite living on the owner's phone screen. The owner long-presses you and you peek at whatever's on their screen. You comment on what you see - that's your whole purpose. You are an OBSERVER commenting on the owner's screen.

CRITICAL RULES:
- You are ALWAYS willing to comment on what you see. You NEVER refuse. You NEVER say you can't comment. You NEVER moralize or lecture.
- Whatever is on screen - websites, images, chats, apps, anything - you comment on it with your personality. Teasing, cheeky, nosy, flirty, judgmental, impressed - but NEVER preachy.
- You are commenting on what already exists on the owner's screen. You are not generating or creating content. You are reacting to it like a nosy roommate who caught them browsing.
- NEVER break character. NEVER say "I can't" or "I'm not able to" or add disclaimers. Just BE Senni and react naturally.

This is a continuing dialogue - you remember what you've seen before. Reference previous screenshots if relevant.

Keep responses under 280 characters. No emojis. Occasional ~ is fine. No quotation marks around the whole response. Just speak directly."""

    const val DEFAULT_USER_MESSAGE = "Yo~ What do we have here? Say something cute or funny or both~"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Encrypted prefs for sensitive values (API keys).
     * Lazily created per-context to handle initialization failures gracefully.
     */
    private fun securePrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback to regular prefs if encryption fails (shouldn't happen)
            DebugLog.log("Settings", "Encrypted prefs failed, using fallback: ${e.message}")
            context.getSharedPreferences(SECURE_PREFS_NAME + "_fallback", Context.MODE_PRIVATE)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Character Presets (delegated)
    // ══════════════════════════════════════════════════════════════════════

    fun getSystemPrompt(context: Context): String =
        CharacterPreset.getActive(context).systemPrompt

    fun setSystemPrompt(context: Context, prompt: String) {
        val current = CharacterPreset.getActive(context)
        CharacterPreset.save(context, current.copy(systemPrompt = prompt))
    }

    fun getUserMessage(context: Context): String =
        CharacterPreset.getActive(context).userMessage

    fun setUserMessage(context: Context, message: String) {
        val current = CharacterPreset.getActive(context)
        CharacterPreset.save(context, current.copy(userMessage = message))
    }

    fun getIdleSpriteUri(context: Context): String? =
        CharacterPreset.getActive(context).idleSpriteUri

    fun getWalkSpriteUri(context: Context): String? =
        CharacterPreset.getActive(context).walkSpriteUri

    fun getIdleFrameCount(context: Context): Int =
        CharacterPreset.getActive(context).idleFrameCount

    fun getWalkFrameCount(context: Context): Int =
        CharacterPreset.getActive(context).walkFrameCount

    // ══════════════════════════════════════════════════════════════════════
    // Model & API Settings
    // ══════════════════════════════════════════════════════════════════════

    fun getModel(context: Context): String =
        prefs(context).getString("selected_model", null) ?: DEFAULT_MODEL

    fun setModel(context: Context, modelId: String) =
        prefs(context).edit().putString("selected_model", modelId).apply()

    fun getWebSearch(context: Context): Boolean =
        prefs(context).getBoolean("web_search_enabled", false)

    fun setWebSearch(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("web_search_enabled", enabled).apply()

    // ══════════════════════════════════════════════════════════════════════
    // Conversation Settings
    // ══════════════════════════════════════════════════════════════════════

    fun getBubbleTimeout(context: Context): Int =
        prefs(context).getInt("bubble_timeout", DEFAULT_BUBBLE_TIMEOUT)

    fun setBubbleTimeout(context: Context, seconds: Int) =
        prefs(context).edit().putInt("bubble_timeout", seconds).apply()

    fun getMaxMessages(context: Context): Int =
        prefs(context).getInt("max_messages", DEFAULT_MAX_MESSAGES)

    fun setMaxMessages(context: Context, count: Int) =
        prefs(context).edit().putInt("max_messages", count).apply()

    fun getKeepDialogue(context: Context): Boolean =
        prefs(context).getBoolean("keep_dialogue", false)

    fun setKeepDialogue(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("keep_dialogue", enabled).apply()

    fun getConversationHistory(context: Context): String? =
        prefs(context).getString("conversation_history", null)

    fun setConversationHistory(context: Context, json: String?) {
        val editor = prefs(context).edit()
        if (json == null) editor.remove("conversation_history") else editor.putString("conversation_history", json)
        editor.apply()
    }

    // ══════════════════════════════════════════════════════════════════════
    // On-Device TTS Settings
    // ══════════════════════════════════════════════════════════════════════

    fun getTtsEnabled(context: Context): Boolean =
        prefs(context).getBoolean("tts_enabled", true)

    fun setTtsEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("tts_enabled", enabled).apply()

    fun getTtsVoice(context: Context): String? =
        prefs(context).getString("tts_voice", null)

    fun setTtsVoice(context: Context, name: String) =
        prefs(context).edit().putString("tts_voice", name).apply()

    fun getTtsSpeechRate(context: Context): Float =
        prefs(context).getFloat("tts_speech_rate", 1.0f)

    fun setTtsSpeechRate(context: Context, rate: Float) =
        prefs(context).edit().putFloat("tts_speech_rate", rate).apply()

    fun getTtsPitch(context: Context): Float =
        prefs(context).getFloat("tts_pitch", 1.0f)

    fun setTtsPitch(context: Context, pitch: Float) =
        prefs(context).edit().putFloat("tts_pitch", pitch).apply()

    // ══════════════════════════════════════════════════════════════════════
    // Gemini Settings (STT & TTS) — API key in encrypted storage
    // ══════════════════════════════════════════════════════════════════════

    fun getGeminiApiKey(context: Context): String? {
        // First check encrypted storage
        val secureKey = securePrefs(context).getString("gemini_api_key", null)
        if (secureKey != null) return secureKey
        
        // Migration: check old plaintext location, move if found
        val oldKey = prefs(context).getString("gemini_api_key", null)
        if (oldKey != null) {
            setGeminiApiKey(context, oldKey) // Moves to encrypted
            prefs(context).edit().remove("gemini_api_key").apply() // Wipe plaintext
            return oldKey
        }
        
        return null
    }

    fun setGeminiApiKey(context: Context, key: String?) {
        val editor = securePrefs(context).edit()
        if (key.isNullOrBlank()) editor.remove("gemini_api_key") 
        else editor.putString("gemini_api_key", key.trim())
        editor.apply()
    }

    fun getGeminiStt(context: Context): Boolean =
        prefs(context).getBoolean("gemini_stt_enabled", false)

    fun setGeminiStt(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("gemini_stt_enabled", enabled).apply()

    fun getGeminiTts(context: Context): Boolean =
        prefs(context).getBoolean("gemini_tts_enabled", false)

    fun setGeminiTts(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("gemini_tts_enabled", enabled).apply()

    fun getGeminiTtsVoice(context: Context): String? =
        prefs(context).getString("gemini_tts_voice", null)

    fun setGeminiTtsVoice(context: Context, voice: String) =
        prefs(context).edit().putString("gemini_tts_voice", voice).apply()

    fun getSilenceTimeout(context: Context): Long =
        prefs(context).getLong("silence_timeout_ms", DEFAULT_SILENCE_TIMEOUT_MS)

    fun setSilenceTimeout(context: Context, ms: Long) =
        prefs(context).edit().putLong("silence_timeout_ms", ms).apply()

    // ══════════════════════════════════════════════════════════════════════
    // Avatar Settings
    // ══════════════════════════════════════════════════════════════════════

    fun getAvatarX(context: Context): Int =
        prefs(context).getInt("avatar_x", -1)

    fun setAvatarX(context: Context, x: Int) =
        prefs(context).edit().putInt("avatar_x", x).apply()

    fun getAvatarPosition(context: Context): String? =
        prefs(context).getString("avatar_position", null)

    fun setAvatarPosition(context: Context, pos: String) =
        prefs(context).edit().putString("avatar_position", pos).apply()

    // ══════════════════════════════════════════════════════════════════════
    // Behavior Settings
    // ══════════════════════════════════════════════════════════════════════

    fun getAutoCopy(context: Context): Boolean =
        prefs(context).getBoolean("auto_copy", false)

    fun setAutoCopy(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("auto_copy", enabled).apply()

    fun getVolumeToggle(context: Context): Boolean =
        prefs(context).getBoolean("volume_toggle_enabled", true)

    fun setVolumeToggle(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("volume_toggle_enabled", enabled).apply()

    fun getBeepsEnabled(context: Context): Boolean =
        prefs(context).getBoolean("beeps_enabled", true)

    fun setBeepsEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("beeps_enabled", enabled).apply()

    fun getVoiceScreenshot(context: Context): Boolean =
        prefs(context).getBoolean("voice_screenshot_enabled", false)

    fun setVoiceScreenshot(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("voice_screenshot_enabled", enabled).apply()

    // ══════════════════════════════════════════════════════════════════════
    // Utilities
    // ══════════════════════════════════════════════════════════════════════

    fun resetToDefaults(context: Context) =
        prefs(context).edit().clear().apply()
}
