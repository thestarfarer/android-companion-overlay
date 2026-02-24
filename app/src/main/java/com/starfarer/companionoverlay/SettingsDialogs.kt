package com.starfarer.companionoverlay

import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.starfarer.companionoverlay.ui.TextEditorBottomSheet

/**
 * Dialog builders for settings — text editing, voice picker, and TTS tuning.
 */
object SettingsDialogs {

    /** Bottom sheet text editor for system prompt and personality. */
    fun showTextEditor(
        activity: FragmentActivity,
        title: String,
        currentText: String,
        defaultText: String,
        onSave: (String) -> Unit
    ) {
        val bottomSheet = TextEditorBottomSheet.newInstance(title, currentText, defaultText)
        bottomSheet.setOnSaveListener(onSave)
        bottomSheet.show(activity.supportFragmentManager, "text_editor")
    }

    /** Voice selection with preview playback. */
    fun showVoicePicker(activity: FragmentActivity, onTuneRequested: () -> Unit) {
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
    fun showTuning(activity: FragmentActivity) {
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
                .setNeutralButton("Preview") { _, _ ->
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
