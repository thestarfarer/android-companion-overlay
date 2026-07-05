package com.starfarer.companionoverlay

/**
 * Constants for CompanionOverlay settings.
 *
 * All runtime settings access flows through [SettingsRepository]. This object
 * holds only default values that multiple components reference (preset sprite
 * defaults, timeouts). Conversation intelligence — including the persona
 * prompts that used to live here — is server-side in Nexus now.
 */
object PromptSettings {

    // ══════════════════════════════════════════════════════════════════════
    // Default Values
    // ══════════════════════════════════════════════════════════════════════

    const val DEFAULT_BUBBLE_TIMEOUT = 30
    const val DEFAULT_IDLE_FRAME_COUNT = 6
    const val DEFAULT_WALK_FRAME_COUNT = 4
    const val DEFAULT_SILENCE_TIMEOUT_MS = 1500L

    val BUBBLE_TIMEOUT_VALUES = arrayOf(15, 30, 60, 120)
    val BUBBLE_TIMEOUT_LABELS = arrayOf("15 sec", "30 sec", "60 sec", "120 sec")
}
