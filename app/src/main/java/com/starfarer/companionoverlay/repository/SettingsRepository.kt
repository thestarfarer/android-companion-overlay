package com.starfarer.companionoverlay.repository

import android.content.SharedPreferences
import com.starfarer.companionoverlay.CharacterPreset
import com.starfarer.companionoverlay.PromptSettings
import com.starfarer.companionoverlay.gateway.GatewayConfig
import java.util.UUID

/** What an upper-body long-press on the avatar does. */
enum class CaptureMode(val key: String) {
    OFF("off"),
    SCREENSHOT("screenshot"),
    CAMERA("camera");

    /** The next mode when cycling Off → Screenshot → Camera → Off. */
    fun next(): CaptureMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromKey(key: String?): CaptureMode = entries.firstOrNull { it.key == key } ?: SCREENSHOT
    }
}

/**
 * Repository layer for application settings.
 *
 * Wraps SharedPreferences to provide a testable, injectable interface.
 * All runtime settings access flows through this class. Default values
 * are defined in [PromptSettings], which is now a pure constants object.
 *
 * Security note: Sensitive values (the Nexus gateway bearer token) are stored
 * in [securePrefs], which uses EncryptedSharedPreferences. Non-sensitive
 * settings use [settingsPrefs].
 *
 * Implements [GatewayConfig]: this is where the presence gateway reads its
 * server URL, token, and device identity from.
 *
 * Injected via Koin as a singleton.
 */
