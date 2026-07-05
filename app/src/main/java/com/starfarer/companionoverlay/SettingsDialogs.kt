package com.starfarer.companionoverlay

import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.starfarer.companionoverlay.repository.SettingsRepository

/**
 * Dialog builders for settings: voice picker and TTS tuning.
 *
 * All settings mutations flow through [SettingsRepository] rather than
 * reaching into PromptSettings statics.
 */
object SettingsDialogs {

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
                Toast.makeText(activity, activity.getString(R.string.dialog_tts_unavailable), Toast.LENGTH_LONG).show()
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
                Toast.makeText(activity, activity.getString(R.string.dialog_no_voices), Toast.LENGTH_LONG).show()
                tts.release()
            } else {
                val currentVoice = settings.ttsVoice
                val labels = voices.map { it.first }.toTypedArray()
                val names = voices.map { it.second.name }
                val checkedIndex = names.indexOf(currentVoice).coerceAtLeast(0)
                var selectedIndex = checkedIndex

                MaterialAlertDialogBuilder(activity, R.style.CompanionDialog)
                    .setTitle(activity.getString(R.string.dialog_voice_title))
                    .setSingleChoiceItems(labels, checkedIndex) { _, which ->
                        selectedIndex = which
                        tts.setVoice(names[which])
                        tts.speak(activity.getString(R.string.dialog_voice_preview))
                    }
                    .setPositiveButton(activity.getString(R.string.dialog_select)) { _, _ ->
                        settings.ttsVoice = names[selectedIndex]
                        tts.release()
                    }
                    .setNeutralButton(activity.getString(R.string.dialog_tune)) { _, _ ->
                        settings.ttsVoice = names[selectedIndex]
                        tts.release()
                        onTuneRequested()
                    }
                    .setNegativeButton(activity.getString(R.string.common_cancel)) { _, _ -> tts.release() }
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
                Toast.makeText(activity, activity.getString(R.string.dialog_tts_unavailable), Toast.LENGTH_LONG).show()
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
            text = activity.getString(R.string.dialog_speed_label, currentRate)
            textSize = 13f
            setTextColor(activity.getColor(R.color.text_primary))
        }
        val pitchLabel = TextView(activity).apply {
            text = activity.getString(R.string.dialog_pitch_label, currentPitch)
            textSize = 13f
            setTextColor(activity.getColor(R.color.text_primary))
        }

        rateSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                rateLabel.text = activity.getString(R.string.dialog_speed_label, 0.5f + progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        pitchSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                pitchLabel.text = activity.getString(R.string.dialog_pitch_label, 0.5f + progress / 100f)
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
                .setTitle(activity.getString(R.string.dialog_voice_tuning_title))
                .setView(container)
                .setPositiveButton(activity.getString(R.string.common_save)) { _, _ ->
                    settings.ttsSpeechRate = 0.5f + rateSeek.progress / 100f
                    settings.ttsPitch = 0.5f + pitchSeek.progress / 100f
                    tts.release()
                }
                // Placeholder handler — overridden below so Preview does NOT
                // dismiss. The default button behavior closed the dialog (and
                // leaked the manager, since only Save/Cancel release it).
                .setNeutralButton(activity.getString(R.string.dialog_preview), null)
                .setNegativeButton(activity.getString(R.string.common_cancel)) { _, _ -> tts.release() }
                .setOnCancelListener { tts.release() }
                .create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                    tts.setSpeechRate(0.5f + rateSeek.progress / 100f)
                    tts.setPitch(0.5f + pitchSeek.progress / 100f)
                    tts.speak(activity.getString(R.string.dialog_tuning_preview))
                }
            }
            dialog.show()
        }
    }
}
