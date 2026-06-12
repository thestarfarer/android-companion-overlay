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
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.starfarer.companionoverlay.event.OverlayCoordinator
import com.starfarer.companionoverlay.repository.SettingsRepository
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Settings screen fragment.
 *
 * Routes all settings reads/writes through [SettingsRepository] rather than
 * calling [PromptSettings] static methods directly. The preference XML still
 * binds to the same SharedPreferences file — the repository is used for
 * programmatic access where the preference framework doesn't handle persistence
 * automatically (int-backed lists, seekbar mappings, encrypted keys).
 *
 * [ClaudeApi] is injected for the debug test button — a settings screen
 * reaching into the API layer is unusual, but a "test connection" feature
 * is squarely a settings concern and doesn't warrant an intermediary.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    private val coordinator: OverlayCoordinator by inject()
    private val claudeAuth: ClaudeAuth by inject()
    private val claudeApi: ClaudeApi by inject()
    private val settings: SettingsRepository by inject()

    // The MCP/Nexus section (server CRUD dialogs + context fetch) lives in its
    // own controller — it was ~400 lines of inline programmatic UI.
    private val mcpUi by lazy { com.starfarer.companionoverlay.ui.McpSettingsUi(this, settings, coordinator) }

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

    private var accountTapCount = 0
    private var lastTapTime = 0L

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
        // Point at the same SharedPreferences file PromptSettings uses
        preferenceManager.sharedPreferencesName = "companion_prompts"

        setPreferencesFromResource(R.xml.settings_preferences, rootKey)

        setupGeminiApi()
        setupSilenceTimeout()
        setupVolumeShortcut()
        setupVoiceOutput()
        setupConversationLists()
        mcpUi.setup()
        setupPermissions()
        setupAccount()
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
                "gemini_stt_enabled" ->
                    findPreference<SwitchPreferenceCompat>("gemini_stt_enabled")?.isChecked = settings.geminiSttEnabled
                "gemini_tts_enabled" ->
                    findPreference<SwitchPreferenceCompat>("gemini_tts_enabled")?.isChecked = settings.geminiTtsEnabled
            }
        }
    }

    /** Read the live-toggleable settings back into their widgets (see [prefsListener]). */
    private fun syncLiveWidgets() {
        findPreference<ListPreference>("capture_mode")?.value = settings.captureMode.key
        findPreference<SwitchPreferenceCompat>("volume_toggle_enabled")?.isChecked = settings.volumeToggleEnabled
        findPreference<SwitchPreferenceCompat>("gemini_stt_enabled")?.isChecked = settings.geminiSttEnabled
        findPreference<SwitchPreferenceCompat>("gemini_tts_enabled")?.isChecked = settings.geminiTtsEnabled
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(prefsListener)
        // The listener only catches changes made WHILE resumed — re-sync the
        // live-toggleable widgets here so writes made while paused (radial menu)
        // are reflected on return.
        syncLiveWidgets()
        refreshPermissions()
        refreshAccount()
        refreshDebugTest()


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

    // ── Claude API Key ──

    private fun setupClaudeApiKey() {
        findPreference<Preference>("claude_api_key")?.apply {
            refreshApiKeySummary(this)
            setOnPreferenceClickListener {
                showApiKeyDialog()
                true
            }
        }
    }

    private fun refreshApiKeySummary(pref: Preference? = findPreference("claude_api_key")) {
        pref ?: return
        val key = settings.claudeApiKey
        pref.summary = if (key.isNullOrBlank()) getString(R.string.settings_api_key_enter)
        else getString(R.string.settings_api_key_set, key.take(10))
    }

    private fun showApiKeyDialog() {
        val ctx = context ?: return
        val d = ctx.resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val r = 12 * d
        val inputLayout = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = ctx.getString(R.string.settings_api_key_hint)
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            setBoxCornerRadii(r, r, r, r)
        }
        val editText = TextInputEditText(inputLayout.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            setText(settings.claudeApiKey ?: "")
        }
        inputLayout.addView(editText)

        val container = FrameLayout(ctx).apply {
            setPadding(pad, (12 * d).toInt(), pad, 0)
            addView(inputLayout)
        }

        MaterialAlertDialogBuilder(ctx, R.style.CompanionDialog)
            .setTitle(getString(R.string.settings_claude_api_key_title))
            .setView(container)
            .setPositiveButton(getString(R.string.common_save)) { _, _ ->
                settings.claudeApiKey = editText.text?.toString()?.trim()
                refreshApiKeySummary()
                refreshDebugTest()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()

        editText.requestFocus()
    }

    // ── Connection Type ──

    private val connectionLabels by lazy {
        arrayOf(getString(R.string.settings_connection_api_key), getString(R.string.settings_connection_oauth))
    }
    private val connectionValues = arrayOf(SettingsRepository.CONNECTION_API_KEY, SettingsRepository.CONNECTION_OAUTH)

    private fun setupConnectionType() {
        findPreference<Preference>("claude_connection_type")?.apply {
            summary = if (settings.isApiKeyMode) getString(R.string.settings_connection_api_key)
            else getString(R.string.settings_connection_oauth)
            setOnPreferenceClickListener {
                showConnectionTypeDialog()
                true
            }
        }
    }

    private fun showConnectionTypeDialog() {
        val ctx = context ?: return
        val currentIndex = connectionValues.indexOf(settings.connectionType).coerceAtLeast(0)
        var selectedIndex = currentIndex

        MaterialAlertDialogBuilder(ctx, R.style.CompanionDialog)
            .setTitle(getString(R.string.settings_connection_type_title))
            .setSingleChoiceItems(connectionLabels, currentIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(getString(R.string.common_save)) { _, _ ->
                val type = connectionValues[selectedIndex]
                settings.connectionType = type
                findPreference<Preference>("claude_connection_type")?.summary =
                    connectionLabels[selectedIndex]
                refreshAccountVisibility()
                refreshDebugTest()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    // ── Gemini API ──

    private fun setupGeminiApi() {
        findPreference<Preference>("gemini_api_key")?.apply {
            refreshGeminiKeySummary(this)
            setOnPreferenceClickListener {
                showGeminiKeyDialog()
                true
            }
        }
    }

    private fun refreshGeminiKeySummary(pref: Preference? = findPreference("gemini_api_key")) {
        pref ?: return
        val key = settings.geminiApiKey
        pref.summary = if (key.isNullOrBlank()) getString(R.string.settings_gemini_key_required)
        else getString(R.string.settings_gemini_key_set, key.take(8))
    }

    private fun showGeminiKeyDialog() {
        val ctx = context ?: return
        val d = ctx.resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val r = 12 * d
        val inputLayout = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = ctx.getString(R.string.settings_gemini_key_hint)
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            setBoxCornerRadii(r, r, r, r)
        }
        val editText = TextInputEditText(inputLayout.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            setText(settings.geminiApiKey ?: "")
        }
        inputLayout.addView(editText)

        val container = FrameLayout(ctx).apply {
            setPadding(pad, (12 * d).toInt(), pad, 0)
            addView(inputLayout)
        }

        MaterialAlertDialogBuilder(ctx, R.style.CompanionDialog)
            .setTitle(getString(R.string.settings_gemini_api_key_title))
            .setView(container)
            .setPositiveButton(getString(R.string.common_save)) { _, _ ->
                settings.geminiApiKey = editText.text?.toString()?.trim()
                refreshGeminiKeySummary()
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
        findPreference<ListPreference>("max_messages")?.apply {
            value = settings.maxMessages.toString()
            summary = getString(R.string.settings_max_messages_summary)
            setOnPreferenceChangeListener { _, newValue ->
                val count = (newValue as String).toIntOrNull() ?: PromptSettings.DEFAULT_MAX_MESSAGES
                settings.maxMessages = count
                true
            }
        }

        findPreference<ListPreference>("bubble_timeout")?.apply {
            value = settings.bubbleTimeoutSeconds.toString()
            summary = getString(R.string.settings_bubble_timeout_summary)
            setOnPreferenceChangeListener { _, newValue ->
                val secs = (newValue as String).toIntOrNull() ?: PromptSettings.DEFAULT_BUBBLE_TIMEOUT
                settings.bubbleTimeoutSeconds = secs
                true
            }
        }

        findPreference<Preference>("clear_history")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.settings_clear_history_title))
                .setMessage(getString(R.string.settings_clear_history_message))
                .setPositiveButton(getString(R.string.settings_clear)) { _, _ ->
                    // Running service clears memory + file via the event; with
                    // the overlay down, delete the stored file directly.
                    if (!coordinator.clearConversation()) {
                        val storage: ConversationStorage by inject()
                        lifecycleScope.launch { storage.clear() }
                    }
                    Toast.makeText(requireContext(), getString(R.string.settings_conversation_cleared), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.common_cancel), null)
                .show()
            true
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
        val ctx = context ?: return
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

    // ── Account ──

    private fun setupAccount() {
        setupClaudeApiKey()
        setupConnectionType()

        findPreference<Preference>("account_auth")?.setOnPreferenceClickListener {
            if (settings.isApiKeyMode && !settings.advancedUnlocked) {
                val now = System.currentTimeMillis()
                if (now - lastTapTime > 2000) accountTapCount = 0
                lastTapTime = now
                accountTapCount++
                val remaining = 10 - accountTapCount
                if (remaining in 1..3) {
                    Toast.makeText(
                        requireContext(),
                        resources.getQuantityString(R.plurals.settings_taps_to_go, remaining, remaining),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                if (accountTapCount >= 10) {
                    settings.advancedUnlocked = true
                    accountTapCount = 0
                    Toast.makeText(requireContext(), getString(R.string.settings_advanced_unlocked), Toast.LENGTH_SHORT).show()
                    refreshAccountVisibility()
                }
            } else if (!settings.isApiKeyMode) {
                handleAuthClick()
            }
            true
        }

        refreshAccountVisibility()
    }

    private fun refreshAccount() {
        context ?: return
        refreshAccountVisibility()

        if (settings.isApiKeyMode) return

        val pref = findPreference<Preference>("account_auth") ?: return
        val isAuth = claudeAuth.isAuthenticated()
        val isWaiting = claudeAuth.isWaitingForCallback()
        val isExpired = isAuth && System.currentTimeMillis() > claudeAuth.getExpiresAt()

        pref.summary = when {
            isWaiting -> getString(R.string.settings_auth_waiting)
            isAuth && !isExpired -> {
                val fmt = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                getString(R.string.settings_auth_connected_until, fmt.format(Date(claudeAuth.getExpiresAt())))
            }
            isAuth && isExpired -> getString(R.string.settings_auth_token_expired)
            else -> getString(R.string.settings_auth_not_connected)
        }
    }

    private fun refreshAccountVisibility() {
        val isApiKey = settings.isApiKeyMode
        val isAdvanced = settings.advancedUnlocked

        findPreference<Preference>("claude_api_key")?.isVisible = isApiKey
        findPreference<Preference>("claude_connection_type")?.isVisible = isAdvanced

        findPreference<Preference>("account_auth")?.apply {
            if (!isAdvanced) {
                isVisible = true
                if (isApiKey) {
                    title = getString(R.string.settings_claude_connection_title)
                    summary = getString(R.string.settings_using_api_key)
                }
            } else {
                isVisible = !isApiKey
            }
        }
    }

    private fun handleAuthClick() {
        when {
            claudeAuth.isWaitingForCallback() -> {
                claudeAuth.cancelAuth()
                refreshAccount()
                Toast.makeText(requireContext(), getString(R.string.settings_cancelled), Toast.LENGTH_SHORT).show()
            }
            claudeAuth.isAuthenticated() && System.currentTimeMillis() > claudeAuth.getExpiresAt() -> {
                lifecycleScope.launch {
                    val result = claudeAuth.refreshToken()
                    if (result.isSuccess) {
                        Toast.makeText(requireContext(), getString(R.string.settings_token_refreshed), Toast.LENGTH_SHORT).show()
                    } else {
                        claudeAuth.logout()
                        startAuth()
                    }
                    refreshAccount()
                    refreshDebugTest()
                }
            }
            claudeAuth.isAuthenticated() -> {
                claudeAuth.logout()
                refreshAccount()
                refreshDebugTest()
                Toast.makeText(requireContext(), getString(R.string.settings_logged_out), Toast.LENGTH_SHORT).show()
            }
            else -> startAuth()
        }
    }

    private fun startAuth() {
        val ctx = requireContext()
        lifecycleScope.launch {
            claudeAuth.startAuthWithCallback(ctx, object : ClaudeAuth.AuthCallback {
                override fun onAuthProgress(message: String) { refreshAccount() }
                override fun onAuthSuccess() {
                    Toast.makeText(ctx, ctx.getString(R.string.settings_connected), Toast.LENGTH_SHORT).show()
                    refreshAccount()
                    refreshDebugTest()
                }
                override fun onAuthFailure(error: String) {
                    Toast.makeText(ctx, ctx.getString(R.string.settings_auth_failed, error), Toast.LENGTH_LONG).show()
                    refreshAccount()
                }
            })
        }
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
        findPreference<Preference>("debug_test")?.setOnPreferenceClickListener {
            val pref = it
            pref.isEnabled = false
            pref.summary = getString(R.string.settings_test_thinking)
            lifecycleScope.launch {
                val response = claudeApi.chat(
                    userMessage = "Hey Senni! Say something cute in under 50 words~",
                    systemPrompt = "You are Senni, a playful and mischievous companion. Be cute, a little teasing, maybe flirty. Keep it short and sweet. Use ~, ♡, and similar flourishes sparingly."
                )
                pref.summary = if (response.success) response.text
                    else getString(R.string.settings_test_error, response.error)
                pref.isEnabled = true
            }
            true
        }

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

    private fun refreshDebugTest() {
        val canTest = if (settings.isApiKeyMode) {
            !settings.claudeApiKey.isNullOrBlank()
        } else {
            claudeAuth.isAuthenticated() && System.currentTimeMillis() <= claudeAuth.getExpiresAt()
        }
        findPreference<Preference>("debug_test")?.apply {
            isEnabled = canTest
            // Reset the summary when the connection becomes valid — the stale
            // "Set Claude API key first" / "Connect to Claude first" lingered
            // after the user fixed it.
            summary = if (canTest) getString(R.string.settings_test_ready)
                else if (settings.isApiKeyMode) getString(R.string.settings_test_need_key)
                else getString(R.string.settings_test_need_auth)
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
