package com.starfarer.companionoverlay.ui

import android.app.Activity
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.starfarer.companionoverlay.CharacterPreset
import com.starfarer.companionoverlay.R

/**
 * Helper for preset management dialogs.
 *
 * Handles the preset list, rename, and delete confirmation dialogs.
 * Uses Material 3 dialogs with CompanionDialog theme.
 */
class PresetDialogHelper(private val activity: Activity) {

    private val density = activity.resources.displayMetrics.density

    /**
     * Show the preset list dialog with options to select, create, rename, or delete.
     */
    fun showPresetList(
        presets: List<CharacterPreset>,
        activeIndex: Int,
        onSelect: (Int) -> Unit,
        onCreate: () -> Unit,
        onRename: () -> Unit,
        onDelete: () -> Unit
    ) {
        val names = presets.map { it.name }.toTypedArray()

        MaterialAlertDialogBuilder(activity, R.style.CompanionDialog)
            .setTitle("Character Presets")
            .setSingleChoiceItems(names, activeIndex) { dialog, which ->
                onSelect(which)
                dialog.dismiss()
            }
            .setPositiveButton("New") { _, _ -> onCreate() }
            .setNeutralButton("Rename") { _, _ -> onRename() }
            .setNegativeButton("Delete") { _, _ -> onDelete() }
            .show()
    }

    /**
     * Show rename dialog for the active preset.
     */
    fun showRenameDialog(currentName: String, onSave: (String) -> Unit) {
        val input = EditText(activity).apply {
            setText(currentName)
            setSelection(text.length)
            hint = "Preset name"
            setTextColor(activity.getColor(R.color.text_primary))
            setHintTextColor(activity.getColor(R.color.text_hint))
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * density).toInt()
            setPadding(pad, (16 * density).toInt(), pad, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(activity, R.style.CompanionDialog)
            .setTitle("Rename Preset")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    onSave(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show delete confirmation for the active preset.
     */
    fun showDeleteConfirmation(presetName: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(activity, R.style.CompanionDialog)
            .setTitle("Delete Preset")
            .setMessage("Delete \"$presetName\"? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
