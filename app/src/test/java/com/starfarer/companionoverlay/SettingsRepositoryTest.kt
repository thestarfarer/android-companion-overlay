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
 * SharedPreferences with proper defaults.
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
        systemPrompt = "Test system prompt",
        userMessage = "Test user message"
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

    @Test
    fun `model returns default when not set`() {
        every { settingsPrefs.getString("selected_model", any()) } returns null
        assertEquals(PromptSettings.DEFAULT_MODEL, repository.model)
    }

    @Test
    fun `model returns saved value`() {
        val customModel = "claude-opus-4-1-20250805"
        every { settingsPrefs.getString("selected_model", any()) } returns customModel
        assertEquals(customModel, repository.model)
    }

    @Test
    fun `setting model writes to preferences`() {
        val newModel = "claude-opus-4-6"
        repository.model = newModel
        verify { settingsEditor.putString("selected_model", newModel) }
        verify { settingsEditor.apply() }
    }

    @Test
    fun `webSearchEnabled defaults to false`() {
        every { settingsPrefs.getBoolean("web_search_enabled", false) } returns false
        assertFalse(repository.webSearchEnabled)
    }

    @Test
    fun `ttsEnabled defaults to true`() {
        every { settingsPrefs.getBoolean("tts_enabled", true) } returns true
        assertTrue(repository.ttsEnabled)
    }

    @Test
    fun `geminiApiKey reads from secure prefs`() {
        every { securePrefs.getString("gemini_api_key", null) } returns "test-key"
        assertEquals("test-key", repository.geminiApiKey)
    }

    @Test
    fun `geminiApiKey writes to secure prefs`() {
        repository.geminiApiKey = "new-api-key"
        verify { secureEditor.putString("gemini_api_key", "new-api-key") }
        verify { secureEditor.apply() }
    }

    @Test
    fun `geminiApiKey trims whitespace`() {
        repository.geminiApiKey = "  spaced-key  "
        verify { secureEditor.putString("gemini_api_key", "spaced-key") }
    }

    @Test
    fun `geminiApiKey removes on blank`() {
        repository.geminiApiKey = "   "
        verify { secureEditor.remove("gemini_api_key") }
    }

    @Test
    fun `system prompt delegates to preset provider`() {
        assertEquals("Test system prompt", repository.systemPrompt)
    }

    @Test
    fun `user message delegates to preset provider`() {
        assertEquals("Test user message", repository.userMessage)
    }

    @Test
    fun `bubbleTimeoutSeconds returns default when not set`() {
        every { settingsPrefs.getInt("bubble_timeout", PromptSettings.DEFAULT_BUBBLE_TIMEOUT) } returns PromptSettings.DEFAULT_BUBBLE_TIMEOUT
        assertEquals(PromptSettings.DEFAULT_BUBBLE_TIMEOUT, repository.bubbleTimeoutSeconds)
    }

    @Test
    fun `maxMessages returns default when not set`() {
        every { settingsPrefs.getInt("max_messages", PromptSettings.DEFAULT_MAX_MESSAGES) } returns PromptSettings.DEFAULT_MAX_MESSAGES
        assertEquals(PromptSettings.DEFAULT_MAX_MESSAGES, repository.maxMessages)
    }
}
