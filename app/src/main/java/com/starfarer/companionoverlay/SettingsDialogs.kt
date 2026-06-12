package com.starfarer.companionoverlay

import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.starfarer.companionoverlay.repository.SettingsRepository
import com.starfarer.companionoverlay.ui.TextEditorBottomSheet

/**
 * Dialog builders for settings: text editing, voice picker, and TTS tuning.
 *
 * All settings mutations flow through [SettingsRepository] rather than
 * reaching into PromptSettings statics.
 */
object SettingsDialogs {

    /**
     * Show the text editor for a field. The result is delivered to [requestKey]
     * via the Fragment Result API; the caller registers the listener once at
     * Activity-create time (so a rotation can't drop the save or misroute it).
     */
    fun showTextEditor(
        activity: FragmentActivity,
        title: String,
        currentText: String,
        defaultText: String,
        requestKey: String
    ) {
        TextEditorBottomSheet.newInstance(title, currentText, defaultText, requestKey)
            .show(activity.supportFragmentManager, "text_editor")
    }

    /**
     * Release [tts] when the activity stops — covers rotation and the
     * init-never-completes window, where the dialog's own release paths can't
     * run. release() is idempotent, so a later normal release is harmless.
     */
    private fun releaseOnStop(activity: FragmentActivity, tts: TtsManager) {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                tts.release()
                activity.lifecycle.removeObserver(this)
            }
        })
    }

    /** Voice selection with preview playback. */
    fun showVoicePicker(activity: FragmentActivity, settings: SettingsRepository, onTuneRequested: () -> Unit) {
        val tts = TtsManager(activity, settings)
        releaseOnStop(activity, tts)

        // Init failure used to leave onReady un-fired: the dialog never appeared
        // AND the manager leaked. Surface it and release.
        tts.onInitFailed = {
            if (!activity.isFinishing && !activity.isDestroyed) {
                Toast.makeText(activity, "Text-to-speech engine unavailable", Toast.LENGTH_LONG).show()
            }
            tts.release()
        }

        tts.onReady = ready@{
            if (activity.isFinishing || activity.isDestroyed) {
                tts.release()
                return@ready
            }

            val voices = tts.getAvailableVoices()
            if (voices.isEmpty()) {
                Toast.makeText(activity, "No voices available on this device", Toast.LENGTH_LONG).show()
                tts.release()
            } else {
                val currentVoice = settings.ttsVoice
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
                        settings.ttsVoice = names[selectedIndex]
                        tts.release()
                    }
                    .setNeutralButton("Tune") { _, _ ->
                        settings.ttsVoice = names[selectedIndex]
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
    fun showTuning(activity: FragmentActivity, settings: SettingsRepository) {
        val d = activity.resources.displayMetrics.density
        val currentRate = settings.ttsSpeechRate
        val currentPitch = settings.ttsPitch
        val tts = TtsManager(activity, settings)
        releaseOnStop(activity, tts)

        tts.onInitFailed = {
            if (!activity.isFinishing && !activity.isDestroyed) {
                Toast.makeText(activity, "Text-to-speech engine unavailable", Toast.LENGTH_LONG).show()
            }
            tts.release()
        }

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

        tts.onReady = ready@{
            if (activity.isFinishing || activity.isDestroyed) {
                tts.release()
                return@ready
            }

            val dialog = MaterialAlertDialogBuilder(activity, R.style.CompanionDialog)
                .setTitle("Voice Tuning")
                .setView(container)
                .setPositiveButton("Save") { _, _ ->
                    settings.ttsSpeechRate = 0.5f + rateSeek.progress / 100f
                    settings.ttsPitch = 0.5f + pitchSeek.progress / 100f
                    tts.release()
                }
                // Placeholder handler — overridden below so Preview does NOT
                // dismiss. The default button behavior closed the dialog (and
                // leaked the manager, since only Save/Cancel release it).
                .setNeutralButton("Preview", null)
                .setNegativeButton("Cancel") { _, _ -> tts.release() }
                .setOnCancelListener { tts.release() }
                .create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                    tts.setSpeechRate(0.5f + rateSeek.progress / 100f)
                    tts.setPitch(0.5f + pitchSeek.progress / 100f)
                    tts.speak("This is how I'll sound with these settings~")
                }
            }
            dialog.show()
        }
    }
}
