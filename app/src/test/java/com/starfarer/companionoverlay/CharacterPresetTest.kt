package com.starfarer.companionoverlay

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for CharacterPreset serialization round-trips.
 */
class CharacterPresetTest {
    
    private val json = Json { 
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    @Test
    fun `preset serializes and deserializes correctly`() {
        val original = CharacterPreset(
            id = "test-id-123",
            name = "Test Character",
            systemPrompt = "You are a test.",
            userMessage = "Hello test",
            idleSpriteUri = "content://some/uri",
            walkSpriteUri = null,
            idleFrameCount = 8,
            walkFrameCount = 4,
            createdAt = 1234567890L
        )
        
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<CharacterPreset>(encoded)
        
        assertEquals(original.id, decoded.id)
        assertEquals(original.name, decoded.name)
        assertEquals(original.systemPrompt, decoded.systemPrompt)
        assertEquals(original.userMessage, decoded.userMessage)
        assertEquals(original.idleSpriteUri, decoded.idleSpriteUri)
        assertEquals(original.walkSpriteUri, decoded.walkSpriteUri)
        assertEquals(original.idleFrameCount, decoded.idleFrameCount)
        assertEquals(original.walkFrameCount, decoded.walkFrameCount)
        assertEquals(original.createdAt, decoded.createdAt)
    }
    
    @Test
    fun `preset list serializes and deserializes correctly`() {
        val presets = listOf(
            CharacterPreset(name = "First"),
            CharacterPreset(name = "Second"),
            CharacterPreset(name = "Third")
        )
        
        val encoded = json.encodeToString(presets)
        val decoded = json.decodeFromString<List<CharacterPreset>>(encoded)
        
        assertEquals(3, decoded.size)
        assertEquals("First", decoded[0].name)
        assertEquals("Second", decoded[1].name)
        assertEquals("Third", decoded[2].name)
    }
    
    @Test
    fun `preset handles null sprite URIs`() {
        val preset = CharacterPreset(
            name = "No Sprites",
            idleSpriteUri = null,
            walkSpriteUri = null
        )
        
        val encoded = json.encodeToString(preset)
        val decoded = json.decodeFromString<CharacterPreset>(encoded)
        
        assertNull(decoded.idleSpriteUri)
        assertNull(decoded.walkSpriteUri)
    }
    
    @Test
    fun `preset ignores unknown keys during deserialization`() {
        val jsonWithExtra = """
            {
                "id": "test",
                "name": "Test",
                "systemPrompt": "prompt",
                "userMessage": "msg",
                "idleSpriteUri": null,
                "walkSpriteUri": null,
                "idleFrameCount": 6,
                "walkFrameCount": 4,
                "createdAt": 0,
                "futureField": "should be ignored",
                "anotherFutureField": 42
            }
        """.trimIndent()
        
        val decoded = json.decodeFromString<CharacterPreset>(jsonWithExtra)
        
        assertEquals("test", decoded.id)
        assertEquals("Test", decoded.name)
    }
    
    @Test
    fun `preset copy preserves unchanged fields`() {
        val original = CharacterPreset(
            id = "original-id",
            name = "Original",
            systemPrompt = "Original prompt",
            createdAt = 999L
        )
        
        val updated = original.copy(name = "Updated")
        
        assertEquals("original-id", updated.id)
        assertEquals("Updated", updated.name)
        assertEquals("Original prompt", updated.systemPrompt)
        assertEquals(999L, updated.createdAt)
    }
    
    @Test
    fun `default preset has expected values`() {
        val preset = CharacterPreset()
        
        assertEquals("Senni", preset.name)
        assertEquals(PromptSettings.DEFAULT_SYSTEM_PROMPT, preset.systemPrompt)
        assertEquals(PromptSettings.DEFAULT_USER_MESSAGE, preset.userMessage)
        assertEquals(PromptSettings.DEFAULT_IDLE_FRAME_COUNT, preset.idleFrameCount)
        assertEquals(PromptSettings.DEFAULT_WALK_FRAME_COUNT, preset.walkFrameCount)
        assertNull(preset.idleSpriteUri)
        assertNull(preset.walkSpriteUri)
    }
}
