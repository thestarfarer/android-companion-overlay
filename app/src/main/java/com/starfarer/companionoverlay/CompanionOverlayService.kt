package com.starfarer.companionoverlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.graphics.*
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CompanionOverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "companion_overlay_channel"
        var isRunning = false
        var instance: CompanionOverlayService? = null
            private set
        
        /** Dismiss with fade-out animation. Call this instead of stopService. */
        fun dismiss() {
            instance?.dismissAnimated()
        }
        
        /** Listener for state changes */
        var stateListener: (() -> Unit)? = null
        
        private fun notifyStateChanged() {
            stateListener?.invoke()
        }

        // Animation constants (matching the C++ version)
        private const val TARGET_FPS = 60
        private const val DEFAULT_IDLE_FRAME_COUNT = 6
        private const val DEFAULT_WALK_FRAME_COUNT = 4
        private const val IDLE_FRAME_DURATION_MS = 1000L  // 1 second per idle frame
        private const val WALK_FRAME_DURATION_MS = 200L   // 0.2 seconds per walk frame
        private const val WALK_DISTANCE_DP = 100
        private const val MARGIN_RIGHT_DP = 40
        private const val MARGIN_BOTTOM_DP = 80
        
        // Disturbance thresholds
        private const val DISTURBANCE_TIMEOUT_MS = 1000L
        private const val CROSSING_THRESHOLD_TIME_MS = 10000L
        private const val DISTURBANCE_MIN = 2
        private const val DISTURBANCE_MAX = 5
    }

    private fun log(msg: String) = DebugLog.log("Overlay", msg)

    private fun saveConversationHistory() {
        if (PromptSettings.getKeepDialogue(this)) {
            val arr = JSONArray()
            for (msg in conversationHistory) arr.put(msg)
            PromptSettings.setConversationHistory(this, arr.toString())
        } else {
            PromptSettings.setConversationHistory(this, null)
        }
    }

    private fun loadSprite(customUri: String?, customAsset: String, defaultAsset: String): Bitmap {
        // Try user-picked sprite first
        if (customUri != null) {
            try {
                val uri = Uri.parse(customUri)
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) {
                        log("Loaded custom sprite from $customUri")
                        return bitmap
                    }
                }
            } catch (e: Exception) {
                log("Failed to load custom sprite: ${e.message}")
            }
        }

        // Try build-time custom asset
        try {
            assets.open(customAsset).use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                if (bitmap != null) {
                    log("Loaded custom asset: $customAsset")
                    return bitmap
                }
            }
        } catch (_: Exception) {}

        // Fall back to default
        return assets.open(defaultAsset).use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: ImageView
    private lateinit var params: WindowManager.LayoutParams
    private var speechBubble: View? = null
    private var speechParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    // Sprite sheets
    private var idleSpriteSheet: Bitmap? = null
    private var walkSpriteSheet: Bitmap? = null
    private var idleFrameCount = DEFAULT_IDLE_FRAME_COUNT
    private var walkFrameCount = DEFAULT_WALK_FRAME_COUNT
    private var idleFrameWidth = 0
    private var idleFrameHeight = 0
    private var walkFrameWidth = 0
    private var walkFrameHeight = 0

    // Ghost mode (when keyboard visible)
    private var isGhostMode = false

    // Tracked dismiss runnable to prevent stale callbacks killing new bubbles
    private var pendingBubbleDismiss: Runnable? = null

    // State machine
    private enum class OverlayState { Idle, WalkingLeft, WalkingRight }
    private enum class OverlayPosition { Left, Right }
    private enum class EscapePhase { None, Exit, Enter }

    private var state = OverlayState.Idle
    private var position = OverlayPosition.Right
    private var escapePhase = EscapePhase.None
    private var escaping = false

    // Animation state
    private var startTime = 0L
    private var walkStartTime = 0L
    private var walkStartX = 0
    private var walkTargetX = 0
    private var currentFrame = 0

    // Touch detection
    private var lastTouchTime = 0L
    private val disturbanceTimestamps = mutableListOf<Long>()
    private var disturbanceThreshold = Random.nextInt(DISTURBANCE_MIN, DISTURBANCE_MAX + 1)

    // Screen dimensions
    private var screenWidth = 0
    private var screenHeight = 0
    private var density = 1f
    
    // OAuth and screenshot
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var claudeAuth: ClaudeAuth
    private lateinit var claudeApi: ClaudeApi

    /** Tracks the in-flight Claude API coroutine so we can cancel it. */
    private var activeRequestJob: Job? = null
    private lateinit var screenshotManager: ScreenshotManager
    lateinit var voiceController: VoiceInputController
        private set
    lateinit var ttsManager: TtsManager
    lateinit var geminiTtsManager: GeminiTtsManager
        private set
    private val beepManager = BeepManager()
    
    // Dialogue history - reset every app launch
    private val conversationHistory = mutableListOf<JSONObject>()
    
    // Long press detection
    private var longPressHandler = Handler(Looper.getMainLooper())
    private var isLongPress = false
    private var touchDownY = 0f
    private var lastCompanionMessage: String? = null
    private val longPressTimeout = 500L

    private val animationRunnable = object : Runnable {
        override fun run() {
            updateAnimation()
            handler.postDelayed(this, 1000L / TARGET_FPS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacks(animationRunnable)
                }
                Intent.ACTION_SCREEN_ON -> {
                    startTime = System.currentTimeMillis()
                    overlayView.alpha = 0f
                    updateAnimation()
                    handler.post(animationRunnable)
                }
                Intent.ACTION_USER_PRESENT -> {
                    overlayView.animate().alpha(1f).setDuration(300).start()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        notifyStateChanged()
        
        // Initialize OAuth components
        claudeAuth = ClaudeAuth(this)
        claudeApi = ClaudeApi(claudeAuth)
        claudeApi.model = PromptSettings.getModel(this)
        screenshotManager = ScreenshotManager(this)
        voiceController = VoiceInputController(this)
        ttsManager = TtsManager(this)
        geminiTtsManager = GeminiTtsManager(this)
        geminiTtsManager.onStatusUpdate = { status ->
            if (status.isNotEmpty()) {
                showBriefBubble(status, 30000L) // stays until cleared or audio plays
            } else {
                hideSpeechBubble() // audio started, clear the bubble
            }
        }
        geminiTtsManager.onSpeechError = { failedText ->
            log("Gemini TTS failed, falling back to on-device TTS")
            playBeep(BeepManager.Beep.ERROR)
            // Transfer the onSpeechDone callback to on-device TTS and speak
            ttsManager.onSpeechDone = geminiTtsManager.onSpeechDone
            geminiTtsManager.onSpeechDone = null
            ttsManager.speak(failedText)
        }

        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Get screen dimensions
        val displayMetrics = resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        density = displayMetrics.density
        
        loadSprites()
        createOverlayView()
        createNotificationChannel()
        startForeground(1, createNotification())
        
        // Restore conversation history if keep-dialogue is enabled
        if (PromptSettings.getKeepDialogue(this)) {
            PromptSettings.getConversationHistory(this)?.let { json ->
                try {
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) {
                        conversationHistory.add(arr.getJSONObject(i))
                    }
                    log("Restored ${conversationHistory.size} messages from history")
                    // Restore last companion message for bubble reopen
                    for (i in conversationHistory.indices.reversed()) {
                        val msg = conversationHistory[i]
                        if (msg.optString("role") == "assistant") {
                            val content = msg.opt("content")
                            if (content is String) {
                                lastCompanionMessage = content
                                log("Restored lastCompanionMessage: ${content.take(50)}")
                            }
                            break
                        }
                    }
                } catch (e: Exception) {
                    log("Failed to restore history: ${e.message}")
                }
            }
        }

        startTime = System.currentTimeMillis()
        handler.post(animationRunnable)

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceController.destroy()
        ttsManager.release()
        geminiTtsManager.release()
        if (::params.isInitialized) {
            saveConversationHistory()
            PromptSettings.setAvatarX(this, params.x)
            PromptSettings.setAvatarPosition(this, if (position == OverlayPosition.Left) "left" else "right")
        }
        isRunning = false
        instance = null
        notifyStateChanged()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        handler.removeCallbacks(animationRunnable)
        longPressHandler.removeCallbacksAndMessages(null)
        hideVoiceBubble()
        hideSpeechBubble()
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) { }
        }
        idleSpriteSheet?.recycle()
        walkSpriteSheet?.recycle()
    }

    private fun loadSprites() {
        // Load sprites - custom or default
        // Load frame counts
        idleFrameCount = PromptSettings.getIdleFrameCount(this)
        walkFrameCount = PromptSettings.getWalkFrameCount(this)
        
        idleSpriteSheet = loadSprite(PromptSettings.getIdleSpriteUri(this), "custom_idle_sheet.png", "idle_sheet.png")
        walkSpriteSheet = loadSprite(PromptSettings.getWalkSpriteUri(this), "custom_walk_sheet.png", "walk_sheet.png")
        
        idleSpriteSheet?.let {
            idleFrameWidth = it.width / idleFrameCount
            idleFrameHeight = it.height
        }
        
        walkSpriteSheet?.let {
            walkFrameWidth = it.width / walkFrameCount
            walkFrameHeight = it.height
        }
    }

    private fun createOverlayView() {
        overlayView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        // Use the larger of the two frame sizes
        val viewWidth = maxOf(idleFrameWidth, walkFrameWidth)
        val viewHeight = maxOf(idleFrameHeight, walkFrameHeight)
        
        val marginRight = (MARGIN_RIGHT_DP * density).toInt()
        val marginBottom = (MARGIN_BOTTOM_DP * density).toInt()
        val walkDistance = (WALK_DISTANCE_DP * density).toInt()

        params = WindowManager.LayoutParams(
            viewWidth,
            viewHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // FAR position: margin + walkDistance from edge
            x = screenWidth - viewWidth - marginRight - walkDistance
            y = screenHeight - viewHeight - marginBottom
        }

        // Restore saved position if available
        val savedX = PromptSettings.getAvatarX(this)
        if (savedX >= 0) {
            params.x = savedX
            PromptSettings.getAvatarPosition(this)?.let {
                position = if (it == "left") OverlayPosition.Left else OverlayPosition.Right
            }
        }

        walkStartX = params.x
        walkTargetX = params.x

        // Touch handling: short tap = disturb, long press = screenshot
        overlayView.isClickable = true
        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    log("Touch DOWN at y=${event.y}")
                    isLongPress = false
                    touchDownY = event.y
                    longPressHandler.postDelayed({
                        isLongPress = true
                        val threshold = overlayView.height * 2f / 3f
                        if (touchDownY < threshold) {
                            log("Long press TOP zone -> screenshot")
                            onLongPress()
                        } else {
                            log("Long press BOTTOM zone -> reopen bubble")
                            reopenBubble()
                        }
                    }, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    log("Touch UP/CANCEL, isLongPress=$isLongPress")
                    longPressHandler.removeCallbacksAndMessages(null)
                    if (!isLongPress) {
                        handleTouch()
                    }
                    true
                }
                else -> true  // Must return true to keep receiving events
            }
        }

        windowManager.addView(overlayView, params)
        
        // Fade in entrance
        overlayView.alpha = 0f
        overlayView.animate().alpha(1f).setDuration(300).start()
    }

    private fun onLongPress() {
        log("Long press detected!")
        
        overlayView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        
        if (!CompanionAccessibilityService.isRunning) {
            log("No screenshot permission!")
            showBriefBubble("Grant screenshot permission in the app first~", 4000L)
            return
        }
        
        if (!claudeAuth.isAuthenticated()) {
            log("Not authenticated!")
            showBriefBubble("Authenticate first, silly~")
            return
        }
        
        showBriefBubble("Let me see~", 2000L)
        
        // Don't hide - I want to be in the screenshot too!
        handler.postDelayed({
            log("Taking screenshot...")
            screenshotManager.takeScreenshot { base64 ->
                log("Screenshot result: ${if (base64 != null) "${base64.length} chars" else "null"}")
                
                if (base64 != null) {
                    if (PromptSettings.getVoiceScreenshot(this@CompanionOverlayService)) {
                        // Store screenshot and start voice input on main thread
                        handler.post {
                            pendingScreenshotBase64 = base64
                            pendingBubbleDismiss?.let { handler.removeCallbacks(it) }
                            hideSpeechBubble()
                            voiceController.toggle()
                        }
                    } else {
                        commentOnScreen(base64)
                    }
                } else {
                    showBriefBubble("Couldn't peek at your screen...")
                }
            }
        }, 150)
    }

    private fun reopenBubble() {
        log("Reopening bubble, lastMessage=${lastCompanionMessage?.take(30)}")
        overlayView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        
        if (lastCompanionMessage != null) {
            showLongToast(lastCompanionMessage!!)
        } else {
            showLongToast("Ask me anything~")
        }
    }

    private fun commentOnScreen(imageBase64: String, voiceText: String? = null) {
        log("Sending screenshot to Claude (turn ${(conversationHistory.size / 2) + 1})...")
        claudeApi.model = PromptSettings.getModel(this)
        cancelActiveRequest()
        activeRequestJob = serviceScope.launch {
            // Build this turn's user message with image
            val userMessage = JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "image")
                        put("source", JSONObject().apply {
                            put("type", "base64")
                            put("media_type", "image/jpeg")
                            put("data", imageBase64)
                        })
                    })
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", voiceText ?: PromptSettings.getUserMessage(this@CompanionOverlayService))
                    })
                })
            }
            
            // Build full messages array: history + new message (keep all images!)
            val messagesArray = JSONArray()
            for (msg in conversationHistory) {
                messagesArray.put(msg)
            }
            messagesArray.put(userMessage)
            
            val systemPrompt = PromptSettings.getSystemPrompt(this@CompanionOverlayService)
            val webSearch = PromptSettings.getWebSearch(this@CompanionOverlayService)

            val response = claudeApi.sendConversation(messagesArray, systemPrompt, webSearch)
            
            log("Claude response: success=${response.success}, text=${response.text.take(50)}, error=${response.error}")
            
            if (response.success) {
                voiceController.cancelSafetyTimeoutPublic()
                // Save both user message and companion response to history
                conversationHistory.add(userMessage)
                conversationHistory.add(JSONObject().apply {
                    put("role", "assistant")
                    put("content", response.text)
                })
                
                lastCompanionMessage = response.text
                log("Conversation history: ${conversationHistory.size} messages")
                
                // Trim old history
                val maxMsgs = PromptSettings.getMaxMessages(this@CompanionOverlayService)
                while (conversationHistory.size > maxMsgs) {
                    conversationHistory.removeAt(0)
                    conversationHistory.removeAt(0)
                }
                
                saveConversationHistory()

                // Auto-copy to clipboard if enabled
                if (PromptSettings.getAutoCopy(this@CompanionOverlayService)) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Senni", response.text))
                }

                playBeep(BeepManager.Beep.STEP)
                // TTS-aware response: speak if enabled, bubble if not
                val ttsEnabled = PromptSettings.getTtsEnabled(this@CompanionOverlayService)
                val wasVoice = pendingVoiceReply
                pendingVoiceReply = false
                if (ttsEnabled || wasVoice) {
                    pendingBubbleDismiss?.let { handler.removeCallbacks(it) }
                    hideSpeechBubble()
                    setActiveTtsOnDone {
                        playBeep(BeepManager.Beep.DONE)
                        if (wasVoice) {
                            handler.post { voiceController.onVoiceResponseComplete() }
                        }
                    }
                    activeTtsSpeak(response.text)
                } else {
                    showLongToast(response.text)
                    voiceController.onVoiceResponseComplete()
                }
            } else if (response.error == "Cancelled") {
                // Request was cancelled by a new one — stay silent
                log("Request cancelled, suppressing error")
                pendingVoiceReply = false
            } else if (response.error == "Cancelled") {
                log("Request cancelled, suppressing error")
                pendingVoiceReply = false
            } else {
                playBeep(BeepManager.Beep.ERROR)
                pendingVoiceReply = false
                showBriefBubble("Hmph! ${response.error}")
                voiceController.onVoiceResponseComplete()
            }
        }
    }


    private fun showBriefBubble(message: String, durationMs: Long = 3000L) {
        pendingBubbleDismiss?.let { handler.removeCallbacks(it) }
        hideSpeechBubble()

        val bgColor: Int
        val textColor: Int
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            bgColor = resources.getColor(android.R.color.system_accent1_100, theme)
            textColor = resources.getColor(android.R.color.system_accent1_900, theme)
        } else {
            bgColor = 0xFFF5E6D3.toInt()
            textColor = 0xFF2D1B0E.toInt()
        }

        val bgWithAlpha = (bgColor and 0x00FFFFFF) or 0xFA000000.toInt()
        val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(bgWithAlpha)
            cornerRadius = 24f * resources.displayMetrics.density
        }

        val bubble = android.widget.TextView(this).apply {
            text = message
            setTextColor(textColor)
            textSize = 14f
            background = bgDrawable
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad + 8, pad, pad + 8, pad)
            gravity = android.view.Gravity.CENTER
        }

        val bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            y = (120 * resources.displayMetrics.density).toInt()
        }

        try {
            bubble.alpha = 0f
            windowManager.addView(bubble, bubbleParams)
            bubble.animate().alpha(1f).setDuration(200).start()
            speechBubble = bubble
            speechParams = bubbleParams

            bubble.setOnClickListener { activeTtsStop(); hideSpeechBubble() }
            val dismiss = Runnable { hideSpeechBubble() }
            pendingBubbleDismiss = dismiss
            handler.postDelayed(dismiss, durationMs)
        } catch (e: Exception) {
            log("Failed to show brief bubble: ${e.message}")
        }
    }

    private fun showLongToast(message: String) {
        log("Showing speech bubble: $message")

        // Cancel any pending dismiss timer and remove existing bubble
        pendingBubbleDismiss?.let { handler.removeCallbacks(it) }
        hideSpeechBubble()

        val d = resources.displayMetrics.density

        // Get Monet/Material You colors if available (Android 12+)
        val bgColor: Int
        val textColor: Int
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            bgColor = resources.getColor(android.R.color.system_accent1_100, theme)
            textColor = resources.getColor(android.R.color.system_accent1_900, theme)
        } else {
            bgColor = 0xFFF5E6D3.toInt()
            textColor = 0xFF2D1B0E.toInt()
        }

        val bgWithAlpha = (bgColor and 0x00FFFFFF) or 0xFA000000.toInt()

        val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(bgWithAlpha)
            cornerRadius = 24f * d
        }

        // Response text (top padding handled by ScrollView to inset scrollbar)
        val responseText = TextView(this).apply {
            text = message
            setTextColor(textColor)
            textSize = 15f
            val pad = (16 * d).toInt()
            setPadding(pad + 8, 0, pad + 8, (8 * d).toInt())
            gravity = Gravity.CENTER
            setLineSpacing(4f, 1f)
        }

        // Reply input row
        val inputBg = android.graphics.drawable.GradientDrawable().apply {
            setColor((textColor and 0x00FFFFFF) or 0x18000000.toInt())
            cornerRadius = 16f * d
        }

        val replyInput = android.widget.EditText(this).apply {
            hint = "Reply..."
            setHintTextColor((textColor and 0x00FFFFFF) or 0x66000000.toInt())
            setTextColor(textColor)
            textSize = 13f
            background = inputBg
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            val inputPad = (10 * d).toInt()
            setPadding(inputPad + 4, inputPad, inputPad, inputPad)
        }

        val sendButton = TextView(this).apply {
            text = "\u27A4"  // ➤ arrow
            textSize = 20f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            val btnPad = (8 * d).toInt()
            setPadding(btnPad + 4, btnPad, btnPad + 4, btnPad)
        }

        val inputRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val rowPad = (8 * d).toInt()
            setPadding(rowPad + 4, 0, rowPad + 4, rowPad)

            addView(replyInput, android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            addView(sendButton, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        // Scrollable response area for long messages
        val cornerPad = (24 * d).toInt()  // match container corner radius
        val responseScroll = android.widget.ScrollView(this).apply {
            addView(responseText)
            isVerticalScrollBarEnabled = true
            setPadding(0, cornerPad, 0, cornerPad)
            clipToPadding = false
            // Darker Monet scrollbar thumb
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val thumbColor = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    resources.getColor(android.R.color.system_accent1_300, theme)
                } else {
                    0xFFBFA68E.toInt()
                }
                setVerticalScrollbarThumbDrawable(android.graphics.drawable.GradientDrawable().apply {
                    setColor(thumbColor)
                    cornerRadius = 4f * d
                    setSize((4 * d).toInt(), 0)
                })
            }
        }

        // Root container
        val maxW = (resources.displayMetrics.widthPixels * 0.85).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = bgDrawable
            minimumWidth = (200 * d).toInt()
            addView(responseScroll, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(inputRow, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val bubbleParams = WindowManager.LayoutParams(
            maxW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        try {
            container.alpha = 0f
            windowManager.addView(container, bubbleParams)
            container.animate().alpha(1f).setDuration(300).start()
            speechBubble = container
            speechParams = bubbleParams

            // Cap scroll height so input row stays visible on long responses
            val maxResponseH = (screenHeight * 0.6).toInt()
            responseScroll.post {
                if (responseScroll.height > maxResponseH) {
                    responseScroll.layoutParams.height = maxResponseH
                    responseScroll.requestLayout()
                }
            }

            // Tap response text to dismiss
            responseText.setOnClickListener { activeTtsStop(); hideSpeechBubble() }

            // When EditText is touched, make the overlay focusable so keyboard appears
            replyInput.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN &&
                    bubbleParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) {
                    bubbleParams.flags = bubbleParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                    try { windowManager.updateViewLayout(container, bubbleParams) } catch (_: Exception) {}
                    replyInput.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    replyInput.post { imm.showSoftInput(replyInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
                    pendingBubbleDismiss?.let { handler.removeCallbacks(it) }
                    // Next frame: re-add sprite above keyboard + ghost fade-in
                    handler.post {
                        overlayView.alpha = 0f
                        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        try {
                            windowManager.removeView(overlayView)
                            windowManager.addView(overlayView, params)
                        } catch (_: Exception) {}
                        overlayView.animate().alpha(0.5f).setDuration(200).start()
                        isGhostMode = true
                    }
                }
                false
            }

            // Send on IME action
            replyInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    val text = replyInput.text.toString().trim()
                    if (text.isNotEmpty()) sendTextReply(text)
                    true
                } else false
            }

            // Send button click
            sendButton.setOnClickListener {
                val text = replyInput.text.toString().trim()
                if (text.isNotEmpty()) sendTextReply(text)
            }

            // Auto-dismiss after configured timeout
            val timeoutMs = PromptSettings.getBubbleTimeout(this) * 1000L
            val dismiss = Runnable { hideSpeechBubble() }
            pendingBubbleDismiss = dismiss
            handler.postDelayed(dismiss, timeoutMs)

        } catch (e: Exception) {
            log("Failed to show speech bubble: ${e.message}")
            log("Also failed fallback for: $message")
        }
    }

    private fun sendTextReply(text: String) {
        log("Sending text reply: ${text.take(50)}")
        pendingBubbleDismiss?.let { handler.removeCallbacks(it) }
        // Hide keyboard gracefully before removing the bubble
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        speechBubble?.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
        hideSpeechBubble()
        playBeep(BeepManager.Beep.STEP)
        showBriefBubble("Thinking...", 30000L)

        cancelActiveRequest()
        activeRequestJob = serviceScope.launch {
            val userMessage = JSONObject().apply {
                put("role", "user")
                put("content", text)
            }
            val messagesArray = JSONArray()
            for (msg in conversationHistory) messagesArray.put(msg)
            messagesArray.put(userMessage)

            val systemPrompt = PromptSettings.getSystemPrompt(this@CompanionOverlayService)
            claudeApi.model = PromptSettings.getModel(this@CompanionOverlayService)
            val webSearch = PromptSettings.getWebSearch(this@CompanionOverlayService)
            val response = claudeApi.sendConversation(messagesArray, systemPrompt, webSearch)

            if (response.success) {
                voiceController.cancelSafetyTimeoutPublic()
                conversationHistory.add(userMessage)
                conversationHistory.add(JSONObject().apply {
                    put("role", "assistant")
                    put("content", response.text)
                })
                lastCompanionMessage = response.text
                val maxMsgs = PromptSettings.getMaxMessages(this@CompanionOverlayService)
                while (conversationHistory.size > maxMsgs) {
                    conversationHistory.removeAt(0)
                    conversationHistory.removeAt(0)
                }
                saveConversationHistory()
                if (PromptSettings.getAutoCopy(this@CompanionOverlayService)) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Senni", response.text))
                }
                playBeep(BeepManager.Beep.STEP)
                val ttsEnabled = PromptSettings.getTtsEnabled(this@CompanionOverlayService)
                // When TTS is enabled (or voice input), speak instead of showing bubble
                if (ttsEnabled || pendingVoiceReply) {
                    // Dismiss "Thinking..." bubble without stopping TTS
                    pendingBubbleDismiss?.let { handler.removeCallbacks(it) }
                    hideSpeechBubble()
                    val wasVoice = pendingVoiceReply
                    pendingVoiceReply = false
                    setActiveTtsOnDone {
                        playBeep(BeepManager.Beep.DONE)
                        if (wasVoice) {
                            handler.post { voiceController.onVoiceResponseComplete() }
                        }
                    }
                    activeTtsSpeak(response.text)
                } else {
                    // TTS off and typed input — show response bubble
                    pendingVoiceReply = false
                    showLongToast(response.text)
                    voiceController.onVoiceResponseComplete()
                }
            } else {
                playBeep(BeepManager.Beep.ERROR)
                showBriefBubble("Hmph! ${response.error}")
            }
        }
    }


    // === Voice input integration ===

    private var voiceBubble: android.widget.TextView? = null
    private var pendingVoiceReply = false
    private var pendingScreenshotBase64: String? = null
    private var voiceBubbleParams: WindowManager.LayoutParams? = null

    /** Public entry point for voice-transcribed text. Same flow as typed reply. */
    /** Whether beeps are enabled. */
    private val beepsEnabled: Boolean
        get() = PromptSettings.getBeepsEnabled(this)

    /** Cancel any in-flight Claude API request (coroutine + HTTP call). */
    private fun cancelActiveRequest() {
        activeRequestJob?.let { job ->
            if (job.isActive) {
                log("Cancelling active request")
                job.cancel()
                claudeApi.cancelPending()
            }
        }
        activeRequestJob = null
    }

    /** Play a beep if enabled. */
    fun playBeep(beep: BeepManager.Beep) {
        log("playBeep($beep) enabled=$beepsEnabled")
        if (beepsEnabled) beepManager.play(beep)
    }

    /** Whether to use Gemini TTS instead of on-device. */
    private val useGeminiTts: Boolean
        get() = PromptSettings.getGeminiTts(this) && !PromptSettings.getGeminiApiKey(this).isNullOrBlank()

    /** Speak text through the active TTS engine. */
    fun activeTtsSpeak(text: String) {
        if (useGeminiTts) {
            geminiTtsManager.speak(text)
        } else {
            ttsManager.speak(text)
        }
    }

    /** Stop the active TTS engine. */
    fun activeTtsStop() {
        ttsManager.stop()
        geminiTtsManager.stop()
        cancelActiveRequest()
    }

    /** Set onSpeechDone on the active TTS engine. */
    fun setActiveTtsOnDone(callback: (() -> Unit)?) {
        if (useGeminiTts) {
            geminiTtsManager.onSpeechDone = callback
        } else {
            ttsManager.onSpeechDone = callback
        }
    }

    /** Check if either TTS engine is speaking. */
    val isActiveTtsSpeaking: Boolean
        get() = ttsManager.isSpeaking || geminiTtsManager.isSpeaking

    /**
     * Build a short text summary of recent conversation for Gemini STT context.
     * This helps Gemini understand domain terms (PLTR, Senni, Kulikovsky, etc.)
     */
    fun getConversationContextForStt(): String {
        // Take last 6 messages (3 turns) — enough for context without bloating the request
        val recent = conversationHistory.takeLast(6)
        if (recent.isEmpty()) return ""

        return buildString {
            for (msg in recent) {
                val role = msg.optString("role", "user")
                val content = msg.opt("content")
                val text = when (content) {
                    is String -> content
                    is JSONArray -> {
                        // Image messages: extract only the text part, skip base64 data
                        var found = ""
                        for (j in 0 until content.length()) {
                            val block = content.getJSONObject(j)
                            if (block.optString("type") == "text") {
                                found = block.optString("text", "")
                                break
                            }
                        }
                        found
                    }
                    else -> ""
                }
                if (text.isNotBlank()) {
                    val label = if (role == "assistant") "Assistant" else "User"
                    appendLine("$label: ${text.take(200)}")
                }
            }
        }.trim()
    }

    fun sendVoiceInput(text: String) {
        log("Voice input: ${text.take(80)}")
        pendingVoiceReply = true
        val screenshot = pendingScreenshotBase64
        if (screenshot != null) {
            // Voice + Screenshot: send both together
            pendingScreenshotBase64 = null
            playBeep(BeepManager.Beep.STEP)
            showBriefBubble("Thinking...", 30000L)
            commentOnScreen(screenshot, text)
        } else {
            sendTextReply(text)
        }
    }

    /** Called when voice recognition is cancelled while a screenshot was pending */
    fun clearPendingScreenshot() {
        pendingScreenshotBase64 = null
    }

    /** Wrapper so VoiceInputController can show brief bubbles. */
    fun showBriefBubblePublic(message: String, durationMs: Long = 3000L) {
        showBriefBubble(message, durationMs)
    }

    /** Show a persistent voice-listening indicator bubble. */
    fun showVoiceBubble(text: String) {
        hideVoiceBubble()
        val d = resources.displayMetrics.density

        val bgColor: Int
        val textColor: Int
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            bgColor = resources.getColor(android.R.color.system_accent1_100, theme)
            textColor = resources.getColor(android.R.color.system_accent1_900, theme)
        } else {
            bgColor = 0xFFF5E6D3.toInt()
            textColor = 0xFF2D1B0E.toInt()
        }

        val bgWithAlpha = (bgColor and 0x00FFFFFF) or 0xFA000000.toInt()
        val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(bgWithAlpha)
            cornerRadius = 20f * d
        }

        val bubble = android.widget.TextView(this).apply {
            this.text = "\uD83C\uDF99 $text"  // 🎙
            setTextColor(textColor)
            textSize = 13f
            background = bgDrawable
            val pad = (10 * d).toInt()
            setPadding(pad + 8, pad, pad + 8, pad)
            gravity = android.view.Gravity.CENTER
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            y = (120 * d).toInt()
        }

        try {
            bubble.alpha = 0f
            windowManager.addView(bubble, params)
            bubble.animate().alpha(1f).setDuration(200).start()
            voiceBubble = bubble
            voiceBubbleParams = params

            // Tap to cancel listening
            bubble.setOnClickListener {
                voiceController.toggle()
            }
        } catch (e: Exception) {
            log("Failed to show voice bubble: ${e.message}")
        }
    }

    /** Update the voice bubble text (for partial transcription). */
    fun updateVoiceBubble(text: String) {
        voiceBubble?.text = "\uD83C\uDF99 $text"  // 🎙
    }

    /** Hide the voice bubble. */
    fun hideVoiceBubble() {
        voiceBubble?.let { bubble ->
            bubble.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction {
                    try { windowManager.removeView(bubble) } catch (_: Exception) {}
                }
                .start()
        }
        voiceBubble = null
        voiceBubbleParams = null
    }

    private fun hideSpeechBubble(animate: Boolean = true) {
        val bubble = speechBubble ?: return

        // Hide keyboard gracefully before removing bubble
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        bubble.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }

        if (animate) {
            bubble.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    try { windowManager.removeView(bubble) } catch (_: Exception) {}
                }
                .start()
        } else {
            try { windowManager.removeView(bubble) } catch (_: Exception) {}
        }
        speechBubble = null
        speechParams = null
    }

    fun setGhostMode(ghost: Boolean) {
        if (ghost == isGhostMode) return
        isGhostMode = ghost

        handler.post {
            if (ghost) {
                // Fade to 50% and make click-through
                overlayView.animate().alpha(0.5f).setDuration(200).start()
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                // Restore full opacity and touch handling
                overlayView.animate().alpha(1f).setDuration(200).start()
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            try {
                windowManager.updateViewLayout(overlayView, params)
            } catch (_: Exception) {}
        }
    }

    private fun dismissAnimated() {
        // Fade out, then stop service
        overlayView.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction {
                stopSelf()
            }
            .start()
    }

    private fun handleTouch() {
        val now = System.currentTimeMillis()
        
        if (state != OverlayState.Idle) return
        if (now - lastTouchTime < DISTURBANCE_TIMEOUT_MS) return
        
        lastTouchTime = now
        disturbanceTimestamps.add(now)
        
        // Remove old timestamps
        disturbanceTimestamps.removeAll { now - it > CROSSING_THRESHOLD_TIME_MS }
        
        if (disturbanceTimestamps.size >= disturbanceThreshold) {
            // Trigger escape!
            disturbanceTimestamps.clear()
            disturbanceThreshold = Random.nextInt(DISTURBANCE_MIN, DISTURBANCE_MAX + 1)
            triggerEscape()
        } else {
            // Just walk away a bit
            triggerWalk()
        }
    }

    private fun triggerWalk() {
        walkStartTime = System.currentTimeMillis()
        walkStartX = params.x
        val walkDistance = (WALK_DISTANCE_DP * density).toInt()
        
        if (position == OverlayPosition.Right) {
            state = OverlayState.WalkingRight
            walkTargetX = walkStartX + walkDistance
            position = OverlayPosition.Left
        } else {
            state = OverlayState.WalkingLeft
            walkTargetX = walkStartX - walkDistance
            position = OverlayPosition.Right
        }
        
        // Clamp to screen bounds
        val marginRight = (MARGIN_RIGHT_DP * density).toInt()
        walkTargetX = walkTargetX.coerceIn(marginRight, screenWidth - params.width - marginRight)
    }

    private fun triggerEscape() {
        escaping = true
        escapePhase = EscapePhase.Exit
        walkStartTime = System.currentTimeMillis()
        walkStartX = params.x
        
        // Determine closest edge
        val distanceToLeft = params.x + params.width
        val distanceToRight = screenWidth - params.x
        
        if (distanceToRight < distanceToLeft) {
            walkTargetX = screenWidth + params.width
            position = OverlayPosition.Right
            state = OverlayState.WalkingRight
        } else {
            walkTargetX = -params.width * 2
            position = OverlayPosition.Left
            state = OverlayState.WalkingLeft
        }
    }

    private fun updateAnimation() {
        val now = System.currentTimeMillis()
        val elapsed = (now - startTime) / 1000.0  // seconds
        
        when (state) {
            OverlayState.Idle -> drawIdle(elapsed)
            OverlayState.WalkingLeft, OverlayState.WalkingRight -> {
                handleWalking(now)
                drawWalking(now)
            }
        }
    }

    private fun drawIdle(t: Double) {
        val idleSheet = idleSpriteSheet ?: return
        
        // Frame selection (1 second per frame)
        val frame = ((t / (IDLE_FRAME_DURATION_MS / 1000.0)).toInt()) % idleFrameCount
        
        // Breathing effect: scale Y between 0.99 and 1.01
        val scale = 1.0f + 0.01f * sin(2 * Math.PI * t).toFloat()
        
        // Floating effect: 4 pixel amplitude at half speed
        val yOffset = (4.0 * sin(2 * Math.PI * t * 0.5)).toFloat()
        
        // Create output bitmap matching view size
        val viewWidth = params.width
        val viewHeight = params.height
        val outputBitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        
        // Calculate offsets to center the sprite in the view
        val xOffset = (viewWidth - idleFrameWidth) / 2f
        val yCenter = (viewHeight - idleFrameHeight) / 2f
        
        // Apply transforms like C++: translate to center+float, scale Y, translate back
        canvas.save()
        canvas.translate(viewWidth / 2f, viewHeight / 2f + yOffset)
        canvas.scale(1f, scale)
        canvas.translate(-viewWidth / 2f, -viewHeight / 2f)
        
        // Draw frame from spritesheet
        val srcRect = Rect(frame * idleFrameWidth, 0, (frame + 1) * idleFrameWidth, idleFrameHeight)
        val dstRect = RectF(xOffset, yCenter, xOffset + idleFrameWidth, yCenter + idleFrameHeight)
        canvas.drawBitmap(idleSheet, srcRect, dstRect, null)
        
        canvas.restore()
        
        overlayView.setImageBitmap(outputBitmap)
    }

    private fun handleWalking(now: Long) {
        val walkDuration = WALK_FRAME_DURATION_MS * walkFrameCount
        val walkProgress = (now - walkStartTime).toFloat() / walkDuration
        
        if (walkProgress >= 1.0f) {
            // Walking complete
            params.x = walkTargetX
            
            if (escaping) {
                if (escapePhase == EscapePhase.Exit) {
                    // Teleport to other side
                    escapePhase = EscapePhase.Enter
                    walkStartTime = now
                    
                    val marginRight = (MARGIN_RIGHT_DP * density).toInt()
                    val walkDistance = (WALK_DISTANCE_DP * density).toInt()
                    
                    if (position == OverlayPosition.Right) {
                        // Exited right, enter from left to FAR position
                        params.x = -params.width
                        walkStartX = -params.width
                        walkTargetX = marginRight + walkDistance
                        // DO NOT reassign position here - it was set correctly in triggerEscape
                        state = OverlayState.WalkingRight
                    } else {
                        // Exited left, enter from right to FAR position
                        params.x = screenWidth + params.width
                        walkStartX = screenWidth + params.width
                        walkTargetX = screenWidth - params.width - marginRight - walkDistance
                        // DO NOT reassign position here - it was set correctly in triggerEscape
                        state = OverlayState.WalkingLeft
                    }
                } else {
                    // Finished enter phase - flip position to reflect new side
                    position = if (position == OverlayPosition.Right) OverlayPosition.Left else OverlayPosition.Right
                    state = OverlayState.Idle
                    escaping = false
                    escapePhase = EscapePhase.None
                }
            } else {
                state = OverlayState.Idle
            }
            
            windowManager.updateViewLayout(overlayView, params)
        } else {
            // Interpolate position
            val newX = walkStartX + ((walkTargetX - walkStartX) * walkProgress).toInt()
            params.x = newX
            windowManager.updateViewLayout(overlayView, params)
        }
    }

    private fun drawWalking(now: Long) {
        val walkSheet = walkSpriteSheet ?: return
        
        val walkElapsed = now - walkStartTime
        val frame = ((walkElapsed / WALK_FRAME_DURATION_MS).toInt()) % walkFrameCount
        
        // Extract frame
        var frameBitmap = Bitmap.createBitmap(
            walkSheet,
            frame * walkFrameWidth,
            0,
            walkFrameWidth,
            walkFrameHeight
        )
        
        // Mirror based on direction
        if (state == OverlayState.WalkingLeft) {
            val matrix = Matrix().apply {
                preScale(-1f, 1f)
                postTranslate(walkFrameWidth.toFloat(), 0f)
            }
            val mirrored = Bitmap.createBitmap(
                frameBitmap,
                0, 0,
                frameBitmap.width,
                frameBitmap.height,
                matrix,
                true
            )
            frameBitmap.recycle()
            frameBitmap = mirrored
        }
        
        overlayView.setImageBitmap(frameBitmap)
    }

    private fun createNotificationChannel() {
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            "Companion Overlay",
            android.app.NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Senni alive on your screen"
        }
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Senni is here~")
            .setContentText("Tap to open settings")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
