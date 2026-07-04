package com.starfarer.companionoverlay

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.starfarer.companionoverlay.event.OverlayCoordinator
import com.starfarer.companionoverlay.repository.SettingsRepository
import org.koin.android.ext.android.inject

/**
 * Settings screen fragment.
 *
 * Routes all settings reads/writes through [SettingsRepository] rather than
 * touching SharedPreferences directly. The preference XML still binds to the
 * same SharedPreferences file — the repository is used for programmatic
 * access where the preference framework doesn't handle persistence
 * automatically (int-backed lists, seekbar mappings, encrypted values).
 *
 * The brain lives server-side in Nexus now: the connection section is just a
 * server URL, a bearer token (encrypted at rest), and a device name.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    private val coordinator: OverlayCoordinator by inject()
    private val settings: SettingsRepository by inject()

    companion object {
        private val LICENSES_TEXT = """
            OkHttp
            Copyright Square, Inc.
            Apache License 2.0

            ONNX Runtime
            Copyright Microsoft Corporation
            MIT License

            Silero VAD
            Copyright Silero Team
            MIT License

            Koin
            Copyright Kotzilla
            Apache License 2.0

            Kotlinx Coroutines
            Copyright JetBrains s.r.o.
            Apache License 2.0

            Kotlinx Serialization
            Copyright JetBrains s.r.o.
            Apache License 2.0

            AndroidX Libraries
            Copyright The Android Open Source Project
            Apache License 2.0

            Material Components for Android
            Copyright The Android Open Source Project
            Apache License 2.0
        """.trimIndent()
    }

    private val voicePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val ctx = context ?: return@registerForActivityResult
        when {
            results.all { it.value } -> {
                Toast.makeText(ctx, ctx.getString(R.string.settings_voice_input_ready), Toast.LENGTH_SHORT).show()
            }
            results[Manifest.permission.RECORD_AUDIO] == false -> {
                Toast.makeText(ctx, ctx.getString(R.string.settings_voice_needs_mic), Toast.LENGTH_LONG).show()
            }
            else -> {
                // Mic granted but Bluetooth denied — voice works, just not over BT.
                Toast.makeText(ctx, ctx.getString(R.string.settings_bluetooth_mic_denied), Toast.LENGTH_LONG).show()
            }
        }
        refreshPermissions()
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val ctx = context ?: return@registerForActivityResult
        if (granted) {
            Toast.makeText(ctx, ctx.getString(R.string.settings_camera_ready), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, ctx.getString(R.string.settings_camera_needs_permission), Toast.LENGTH_LONG).show()
        }
        refreshPermissions()
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val ctx = context ?: return@registerForActivityResult
        if (granted) {
            Toast.makeText(ctx, ctx.getString(R.string.settings_notifications_ready), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, ctx.getString(R.string.settings_notifications_denied), Toast.LENGTH_LONG).show()
        }
        refreshPermissions()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Point at the same SharedPreferences file the repository uses
        preferenceManager.sharedPreferencesName = "companion_prompts"

        setPreferencesFromResource(R.xml.settings_preferences, rootKey)

        setupGateway()
        setupSilenceTimeout()
        setupVolumeShortcut()
        setupVoiceOutput()
        setupConversationLists()
        setupPermissions()
        setupAbout()
        setupDebug()
    }

    private var pendingHighlightKey: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pendingHighlightKey = arguments?.getString(SettingsActivity.EXTRA_HIGHLIGHT_KEY)
        arguments?.remove(SettingsActivity.EXTRA_HIGHLIGHT_KEY)
    }

    /**
     * Keep visible widgets in sync when settings are changed elsewhere (the radial quick menu
     * writes to the same prefs while this screen is still resumed behind the overlay).
     * PreferenceFragmentCompat only reads widget state at bind time, so we push live updates.
     */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        activity?.runOnUiThread {
            when (key) {
                "capture_mode" ->
                    findPreference<ListPreference>("capture_mode")?.value = settings.captureMode.key
                "volume_toggle_enabled" ->
                    findPreference<SwitchPreferenceCompat>("volume_toggle_enabled")?.isChecked = settings.volumeToggleEnabled
                "tts_enabled" ->
                    findPreference<SwitchPreferenceCompat>("tts_enabled")?.isChecked = settings.ttsEnabled
            }
        }
    }

    /** Read the live-toggleable settings back into their widgets (see [prefsListener]). */
    private fun syncLiveWidgets() {
        findPreference<ListPreference>("capture_mode")?.value = settings.captureMode.key
        findPreference<SwitchPreferenceCompat>("volume_toggle_enabled")?.isChecked = settings.volumeToggleEnabled
        findPreference<SwitchPreferenceCompat>("tts_enabled")?.isChecked = settings.ttsEnabled
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(prefsListener)
        // The listener only catches changes made WHILE resumed — re-sync the
        // live-toggleable widgets here so writes made while paused (radial menu)
        // are reflected on return.
        syncLiveWidgets()
        refreshPermissions()

        val key = pendingHighlightKey ?: return
        pendingHighlightKey = null
        scrollToPreference(key)
        listView.post {
            findPreference<Preference>(key)?.performClick()
        }
    }

    override fun onPause() {
        super.onPause()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    // ── Nexus gateway ──

    private fun setupGateway() {
        findPreference<Preference>("gateway_url")?.apply {
            refreshGatewayUrlSummary(this)
            setOnPreferenceClickListener {
                showTextDialog(
                    title = getString(R.string.settings_gateway_url_title),
                    hint = getString(R.string.settings_gateway_url_hint),
                    current = settings.gatewayUrl,
                    password = false
                ) { value ->
                    settings.gatewayUrl = value
                    refreshGatewayUrlSummary()
                    coordinator.gatewayConfigChanged()
                }
                true
            }
        }

        findPreference<Preference>("gateway_token")?.apply {
            refreshGatewayTokenSummary(this)
            setOnPreferenceClickListener {
                showTextDialog(
                    title = getString(R.string.settings_gateway_token_title),
                    hint = getString(R.string.settings_gateway_token_hint),
                    current = settings.gatewayToken,
                    password = true
                ) { value ->
                    settings.gatewayToken = value
                    refreshGatewayTokenSummary()
                    coordinator.gatewayConfigChanged()
                }
                true
            }
        }

        findPreference<Preference>("gateway_device_name")?.apply {
            summary = settings.deviceNameSetting
            setOnPreferenceClickListener {
                showTextDialog(
                    title = getString(R.string.settings_gateway_device_name_title),
                    hint = getString(R.string.settings_gateway_device_name_hint),
                    current = settings.deviceNameSetting,
                    password = false
                ) { value ->
                    settings.deviceNameSetting = value ?: ""
                    summary = settings.deviceNameSetting
                    coordinator.gatewayConfigChanged()
                }
                true
            }
        }
    }

    private fun refreshGatewayUrlSummary(pref: Preference? = findPreference("gateway_url")) {
        pref ?: return
        val url = settings.gatewayUrl
        pref.summary = if (url.isNullOrBlank()) getString(R.string.settings_gateway_url_not_set) else url
    }

    private fun refreshGatewayTokenSummary(pref: Preference? = findPreference("gateway_token")) {
        pref ?: return
        val token = settings.gatewayToken
        pref.summary = if (token.isNullOrBlank()) getString(R.string.settings_gateway_token_not_set)
        else getString(R.string.settings_gateway_token_set, token.take(6))
    }

    /** Shared single-field text dialog (URL, token, device name). */
    private fun showTextDialog(
        title: String,
        hint: String,
        current: String?,
        password: Boolean,
        onSave: (String?) -> Unit
    ) {
        val ctx = context ?: return
        val d = ctx.resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val r = 12 * d
        val inputLayout = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            this.hint = hint
            if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            setBoxCornerRadii(r, r, r, r)
        }
        val editText = TextInputEditText(inputLayout.context).apply {
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            isSingleLine = true
            setText(current ?: "")
        }
        inputLayout.addView(editText)

        val container = FrameLayout(ctx).apply {
            setPadding(pad, (12 * d).toInt(), pad, 0)
            addView(inputLayout)
        }

        MaterialAlertDialogBuilder(ctx, R.style.CompanionDialog)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(getString(R.string.common_save)) { _, _ ->
                onSave(editText.text?.toString()?.trim())
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()

        editText.requestFocus()
    }

    // ── Silence timeout ──

    private fun setupSilenceTimeout() {
        findPreference<SeekBarPreference>("silence_timeout_seek")?.apply {
            val savedMs = settings.silenceTimeoutMs
            // Range 1–50 maps to 100ms–5000ms; step N = N×100ms. The load
            // mapping must invert the save mapping (ms = step×100) exactly —
            // it used (ms-100)/100, one step low, so the seekbar position
            // drifted down by 100ms relative to the saved value.
            value = (savedMs / 100).toInt().coerceIn(1, 50)
            summary = formatSilenceMs(savedMs)

            setOnPreferenceChangeListener { pref, newValue ->
                val steps = newValue as Int
                val ms = steps * 100L + 0L  // step 1 = 100ms, step 50 = 5000ms
                settings.silenceTimeoutMs = ms
                pref.summary = formatSilenceMs(ms)
                true
            }
        }
    }

    private fun formatSilenceMs(ms: Long): String {
        val sec = ms / 1000.0
        return getString(R.string.settings_silence_timeout_summary, sec)
    }

    // ── Volume shortcut ──

    private fun setupVolumeShortcut() {
        findPreference<SwitchPreferenceCompat>("volume_toggle_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) {
                Toast.makeText(requireContext(), getString(R.string.settings_volume_toggle_warning), Toast.LENGTH_LONG).show()
            }
            true
        }
    }

    // ── Voice output ──

    private fun setupVoiceOutput() {
        findPreference<Preference>("tts_voice_picker")?.setOnPreferenceClickListener {
            val activity = requireActivity()
            SettingsDialogs.showVoicePicker(activity, settings) { SettingsDialogs.showTuning(activity, settings) }
            true
        }
    }

    // ── Conversation lists (non-persistent, int-backed) ──

    private fun setupConversationLists() {
        findPreference<ListPreference>("bubble_timeout")?.apply {
            value = settings.bubbleTimeoutSeconds.toString()
            summary = getString(R.string.settings_bubble_timeout_summary)
            setOnPreferenceChangeListener { _, newValue ->
                val secs = (newValue as String).toIntOrNull() ?: PromptSettings.DEFAULT_BUBBLE_TIMEOUT
                settings.bubbleTimeoutSeconds = secs
                true
            }
        }
    }

    // ── Permissions ──

    private fun setupPermissions() {
        findPreference<Preference>("perm_accessibility")?.setOnPreferenceClickListener {
            val ctx = requireContext()
            if (coordinator.accessibilityRunning.value) {
                Toast.makeText(ctx, ctx.getString(R.string.settings_already_enabled), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, ctx.getString(R.string.settings_enable_accessibility), Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            true
        }

        findPreference<Preference>("perm_voice")?.setOnPreferenceClickListener {
            if (hasVoicePerms()) {
                Toast.makeText(requireContext(), getString(R.string.settings_already_granted), Toast.LENGTH_SHORT).show()
            } else {
                val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                }
                voicePermLauncher.launch(perms.toTypedArray())
            }
            true
        }

        findPreference<Preference>("perm_camera")?.setOnPreferenceClickListener {
            if (hasCameraPerm()) {
                Toast.makeText(requireContext(), getString(R.string.settings_already_granted), Toast.LENGTH_SHORT).show()
            } else {
                cameraPermLauncher.launch(Manifest.permission.CAMERA)
            }
            true
        }

        findPreference<Preference>("perm_notifications")?.setOnPreferenceClickListener {
            if (hasNotifPerm()) {
                Toast.makeText(requireContext(), getString(R.string.settings_already_granted), Toast.LENGTH_SHORT).show()
            } else {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            true
        }
    }

    private fun hasCameraPerm(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    // POST_NOTIFICATIONS is runtime-requestable from API 33; below that it is
    // granted by install (minSdk 31 covers 31/32).
    private fun hasNotifPerm(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun refreshPermissions() {
        context ?: return
        findPreference<Preference>("perm_accessibility")?.summary =
            if (coordinator.accessibilityRunning.value) getString(R.string.settings_perm_enabled)
            else getString(R.string.settings_perm_accessibility_desc)

        findPreference<Preference>("perm_voice")?.summary =
            if (hasVoicePerms()) getString(R.string.settings_perm_granted)
            else getString(R.string.settings_perm_voice_desc)

        findPreference<Preference>("perm_camera")?.summary =
            if (hasCameraPerm()) getString(R.string.settings_perm_granted)
            else getString(R.string.settings_perm_camera_desc)

        findPreference<Preference>("perm_notifications")?.summary =
            if (hasNotifPerm()) getString(R.string.settings_perm_granted)
            else getString(R.string.settings_perm_notifications_desc)
    }

    // ── About ──

    private fun setupAbout() {
        findPreference<Preference>("tutorial")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), TutorialActivity::class.java))
            true
        }
        findPreference<Preference>("open_source_licenses")?.setOnPreferenceClickListener {
            val ctx = context ?: return@setOnPreferenceClickListener true
            MaterialAlertDialogBuilder(ctx, R.style.CompanionDialog)
                .setTitle(getString(R.string.settings_open_source_licenses_title))
                .setMessage(LICENSES_TEXT)
                .setPositiveButton(getString(R.string.common_close), null)
                .show()
            true
        }
    }

    // ── Debug ──

    private fun setupDebug() {
        findPreference<SwitchPreferenceCompat>("save_sent_images")?.apply {
            isChecked = settings.saveSentImages
            setOnPreferenceChangeListener { _, newValue ->
                settings.saveSentImages = newValue as Boolean
                true
            }
        }

        findPreference<Preference>("debug_view_image")?.setOnPreferenceClickListener {
            val ctx = requireContext()
            val file = ImageAudit.latest(ctx)
            if (file == null) {
                Toast.makeText(ctx, ctx.getString(R.string.settings_no_saved_images), Toast.LENGTH_LONG).show()
            } else {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, "${ctx.packageName}.fileprovider", file
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/jpeg")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(ctx, ctx.getString(R.string.settings_no_image_viewer), Toast.LENGTH_SHORT).show()
                }
            }
            true
        }

        findPreference<Preference>("debug_copy_log")?.setOnPreferenceClickListener {
            val ctx = requireContext()
            val log = DebugLog.getLog()
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Senni Debug Log", log))
            Toast.makeText(ctx, ctx.getString(R.string.settings_log_copied, log.length), Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<Preference>("debug_clear_log")?.setOnPreferenceClickListener {
            DebugLog.clear()
            Toast.makeText(requireContext(), getString(R.string.settings_log_cleared), Toast.LENGTH_SHORT).show()
            true
        }
    }


    // ── Helpers ──

    private fun hasVoicePerms(): Boolean {
        val ctx = context ?: return false
        val mic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val bt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
        return mic && bt
    }
}
