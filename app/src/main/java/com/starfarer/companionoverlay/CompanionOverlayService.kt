package com.starfarer.companionoverlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.starfarer.companionoverlay.event.OverlayCoordinator
import com.starfarer.companionoverlay.event.OverlayEvent
import com.starfarer.companionoverlay.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Foreground service that displays the companion overlay on screen.
 *
 * Orchestrates the various managers but doesn't implement business logic itself:
 * - [SpriteAnimator] handles sprite rendering and animation
 * - [BubbleManager] handles speech bubbles and UI
 * - [ConversationManager] handles Claude API and history
 * - [AudioCoordinator] handles TTS and beeps
 * - [VoiceInputController] handles voice input state machine
 * - [ScreenshotManager] handles accessibility-based screenshots
 *
 * Implements [VoiceInputHost] to provide the interface that [VoiceInputController]
 * needs without exposing internal implementation details.
 *
 * Communicates with other components via [OverlayCoordinator] instead of
 * static instance references.
 */
class CompanionOverlayService : Service(), ConversationManager.Listener, VoiceInputHost {

    companion object {
        const val CHANNEL_ID = "companion_overlay_channel"
        private const val TARGET_FPS = 60
        private const val LONG_PRESS_TIMEOUT_MS = 500L
    }

    // ══════════════════════════════════════════════════════════════════════
    // Dependencies (injected)
    // ══════════════════════════════════════════════════════════════════════

    private val coordinator: OverlayCoordinator by inject()
    private val conversationManager: ConversationManager by inject()
    private val audioCoordinator: AudioCoordinator by inject()
    private val screenshotManager: ScreenshotManager by inject()
    private val settings: SettingsRepository by inject()
    private val beepManager: BeepManager by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ══════════════════════════════════════════════════════════════════════
    // Managers (created in service)
    // ══════════════════════════════════════════════════════════════════════

    private lateinit var windowManager: WindowManager
    private lateinit var spriteAnimator: SpriteAnimator
    private lateinit var bubbleManager: BubbleManager
    private lateinit var voiceController: VoiceInputController

    // ══════════════════════════════════════════════════════════════════════
    // View state
    // ══════════════════════════════════════════════════════════════════════

