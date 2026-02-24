package com.starfarer.companionoverlay

import android.app.Activity
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Dialog builders for settings — text editing, voice picker, and TTS tuning.
 *
 * Uses Material 3 dialogs with the CompanionDialog theme overlay for consistent
 * dark styling. Each method is self-contained: builds its UI, wires its callbacks,
 * and dismisses on completion.
 */
object SettingsDialogs {

    /** Multiline text editor for system prompt and personality. */
    fun showTextEditor(
        activity: Activity,
        title: String,
        currentText: String,
        defaultText: String,
        onSave: (String) -> Unit
    ) {
        val d = activity.resources.displayMetrics.density
        val screenHeight = activity.resources.displayMetrics.heightPixels

        val editText = EditText(activity).apply {
            setText(currentText)
            setSelection(text.length)
            gravity = Gravity.TOP
            setHorizontallyScrolling(false)
            isSingleLine = false
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(activity.getColor(R.color.text_primary))
            setHintTextColor(activity.getColor(R.color.text_hint))
        }

        val scrollView = ScrollView(activity).apply {
            addView(editText)
            isFillViewport = true
        }

        val resetBtn = Button(activity, null, android.R.attr.buttonBarButtonStyle).apply {
            text = "Reset"
            setTextColor(activity.getColor(R.color.gold))
        }
        val clearBtn = Button(activity, null, android.R.attr.buttonBarButtonStyle).apply {
            text = "Clear"
            setTextColor(activity.getColor(R.color.gold))
        }
        val cancelBtn = Button(activity, null, android.R.attr.buttonBarButtonStyle).apply {
            text = "Cancel"
            setTextColor(activity.getColor(R.color.text_secondary))
        }
        val saveBtn = Button(activity, null, android.R.attr.buttonBarButtonStyle).apply {
            text = "Save"
            setTextColor(activity.getColor(R.color.gold))
        }

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (8 * d).toInt()
            setPadding(pad, (12 * d).toInt(), pad, pad)
            addView(resetBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(clearBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(android.widget.Space(activity), LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(cancelBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(saveBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * d).toInt()
            setPadding(pad, pad, pad, 0)
            addView(scrollView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buttonRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val dialog = MaterialAlertDialogBuilder(activity, R.style.CompanionDialog)
            .setTitle(title)
            .setView(container)
            .create()

        dialog.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                (screenHeight * 0.85).toInt()
            )
        }

        resetBtn.setOnClickListener { editText.setText(defaultText) }
        clearBtn.setOnClickListener { editText.setText("") }
        cancelBtn.setOnClickListener { dialog.dismiss() }
        saveBtn.setOnClickListener {
            val newText = editText.text.toString().trim()
            if (newText.isEmpty()) {
                Toast.makeText(activity, "Prompt can't be empty~", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onSave(newText)
            Toast.makeText(activity, "Saved~", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    /** Voice selection with preview playback. */
    fun showVoicePicker(activity: Activity, onTuneRequested: () -> Unit) {
        val tts = TtsManager(activity)

        tts.onReady = {
            val voices = tts.getAvailableVoices()
            if (voices.isEmpty()) {
                Toast.makeText(activity, "No voices available on this device", Toast.LENGTH_LONG).show()
                tts.release()
            } else {
                val currentVoice = PromptSettings.getTtsVoice(activity)
                val labels = voices.map { it.first }.toTypedArray()
                val names = voices.map { it.second.name }
                val checkedIndex = names.indexOf(currentVoice).coerceAtLeast(0)
                var selectedIndex = checkedIndex

                MaterialAlertDialogBuilder(activity, R.style.CompanionDialog)
                    .setTitle("Voice")
                    .setSingleChoiceItems(labels, checkedIndex) { _, which ->
                        selectedIndex = which
                        tts.setVoice(names[which])
                        tts.speak("Hello~ I'm Senni, your companion.")
                    }
                    .setPositiveButton("Select") { _, _ ->
                        PromptSettings.setTtsVoice(activity, names[selectedIndex])
                        tts.release()
                    }
                    .setNeutralButton("Tune") { _, _ ->
                        PromptSettings.setTtsVoice(activity, names[selectedIndex])
                        tts.release()
                        onTuneRequested()
                    }
                    .setNegativeButton("Cancel") { _, _ -> tts.release() }
                    .setOnCancelListener { tts.release() }
                    .show()
            }
        }
    }

    /** TTS rate/pitch tuning with live preview. */
    fun showTuning(activity: Activity) {
        val d = activity.resources.displayMetrics.density
        val currentRate = PromptSettings.getTtsSpeechRate(activity)
        val currentPitch = PromptSettings.getTtsPitch(activity)
        val tts = TtsManager(activity)

        val rateSeek = SeekBar(activity).apply {
            max = 150
            progress = ((currentRate - 0.5f) * 100).toInt()
        }
        val pitchSeek = SeekBar(activity).apply {
            max = 150
            progress = ((currentPitch - 0.5f) * 100).toInt()
        }

        val rateLabel = TextView(activity).apply {
            text = "Speed: %.1fx".format(currentRate)
            textSize = 13f
            setTextColor(activity.getColor(R.color.text_primary))
        }
        val pitchLabel = TextView(activity).apply {
            text = "Pitch: %.1fx".format(currentPitch)
            textSize = 13f
            setTextColor(activity.getColor(R.color.text_primary))
        }

        rateSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                rateLabel.text = "Speed: %.1fx".format(0.5f + progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        pitchSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                pitchLabel.text = "Pitch: %.1fx".format(0.5f + progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * d).toInt()
            setPadding(pad, pad, pad, pad)
            addView(rateLabel)
            addView(rateSeek)
            addView(android.view.View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (16 * d).toInt()
                )
            })
            addView(pitchLabel)
            addView(pitchSeek)
        }

        tts.onReady = {
            MaterialAlertDialogBuilder(activity, R.style.CompanionDialog)
                .setTitle("Voice Tuning")
                .setView(container)
                .setPositiveButton("Save") { _, _ ->
                    val rate = 0.5f + rateSeek.progress / 100f
                    val pitch = 0.5f + pitchSeek.progress / 100f
                    PromptSettings.setTtsSpeechRate(activity, rate)
                    PromptSettings.setTtsPitch(activity, pitch)
                    tts.release()
                }
                .setNeutralButton("Preview") { dialog, _ ->
                    val rate = 0.5f + rateSeek.progress / 100f
                    val pitch = 0.5f + pitchSeek.progress / 100f
                    tts.setSpeechRate(rate)
                    tts.setPitch(pitch)
                    tts.speak("This is how I'll sound with these settings~")
                }
                .setNegativeButton("Cancel") { _, _ -> tts.release() }
                .setOnCancelListener { tts.release() }
                .show()
        }
    }
}
