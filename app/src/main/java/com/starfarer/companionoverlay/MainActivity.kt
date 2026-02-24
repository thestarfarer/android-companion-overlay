package com.starfarer.companionoverlay

import android.app.ActivityOptions
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.starfarer.companionoverlay.databinding.ActivityMainBinding
import com.starfarer.companionoverlay.event.OverlayCoordinator
import com.starfarer.companionoverlay.repository.SettingsRepository
import com.starfarer.companionoverlay.ui.PresetDialogHelper
import com.starfarer.companionoverlay.ui.PresetPagerAdapter
import com.starfarer.companionoverlay.ui.SpritePickerHelper
import com.starfarer.companionoverlay.ui.SpritePreviewAnimator
import com.starfarer.companionoverlay.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main launcher activity.
 *
 * Provides:
 * - Character preset carousel with swipe navigation
 * - Claude authentication
 * - Overlay service control
 * - Navigation to settings
 *
 * State is managed by [MainViewModel]. Sprite preview animation is handled
 * by [SpritePreviewAnimator], which pre-extracts frames on load to avoid
 * per-tick Bitmap allocations.
 */
class MainActivity : AppCompatActivity() {

    // ══════════════════════════════════════════════════════════════════════
    // ViewModel & Dependencies
    // ══════════════════════════════════════════════════════════════════════

    private val viewModel: MainViewModel by viewModel()
    private val claudeAuth: ClaudeAuth by inject()
    private val claudeApi: ClaudeApi by inject()
    private val coordinator: OverlayCoordinator by inject()
    private val settings: SettingsRepository by inject()

    // UI Helpers
    private lateinit var presetDialogHelper: PresetDialogHelper
    private lateinit var spritePickerHelper: SpritePickerHelper
    private lateinit var spriteAnimator: SpritePreviewAnimator

    // ══════════════════════════════════════════════════════════════════════
    // View Binding (compile-time safe view references)
    // ══════════════════════════════════════════════════════════════════════

    private lateinit var binding: ActivityMainBinding

    // Convenience accessors for frequently used views
    private val authDot get() = binding.authDot
    private val authStatusText get() = binding.authStatusText
    private val authButton get() = binding.authButton
    private val presetName get() = binding.presetName
    private val presetPager get() = binding.presetPager
    private val pageIndicatorContainer get() = binding.pageIndicatorContainer
    private val systemPromptPreview get() = binding.systemPromptPreview
    private val userMessagePreview get() = binding.userMessagePreview
    private val statusText get() = binding.statusText
    private val toggleButton get() = binding.toggleButton
    private val modelSelector get() = binding.modelSelector

    private lateinit var pagerAdapter: PresetPagerAdapter

    // ══════════════════════════════════════════════════════════════════════
    // Sprite Picker
    // ══════════════════════════════════════════════════════════════════════

    private var pendingSpriteType: String? = null
    private var lastDisplayedPresetId: String? = null

