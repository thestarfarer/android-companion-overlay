package com.starfarer.companionoverlay

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A character preset bundles everything that defines a companion's identity:
 * her name, how she speaks (prompts), and how she looks (sprites).
 *
 * Pure data class — persistence is handled by [PresetRepository].
 * Serialized to JSON via kotlinx.serialization for disk storage.
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
)
