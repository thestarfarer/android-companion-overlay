package com.starfarer.companionoverlay.repository

import android.content.SharedPreferences
import com.starfarer.companionoverlay.CharacterPreset

/**
 * Tutorial-scoped settings sandbox. Reads pass through to the real prefs, but the
 * settings the tutorial's radial menu can flip are overridden with in-memory copies
 * seeded at construction — writes never reach disk. This replaces the old
 * snapshot-on-entry/restore-in-finish() dance, which leaked flipped toggles
 * permanently whenever the Activity died without going through finish()
 * (rotation, process death, task-kill).
 *
 * Anything newly made tutorial-mutable must be overridden here too.
 */
class TutorialSettings(
    settingsPrefs: SharedPreferences,
    securePrefs: SharedPreferences,
    presetProvider: () -> CharacterPreset
) : SettingsRepository(settingsPrefs, securePrefs, presetProvider) {

    override var captureMode: CaptureMode = super.captureMode
    override var volumeToggleEnabled: Boolean = super.volumeToggleEnabled
    override var ttsEnabled: Boolean = super.ttsEnabled
}
