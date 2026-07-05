package com.starfarer.companionoverlay

import android.content.SharedPreferences
import com.starfarer.companionoverlay.repository.SettingsRepository
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SettingsRepository].
 *
 * Verifies that settings are correctly read from and written to
 * SharedPreferences with proper defaults, and that the GatewayConfig
 * surface (URL, token, device identity) behaves.
 */
class SettingsRepositoryTest {

    private lateinit var settingsPrefs: SharedPreferences
    private lateinit var securePrefs: SharedPreferences
    private lateinit var settingsEditor: SharedPreferences.Editor
    private lateinit var secureEditor: SharedPreferences.Editor
    private lateinit var repository: SettingsRepository

    private val mockPreset = CharacterPreset(
        id = "test",
        name = "Test",
        idleSpriteUri = "content://sprites/test-idle",
        idleFrameCount = 8
    )

    @Before
    fun setup() {
        settingsPrefs = mockk(relaxed = true)
        securePrefs = mockk(relaxed = true)
        settingsEditor = mockk(relaxed = true)
        secureEditor = mockk(relaxed = true)

        every { settingsPrefs.edit() } returns settingsEditor
        every { securePrefs.edit() } returns secureEditor
        every { settingsEditor.putString(any(), any()) } returns settingsEditor
        every { settingsEditor.putBoolean(any(), any()) } returns settingsEditor
        every { settingsEditor.putInt(any(), any()) } returns settingsEditor
        every { settingsEditor.putFloat(any(), any()) } returns settingsEditor
        every { settingsEditor.putLong(any(), any()) } returns settingsEditor
        every { settingsEditor.remove(any()) } returns settingsEditor
        every { secureEditor.putString(any(), any()) } returns secureEditor
        every { secureEditor.remove(any()) } returns secureEditor

        repository = SettingsRepository(
            settingsPrefs = settingsPrefs,
            securePrefs = securePrefs,
            presetProvider = { mockPreset }
        )
    }

    // ── Gateway configuration ──

    @Test
    fun `gatewayUrl returns null when not set`() {
        every { settingsPrefs.getString("gateway_url", null) } returns null
        assertNull(repository.gatewayUrl)
        assertNull(repository.serverUrl)
    }

    @Test
    fun `gatewayUrl trims and persists`() {
        repository.gatewayUrl = "  https://tunnel.example.com  "
        verify { settingsEditor.putString("gateway_url", "https://tunnel.example.com") }
        verify { settingsEditor.apply() }
    }

    @Test
    fun `gatewayUrl removes on blank`() {
        repository.gatewayUrl = "   "
        verify { settingsEditor.remove("gateway_url") }
    }

    @Test
    fun `gatewayToken lives in secure prefs`() {
        every { securePrefs.getString("gateway_token", null) } returns "tok-123"
        assertEquals("tok-123", repository.gatewayToken)
        assertEquals("tok-123", repository.token)

        repository.gatewayToken = " new-token "
        verify { secureEditor.putString("gateway_token", "new-token") }

        repository.gatewayToken = ""
        verify { secureEditor.remove("gateway_token") }
    }

    @Test
    fun `deviceId returns stored value without regenerating`() {
        every { settingsPrefs.getString("gateway_device_id", null) } returns "phone-abcd1234"
        assertEquals("phone-abcd1234", repository.deviceId)
        verify(exactly = 0) { settingsEditor.putString("gateway_device_id", any()) }
    }

    @Test
    fun `deviceId is generated once and persisted when missing`() {
        val stored = slot<String>()
        every { settingsPrefs.getString("gateway_device_id", null) } returns null
        every { settingsEditor.putString("gateway_device_id", capture(stored)) } returns settingsEditor

        val id = repository.deviceId
        assertTrue("generated id has the phone- prefix: $id", id.startsWith("phone-"))
        assertEquals(id, stored.captured)
    }

    @Test
    fun `deviceName falls back when unset`() {
        every { settingsPrefs.getString("gateway_device_name", null) } returns null
        // Build.MODEL is null on the JVM — the fallback must still be non-blank.
        assertTrue(repository.deviceName.isNotBlank())
    }

    @Test
    fun `deviceName uses the stored setting`() {
        every { settingsPrefs.getString("gateway_device_name", null) } returns "Pixel 8"
        assertEquals("Pixel 8", repository.deviceName)
    }

    // ── Local settings ──

    @Test
    fun `ttsEnabled defaults to true`() {
        every { settingsPrefs.getBoolean("tts_enabled", true) } returns true
        assertTrue(repository.ttsEnabled)
    }

    @Test
    fun `sprite appearance fields delegate to preset provider`() {
        assertEquals("content://sprites/test-idle", repository.idleSpriteUri)
        assertEquals(8, repository.idleFrameCount)
    }

    @Test
    fun `bubbleTimeoutSeconds returns default when not set`() {
        every { settingsPrefs.getInt("bubble_timeout", PromptSettings.DEFAULT_BUBBLE_TIMEOUT) } returns PromptSettings.DEFAULT_BUBBLE_TIMEOUT
        assertEquals(PromptSettings.DEFAULT_BUBBLE_TIMEOUT, repository.bubbleTimeoutSeconds)
    }

    @Test
    fun `silenceTimeoutMs returns default when not set`() {
        every { settingsPrefs.getLong("silence_timeout_ms", PromptSettings.DEFAULT_SILENCE_TIMEOUT_MS) } returns PromptSettings.DEFAULT_SILENCE_TIMEOUT_MS
        assertEquals(PromptSettings.DEFAULT_SILENCE_TIMEOUT_MS, repository.silenceTimeoutMs)
    }
}