    private lateinit var overlayView: ImageView
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())

    private var screenWidth = 0
    private var screenHeight = 0
    private var density = 1f

    // ══════════════════════════════════════════════════════════════════════
    // Interaction state
    // ══════════════════════════════════════════════════════════════════════

    private var pendingScreenshotBase64: String? = null
    private var pendingVoiceReply = false

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var isLongPress = false
    private var touchDownY = 0f

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        coordinator.onOverlayServiceStarted()

        initializeScreen()
        initializeManagers()
        initializeOverlayView()
        subscribeToEvents()
        startAnimation()

        createNotificationChannel()
        startForeground(1, createNotification())

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })

        DebugLog.log("Overlay", "Service created")
    }

    override fun onDestroy() {
        super.onDestroy()
        saveState()
        stopAnimation()

        runCatching { unregisterReceiver(screenReceiver) }

        voiceController.destroy()
        audioCoordinator.release()
        conversationManager.destroy()
        bubbleManager.hideVoice()
        bubbleManager.hideSpeechBubble()
        spriteAnimator.release()

        if (::overlayView.isInitialized) {
            runCatching { windowManager.removeView(overlayView) }
        }

        serviceScope.cancel()
        coordinator.onOverlayServiceStopped()

        DebugLog.log("Overlay", "Service destroyed")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Initialization
    // ══════════════════════════════════════════════════════════════════════

    private fun initializeScreen() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        density = metrics.density
    }

    private fun initializeManagers() {
        spriteAnimator = SpriteAnimator(this).apply {
            this.screenWidth = this@CompanionOverlayService.screenWidth
            this.screenHeight = this@CompanionOverlayService.screenHeight
            this.density = this@CompanionOverlayService.density
            loadSprites()
        }

        bubbleManager = BubbleManager(this, windowManager, handler, bubbleHost)

        conversationManager.listener = this

        audioCoordinator.onStatusUpdate = { status ->
            if (status.isNotEmpty()) {
                bubbleManager.showBrief(status, 30000L)
            } else {
                bubbleManager.hideSpeechBubble()
            }
        }

        voiceController = VoiceInputController(this, this, settings, beepManager)
    }

    private fun subscribeToEvents() {
        serviceScope.launch {
            coordinator.events.collect { event ->
                when (event) {
                    is OverlayEvent.ToggleVoice -> voiceController.toggle()
                    is OverlayEvent.DismissOverlay -> dismissAnimated()
                    is OverlayEvent.KeyboardVisibility -> setGhostMode(event.visible)
                    else -> { /* Handled elsewhere */ }
                }
            }
        }
    }

    private fun initializeOverlayView() {
        overlayView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        params = WindowManager.LayoutParams(
            spriteAnimator.viewWidth,
            spriteAnimator.viewHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - spriteAnimator.viewWidth - spriteAnimator.marginRightPx - spriteAnimator.walkDistancePx
            y = screenHeight - spriteAnimator.viewHeight - spriteAnimator.marginBottomPx
        }

        restorePosition()
        setupTouchHandling()

        spriteAnimator.overlayView = overlayView
        spriteAnimator.params = params
        spriteAnimator.windowManager = windowManager
        spriteAnimator.walkStartX = params.x
        spriteAnimator.walkTargetX = params.x

        windowManager.addView(overlayView, params)

        overlayView.alpha = 0f
        overlayView.animate().alpha(1f).setDuration(300).start()
    }

    private fun setupTouchHandling() {
        overlayView.isClickable = true
        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPress = false
                    touchDownY = event.y
                    longPressHandler.postDelayed({
                        isLongPress = true
                        handleLongPress()
                    }, LONG_PRESS_TIMEOUT_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacksAndMessages(null)
                    if (!isLongPress) {
                        spriteAnimator.handleTouch()
                    }
                    true
                }
                else -> true
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Animation loop
    // ══════════════════════════════════════════════════════════════════════

    private val animationRunnable = object : Runnable {
        override fun run() {
            spriteAnimator.update()
            handler.postDelayed(this, 1000L / TARGET_FPS)
        }
    }

    private fun startAnimation() {
        spriteAnimator.startTime = System.currentTimeMillis()
        handler.post(animationRunnable)
    }

    private fun stopAnimation() {
        handler.removeCallbacks(animationRunnable)
        longPressHandler.removeCallbacksAndMessages(null)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Touch interactions
    // ══════════════════════════════════════════════════════════════════════

    private fun handleLongPress() {
        overlayView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        val threshold = overlayView.height * 2f / 3f
        if (touchDownY < threshold) {
            handleScreenshotRequest()
        } else {
            handleBubbleReopen()
        }
    }

    private fun handleScreenshotRequest() {
        if (!coordinator.accessibilityRunning.value) {
            bubbleManager.showBrief("Grant screenshot permission in the app first~", 4000L)
            return
        }

        bubbleManager.showBrief("Let me see~", 2000L)

        handler.postDelayed({
            screenshotManager.takeScreenshot { base64 ->
                if (base64 != null) {
                    if (settings.voiceScreenshotEnabled) {
                        handler.post {
                            pendingScreenshotBase64 = base64
                            bubbleManager.cancelPendingDismiss()
                            bubbleManager.hideSpeechBubble()
                            voiceController.toggle()
                        }
                    } else {
                        sendScreenshot(base64, null)
                    }
                } else {
                    bubbleManager.showBrief("Couldn't peek at your screen...")
                }
            }
        }, 150)
    }

    private fun handleBubbleReopen() {
        val lastMessage = conversationManager.lastAssistantMessage
        if (lastMessage != null) {
            bubbleManager.showResponse(lastMessage, settings.bubbleTimeoutSeconds * 1000L)
        } else {
            bubbleManager.showResponse("Ask me anything~", settings.bubbleTimeoutSeconds * 1000L)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Conversation flow
    // ══════════════════════════════════════════════════════════════════════

    private fun sendScreenshot(imageBase64: String, voiceText: String?) {
        audioCoordinator.playBeep(BeepManager.Beep.STEP)
        bubbleManager.showBrief("Thinking...", 30000L)
        conversationManager.sendWithScreenshot(imageBase64, voiceText)
    }

    private fun sendTextReply(text: String) {
        bubbleManager.cancelPendingDismiss()
        bubbleManager.hideSpeechBubble()
        audioCoordinator.playBeep(BeepManager.Beep.STEP)
        bubbleManager.showBrief("Thinking...", 30000L)
        conversationManager.sendText(text)
    }

    // ══════════════════════════════════════════════════════════════════════
    // ConversationManager.Listener
    // ══════════════════════════════════════════════════════════════════════

    override fun onResponseReceived(text: String) {
        voiceController.cancelSafetyTimeout()
        audioCoordinator.playBeep(BeepManager.Beep.STEP)

        val shouldSpeak = settings.ttsEnabled || pendingVoiceReply
        val wasVoice = pendingVoiceReply
        pendingVoiceReply = false

        if (shouldSpeak) {
            bubbleManager.cancelPendingDismiss()
            bubbleManager.hideSpeechBubble()

            audioCoordinator.onSpeechComplete = {
                audioCoordinator.playBeep(BeepManager.Beep.DONE)
                if (wasVoice) {
                    handler.post { voiceController.onVoiceResponseComplete() }
                }
                audioCoordinator.onSpeechComplete = null
            }
            audioCoordinator.speak(text)
        } else {
            bubbleManager.showResponse(text, settings.bubbleTimeoutSeconds * 1000L)
            voiceController.onVoiceResponseComplete()
        }
    }

    override fun onError(message: String) {
        audioCoordinator.playBeep(BeepManager.Beep.ERROR)
        pendingVoiceReply = false
        bubbleManager.showBrief("Hmph! $message")
        voiceController.onVoiceResponseComplete()
    }

    override fun onCancelled() {
        pendingVoiceReply = false
    }

    // ══════════════════════════════════════════════════════════════════════
    // VoiceInputHost implementation
    // ══════════════════════════════════════════════════════════════════════

    override fun showVoiceBubble(text: String) = bubbleManager.showVoice(text)
    override fun updateVoiceBubble(text: String) = bubbleManager.updateVoice(text)
    override fun hideVoiceBubble() = bubbleManager.hideVoice()
    override fun showBriefBubble(message: String, durationMs: Long) = bubbleManager.showBrief(message, durationMs)

    override fun stopTtsAndCancel() {
        audioCoordinator.stopSpeaking()
        conversationManager.cancelPending()
    }

    override fun sendVoiceInput(text: String) {
        DebugLog.log("Overlay", "Voice input: ${text.take(80)}")
        pendingVoiceReply = true

        val screenshot = pendingScreenshotBase64
        if (screenshot != null) {
            pendingScreenshotBase64 = null
            sendScreenshot(screenshot, text)
        } else {
            sendTextReply(text)
        }
    }

    override fun clearPendingScreenshot() {
        pendingScreenshotBase64 = null
    }

    override fun getConversationContextForStt(): String = conversationManager.getContextForStt()

    // ══════════════════════════════════════════════════════════════════════
    // Internal helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun setGhostMode(ghost: Boolean) = spriteAnimator.setGhostMode(ghost)

    private fun dismissAnimated() {
        spriteAnimator.dismissAnimated { stopSelf() }
    }

    // ══════════════════════════════════════════════════════════════════════
    // State persistence
    // ══════════════════════════════════════════════════════════════════════

    private fun restorePosition() {
        val savedX = settings.avatarX
        if (savedX >= 0) {
            params.x = savedX
            settings.avatarPosition?.let {
                spriteAnimator.position = if (it == "left")
                    SpriteAnimator.OverlayPosition.Left
                else
                    SpriteAnimator.OverlayPosition.Right
            }
        }
    }

    private fun saveState() {
        if (::params.isInitialized) {
            settings.avatarX = params.x
            settings.avatarPosition = if (spriteAnimator.position == SpriteAnimator.OverlayPosition.Left) "left" else "right"
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Screen state receiver
    // ══════════════════════════════════════════════════════════════════════

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> stopAnimation()
                Intent.ACTION_SCREEN_ON -> {
                    spriteAnimator.startTime = System.currentTimeMillis()
                    overlayView.alpha = 0f
                    spriteAnimator.update()
                    startAnimation()
                }
                Intent.ACTION_USER_PRESENT -> {
                    overlayView.animate().alpha(1f).setDuration(300).start()
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Bubble host interface
    // ══════════════════════════════════════════════════════════════════════

    private val bubbleHost = object : BubbleManager.Host {
        override fun onTtsStop() = stopTtsAndCancel()
        override fun onSendReply(text: String) = sendTextReply(text)
        override fun onVoiceToggle() = voiceController.toggle()
        override fun onKeyboardShown() {
            handler.post {
                overlayView.alpha = 0f
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching {
                    windowManager.removeView(overlayView)
                    windowManager.addView(overlayView, params)
                }
                overlayView.animate().alpha(0.5f).setDuration(200).start()
                spriteAnimator.setGhostMode(true)
            }
        }
        override fun cancelBubbleTimeout() = bubbleManager.cancelPendingDismiss()
        override val screenHeight get() = this@CompanionOverlayService.screenHeight
    }

    // ══════════════════════════════════════════════════════════════════════
    // Notification
    // ══════════════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            "Companion Overlay",
            android.app.NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Senni alive on your screen"
        }
        getSystemService(android.app.NotificationManager::class.java)
            .createNotificationChannel(channel)
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
