package com.starfarer.companionoverlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import android.widget.TextView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 1001
    }

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var authStatusText: TextView
    private lateinit var authButton: Button
    private lateinit var testButton: Button
    private lateinit var responseText: TextView
    private lateinit var copyLogButton: Button
    private lateinit var screenshotPermButton: Button
    private lateinit var editSystemPromptButton: Button
    private lateinit var editUserMessageButton: Button
    private lateinit var autoCopyCheckbox: CheckBox
    private lateinit var keepDialogueCheckbox: CheckBox
    private lateinit var webSearchCheckbox: CheckBox
    private lateinit var volumeToggleCheckbox: CheckBox
    private lateinit var editIdleSpriteButton: Button
    private lateinit var editWalkSpriteButton: Button
    private lateinit var resetSpritesButton: Button
    private lateinit var modelSpinner: Spinner
    private lateinit var timeoutSpinner: Spinner
    private lateinit var historySpinner: Spinner

    private var pendingSpriteType: String? = null

    private lateinit var claudeAuth: ClaudeAuth
    private lateinit var claudeApi: ClaudeApi
    private lateinit var screenshotManager: ScreenshotManager

    private val spritePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handleSpriteSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        DebugLog.log("Main", "=== App started ===")

        claudeAuth = ClaudeAuth(this)
        claudeApi = ClaudeApi(claudeAuth)
        screenshotManager = ScreenshotManager(this)

        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        authStatusText = findViewById(R.id.authStatusText)
        authButton = findViewById(R.id.authButton)
        testButton = findViewById(R.id.testButton)
        responseText = findViewById(R.id.responseText)
        copyLogButton = findViewById(R.id.copyLogButton)
        screenshotPermButton = findViewById(R.id.screenshotPermButton)
        editSystemPromptButton = findViewById(R.id.editSystemPromptButton)
        editUserMessageButton = findViewById(R.id.editUserMessageButton)
        autoCopyCheckbox = findViewById(R.id.autoCopyCheckbox)
        keepDialogueCheckbox = findViewById(R.id.keepDialogueCheckbox)
        editIdleSpriteButton = findViewById(R.id.editIdleSpriteButton)
        editWalkSpriteButton = findViewById(R.id.editWalkSpriteButton)
        resetSpritesButton = findViewById(R.id.resetSpritesButton)
        
        // Load saved state
        autoCopyCheckbox.isChecked = PromptSettings.getAutoCopy(this)
        autoCopyCheckbox.setOnCheckedChangeListener { _, isChecked ->
            PromptSettings.setAutoCopy(this, isChecked)
        }

        keepDialogueCheckbox.isChecked = PromptSettings.getKeepDialogue(this)
        keepDialogueCheckbox.setOnCheckedChangeListener { _, isChecked ->
            PromptSettings.setKeepDialogue(this, isChecked)
        }

        webSearchCheckbox = findViewById(R.id.webSearchCheckbox)
        webSearchCheckbox.isChecked = PromptSettings.getWebSearch(this)
        webSearchCheckbox.setOnCheckedChangeListener { _, isChecked ->
            PromptSettings.setWebSearch(this, isChecked)
        }

        volumeToggleCheckbox = findViewById(R.id.volumeToggleCheckbox)
        volumeToggleCheckbox.isChecked = PromptSettings.getVolumeToggle(this)
        volumeToggleCheckbox.setOnCheckedChangeListener { _, isChecked ->
            PromptSettings.setVolumeToggle(this, isChecked)
        }

        // Bubble timeout selector
        timeoutSpinner = findViewById(R.id.timeoutSpinner)
        timeoutSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, PromptSettings.BUBBLE_TIMEOUT_LABELS)
        val savedTimeout = PromptSettings.getBubbleTimeout(this)
        val timeoutIdx = PromptSettings.BUBBLE_TIMEOUT_VALUES.indexOf(savedTimeout)
        if (timeoutIdx >= 0) timeoutSpinner.setSelection(timeoutIdx)
        timeoutSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                PromptSettings.setBubbleTimeout(this@MainActivity, PromptSettings.BUBBLE_TIMEOUT_VALUES[pos])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // History length selector
        historySpinner = findViewById(R.id.historySpinner)
        historySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, PromptSettings.MAX_MESSAGES_LABELS)
        val savedMaxMsgs = PromptSettings.getMaxMessages(this)
        val historyIdx = PromptSettings.MAX_MESSAGES_VALUES.indexOf(savedMaxMsgs)
        if (historyIdx >= 0) historySpinner.setSelection(historyIdx)
        historySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                PromptSettings.setMaxMessages(this@MainActivity, PromptSettings.MAX_MESSAGES_VALUES[pos])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Model selector
        modelSpinner = findViewById(R.id.modelSpinner)
        modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, PromptSettings.MODEL_NAMES)
        val savedModel = PromptSettings.getModel(this)
        val idx = PromptSettings.MODEL_IDS.indexOf(savedModel)
        if (idx >= 0) modelSpinner.setSelection(idx)
        claudeApi.model = savedModel
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                val modelId = PromptSettings.MODEL_IDS[pos]
                PromptSettings.setModel(this@MainActivity, modelId)
                claudeApi.model = modelId
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        editIdleSpriteButton.setOnClickListener {
            showSpriteDialog("idle")
        }
        
        editWalkSpriteButton.setOnClickListener {
            showSpriteDialog("walk")
        }
        
        resetSpritesButton.setOnClickListener {
            PromptSettings.setIdleSpriteUri(this, null)
            PromptSettings.setWalkSpriteUri(this, null)
            PromptSettings.setIdleFrameCount(this, PromptSettings.DEFAULT_IDLE_FRAME_COUNT)
            PromptSettings.setWalkFrameCount(this, PromptSettings.DEFAULT_WALK_FRAME_COUNT)
            Toast.makeText(this, "Sprites reset~ Restart overlay to apply", Toast.LENGTH_SHORT).show()
        }

        createNotificationChannel()

        toggleButton.setOnClickListener {
            if (CompanionOverlayService.isRunning) {
                stopOverlayService()
            } else {
                checkPermissionAndStart()
            }
        }

        authButton.setOnClickListener {
            when {
                claudeAuth.isWaitingForCallback() -> {
                    claudeAuth.cancelAuth()
                    updateAuthUI()
                    Toast.makeText(this, "Cancelled", Toast.LENGTH_SHORT).show()
                }
                claudeAuth.isAuthenticated() && System.currentTimeMillis() > claudeAuth.getExpiresAt() -> {
                    // Token expired — try refresh, then re-auth if that fails
                    lifecycleScope.launch {
                        val result = claudeAuth.refreshToken()
                        if (result.isSuccess) {
                            Toast.makeText(this@MainActivity, "Token refreshed~", Toast.LENGTH_SHORT).show()
                            updateAuthUI()
                        } else {
                            claudeAuth.logout()
                            startAuthentication()
                        }
                    }
                }
                claudeAuth.isAuthenticated() -> {
                    claudeAuth.logout()
                    updateAuthUI()
                    Toast.makeText(this, "Logged out~", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    startAuthentication()
                }
            }
        }

        testButton.setOnClickListener {
            sendTestMessage()
        }

        copyLogButton.setOnClickListener {
            val log = DebugLog.getLog()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Senni Debug Log", log)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Log copied! (${log.length} chars)", Toast.LENGTH_SHORT).show()
        }


        editSystemPromptButton.setOnClickListener {
            showEditDialog(
                title = "System Prompt",
                currentText = PromptSettings.getSystemPrompt(this),
                defaultText = PromptSettings.DEFAULT_SYSTEM_PROMPT,
                onSave = { PromptSettings.setSystemPrompt(this, it) }
            )
        }

        editUserMessageButton.setOnClickListener {
            showEditDialog(
                title = "User Message",
                currentText = PromptSettings.getUserMessage(this),
                defaultText = PromptSettings.DEFAULT_USER_MESSAGE,
                onSave = { PromptSettings.setUserMessage(this, it) }
            )
        }
        screenshotPermButton.setOnClickListener {
            if (ScreenshotManager.isAccessibilityEnabled(this)) {
                Toast.makeText(this, "Already enabled~", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Enable 'Senni Overlay' in Accessibility settings", Toast.LENGTH_LONG).show()
                startActivity(screenshotManager.getAccessibilitySettingsIntent())
            }
        }

        DebugLog.log("Main", "Checking initial auth state...")
        updateUI()
        updateAuthUI()
    }

    override fun onResume() {
        super.onResume()
        DebugLog.log("Main", "onResume - updating UI")
        
        // Listen for overlay state changes (from volume button toggle)
        CompanionOverlayService.stateListener = { runOnUiThread { updateUI() } }
        
        updateUI()
        updateAuthUI()
    }
    
    override fun onPause() {
        super.onPause()
        CompanionOverlayService.stateListener = null
    }

    private fun updateUI() {
        if (CompanionOverlayService.isRunning) {
            statusText.text = "Senni is walking around~ ♡"
            toggleButton.text = "Let Her Rest"
        } else {
            statusText.text = "Senni is sleeping..."
            toggleButton.text = "Wake Her Up"
        }
        
        screenshotPermButton.text = if (ScreenshotManager.isAccessibilityEnabled(this)) {
            "✓ Accessibility Enabled"
        } else {
            "Enable Accessibility Service"
        }
    }

    private fun updateAuthUI() {
        val isAuth = claudeAuth.isAuthenticated()
        val isWaiting = claudeAuth.isWaitingForCallback()
        DebugLog.log("Main", "updateAuthUI - isAuth: $isAuth, isWaiting: $isWaiting")
        
        when {
            isWaiting -> {
                authStatusText.text = "Waiting for browser..."
                authButton.text = "Cancel"
                authButton.isEnabled = true
                testButton.isEnabled = false
            }
            isAuth -> {
                val expiresAt = claudeAuth.getExpiresAt()
                val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                val expiresStr = dateFormat.format(Date(expiresAt))
                val isExpired = System.currentTimeMillis() > expiresAt
                
                DebugLog.log("Main", "Auth OK - expires: $expiresStr, isExpired: $isExpired")
                
                if (isExpired) {
                    authStatusText.text = "⚠️ Token expired"
                    authButton.text = "Re-authenticate"
                    testButton.isEnabled = false
                    responseText.text = "Token expired, authenticate again~"
                } else {
                    authStatusText.text = "✓ Connected (until $expiresStr)"
                    authButton.text = "Logout"
                    testButton.isEnabled = true
                    responseText.text = "Tap the button and I might say something nice~"
                }
                authButton.isEnabled = true
            }
            else -> {
                authStatusText.text = "Not connected to Claude"
                authButton.text = "Authenticate"
                authButton.isEnabled = true
                testButton.isEnabled = false
                responseText.text = "Authenticate first to hear me speak~"
            }
        }
    }

    private fun startAuthentication() {
        DebugLog.log("Main", "Starting authentication...")
        lifecycleScope.launch {
            claudeAuth.startAuthWithCallback(this@MainActivity, object : ClaudeAuth.AuthCallback {
                override fun onAuthProgress(message: String) {
                    DebugLog.log("Main", "Auth progress: $message")
                    responseText.text = message
                    updateAuthUI()
                }

                override fun onAuthSuccess() {
                    DebugLog.log("Main", "Auth SUCCESS callback received")
                    Toast.makeText(this@MainActivity, "Connected! ♡", Toast.LENGTH_SHORT).show()
                    updateAuthUI()
                }

                override fun onAuthFailure(error: String) {
                    DebugLog.log("Main", "Auth FAILURE callback: $error")
                    Toast.makeText(this@MainActivity, "Failed: $error", Toast.LENGTH_LONG).show()
                    responseText.text = "Auth failed: $error"
                    updateAuthUI()
                }
            })
        }
    }

    private fun sendTestMessage() {
        testButton.isEnabled = false
        responseText.text = "Thinking..."
        DebugLog.log("Main", "Sending test message...")

        lifecycleScope.launch {
            val response = claudeApi.chat(
                userMessage = "Hey Senni! Say something cute in under 50 words~",
                systemPrompt = "You are Senni, a playful and mischievous companion. Be cute, a little teasing, maybe flirty. Keep it short and sweet. Use ~, ♡, and similar flourishes sparingly."
            )

            DebugLog.log("Main", "API response - success: ${response.success}, error: ${response.error}")
            
            if (response.success) {
                responseText.text = response.text
            } else {
                responseText.text = "Hmph! Something went wrong: ${response.error}"
            }
            testButton.isEnabled = claudeAuth.isAuthenticated()
        }
    }

    private fun checkPermissionAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant overlay permission!", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
        } else {
            startOverlayService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            OVERLAY_PERMISSION_REQUEST -> {
                if (Settings.canDrawOverlays(this)) {
                    startOverlayService()
                } else {
                    Toast.makeText(this, "Overlay permission denied :(", Toast.LENGTH_SHORT).show()
                }
            }

        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, CompanionOverlayService::class.java)
        startForegroundService(intent)
        android.os.Handler(mainLooper).postDelayed({ updateUI() }, 100)
    }

    private fun stopOverlayService() {
        CompanionOverlayService.dismiss()
        android.os.Handler(mainLooper).postDelayed({ updateUI() }, 350)  // Wait for fade
    }

    private fun showSpriteDialog(type: String) {
        val title = if (type == "idle") "Idle Sprite" else "Walk Sprite"
        val currentUri = if (type == "idle") 
            PromptSettings.getIdleSpriteUri(this) 
        else 
            PromptSettings.getWalkSpriteUri(this)
        
        val currentFrameCount = if (type == "idle")
            PromptSettings.getIdleFrameCount(this)
        else
            PromptSettings.getWalkFrameCount(this)
        
        val defaultFrameCount = if (type == "idle")
            PromptSettings.DEFAULT_IDLE_FRAME_COUNT
        else
            PromptSettings.DEFAULT_WALK_FRAME_COUNT
        
        val hasCustom = currentUri != null
        
        // Build dialog with frame count input
        val frameInput = EditText(this).apply {
            setText(currentFrameCount.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Frame count"
            gravity = android.view.Gravity.CENTER
        }
        
        val label = TextView(this).apply {
            text = "Frames in sprite sheet:"
            textSize = 14f
        }
        
        val statusLabel = TextView(this).apply {
            text = if (hasCustom) "Custom sprite set" else "Using default sprite"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
        }
        
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
            addView(statusLabel)
            addView(android.view.View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 
                    (16 * resources.displayMetrics.density).toInt()
                )
            })
            addView(label)
            addView(frameInput)
        }
        
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("Choose Image") { _, _ ->
                // Save frame count first
                val count = frameInput.text.toString().toIntOrNull() ?: defaultFrameCount
                if (type == "idle") {
                    PromptSettings.setIdleFrameCount(this, count.coerceIn(1, 60))
                } else {
                    PromptSettings.setWalkFrameCount(this, count.coerceIn(1, 60))
                }
                pendingSpriteType = type
                spritePickerLauncher.launch(arrayOf("image/*"))
            }
            .setNegativeButton("Save Count") { _, _ ->
                val count = frameInput.text.toString().toIntOrNull() ?: defaultFrameCount
                if (type == "idle") {
                    PromptSettings.setIdleFrameCount(this, count.coerceIn(1, 60))
                } else {
                    PromptSettings.setWalkFrameCount(this, count.coerceIn(1, 60))
                }
                Toast.makeText(this, "Saved~ Restart overlay to apply", Toast.LENGTH_SHORT).show()
            }
            .apply {
                if (hasCustom) {
                    setNeutralButton("Reset") { _, _ ->
                        if (type == "idle") {
                            PromptSettings.setIdleSpriteUri(this@MainActivity, null)
                            PromptSettings.setIdleFrameCount(this@MainActivity, PromptSettings.DEFAULT_IDLE_FRAME_COUNT)
                        } else {
                            PromptSettings.setWalkSpriteUri(this@MainActivity, null)
                            PromptSettings.setWalkFrameCount(this@MainActivity, PromptSettings.DEFAULT_WALK_FRAME_COUNT)
                        }
                        Toast.makeText(this@MainActivity, "Reset to default~", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun handleSpriteSelected(uri: Uri) {
        val type = pendingSpriteType ?: return
        pendingSpriteType = null
        
        // Take persistable permission so we can read it later
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            DebugLog.log("Main", "Failed to take permission: ${e.message}")
        }
        
        if (type == "idle") {
            PromptSettings.setIdleSpriteUri(this, uri.toString())
        } else {
            PromptSettings.setWalkSpriteUri(this, uri.toString())
        }
        
        Toast.makeText(this, "Sprite updated~ Restart overlay to apply", Toast.LENGTH_LONG).show()
    }

    private fun showEditDialog(
        title: String,
        currentText: String,
        defaultText: String,
        onSave: (String) -> Unit
    ) {
        val density = resources.displayMetrics.density
        val screenHeight = resources.displayMetrics.heightPixels
        
        val editText = EditText(this).apply {
            setText(currentText)
            setSelection(text.length)
            gravity = android.view.Gravity.TOP
            setHorizontallyScrolling(false)
            isSingleLine = false
            inputType = android.text.InputType.TYPE_CLASS_TEXT or 
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        val scrollView = ScrollView(this).apply {
            addView(editText)
            isFillViewport = true
        }

        // Buttons
        val resetBtn = Button(this, null, android.R.attr.buttonBarButtonStyle).apply { text = "Reset" }
        val clearBtn = Button(this, null, android.R.attr.buttonBarButtonStyle).apply { text = "Clear" }
        val cancelBtn = Button(this, null, android.R.attr.buttonBarButtonStyle).apply { text = "Cancel" }
        val saveBtn = Button(this, null, android.R.attr.buttonBarButtonStyle).apply { text = "Save" }

        // Button row: [Reset][Clear] <--spacer--> [Cancel][Save]
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (8 * density).toInt()
            setPadding(pad, (12 * density).toInt(), pad, pad)
            
            addView(resetBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(clearBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            // Spacer pushes remaining buttons to the right
            addView(android.widget.Space(this@MainActivity), LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ))
            addView(cancelBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(saveBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * density).toInt()
            setPadding(pad, pad, pad, 0)
            
            addView(scrollView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
            addView(buttonRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .create()
        
        // Set size BEFORE showing to avoid jump
        dialog.window?.apply {
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                (screenHeight * 0.85).toInt()
            )
        }
        
        resetBtn.setOnClickListener { editText.setText(defaultText) }
        clearBtn.setOnClickListener { editText.setText("") }
        cancelBtn.setOnClickListener { dialog.dismiss() }
        saveBtn.setOnClickListener {
            val newText = editText.text.toString().trim()
            if (newText.isEmpty()) {
                Toast.makeText(this, "Prompt can't be empty~", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onSave(newText)
            Toast.makeText(this, "Saved~", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        
        dialog.show()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CompanionOverlayService.CHANNEL_ID,
            "Companion Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Senni alive on your screen"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
