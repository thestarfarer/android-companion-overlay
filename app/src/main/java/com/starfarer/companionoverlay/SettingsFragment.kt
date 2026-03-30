package com.starfarer.companionoverlay

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import com.starfarer.companionoverlay.event.OverlayCoordinator
import com.starfarer.companionoverlay.mcp.McpAuthType
import com.starfarer.companionoverlay.mcp.McpClient
import com.starfarer.companionoverlay.mcp.McpManager
import com.starfarer.companionoverlay.mcp.McpRepository
import com.starfarer.companionoverlay.mcp.McpServerConfig
import com.starfarer.companionoverlay.mcp.NexusContextFetcher
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
        if (results.all { it.value }) {
            Toast.makeText(ctx, "Voice input ready~", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, "Voice needs microphone permission", Toast.LENGTH_LONG).show()
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
        setupMcpServers()
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

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshAccount()
        refreshDebugTest()

        applyNexusLongPress()

        val key = pendingHighlightKey ?: return
        pendingHighlightKey = null
        scrollToPreference(key)
        listView.post {
            findPreference<Preference>(key)?.performClick()
        }
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
        pref.summary = if (key.isNullOrBlank()) "Enter your sk-ant-... API key"
        else "Key set (${key.take(10)}...)"
    }

    private fun showApiKeyDialog() {
        val ctx = context ?: return
        val d = ctx.resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val r = 12 * d
        val inputLayout = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "sk-ant-..."
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
            .setTitle("Claude API Key")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                settings.claudeApiKey = editText.text?.toString()?.trim()
                refreshApiKeySummary()
                refreshDebugTest()
            }
            .setNegativeButton("Cancel", null)
            .show()

        editText.requestFocus()
    }

    // ── Connection Type ──

    private val connectionLabels = arrayOf("API Key", "Claude Code OAuth")
    private val connectionValues = arrayOf(SettingsRepository.CONNECTION_API_KEY, SettingsRepository.CONNECTION_OAUTH)

    private fun setupConnectionType() {
        findPreference<Preference>("claude_connection_type")?.apply {
            summary = if (settings.isApiKeyMode) "API Key" else "Claude Code OAuth"
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
            .setTitle("Connection type")
            .setSingleChoiceItems(connectionLabels, currentIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("Save") { _, _ ->
                val type = connectionValues[selectedIndex]
                settings.connectionType = type
                findPreference<Preference>("claude_connection_type")?.summary =
                    connectionLabels[selectedIndex]
                refreshAccountVisibility()
                refreshDebugTest()
            }
            .setNegativeButton("Cancel", null)
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
        pref.summary = if (key.isNullOrBlank()) "Required for Gemini speech-to-text and text-to-speech"
        else "Key set (${key.take(8)}…)"
    }

    private fun showGeminiKeyDialog() {
        val ctx = context ?: return
        val d = ctx.resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val r = 12 * d
        val inputLayout = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "AIza..."
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
            .setTitle("Gemini API Key")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                settings.geminiApiKey = editText.text?.toString()?.trim()
                refreshGeminiKeySummary()
            }
            .setNegativeButton("Cancel", null)
            .show()

        editText.requestFocus()
    }

    // ── Silence timeout ──

    private fun setupSilenceTimeout() {
        findPreference<SeekBarPreference>("silence_timeout_seek")?.apply {
            val savedMs = settings.silenceTimeoutMs
            // Range 1–50 maps to 100ms–5000ms in steps of 100
            value = ((savedMs - 100) / 100).toInt().coerceIn(1, 50)
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
        return "${"%.1f".format(sec)}s — wait this long after silence before sending"
    }

    // ── Volume shortcut ──

    private fun setupVolumeShortcut() {
        findPreference<SwitchPreferenceCompat>("volume_toggle_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) {
                Toast.makeText(requireContext(), "This option will interfere with long press for volume down", Toast.LENGTH_LONG).show()
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
            summary = "Keep %s messages in context"
            setOnPreferenceChangeListener { _, newValue ->
                val count = (newValue as String).toIntOrNull() ?: PromptSettings.DEFAULT_MAX_MESSAGES
                settings.maxMessages = count
                true
            }
        }

        findPreference<ListPreference>("bubble_timeout")?.apply {
            value = settings.bubbleTimeoutSeconds.toString()
            summary = "Dismiss after %s seconds"
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
                Toast.makeText(ctx, "Already enabled~", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "Enable 'Senni Overlay' in Accessibility settings", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            true
        }

        findPreference<Preference>("perm_voice")?.setOnPreferenceClickListener {
            if (hasVoicePerms()) {
                Toast.makeText(requireContext(), "Already granted~", Toast.LENGTH_SHORT).show()
            } else {
                val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                }
                voicePermLauncher.launch(perms.toTypedArray())
            }
            true
        }
    }

    private fun refreshPermissions() {
        val ctx = context ?: return
        findPreference<Preference>("perm_accessibility")?.summary =
            if (coordinator.accessibilityRunning.value) "✓ Enabled"
            else "Required for screenshots and volume button shortcut"

        findPreference<Preference>("perm_voice")?.summary =
            if (hasVoicePerms()) "✓ Granted"
            else "Microphone and Bluetooth permissions for voice recording"
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
                    Toast.makeText(requireContext(), "$remaining taps to go...", Toast.LENGTH_SHORT).show()
                }
                if (accountTapCount >= 10) {
                    settings.advancedUnlocked = true
                    accountTapCount = 0
                    Toast.makeText(requireContext(), "Advanced mode unlocked", Toast.LENGTH_SHORT).show()
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
            isWaiting -> "Waiting for browser… (tap to cancel)"
            isAuth && !isExpired -> {
                val fmt = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                "Connected until ${fmt.format(Date(claudeAuth.getExpiresAt()))} — tap to log out"
            }
            isAuth && isExpired -> "Token expired — tap to reconnect"
            else -> "Not connected — tap to log in"
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
                    title = "Claude connection"
                    summary = "Using API Key"
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
                Toast.makeText(requireContext(), "Cancelled", Toast.LENGTH_SHORT).show()
            }
            claudeAuth.isAuthenticated() && System.currentTimeMillis() > claudeAuth.getExpiresAt() -> {
                lifecycleScope.launch {
                    val result = claudeAuth.refreshToken()
                    if (result.isSuccess) {
                        Toast.makeText(requireContext(), "Token refreshed~", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(requireContext(), "Logged out~", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(ctx, "Connected~", Toast.LENGTH_SHORT).show()
                    refreshAccount()
                    refreshDebugTest()
                }
                override fun onAuthFailure(error: String) {
                    Toast.makeText(ctx, "Failed: $error", Toast.LENGTH_LONG).show()
                    refreshAccount()
                }
            })
        }
    }

    // ── About ──

    private fun setupAbout() {
        findPreference<Preference>("open_source_licenses")?.setOnPreferenceClickListener {
            val ctx = context ?: return@setOnPreferenceClickListener true
            MaterialAlertDialogBuilder(ctx, R.style.CompanionDialog)
                .setTitle("Open source licenses")
                .setMessage(LICENSES_TEXT)
                .setPositiveButton("Close", null)
                .show()
            true
        }
    }

    // ── Debug ──

    private fun setupDebug() {
        findPreference<Preference>("debug_test")?.setOnPreferenceClickListener {
            val pref = it
            pref.isEnabled = false
            pref.summary = "Thinking…"
            lifecycleScope.launch {
                val response = claudeApi.chat(
                    userMessage = "Hey Senni! Say something cute in under 50 words~",
                    systemPrompt = "You are Senni, a playful and mischievous companion. Be cute, a little teasing, maybe flirty. Keep it short and sweet. Use ~, ♡, and similar flourishes sparingly."
                )
                pref.summary = if (response.success) response.text
                    else "Error: ${response.error}"
                pref.isEnabled = true
            }
            true
        }

        findPreference<Preference>("debug_copy_log")?.setOnPreferenceClickListener {
            val ctx = requireContext()
            val log = DebugLog.getLog()
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Senni Debug Log", log))
            Toast.makeText(ctx, "Log copied! (${log.length} chars)", Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<Preference>("debug_clear_log")?.setOnPreferenceClickListener {
            DebugLog.clear()
            Toast.makeText(requireContext(), "Log cleared", Toast.LENGTH_SHORT).show()
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
            if (!canTest) {
                summary = if (settings.isApiKeyMode) "Set Claude API key first"
                    else "Connect to Claude first"
            }
        }
    }

    // ── MCP Servers ──

    private fun setupMcpServers() {
        findPreference<SwitchPreferenceCompat>("mcp_enabled")?.apply {
            isChecked = settings.mcpEnabled
            setOnPreferenceChangeListener { _, newValue ->
                settings.mcpEnabled = newValue as Boolean
                true
            }
        }

        findPreference<Preference>("mcp_manage_servers")?.apply {
            refreshMcpServersSummary(this)
            setOnPreferenceClickListener {
                showMcpServersDialog()
                true
            }
        }

        findPreference<Preference>("nexus_fetch_context")?.apply {
            refreshNexusContextSummary(this)
            setOnPreferenceClickListener {
                fetchNexusContext(this)
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("nexus_context_append_to_prompt")?.apply {
            isChecked = settings.nexusContextAppendToPrompt
            setOnPreferenceChangeListener { _, newValue ->
                settings.nexusContextAppendToPrompt = newValue as Boolean
                true
            }
        }
    }

    private fun applyNexusLongPress() {
        val rv = listView ?: return
        rv.post {
            val adapter = rv.adapter ?: return@post
            for (i in 0 until adapter.itemCount) {
                val holder = rv.findViewHolderForAdapterPosition(i) ?: continue
                val pref = (holder as? PreferenceViewHolder)
                // Match by checking the preference at this position
                val prefAdapter = adapter as? PreferenceGroupAdapter ?: return@post
                if (i < prefAdapter.itemCount && prefAdapter.getItem(i)?.key == "nexus_fetch_context") {
                    holder.itemView.setOnLongClickListener {
                        showCachedNexusContext()
                        true
                    }
                    return@post
                }
            }
        }
    }

        private fun showCachedNexusContext() {
        val cache = settings.nexusContextCache
        if (cache == null) {
            Toast.makeText(requireContext(), "No cached context", Toast.LENGTH_SHORT).show()
            return
        }

        val scrollView = android.widget.ScrollView(requireContext()).apply {
            setPadding(48, 32, 48, 32)
        }
        val textView = android.widget.TextView(requireContext()).apply {
            text = cache
            textSize = 13f
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Nexus Context")
            .setView(scrollView)
            .setPositiveButton("OK", null)
            .setNeutralButton("Clear") { _, _ ->
                settings.nexusContextCache = null
                settings.nexusContextTimestamp = 0
                refreshNexusContextSummary()
                Toast.makeText(requireContext(), "Context cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

        private fun refreshNexusContextSummary(pref: Preference? = findPreference("nexus_fetch_context")) {
        pref ?: return
        val ts = settings.nexusContextTimestamp
        val cache = settings.nexusContextCache
        pref.summary = when {
            ts == 0L || cache == null -> "Never fetched"
            else -> {
                val date = SimpleDateFormat("EEE, MMM d HH:mm", Locale.getDefault())
                    .format(Date(ts))
                val chars = cache.length
                "Last fetched: $date (${chars} chars)"
            }
        }
    }

    private fun fetchNexusContext(pref: Preference) {
        val mcpManager: McpManager by inject()
        val fetcher = NexusContextFetcher(mcpManager, settings)

        pref.summary = "Fetching..."
        pref.isEnabled = false

        lifecycleScope.launch {
            val result = fetcher.fetch()
            pref.isEnabled = true
            result.fold(
                onSuccess = { context ->
                    refreshNexusContextSummary(pref)
                    Toast.makeText(requireContext(), "Context fetched (${context.length} chars)", Toast.LENGTH_SHORT).show()
                },
                onFailure = { error ->
                    pref.summary = "Error: ${error.message?.take(60)}"
                    Toast.makeText(requireContext(), "Fetch failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun refreshMcpServersSummary(pref: Preference? = findPreference("mcp_manage_servers")) {
        pref ?: return
        val mcpRepo: McpRepository by inject()
        val servers = mcpRepo.loadServers()
        pref.summary = if (servers.isEmpty()) "No servers configured"
        else "${servers.size} server(s) configured"
    }

    private fun showMcpServersDialog() {
        val ctx = context ?: return
        val mcpRepo: McpRepository by inject()
        val servers = mcpRepo.loadServers()

        if (servers.isEmpty()) {
            showMcpServerFormDialog(null)
            return
        }

        val d = ctx.resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val scrollView = android.widget.ScrollView(ctx)
        val listContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, 0)
        }

        for (server in servers) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            }

            val nameLabel = android.widget.TextView(ctx).apply {
                text = server.name
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                setOnClickListener { showMcpServerFormDialog(server) }
            }

            val checkButton = android.widget.Button(ctx, null,
                com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "Check"
                textSize = 13f
                minWidth = 0
                minimumWidth = 0
                setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                setOnClickListener { checkMcpServer(server) }
            }

            row.addView(nameLabel)
            row.addView(checkButton)
            listContainer.addView(row)
        }

        scrollView.addView(listContainer)

        MaterialAlertDialogBuilder(ctx, R.style.CompanionDialog)
            .setTitle("MCP Servers")
            .setView(scrollView)
            .setPositiveButton("Add server") { _, _ ->
                showMcpServerFormDialog(null)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun checkMcpServer(config: McpServerConfig) {
        val ctx = context ?: return
        val mcpRepo: McpRepository by inject()
        val baseClient: okhttp3.OkHttpClient by inject()

        Toast.makeText(ctx, "Connecting to ${config.name}...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val client = McpClient(
                baseClient = baseClient,
                config = config,
                secretProvider = { mcpRepo.getClientSecret(config.id) }
            )

            val result = client.initialize()
            client.disconnect()

            if (result.isSuccess) {
                val tools = result.getOrDefault(emptyList())
                val toolList = if (tools.isEmpty()) "No tools found."
                else tools.joinToString("\n") { "• ${it.name}" +
                    (it.description?.let { d -> " — $d" } ?: "") }

                MaterialAlertDialogBuilder(ctx, R.style.CompanionDialog)
                    .setTitle("${config.name} — ${tools.size} tools")
                    .setMessage(toolList)
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                MaterialAlertDialogBuilder(ctx, R.style.CompanionDialog)
                    .setTitle("${config.name} — Failed")
                    .setMessage(error)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun showMcpServerFormDialog(existing: McpServerConfig?) {
        val ctx = context ?: return
        val mcpRepo: McpRepository by inject()
        val d = ctx.resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val r = 12 * d

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (12 * d).toInt(), pad, 0)
        }

        // Name field
        val nameLayout = TextInputLayout(ctx, null,
            com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Server name"
            setBoxCornerRadii(r, r, r, r)
        }
        val nameEdit = TextInputEditText(nameLayout.context).apply {
            isSingleLine = true
            setText(existing?.name ?: "")
        }
        nameLayout.addView(nameEdit)
        container.addView(nameLayout)

        // URL field
        val urlLayout = TextInputLayout(ctx, null,
            com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Server URL (https://...)"
            setBoxCornerRadii(r, r, r, r)
        }
        val urlEdit = TextInputEditText(urlLayout.context).apply {
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.url ?: "")
        }
        urlLayout.addView(urlEdit)
        container.addView(urlLayout)

        // Auth type radio group
        val radioGroup = RadioGroup(ctx).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        }
        val radioNone = RadioButton(ctx).apply {
            text = "No auth"
            id = android.view.View.generateViewId()
        }
        val radioCredentials = RadioButton(ctx).apply {
            text = "Client Credentials"
            id = android.view.View.generateViewId()
        }
        radioGroup.addView(radioNone)
        radioGroup.addView(radioCredentials)
        container.addView(radioGroup)

        // Client ID field
        val clientIdLayout = TextInputLayout(ctx, null,
            com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Client ID"
            setBoxCornerRadii(r, r, r, r)
            visibility = if (existing?.authType == McpAuthType.CLIENT_CREDENTIALS)
                android.view.View.VISIBLE else android.view.View.GONE
        }
        val clientIdEdit = TextInputEditText(clientIdLayout.context).apply {
            isSingleLine = true
            setText(existing?.clientId ?: "")
        }
        clientIdLayout.addView(clientIdEdit)
        container.addView(clientIdLayout)

        // Client Secret field
        val secretLayout = TextInputLayout(ctx, null,
            com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Client Secret"
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            setBoxCornerRadii(r, r, r, r)
            visibility = clientIdLayout.visibility
        }
        val secretEdit = TextInputEditText(secretLayout.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        secretLayout.addView(secretEdit)
        container.addView(secretLayout)

        // Set initial radio selection and toggle credential fields
        if (existing?.authType == McpAuthType.CLIENT_CREDENTIALS) {
            radioGroup.check(radioCredentials.id)
        } else {
            radioGroup.check(radioNone.id)
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val showCreds = checkedId == radioCredentials.id
            val vis = if (showCreds) android.view.View.VISIBLE else android.view.View.GONE
            clientIdLayout.visibility = vis
            secretLayout.visibility = vis
        }

        val builder = MaterialAlertDialogBuilder(ctx, R.style.CompanionDialog)
            .setTitle(if (existing != null) "Edit Server" else "Add MCP Server")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = nameEdit.text?.toString()?.trim() ?: return@setPositiveButton
                val url = urlEdit.text?.toString()?.trim() ?: return@setPositiveButton
                if (name.isBlank() || url.isBlank()) return@setPositiveButton

                val authType = if (radioGroup.checkedRadioButtonId == radioCredentials.id)
                    McpAuthType.CLIENT_CREDENTIALS else McpAuthType.NONE

                val config = McpServerConfig(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name = name,
                    url = url,
                    authType = authType,
                    clientId = clientIdEdit.text?.toString()?.trim()?.ifBlank { null },
                    enabled = existing?.enabled ?: true
                )

                val secret = secretEdit.text?.toString()?.trim()?.ifBlank { null }

                if (existing != null) {
                    mcpRepo.updateServer(config, secret)
                } else {
                    mcpRepo.addServer(config, secret)
                }

                refreshMcpServersSummary()
                coordinator.reloadMcp()
            }
            .setNegativeButton("Cancel", null)

        if (existing != null) {
            builder.setNeutralButton("Delete") { _, _ ->
                mcpRepo.removeServer(existing.id)
                refreshMcpServersSummary()
                coordinator.reloadMcp()
                Toast.makeText(ctx, "Server removed", Toast.LENGTH_SHORT).show()
            }
        }

        builder.show()
        nameEdit.requestFocus()
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
