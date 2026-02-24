package com.starfarer.companionoverlay

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.starfarer.companionoverlay.event.OverlayCoordinator
import com.starfarer.companionoverlay.ui.PresetDialogHelper
import com.starfarer.companionoverlay.ui.PresetPagerAdapter
import com.starfarer.companionoverlay.ui.SpritePickerHelper
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
 * State is managed by [MainViewModel]. UI helpers handle dialog creation.
 * Sprite animation stays here since it directly manipulates Bitmaps.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 1001
    }

    // ══════════════════════════════════════════════════════════════════════
    // ViewModel & Dependencies
    // ══════════════════════════════════════════════════════════════════════

    private val viewModel: MainViewModel by viewModel()
    private val claudeAuth: ClaudeAuth by inject()
    private val claudeApi: ClaudeApi by inject()
    private val coordinator: OverlayCoordinator by inject()

    // UI Helpers
    private lateinit var presetDialogHelper: PresetDialogHelper
    private lateinit var spritePickerHelper: SpritePickerHelper

    // ══════════════════════════════════════════════════════════════════════
    // Views
    // ══════════════════════════════════════════════════════════════════════

    private lateinit var authDot: View
    private lateinit var authStatusText: TextView
    private lateinit var authButton: Button
    private lateinit var presetName: TextView
    private lateinit var presetPager: ViewPager2
    private lateinit var pageIndicatorContainer: LinearLayout
    private lateinit var systemPromptPreview: TextView
    private lateinit var userMessagePreview: TextView
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var modelSelector: AutoCompleteTextView

    private lateinit var pagerAdapter: PresetPagerAdapter

    // ══════════════════════════════════════════════════════════════════════
    // Sprite Animation
    // ══════════════════════════════════════════════════════════════════════

    private val animHandler = Handler(Looper.getMainLooper())
    private val spriteSheets = mutableMapOf<Int, Pair<Bitmap?, Bitmap?>>() // position -> (idle, walk)
    private val frameCounts = mutableMapOf<Int, Pair<Int, Int>>() // position -> (idleCount, walkCount)
    private var currentIdleFrame = 0
    private var currentWalkFrame = 0
    private var animating = false
    private var pendingSpriteType: String? = null

    private val idleAnimRunnable = object : Runnable {
        override fun run() {
            if (!animating) return
            advanceIdleFrame()
            animHandler.postDelayed(this, SpriteAnimator.IDLE_FRAME_DURATION_MS)
        }
    }

    private val walkAnimRunnable = object : Runnable {
        override fun run() {
            if (!animating) return
            advanceWalkFrame()
            animHandler.postDelayed(this, SpriteAnimator.WALK_FRAME_DURATION_MS)
        }
    }

    private val spritePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { handleSpriteSelected(it) } }

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        DebugLog.log("Main", "=== App started ===")

        presetDialogHelper = PresetDialogHelper(this)
        spritePickerHelper = SpritePickerHelper(this)

        findViews()
        setupPagerAdapter()
        setupModelDropdown()
        setupClickListeners()
        createNotificationChannel()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadPresets()
        viewModel.refreshAuthState()
        startSpriteAnimation()
    }

    override fun onPause() {
        super.onPause()
        stopSpriteAnimation()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSpriteAnimation()
        spriteSheets.values.forEach { (idle, walk) ->
            idle?.recycle()
            walk?.recycle()
        }
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

    private fun findViews() {
        authDot = findViewById(R.id.authDot)
        authStatusText = findViewById(R.id.authStatusText)
        authButton = findViewById(R.id.authButton)
        presetName = findViewById(R.id.presetName)
        presetPager = findViewById(R.id.presetPager)
        pageIndicatorContainer = findViewById(R.id.pageIndicatorContainer)
        systemPromptPreview = findViewById(R.id.systemPromptPreview)
        userMessagePreview = findViewById(R.id.userMessagePreview)
        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        modelSelector = findViewById(R.id.modelSelector)
    }

    private fun setupPagerAdapter() {
        pagerAdapter = PresetPagerAdapter(
            onIdleSpriteClick = { openSpritePicker("idle") },
            onWalkSpriteClick = { openSpritePicker("walk") }
        )

        presetPager.adapter = pagerAdapter
        presetPager.offscreenPageLimit = 1

        // Page change callback
        presetPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                viewModel.selectPreset(position)
            }
        })
    }

    private fun setupModelDropdown() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, PromptSettings.MODEL_NAMES)
        modelSelector.setAdapter(adapter)

        val savedModel = PromptSettings.getModel(this)
        val idx = PromptSettings.MODEL_IDS.indexOf(savedModel)
        if (idx >= 0) {
            modelSelector.setText(PromptSettings.MODEL_NAMES[idx], false)
        }

        modelSelector.setOnItemClickListener { _, _, position, _ ->
            PromptSettings.setModel(this, PromptSettings.MODEL_IDS[position])
        }
    }

    private fun setupClickListeners() {
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            val options = ActivityOptions.makeSceneTransitionAnimation(
                this,
                android.util.Pair(authDot, "auth_dot")
            )
            startActivity(intent, options.toBundle())
        }

        findViewById<View>(R.id.presetHeader).setOnClickListener { showPresetList() }

        findViewById<MaterialCardView>(R.id.systemPromptCard).setOnClickListener {
            val preset = viewModel.state.value.activePreset ?: return@setOnClickListener
            SettingsDialogs.showTextEditor(this,
                title = "System Prompt",
                currentText = preset.systemPrompt,
                defaultText = PromptSettings.DEFAULT_SYSTEM_PROMPT
            ) { text ->
                viewModel.updateActivePreset { it.copy(systemPrompt = text) }
            }
        }

        findViewById<MaterialCardView>(R.id.userMessageCard).setOnClickListener {
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

        presetName.text = preset.name
        systemPromptPreview.text = preset.systemPrompt.take(200)
        userMessagePreview.text = preset.userMessage.take(150)

        // Update pager if preset list changed
        if (pagerAdapter.itemCount != state.presets.size) {
            pagerAdapter.submitList(state.presets)
        }

        // Sync pager position
        if (presetPager.currentItem != state.activeIndex) {
            presetPager.setCurrentItem(state.activeIndex, true)
        }

        // Load sprites for current preset
        loadSpritesForPreset(state.activeIndex, preset)

        // Update page indicators
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
    // Sprite Animation
    // ══════════════════════════════════════════════════════════════════════

    private fun loadSpritesForPreset(position: Int, preset: CharacterPreset) {
        // Don't reload if we already have this preset's sprites
        if (spriteSheets.containsKey(position)) {
            updateCurrentPresetSprites()
            return
        }

        val idleSheet = viewModel.loadSpriteSheet(preset.idleSpriteUri, "custom_idle_sheet.png", "idle_sheet.png")
        val walkSheet = viewModel.loadSpriteSheet(preset.walkSpriteUri, "custom_walk_sheet.png", "walk_sheet.png")

        spriteSheets[position] = Pair(idleSheet, walkSheet)
        frameCounts[position] = Pair(
            preset.idleFrameCount.coerceAtLeast(1),
            preset.walkFrameCount.coerceAtLeast(1)
        )

        updateCurrentPresetSprites()
    }

    private fun updateCurrentPresetSprites() {
        val position = presetPager.currentItem
        val (idleSheet, walkSheet) = spriteSheets[position] ?: return
        val (idleCount, walkCount) = frameCounts[position] ?: return

        val idleFrame = extractFrame(idleSheet, currentIdleFrame, idleCount)
        val walkFrame = extractFrame(walkSheet, currentWalkFrame, walkCount)

        pagerAdapter.updateSpriteFrame(position, idleFrame, walkFrame)
    }

    private fun extractFrame(sheet: Bitmap?, frameIndex: Int, frameCount: Int): Bitmap? {
        if (sheet == null) return null
        val frameWidth = sheet.width / frameCount.coerceAtLeast(1)
        val safeIndex = frameIndex % frameCount.coerceAtLeast(1)
        return runCatching {
            Bitmap.createBitmap(sheet, safeIndex * frameWidth, 0, frameWidth, sheet.height)
        }.getOrNull()
    }

    private fun advanceIdleFrame() {
        val position = presetPager.currentItem
        val (idleCount, _) = frameCounts[position] ?: return
        currentIdleFrame = (currentIdleFrame + 1) % idleCount.coerceAtLeast(1)
        updateCurrentPresetSprites()
    }

    private fun advanceWalkFrame() {
        val position = presetPager.currentItem
        val (_, walkCount) = frameCounts[position] ?: return
        currentWalkFrame = (currentWalkFrame + 1) % walkCount.coerceAtLeast(1)
        updateCurrentPresetSprites()
    }

    private fun startSpriteAnimation() {
        if (animating) return
        animating = true
        currentIdleFrame = 0
        currentWalkFrame = 0
        animHandler.postDelayed(idleAnimRunnable, SpriteAnimator.IDLE_FRAME_DURATION_MS)
        animHandler.postDelayed(walkAnimRunnable, SpriteAnimator.WALK_FRAME_DURATION_MS)
    }

    private fun stopSpriteAnimation() {
        animating = false
        animHandler.removeCallbacks(idleAnimRunnable)
        animHandler.removeCallbacks(walkAnimRunnable)
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
        // Clear cached sprites so new preset loads fresh
        spriteSheets.clear()
        frameCounts.clear()
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
            val deletedPosition = viewModel.state.value.activeIndex
            viewModel.deleteActivePreset()
            spriteSheets.remove(deletedPosition)
            frameCounts.remove(deletedPosition)
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
                    clearCachedSprites()
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
                    clearCachedSprites()
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

        clearCachedSprites()
        Toast.makeText(this, "Sprite updated~", Toast.LENGTH_SHORT).show()
    }

    private fun clearCachedSprites() {
        spriteSheets.values.forEach { (idle, walk) ->
            idle?.recycle()
            walk?.recycle()
        }
        spriteSheets.clear()
        frameCounts.clear()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Authentication
    // ══════════════════════════════════════════════════════════════════════

    private fun handleAuthClick() {
        when (viewModel.state.value.authState) {
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
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                OVERLAY_PERMISSION_REQUEST
            )
        } else {
            startOverlayService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST && Settings.canDrawOverlays(this)) {
            startOverlayService()
        }
    }

    private fun startOverlayService() {
        startForegroundService(Intent(this, CompanionOverlayService::class.java))
    }

    private fun stopOverlayService() {
        coordinator.dismissOverlay()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Notification
    // ══════════════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CompanionOverlayService.CHANNEL_ID,
            "Companion Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Keeps Senni alive on your screen" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
