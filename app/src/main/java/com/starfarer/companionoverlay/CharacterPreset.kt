package com.starfarer.companionoverlay

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A character preset bundles the companion's local appearance: her name and
 * how she looks (sprites). Persona and prompts live server-side in Nexus —
 * presets stopped carrying prompt text when the local brain was removed.
 *
 * Pure data class — persistence is handled by [PresetRepository].
 * Serialized to JSON via kotlinx.serialization for disk storage. Presets
 * saved by older builds carry `systemPrompt`/`userMessage` keys; the
 * repository's `ignoreUnknownKeys` decoder drops them silently.
 */
@Serializable
data class CharacterPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Senni",
    val idleSpriteUri: String? = null,
    val walkSpriteUri: String? = null,
    val idleFrameCount: Int = PromptSettings.DEFAULT_IDLE_FRAME_COUNT,
    val walkFrameCount: Int = PromptSettings.DEFAULT_WALK_FRAME_COUNT,
    val createdAt: Long = System.currentTimeMillis()
)
