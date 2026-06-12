package com.starfarer.companionoverlay.ui

import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.starfarer.companionoverlay.R
import com.starfarer.companionoverlay.event.OverlayCoordinator
import com.starfarer.companionoverlay.mcp.McpAuthType
import com.starfarer.companionoverlay.mcp.McpClient
import com.starfarer.companionoverlay.mcp.McpManager
import com.starfarer.companionoverlay.mcp.McpRepository
import com.starfarer.companionoverlay.mcp.McpServerConfig
import com.starfarer.companionoverlay.mcp.NexusContextFetcher
import com.starfarer.companionoverlay.repository.SettingsRepository
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The MCP-server and Nexus-context section of the settings screen.
 *
 * Extracted from SettingsFragment, which had ~400 lines of programmatic CRUD
 * dialogs inline. Behavior is unchanged — this owns the preference wiring and
 * the add/edit/check/fetch dialogs and talks to the same repositories. Hosted
 * by [fragment] for context, lifecycle scope, and preference lookup.
 */
class McpSettingsUi(
    private val fragment: PreferenceFragmentCompat,
    private val settings: SettingsRepository,
    private val coordinator: OverlayCoordinator
) : KoinComponent {

    private val mcpRepo: McpRepository by inject()
    private val mcpManager: McpManager by inject()
    private val baseClient: OkHttpClient by inject()

    fun setup() {
        fragment.findPreference<SwitchPreferenceCompat>("mcp_enabled")?.apply {
            isChecked = settings.mcpEnabled
            setOnPreferenceChangeListener { _, newValue ->
                settings.mcpEnabled = newValue as Boolean
                true
            }
        }

        fragment.findPreference<Preference>("mcp_manage_servers")?.apply {
            refreshMcpServersSummary(this)
            setOnPreferenceClickListener {
                showMcpServersDialog()
                true
            }
        }

        fragment.findPreference<Preference>("nexus_context_prompt")?.apply {
            summary = settings.nexusContextPrompt.take(80).let {
                if (settings.nexusContextPrompt.length > 80) "$it..." else it
            }
            setOnPreferenceClickListener {
                showNexusPromptEditor(this)
                true
            }
        }

        fragment.findPreference<LongClickPreference>("nexus_fetch_context")?.apply {
            refreshNexusContextSummary(this)
            setOnPreferenceClickListener {
                fetchNexusContext(this)
                true
            }
            onLongClick = { showCachedNexusContext() }
        }

        fragment.findPreference<SwitchPreferenceCompat>("nexus_context_append_to_prompt")?.apply {
            isChecked = settings.nexusContextAppendToPrompt
            setOnPreferenceChangeListener { _, newValue ->
                settings.nexusContextAppendToPrompt = newValue as Boolean
                true
            }
        }
    }

    private fun showNexusPromptEditor(pref: Preference) {
        val ctx = fragment.context ?: return
        val inputLayout = TextInputLayout(ctx).apply {
            setPadding(48, 16, 48, 0)
        }
        val editText = TextInputEditText(inputLayout.context).apply {
            setText(settings.nexusContextPrompt)
            minLines = 3
            maxLines = 6
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        inputLayout.addView(editText)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Context Prompt")
            .setView(inputLayout)
            .setPositiveButton("Save") { _, _ ->
                val text = editText.text?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    settings.nexusContextPrompt = text
                    pref.summary = text.take(80).let {
                        if (text.length > 80) "$it..." else it
                    }
                }
            }
            .setNeutralButton("Reset") { _, _ ->
                settings.nexusContextPrompt = "What happened recently? What are we up to? What should I know?"
                pref.summary = settings.nexusContextPrompt.take(80)
            }
            .setNegativeButton("Cancel", null)
            .show()

        editText.requestFocus()
    }

    private fun showCachedNexusContext() {
        val ctx = fragment.context ?: return
        val cache = settings.nexusContextCache
        if (cache == null) {
            Toast.makeText(ctx, "No cached context", Toast.LENGTH_SHORT).show()
            return
        }

        val scrollView = android.widget.ScrollView(ctx).apply {
            setPadding(48, 32, 48, 32)
        }
        val textView = android.widget.TextView(ctx).apply {
            text = cache
            textSize = 13f
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Nexus Context")
            .setView(scrollView)
            .setPositiveButton("OK", null)
            .setNeutralButton("Clear") { _, _ ->
                settings.nexusContextCache = null
                settings.nexusContextTimestamp = 0
                refreshNexusContextSummary()
                Toast.makeText(ctx, "Context cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun refreshNexusContextSummary(
        pref: Preference? = fragment.findPreference("nexus_fetch_context")
    ) {
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
        val fetcher = NexusContextFetcher(mcpManager, settings)

        pref.summary = "Fetching..."
        pref.isEnabled = false

        fragment.lifecycleScope.launch {
            val result = fetcher.fetch()
            pref.isEnabled = true
            result.fold(
                onSuccess = { context ->
                    refreshNexusContextSummary(pref)
                    Toast.makeText(fragment.requireContext(), "Context fetched (${context.length} chars)", Toast.LENGTH_SHORT).show()
                },
                onFailure = { error ->
                    pref.summary = "Error: ${error.message?.take(60)}"
                    Toast.makeText(fragment.requireContext(), "Fetch failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun refreshMcpServersSummary(
        pref: Preference? = fragment.findPreference("mcp_manage_servers")
    ) {
        pref ?: return
        val servers = mcpRepo.loadServers()
        pref.summary = if (servers.isEmpty()) "No servers configured"
        else "${servers.size} server(s) configured"
    }

    private fun showMcpServersDialog() {
        val ctx = fragment.context ?: return
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
        val ctx = fragment.context ?: return

        Toast.makeText(ctx, "Connecting to ${config.name}...", Toast.LENGTH_SHORT).show()

        fragment.lifecycleScope.launch {
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
        val ctx = fragment.context ?: return
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

        // Client Secret field. When one is already stored, say so and treat
        // blank as "keep" — the field can't show the saved value.
        val hasStoredSecret = existing != null && mcpRepo.hasClientSecret(existing.id)
        val secretLayout = TextInputLayout(ctx, null,
            com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = if (hasStoredSecret) "Client Secret (saved — leave blank to keep)" else "Client Secret"
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
}