open class SettingsRepository(
    private val settingsPrefs: SharedPreferences,
    private val securePrefs: SharedPreferences,
    private val presetProvider: () -> CharacterPreset
) : GatewayConfig {


    // ══════════════════════════════════════════════════════════════════════
    // Character Preset (appearance only — read-only here, mutations go
    // through PresetRepository)
    // ══════════════════════════════════════════════════════════════════════

    val idleSpriteUri: String? get() = presetProvider().idleSpriteUri
    val walkSpriteUri: String? get() = presetProvider().walkSpriteUri
    val idleFrameCount: Int get() = presetProvider().idleFrameCount
    val walkFrameCount: Int get() = presetProvider().walkFrameCount

    // ══════════════════════════════════════════════════════════════════════
    // Nexus gateway connection (GatewayConfig)
    // ══════════════════════════════════════════════════════════════════════

    /** Nexus base URL (rotating tunnel / port-forward — editable at runtime). */
    var gatewayUrl: String?
        get() = settingsPrefs.getString(KEY_GATEWAY_URL, null)
        set(value) {
            val editor = settingsPrefs.edit()
            if (value.isNullOrBlank()) editor.remove(KEY_GATEWAY_URL)
            else editor.putString(KEY_GATEWAY_URL, value.trim())
            editor.apply()
        }

    /** Bearer token for /ws and the avatar routes — encrypted at rest. */
    var gatewayToken: String?
        get() = securePrefs.getString(KEY_GATEWAY_TOKEN, null)
        set(value) {
            val editor = securePrefs.edit()
            if (value.isNullOrBlank()) editor.remove(KEY_GATEWAY_TOKEN)
            else editor.putString(KEY_GATEWAY_TOKEN, value.trim())
            editor.apply()
        }

    override val serverUrl: String? get() = gatewayUrl
    override val token: String? get() = gatewayToken

    /**
     * Stable per-install device id — the server keys session resume and
     * capability routing on it. Generated once, then never changes.
     */
    override val deviceId: String
        get() {
            settingsPrefs.getString(KEY_DEVICE_ID, null)?.let { return it }
            val generated = "phone-" + UUID.randomUUID().toString().take(8)
            settingsPrefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }

    override val deviceName: String get() = deviceNameSetting

    var deviceNameSetting: String
        get() = settingsPrefs.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }
            ?: defaultDeviceName()
        set(value) {
            val editor = settingsPrefs.edit()
            if (value.isBlank()) editor.remove(KEY_DEVICE_NAME)
            else editor.putString(KEY_DEVICE_NAME, value.trim())
            editor.apply()
        }

    private fun defaultDeviceName(): String =
        try {
            android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android phone"
        } catch (_: Throwable) {
            "Android phone"
        }

    // ══════════════════════════════════════════════════════════════════════
    // Conversation UI
    // ══════════════════════════════════════════════════════════════════════

    var bubbleTimeoutSeconds: Int
        get() = settingsPrefs.getInt(KEY_BUBBLE_TIMEOUT, PromptSettings.DEFAULT_BUBBLE_TIMEOUT)
        set(value) = settingsPrefs.edit().putInt(KEY_BUBBLE_TIMEOUT, value).apply()

    // ══════════════════════════════════════════════════════════════════════
    // On-Device TTS
    // ══════════════════════════════════════════════════════════════════════

    // open: the tutorial sandboxes the radial-menu-mutable settings ([TutorialSettings]).
    open var ttsEnabled: Boolean
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
    // Voice input
    // ══════════════════════════════════════════════════════════════════════

    var silenceTimeoutMs: Long
        get() = settingsPrefs.getLong(KEY_SILENCE_TIMEOUT, PromptSettings.DEFAULT_SILENCE_TIMEOUT_MS)
        set(value) = settingsPrefs.edit().putLong(KEY_SILENCE_TIMEOUT, value).apply()

    /**
     * Which engine transcribes voice (presence protocol §3):
     * [VOICE_MODE_AUTO] — server path while connected, local STT as fallback;
     * [VOICE_MODE_SERVER] / [VOICE_MODE_LOCAL] force one path.
     */
    var voiceMode: String
        get() = settingsPrefs.getString(KEY_VOICE_MODE, VOICE_MODE_AUTO) ?: VOICE_MODE_AUTO
        set(value) = settingsPrefs.edit().putString(KEY_VOICE_MODE, value).apply()

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

    open var volumeToggleEnabled: Boolean
        get() = settingsPrefs.getBoolean(KEY_VOLUME_TOGGLE, true)
        set(value) = settingsPrefs.edit().putBoolean(KEY_VOLUME_TOGGLE, value).apply()

    var beepsEnabled: Boolean
        get() = settingsPrefs.getBoolean(KEY_BEEPS_ENABLED, true)
        set(value) = settingsPrefs.edit().putBoolean(KEY_BEEPS_ENABLED, value).apply()

    var voiceScreenshotEnabled: Boolean
        get() = settingsPrefs.getBoolean(KEY_VOICE_SCREENSHOT, false)
        set(value) = settingsPrefs.edit().putBoolean(KEY_VOICE_SCREENSHOT, value).apply()

    /** What an upper-body long-press does: Off (reopen last bubble), Screenshot, or Camera. */
    open var captureMode: CaptureMode
        get() = CaptureMode.fromKey(settingsPrefs.getString(KEY_CAPTURE_MODE, null))
        set(value) = settingsPrefs.edit().putString(KEY_CAPTURE_MODE, value.key).apply()

    /** Debug: persist a copy of every image sent to the gateway for inspection. */
    var saveSentImages: Boolean
        get() = settingsPrefs.getBoolean(KEY_SAVE_SENT_IMAGES, false)
        set(value) = settingsPrefs.edit().putBoolean(KEY_SAVE_SENT_IMAGES, value).apply()

    /** Whether the first-launch interactive tutorial has been shown. */
    var tutorialSeen: Boolean
        get() = settingsPrefs.getBoolean(KEY_TUTORIAL_SEEN, false)
        set(value) = settingsPrefs.edit().putBoolean(KEY_TUTORIAL_SEEN, value).apply()

    // ══════════════════════════════════════════════════════════════════════
    // Utilities
    // ══════════════════════════════════════════════════════════════════════

    fun resetToDefaults() {
        // This prefs file is SHARED with PresetRepository (presets, active id) —
        // a blanket clear() would wipe the user's characters. Preserve those
        // keys, plus the stable device identity the server keys sessions on.
        val preserved = listOf(
            "character_presets", "active_preset_id", KEY_DEVICE_ID
        ).mapNotNull { key -> settingsPrefs.getString(key, null)?.let { key to it } }

        settingsPrefs.edit().apply {
            clear()
            preserved.forEach { (key, value) -> putString(key, value) }
        }.apply()
        // Don't touch secure prefs — that would wipe the gateway token.
    }

    var overlayMode: String
        get() = settingsPrefs.getString(KEY_OVERLAY_MODE, "sprite") ?: "sprite"
        set(value) = settingsPrefs.edit().putString(KEY_OVERLAY_MODE, value).apply()

    val is3dMode: Boolean
        get() = overlayMode == "godot_3d"

    companion object {
        const val VOICE_MODE_AUTO = "auto"
        const val VOICE_MODE_SERVER = "server"
        const val VOICE_MODE_LOCAL = "local"

        private const val KEY_GATEWAY_URL = "gateway_url"
        private const val KEY_GATEWAY_TOKEN = "gateway_token"
        private const val KEY_DEVICE_ID = "gateway_device_id"
        private const val KEY_DEVICE_NAME = "gateway_device_name"
        private const val KEY_BUBBLE_TIMEOUT = "bubble_timeout"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_TTS_SPEECH_RATE = "tts_speech_rate"
        private const val KEY_TTS_PITCH = "tts_pitch"
        private const val KEY_SILENCE_TIMEOUT = "silence_timeout_ms"
        private const val KEY_VOICE_MODE = "voice_mode"
        private const val KEY_AVATAR_X = "avatar_x"
        private const val KEY_AVATAR_POSITION = "avatar_position"
        private const val KEY_AUTO_COPY = "auto_copy"
        private const val KEY_VOLUME_TOGGLE = "volume_toggle_enabled"
        private const val KEY_BEEPS_ENABLED = "beeps_enabled"
        private const val KEY_VOICE_SCREENSHOT = "voice_screenshot_enabled"
        private const val KEY_CAPTURE_MODE = "capture_mode"
        private const val KEY_SAVE_SENT_IMAGES = "save_sent_images"
        private const val KEY_TUTORIAL_SEEN = "tutorial_seen"
        private const val KEY_OVERLAY_MODE = "overlay_mode"
    }
}
