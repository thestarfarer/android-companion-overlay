package com.starfarer.companionoverlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
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
import com.starfarer.companionoverlay.ui.TextEditorBottomSheet
import com.starfarer.companionoverlay.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.Date

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
    // Frame count is committed only once an image is actually picked — applying
    // it before the picker left the preset re-sliced for a sprite the user then
    // cancelled. Survives process death via instance state.
    private var pendingFrameCount: Int = 0
    private var lastDisplayedPresetId: String? = null

    private val spritePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { handleSpriteSelected(it) } }

    // Mark the tutorial seen only after it returns — setting the flag before
    // launching meant a first-launch crash permanently skipped it.
    private val tutorialLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { settings.tutorialSeen = true }

    companion object {
        private const val RK_SYSTEM_PROMPT = "edit_system_prompt"
        private const val RK_USER_MESSAGE = "edit_user_message"
        private const val KEY_PENDING_SPRITE_TYPE = "pending_sprite_type"
        private const val KEY_PENDING_FRAME_COUNT = "pending_frame_count"
    }

    // ══════════════════════════════════════════════════════════════════════
    // Overlay Permission (replaces deprecated onActivityResult)
    // ══════════════════════════════════════════════════════════════════════

    private val overlayPermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (OverlayController.canStart(this)) {
                startOverlayService()
            }
        }

    // The FGS notification is the overlay's only status/stop surface, and on
    // Android 13+ it is invisible without POST_NOTIFICATIONS. Asked for at the
    // moment the notification first matters — overlay start. The overlay starts
    // regardless of the answer (calls ensureRunning directly, not
    // startOverlayService, so a denial can't re-prompt in a loop).
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            OverlayController.ensureRunning(this, coordinator)
        }

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        DebugLog.log("Main", "=== App started ===")

        savedInstanceState?.let {
            pendingSpriteType = it.getString(KEY_PENDING_SPRITE_TYPE)
            pendingFrameCount = it.getInt(KEY_PENDING_FRAME_COUNT, 0)
        }

        presetDialogHelper = PresetDialogHelper(this)
        spritePickerHelper = SpritePickerHelper(this)

        registerTextEditorListeners()
        setupPagerAdapter()
        setupModelDropdown()
        setupClickListeners()
        observeState()

        // First launch: show the interactive tutorial once. Flag is set when it
        // returns (see tutorialLauncher), not before.
        if (!settings.tutorialSeen) {
            tutorialLauncher.launch(Intent(this, TutorialActivity::class.java))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_SPRITE_TYPE, pendingSpriteType)
        outState.putInt(KEY_PENDING_FRAME_COUNT, pendingFrameCount)
    }

    /**
     * Registered once here (not per-dialog) with the Activity as the result
     * lifecycle owner, and one key per field. A rotation no longer drops the
     * save or delivers a buffered edit into the wrong field.
     */
    private fun registerTextEditorListeners() {
        supportFragmentManager.setFragmentResultListener(RK_SYSTEM_PROMPT, this) { _, bundle ->
            val text = bundle.getString(TextEditorBottomSheet.RESULT_TEXT) ?: return@setFragmentResultListener
            viewModel.updateActivePreset { it.copy(systemPrompt = text) }
        }
        supportFragmentManager.setFragmentResultListener(RK_USER_MESSAGE, this) { _, bundle ->
            val text = bundle.getString(TextEditorBottomSheet.RESULT_TEXT) ?: return@setFragmentResultListener
            viewModel.updateActivePreset { it.copy(userMessage = text) }
        }
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
                coordinator.reloadSprites()
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

        // Resolve by the selected NAME, not the adapter position: an
        // AutoCompleteTextView filters its list, so a filtered position no
        // longer maps 1:1 onto MODEL_IDS and could select the wrong model.
        modelSelector.setOnItemClickListener { _, _, position, _ ->
            val name = adapter.getItem(position) ?: return@setOnItemClickListener
            val modelIdx = PromptSettings.MODEL_NAMES.indexOf(name)
            if (modelIdx >= 0) settings.model = PromptSettings.MODEL_IDS[modelIdx]
        }
    }

    private fun setupClickListeners() {
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.presetHeader.setOnClickListener { showPresetList() }

        binding.systemPromptCard.setOnClickListener {
            val preset = viewModel.state.value.activePreset ?: return@setOnClickListener
            SettingsDialogs.showTextEditor(this,
                title = "System Prompt",
                currentText = preset.systemPrompt,
                defaultText = PromptSettings.DEFAULT_SYSTEM_PROMPT,
                requestKey = RK_SYSTEM_PROMPT
            )
        }

        binding.userMessageCard.setOnClickListener {
            val preset = viewModel.state.value.activePreset ?: return@setOnClickListener
            SettingsDialogs.showTextEditor(this,
                title = "User Message",
                currentText = preset.userMessage,
                defaultText = PromptSettings.DEFAULT_USER_MESSAGE,
                requestKey = RK_USER_MESSAGE
            )
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
                // Locale- and 12/24h-aware via the user's system formats.
                val date = Date(authState.expiresAt)
                val df = android.text.format.DateFormat.getMediumDateFormat(this)
                val tf = android.text.format.DateFormat.getTimeFormat(this)
                authStatusText.text = "Connected until ${df.format(date)} ${tf.format(date)}"
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
        coordinator.reloadSprites()
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
        // The VM refuses to delete the only preset; without this guard the
        // confirmation dialog appeared and then silently did nothing.
        if (viewModel.state.value.presets.size <= 1) {
            Toast.makeText(this, "Can't delete your only preset~", Toast.LENGTH_SHORT).show()
            return
        }
        presetDialogHelper.showDeleteConfirmation(preset.name) {
            viewModel.deleteActivePreset()
            spriteAnimator.clearCache()
            coordinator.reloadSprites()
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
                    // Defer the frame-count commit until an image is actually
                    // picked — committing now then cancelling re-sliced the
                    // existing sprite for a frame count the user backed out of.
                    pendingSpriteType = type
                    pendingFrameCount = result.frameCount
                    spritePickerLauncher.launch(arrayOf("image/*"))
                }
                is SpritePickerHelper.Result.SaveCount -> {
                    viewModel.updateActivePreset { p ->
                        if (type == "idle") p.copy(idleFrameCount = result.frameCount)
                        else p.copy(walkFrameCount = result.frameCount)
                    }
                    spriteAnimator.clearCache()
                    coordinator.reloadSprites()
                    Toast.makeText(this, "Saved~", Toast.LENGTH_SHORT).show()
                }
                is SpritePickerHelper.Result.Reset -> {
                    val old = if (type == "idle") preset.idleSpriteUri else preset.walkSpriteUri
                    releasePersistedUri(old)
                    viewModel.updateActivePreset { p ->
                        if (type == "idle") {
                            p.copy(idleSpriteUri = null, idleFrameCount = PromptSettings.DEFAULT_IDLE_FRAME_COUNT)
                        } else {
                            p.copy(walkSpriteUri = null, walkFrameCount = PromptSettings.DEFAULT_WALK_FRAME_COUNT)
                        }
                    }
                    spriteAnimator.clearCache()
                    coordinator.reloadSprites()
                    Toast.makeText(this, "Reset to default~", Toast.LENGTH_SHORT).show()
                }
                is SpritePickerHelper.Result.Cancelled -> { /* Do nothing */ }
            }
        }
    }

    private fun handleSpriteSelected(uri: Uri) {
        val type = pendingSpriteType ?: return
        val frameCount = pendingFrameCount
        pendingSpriteType = null

        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure {
            DebugLog.log("Main", "Failed to take permission: ${it.message}")
        }

        // Release the URI grant of the sprite we're replacing, so revoked
        // permissions don't pile up across changes.
        val preset = viewModel.state.value.activePreset
        val replaced = if (type == "idle") preset?.idleSpriteUri else preset?.walkSpriteUri
        if (replaced != uri.toString()) releasePersistedUri(replaced)

        viewModel.updateActivePreset { p ->
            if (type == "idle") p.copy(idleSpriteUri = uri.toString(), idleFrameCount = frameCount)
            else p.copy(walkSpriteUri = uri.toString(), walkFrameCount = frameCount)
        }

        spriteAnimator.clearCache()
        coordinator.reloadSprites()
        Toast.makeText(this, "Sprite updated~", Toast.LENGTH_SHORT).show()
    }

    private fun releasePersistedUri(uriString: String?) {
        if (uriString.isNullOrBlank()) return
        runCatching {
            contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
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
        if (!OverlayController.canStart(this)) {
            Toast.makeText(this, "Please grant overlay permission!", Toast.LENGTH_LONG).show()
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            startOverlayService()
        }
    }

    private fun startOverlayService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        OverlayController.ensureRunning(this, coordinator)
    }

    private fun stopOverlayService() {
        coordinator.dismissOverlay()
    }

}
