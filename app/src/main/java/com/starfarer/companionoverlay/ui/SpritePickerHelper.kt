package com.starfarer.companionoverlay.ui

import android.app.Activity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.starfarer.companionoverlay.R

/**
 * Helper for sprite sheet selection and frame count configuration.
 *
 * Sprite sheets are horizontal strips where each frame is the same width.
 * The frame count determines how the sheet is sliced for animation.
 */
class SpritePickerHelper(private val activity: Activity) {

    sealed class Result {
        data class PickImage(val frameCount: Int) : Result()
        data class SaveCount(val frameCount: Int) : Result()
        data object Reset : Result()
        data object Cancelled : Result()
    }

    private val density = activity.resources.displayMetrics.density

    /**
     * Show the sprite configuration dialog.
     *
     * @param type "idle" or "walk" — used for display only
     * @param currentFrameCount current frame count setting
     * @param hasCustomSprite whether a custom sprite is currently set
     * @param onResult callback with the user's choice
     */
    fun show(
        type: String,
        currentFrameCount: Int,
        hasCustomSprite: Boolean,
        onResult: (Result) -> Unit
    ) {
        var frameCount = currentFrameCount

        val seekBar = SeekBar(activity).apply {
            min = 1
            max = 12
            progress = currentFrameCount
        }

        val frameLabel = TextView(activity).apply {
            text = "Frames: $currentFrameCount"
            textSize = 14f
            setTextColor(activity.getColor(R.color.text_primary))
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                frameCount = progress.coerceAtLeast(1)
                frameLabel.text = "Frames: $frameCount"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val hint = TextView(activity).apply {
            text = "Sprite sheet should be a horizontal strip with equally-sized frames."
            textSize = 12f
            setTextColor(activity.getColor(R.color.text_hint))
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * density).toInt()
            setPadding(pad, (16 * density).toInt(), pad, pad)
            addView(frameLabel)
            addView(seekBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() })
            addView(hint, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (16 * density).toInt() })
        }

        val builder = MaterialAlertDialogBuilder(activity, R.style.CompanionDialog)
            .setTitle("${type.replaceFirstChar { it.uppercase() }} Sprite")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                onResult(Result.SaveCount(frameCount))
            }
            .setNeutralButton("Pick Image") { _, _ ->
                onResult(Result.PickImage(frameCount))
            }
            .setNegativeButton(if (hasCustomSprite) "Reset" else "Cancel") { _, _ ->
                if (hasCustomSprite) onResult(Result.Reset)
                else onResult(Result.Cancelled)
            }

        builder.show()
    }
}
