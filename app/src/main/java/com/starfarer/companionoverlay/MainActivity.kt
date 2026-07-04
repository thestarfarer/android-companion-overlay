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
import android.widget.LinearLayout
import android.widget.Toast
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

/**
 * Main launcher activity.
 *
 * Provides:
 * - Character preset carousel with swipe navigation
 * - Nexus gateway status (server configured or not)
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
        viewModel.refreshGatewayState()
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
                    updateGatewayUI(state)
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

    private fun setupClickListeners() {
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.presetHeader.setOnClickListener { showPresetList() }

        binding.systemPromptCard.setOnClickListener {
            val preset = viewModel.state.value.activePreset ?: return@setOnClickListener
            SettingsDialogs.showTextEditor(this,
                title = getString(R.string.main_system_prompt_title),
                currentText = preset.systemPrompt,
                defaultText = PromptSettings.DEFAULT_SYSTEM_PROMPT,
                requestKey = RK_SYSTEM_PROMPT
            )
        }

        binding.userMessageCard.setOnClickListener {
            val preset = viewModel.state.value.activePreset ?: return@setOnClickListener
            SettingsDialogs.showTextEditor(this,
                title = getString(R.string.main_user_message_title),
                currentText = preset.userMessage,
                defaultText = PromptSettings.DEFAULT_USER_MESSAGE,
                requestKey = RK_USER_MESSAGE
            )
        }

        authButton.setOnClickListener { openGatewaySettings() }

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
            statusText.text = getString(R.string.main_status_walking)
            toggleButton.text = getString(R.string.main_toggle_rest)
        } else {
            statusText.text = getString(R.string.main_status_sleeping)
            toggleButton.text = getString(R.string.main_toggle_wake)
        }
    }

    private fun updateGatewayUI(state: MainViewModel.UiState) {
        when (val gateway = state.gateway) {
            is MainViewModel.GatewayState.Configured -> {
                authDot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.status_connected))
                val host = android.net.Uri.parse(gateway.serverUrl).host ?: gateway.serverUrl
                authStatusText.text = getString(R.string.main_gateway_configured, host)
                authButton.visibility = View.GONE
            }
            is MainViewModel.GatewayState.TokenMissing -> {
                authDot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.status_warning))
                authStatusText.text = getString(R.string.main_gateway_token_missing)
                authButton.visibility = View.VISIBLE
                authButton.text = getString(R.string.main_setup_gateway)
            }
            is MainViewModel.GatewayState.NotConfigured -> {
                authDot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.status_error))
                authStatusText.text = getString(R.string.main_gateway_not_configured)
                authButton.visibility = View.VISIBLE
                authButton.text = getString(R.string.main_setup_gateway)
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
            Toast.makeText(this, getString(R.string.main_cant_delete_only_preset), Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, getString(R.string.main_saved), Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, getString(R.string.main_reset_to_default), Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, getString(R.string.main_sprite_updated), Toast.LENGTH_SHORT).show()
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
    // Gateway setup
    // ══════════════════════════════════════════════════════════════════════

    private fun openGatewaySettings() {
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            putExtra(SettingsActivity.EXTRA_HIGHLIGHT_KEY, "gateway_url")
        })
    }

    // ══════════════════════════════════════════════════════════════════════
    // Overlay Control
    // ══════════════════════════════════════════════════════════════════════

    private fun checkPermissionAndStart() {
        if (!OverlayController.canStart(this)) {
            Toast.makeText(this, getString(R.string.main_grant_overlay_permission), Toast.LENGTH_LONG).show()
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