    private val spritePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { handleSpriteSelected(it) } }

    // ══════════════════════════════════════════════════════════════════════
    // Overlay Permission (replaces deprecated onActivityResult)
    // ══════════════════════════════════════════════════════════════════════

    private val overlayPermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                startOverlayService()
            }
        }

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        DebugLog.log("Main", "=== App started ===")

        presetDialogHelper = PresetDialogHelper(this)
        spritePickerHelper = SpritePickerHelper(this)

        setupPagerAdapter()
        setupModelDropdown()
        setupClickListeners()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadPresets()
        viewModel.refreshAuthState()
        spriteAnimator.start()
    }

    override fun onPause() {
        super.onPause()
        spriteAnimator.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        spriteAnimator.release()
    }

    // ══════════════════════════════════════════════════════════════════════
    // State Observation
    // ══════════════════════════════════════════════════════════════════════

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    updatePresetDisplay(state)
                    updateOverlayUI(state)
                    updateAuthUI(state)
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Initialization
    // ══════════════════════════════════════════════════════════════════════

    private fun setupPagerAdapter() {
        pagerAdapter = PresetPagerAdapter(
            onIdleSpriteClick = { openSpritePicker("idle") },
            onWalkSpriteClick = { openSpritePicker("walk") }
        )

        spriteAnimator = SpritePreviewAnimator(pagerAdapter) { uri, custom, default ->
            viewModel.loadSpriteSheet(uri, custom, default)
        }

        presetPager.adapter = pagerAdapter
        presetPager.offscreenPageLimit = 1

        presetPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                viewModel.selectPreset(position)
                spriteAnimator.setCurrentPosition(position)
            }
        })
    }

    private fun setupModelDropdown() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, PromptSettings.MODEL_NAMES)
        modelSelector.setAdapter(adapter)

        val savedModel = settings.model
        val idx = PromptSettings.MODEL_IDS.indexOf(savedModel)
        if (idx >= 0) {
            modelSelector.setText(PromptSettings.MODEL_NAMES[idx], false)
        }

        modelSelector.setOnItemClickListener { _, _, position, _ ->
            settings.model = PromptSettings.MODEL_IDS[position]
        }
    }

    private fun setupClickListeners() {
        binding.settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            val options = ActivityOptions.makeSceneTransitionAnimation(
                this,
                android.util.Pair(authDot, "auth_dot")
            )
            startActivity(intent, options.toBundle())
        }

        binding.presetHeader.setOnClickListener { showPresetList() }

        binding.systemPromptCard.setOnClickListener {
            val preset = viewModel.state.value.activePreset ?: return@setOnClickListener
            SettingsDialogs.showTextEditor(this,
                title = "System Prompt",
                currentText = preset.systemPrompt,
                defaultText = PromptSettings.DEFAULT_SYSTEM_PROMPT
            ) { text ->
                viewModel.updateActivePreset { it.copy(systemPrompt = text) }
            }
        }

        binding.userMessageCard.setOnClickListener {
            val preset = viewModel.state.value.activePreset ?: return@setOnClickListener
            SettingsDialogs.showTextEditor(this,
                title = "User Message",
                currentText = preset.userMessage,
                defaultText = PromptSettings.DEFAULT_USER_MESSAGE
            ) { text ->
                viewModel.updateActivePreset { it.copy(userMessage = text) }
            }
        }

        authButton.setOnClickListener { handleAuthClick() }

        toggleButton.setOnClickListener {
            if (viewModel.state.value.overlayRunning) stopOverlayService()
            else checkPermissionAndStart()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Display Updates
    // ══════════════════════════════════════════════════════════════════════

    private fun updatePresetDisplay(state: MainViewModel.UiState) {
        val preset = state.activePreset ?: return

        val presetChanged = lastDisplayedPresetId != null && lastDisplayedPresetId != preset.id
        lastDisplayedPresetId = preset.id

        presetName.text = preset.name

        if (presetChanged) {
            val duration = 200L
            systemPromptPreview.animate().alpha(0f).setDuration(duration).withEndAction {
                systemPromptPreview.text = preset.systemPrompt.take(200)
                systemPromptPreview.animate().alpha(1f).setDuration(duration).start()
            }.start()
            userMessagePreview.animate().alpha(0f).setDuration(duration).withEndAction {
                userMessagePreview.text = preset.userMessage.take(150)
                userMessagePreview.animate().alpha(1f).setDuration(duration).start()
            }.start()
        } else {
            systemPromptPreview.text = preset.systemPrompt.take(200)
            userMessagePreview.text = preset.userMessage.take(150)
        }

        if (pagerAdapter.itemCount != state.presets.size) {
            pagerAdapter.submitList(state.presets)
        }

        if (presetPager.currentItem != state.activeIndex) {
            presetPager.setCurrentItem(state.activeIndex, true)
        }

        spriteAnimator.loadForPreset(state.activeIndex, preset)
        updatePageIndicators(state)
    }

    private fun updatePageIndicators(state: MainViewModel.UiState) {
        pageIndicatorContainer.removeAllViews()

        if (state.presets.size <= 1) {
            pageIndicatorContainer.visibility = View.GONE
            return
        }

        pageIndicatorContainer.visibility = View.VISIBLE

        val density = resources.displayMetrics.density
        val indicatorSize = (8 * density).toInt()
        val indicatorMargin = (4 * density).toInt()

        state.presets.forEachIndexed { index, _ ->
            val indicator = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(indicatorSize, indicatorSize).apply {
                    marginStart = indicatorMargin
                    marginEnd = indicatorMargin
                }
                setBackgroundResource(
                    if (index == state.activeIndex) R.drawable.page_indicator_selected
                    else R.drawable.page_indicator_unselected
                )
            }
            pageIndicatorContainer.addView(indicator)
        }
    }

    private fun updateOverlayUI(state: MainViewModel.UiState) {
        if (state.overlayRunning) {
            statusText.text = "Senni is walking around~"
            toggleButton.text = "Let Her Rest"
        } else {
            statusText.text = "Senni is sleeping..."
            toggleButton.text = "Wake Her Up"
        }
    }

    private fun updateAuthUI(state: MainViewModel.UiState) {
        when (val authState = state.authState) {
            is MainViewModel.AuthState.ApiKeyMode -> {
                if (authState.hasKey) {
                    authDot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.status_connected))
                    authStatusText.text = "API Key configured"
                    authButton.visibility = View.GONE
                } else {
                    authDot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.status_error))
                    authStatusText.text = "API Key not set"
                    authButton.visibility = View.VISIBLE
                    authButton.text = "Set Claude API key"
                }
            }
            is MainViewModel.AuthState.Waiting -> {
                authDot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.status_warning))
                authStatusText.text = "Waiting for browser..."
                authButton.visibility = View.VISIBLE
                authButton.text = "Cancel"
            }
            is MainViewModel.AuthState.Connected -> {
                authDot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.status_connected))
                val fmt = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                authStatusText.text = "Connected until ${fmt.format(Date(authState.expiresAt))}"
                authButton.visibility = View.GONE
            }
            is MainViewModel.AuthState.Expired -> {
                authDot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.status_error))
                authStatusText.text = "Token expired"
                authButton.visibility = View.VISIBLE
                authButton.text = "Reconnect"
            }
            is MainViewModel.AuthState.NotConnected -> {
                authDot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.status_error))
                authStatusText.text = "Not connected"
                authButton.visibility = View.VISIBLE
                authButton.text = "Connect to Claude"
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Preset Management
    // ══════════════════════════════════════════════════════════════════════

    private fun showPresetList() {
        val state = viewModel.state.value
        presetDialogHelper.showPresetList(
            presets = state.presets,
            activeIndex = state.activeIndex,
            onSelect = {
                viewModel.selectPreset(it)
                presetPager.setCurrentItem(it, true)
            },
            onCreate = { createPreset() },
            onRename = { renameActivePreset() },
            onDelete = { deleteActivePreset() }
        )
    }

    private fun createPreset() {
        viewModel.createPreset()
        spriteAnimator.clearCache()
        renameActivePreset()
    }

    private fun renameActivePreset() {
        val preset = viewModel.state.value.activePreset ?: return
        presetDialogHelper.showRenameDialog(preset.name) { name ->
            viewModel.updateActivePreset { it.copy(name = name) }
        }
    }

    private fun deleteActivePreset() {
        val preset = viewModel.state.value.activePreset ?: return
        presetDialogHelper.showDeleteConfirmation(preset.name) {
            viewModel.deleteActivePreset()
            spriteAnimator.clearCache()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Sprite Picker
    // ══════════════════════════════════════════════════════════════════════

    private fun openSpritePicker(type: String) {
        val preset = viewModel.state.value.activePreset ?: return
        val currentCount = if (type == "idle") preset.idleFrameCount else preset.walkFrameCount
        val hasCustom = (if (type == "idle") preset.idleSpriteUri else preset.walkSpriteUri) != null

        spritePickerHelper.show(type, currentCount, hasCustom) { result ->
            when (result) {
                is SpritePickerHelper.Result.PickImage -> {
                    viewModel.updateActivePreset { p ->
                        if (type == "idle") p.copy(idleFrameCount = result.frameCount)
                        else p.copy(walkFrameCount = result.frameCount)
                    }
                    pendingSpriteType = type
                    spritePickerLauncher.launch(arrayOf("image/*"))
                }
                is SpritePickerHelper.Result.SaveCount -> {
                    viewModel.updateActivePreset { p ->
                        if (type == "idle") p.copy(idleFrameCount = result.frameCount)
                        else p.copy(walkFrameCount = result.frameCount)
                    }
                    spriteAnimator.clearCache()
                    Toast.makeText(this, "Saved~", Toast.LENGTH_SHORT).show()
                }
                is SpritePickerHelper.Result.Reset -> {
                    viewModel.updateActivePreset { p ->
                        if (type == "idle") {
                            p.copy(idleSpriteUri = null, idleFrameCount = PromptSettings.DEFAULT_IDLE_FRAME_COUNT)
                        } else {
                            p.copy(walkSpriteUri = null, walkFrameCount = PromptSettings.DEFAULT_WALK_FRAME_COUNT)
                        }
                    }
                    spriteAnimator.clearCache()
                    Toast.makeText(this, "Reset to default~", Toast.LENGTH_SHORT).show()
                }
                is SpritePickerHelper.Result.Cancelled -> { /* Do nothing */ }
            }
        }
    }

    private fun handleSpriteSelected(uri: Uri) {
        val type = pendingSpriteType ?: return
        pendingSpriteType = null

        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure {
            DebugLog.log("Main", "Failed to take permission: ${it.message}")
        }

        viewModel.updateActivePreset { preset ->
            if (type == "idle") preset.copy(idleSpriteUri = uri.toString())
            else preset.copy(walkSpriteUri = uri.toString())
        }

        spriteAnimator.clearCache()
        Toast.makeText(this, "Sprite updated~", Toast.LENGTH_SHORT).show()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Authentication
    // ══════════════════════════════════════════════════════════════════════

    private fun handleAuthClick() {
        when (viewModel.state.value.authState) {
            is MainViewModel.AuthState.ApiKeyMode -> {
                startActivity(Intent(this, SettingsActivity::class.java).apply {
                    putExtra(SettingsActivity.EXTRA_HIGHLIGHT_KEY, "claude_api_key")
                })
            }
            is MainViewModel.AuthState.Waiting -> {
                viewModel.cancelAuth()
                Toast.makeText(this, "Cancelled", Toast.LENGTH_SHORT).show()
            }
            is MainViewModel.AuthState.Expired -> {
                lifecycleScope.launch {
                    val result = viewModel.refreshToken()
                    if (result.isSuccess) {
                        Toast.makeText(this@MainActivity, "Token refreshed~", Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.logout()
                        startAuthentication()
                    }
                }
            }
            is MainViewModel.AuthState.Connected -> {
                viewModel.logout()
                Toast.makeText(this, "Logged out~", Toast.LENGTH_SHORT).show()
            }
            is MainViewModel.AuthState.NotConnected -> startAuthentication()
        }
    }

    private fun startAuthentication() {
        DebugLog.log("Main", "Starting authentication...")
        lifecycleScope.launch {
            claudeAuth.startAuthWithCallback(this@MainActivity, object : ClaudeAuth.AuthCallback {
                override fun onAuthProgress(message: String) {
                    DebugLog.log("Main", "Auth progress: $message")
                    viewModel.refreshAuthState()
                }
                override fun onAuthSuccess() {
                    DebugLog.log("Main", "Auth SUCCESS")
                    Toast.makeText(this@MainActivity, "Connected~", Toast.LENGTH_SHORT).show()
                    viewModel.refreshAuthState()
                }
                override fun onAuthFailure(error: String) {
                    DebugLog.log("Main", "Auth FAILURE: $error")
                    Toast.makeText(this@MainActivity, "Failed: $error", Toast.LENGTH_LONG).show()
                    viewModel.refreshAuthState()
                }
            })
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Overlay Control
    // ══════════════════════════════════════════════════════════════════════

    private fun checkPermissionAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant overlay permission!", Toast.LENGTH_LONG).show()
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            startOverlayService()
        }
    }

    private fun startOverlayService() {
        startForegroundService(Intent(this, CompanionOverlayService::class.java))
    }

    private fun stopOverlayService() {
        coordinator.dismissOverlay()
    }

}
