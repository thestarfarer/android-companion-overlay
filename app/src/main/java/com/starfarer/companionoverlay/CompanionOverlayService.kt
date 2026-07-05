package com.starfarer.companionoverlay

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.starfarer.companionoverlay.avatar3d.FilamentAvatarRenderer
import com.starfarer.companionoverlay.event.OverlayCoordinator
import com.starfarer.companionoverlay.event.OverlayEvent
import com.starfarer.companionoverlay.gateway.AvatarRepository
import com.starfarer.companionoverlay.gateway.GatewayClient
import com.starfarer.companionoverlay.repository.CaptureMode
import com.starfarer.companionoverlay.repository.SettingsRepository
import com.starfarer.companionoverlay.voice.SpeakRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.android.ext.android.inject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that displays the companion overlay on screen.
 *
 * A thin presence endpoint: rendering, voice capture, and device
 * capabilities live here; the brain lives server-side in Nexus. All
 * conversation flows through [GatewayClient] (presence protocol v1):
 * - user text / locally transcribed voice → `text`
 * - VAD-cut voice utterances → `audio` (server transcribes → `transcript`)
 * - long-press captures → `image`
 * - taps and keyboard visibility → `event`
 * - inbound `message` → [BubbleManager], `speak` → local TTS,
 *   `animate` → [SpriteAnimator], `status` → thinking indicator,
 *   `cap_request` → screenshot/camera/notify handlers.
 *
 * Local reactive animation (tap walks, escape) never depends on the
 * connection — presence works fully offline; only conversation needs Nexus.
 */
class CompanionOverlayService : Service(), VoiceInputHost, GatewayClient.Listener {

    companion object {
        const val CHANNEL_ID = CompanionApplication.NOTIFICATION_CHANNEL_ID
        // Notification "Stop" action — the only way to dismiss the overlay
        // without opening the app.
        const val ACTION_STOP = "com.starfarer.companionoverlay.action.STOP"
        // internal: the tutorial's gesture router mirrors these 1:1.
        internal const val LONG_PRESS_TIMEOUT_MS = 500L
        // Vertical travel (dp) that commits a swipe — opens/closes the radial menu.
        internal const val SWIPE_MIN_DISTANCE_DP = 56f
    }

    // ══════════════════════════════════════════════════════════════════════
    // Dependencies (injected)
    // ══════════════════════════════════════════════════════════════════════

    private val coordinator: OverlayCoordinator by inject()
    private val audioCoordinator: AudioCoordinator by inject()
    private val screenshotManager: ScreenshotManager by inject()
    private val cameraManager: CameraManager by inject()
    private val settings: SettingsRepository by inject()
    private val beepManager: BeepManager by inject()
    private val gateway: GatewayClient by inject()
    private val avatarRepository: AvatarRepository by inject()

    // Main.immediate so launch bodies run inline up to their first suspension —
    // subscribeToEvents() relies on this: its collect subscription must be live
    // before onCreate publishes "running" (see onCreate ordering comment).
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ══════════════════════════════════════════════════════════════════════
    // Managers (created in service)
    // ══════════════════════════════════════════════════════════════════════

    private lateinit var windowManager: WindowManager
    private lateinit var spriteAnimator: SpriteAnimator
    private lateinit var bubbleManager: BubbleManager
    private lateinit var voiceController: VoiceInputController
    private lateinit var radialMenuManager: RadialMenuManager
    private var filamentRenderer: FilamentAvatarRenderer? = null

    // ══════════════════════════════════════════════════════════════════════
    // View state
    // ══════════════════════════════════════════════════════════════════════

    private lateinit var overlayView: View
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

    /** Last finalized assistant message — reopened by a lower-body long-press. */
    private var lastAssistantMessage: String? = null

    // Edge state for the subtle offline hint: shown once per disconnect episode.
    private var offlineHintShown = false

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var isLongPress = false
    // Raw (screen) coordinates for movement deltas — the window itself moves
    // while the sprite walks, so view-relative deltas would drift mid-gesture.
    private var touchDownRawX = 0f
    private var touchDownRawY = 0f
    // View-relative Y kept separately for the upper/lower-body long-press split.
    private var touchDownViewY = 0f
    private var swipeConsumed = false   // a swipe already opened/closed the menu this gesture

    // Set at the end of initializeManagers(); onDestroy uses it to skip teardown
    // of managers that were never created (permission-abort path used to crash
    // here on uninitialized lateinit fields, then crash-loop via START_STICKY).
    private var managersInitialized = false

    // Lets late async callbacks (capture results, FGS type demotion) detect that
    // the service is gone instead of poking dead UI or re-entering foreground.
    @Volatile private var destroyed = false

    // Tracked so screen-on/unlock fades restore to the ghost alpha (0.5) rather
    // than snapping a ghosted sprite back to fully visible.
    private var ghostActive = false

    // Ids for server-requested `notify` capability notifications.
    private val notifyIds = AtomicInteger(100)

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()

        // Some entry points (assistant trigger, volume triple-press) can start the
        // service without a permission pre-check. Adding the overlay window without
        // "Display over other apps" throws BadTokenException, so bail cleanly here —
        // but still satisfy the foreground-service contract before stopping.
        if (!Settings.canDrawOverlays(this)) {
            DebugLog.log("Overlay", "Overlay permission missing — aborting service start")
            Toast.makeText(this, getString(R.string.svc_toast_overlay_permission_needed), Toast.LENGTH_LONG).show()
            startForeground(1, createNotification(), baseForegroundType())
            stopSelf()
            return
        }

        // Enter the foreground state before any heavy work — sprite decode and
        // (in 3D mode) Filament engine creation can take long enough to trip the
        // "did not call startForeground" ANR window. Base types only: camera is
        // declared in the manifest but deliberately excluded here — applying it
        // unconditionally would demand CAMERA permission at every overlay start.
        // It's promoted in transiently during capture via setCameraForeground().
        startForeground(1, createNotification(), baseForegroundType())

        initializeScreen()
        initializeManagers()
        if (!initializeOverlayView()) {
            // addView rejected (e.g. permission revoked mid-flight). stopSelf has
            // already been called; skip the rest and let onDestroy tear down what
            // exists instead of continuing to initialize a dead service.
            return
        }
        subscribeToEvents()
        startAnimation()

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })

        // Publish "running" only after the event collector is live (the scope is
        // Main.immediate, so subscribeToEvents' collect subscription is already
        // active here). Flipping the flag earlier let a ToggleVoice fired by a
        // fast observer land on a SharedFlow with no subscriber — silently dropped.
        coordinator.onOverlayServiceStarted()

        // Bring the presence link up last — everything it renders into exists now.
        gateway.capabilities = deviceCapabilities()
        gateway.listener = this
        gateway.start()

        DebugLog.log("Overlay", "Service created")
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        // The gateway singleton outlives the service; detach before teardown so
        // late frames can't poke dead UI. Presence endpoint down = socket down.
        gateway.listener = null
        gateway.stop()
        saveState()
        stopAnimation()
        // Kill pending delayed work (screenshot/camera capture posts) — a capture
        // firing after destroy used to be able to start the microphone.
        handler.removeCallbacksAndMessages(null)

        runCatching { unregisterReceiver(screenReceiver) }
            .onFailure { DebugLog.log("Overlay", "Failed to unregister receiver: ${it.message}") }

        // Only tear down what initializeManagers() actually created — the
        // permission-abort path never gets there, and the injected delegates
        // (audioCoordinator) must not be lazily *created* here just to be destroyed.
        if (managersInitialized) {
            voiceController.destroy()
            audioCoordinator.release()
            bubbleManager.destroy()
            radialMenuManager.close()
            spriteAnimator.release()
        }
        filamentRenderer?.destroy()

        if (::overlayView.isInitialized) {
            runCatching { windowManager.removeView(overlayView) }
                .onFailure { DebugLog.log("Overlay", "Failed to remove overlay view: ${it.message}") }
        }

        serviceScope.cancel()
        coordinator.onOverlayServiceStopped()

        DebugLog.log("Overlay", "Service destroyed")
    }

    /**
     * Screen bounds are captured at onCreate; rotation and fold changes move
     * them under us. Re-measure, re-point the animator's walk/escape math, and
     * clamp the window back on-screen.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!managersInitialized || !::overlayView.isInitialized) return

        initializeScreen()
        spriteAnimator.onScreenResized(screenWidth, screenHeight)
        params.x = params.x.coerceIn(0, maxOf(0, screenWidth - params.width))
        params.y = params.y.coerceIn(0, maxOf(0, screenHeight - params.height))
        runCatching { windowManager.updateViewLayout(overlayView, params) }
            .onFailure { DebugLog.log("Overlay", "Rotation relayout failed: ${it.message}") }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Initialization
    // ══════════════════════════════════════════════════════════════════════

    private fun initializeScreen() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density

        val wm = windowManager.maximumWindowMetrics
        val insets = wm.windowInsets
            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
        screenWidth = wm.bounds.width()
        screenHeight = wm.bounds.height() - insets.top - insets.bottom
    }

    private fun initializeManagers() {
        val animConfig = SpriteAnimator.Config(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = density
        )
        spriteAnimator = SpriteAnimator(this, animConfig, settings, avatarRepository).apply {
            loadSprites()
        }

        bubbleManager = BubbleManager(this, OverlayBubbleSurface(windowManager, density), handler, bubbleHost)
        radialMenuManager = RadialMenuManager(this, windowManager, settings)

        audioCoordinator.warmUp()
        voiceController = VoiceInputController(this, this, settings, beepManager, gateway)

        managersInitialized = true
    }

    private fun subscribeToEvents() {
        serviceScope.launch {
            coordinator.events.collect { event ->
                when (event) {
                    is OverlayEvent.ToggleVoice -> voiceController.toggle()
                    is OverlayEvent.DismissOverlay -> dismissAnimated()
                    is OverlayEvent.KeyboardVisibility -> setGhostMode(event.visible)
                    is OverlayEvent.ReloadSprites -> reloadSprites()
                    is OverlayEvent.GatewayConfigChanged -> restartGateway()
                    else -> { /* Handled elsewhere */ }
                }
            }
        }
    }

    /** Live sprite/preset change from MainActivity or avatar sync — reload sheets in place. */
    private fun reloadSprites() {
        // 3D mode doesn't render sprite sheets; nothing to reload.
        if (filamentRenderer != null) return
        DebugLog.log("Overlay", "Sprites/preset changed — reloading sheets")
        spriteAnimator.reloadSprites()
        // The window is sized to the sheet's frame dimensions — track changes.
        if (params.width != spriteAnimator.viewWidth || params.height != spriteAnimator.viewHeight) {
            params.width = spriteAnimator.viewWidth
            params.height = spriteAnimator.viewHeight
            runCatching { windowManager.updateViewLayout(overlayView, params) }
                .onFailure { DebugLog.log("Overlay", "Sprite resize failed: ${it.message}") }
        }
        spriteAnimator.update()
    }

    /** Server URL/token changed in settings — tear the socket down and redial. */
    private fun restartGateway() {
        DebugLog.log("Overlay", "Gateway settings changed — reconnecting")
        gateway.stop()
        offlineHintShown = false
        gateway.start()
    }

    /** @return false when the overlay window could not be added (service is stopping). */
    private fun initializeOverlayView(): Boolean {
        if (settings.is3dMode) {
            return initializeFilamentOverlay()
        }
        return initializeSpriteOverlay()
    }

    private fun initializeFilamentOverlay(): Boolean {
        try {
            val renderer = FilamentAvatarRenderer(this)
            filamentRenderer = renderer
            val textureView = renderer.createTextureView()
            overlayView = textureView

            val heightPx = spriteAnimator.viewHeight
            val widthPx = (heightPx * 0.55).toInt()

            params = WindowManager.LayoutParams(
                widthPx,
                heightPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = screenWidth - widthPx - spriteAnimator.marginRightPx - spriteAnimator.walkDistancePx
                y = screenHeight - heightPx - spriteAnimator.marginBottomPx
            }

            restorePosition()
            setupTouchHandling()
            spriteAnimator.attach(OverlaySpriteSurface(windowManager, overlayView, params))
            windowManager.addView(overlayView, params)
            overlayView.alpha = 0f
            overlayView.animate().alpha(1f).setDuration(300).start()
            renderer.start()
            DebugLog.log("Overlay", "Filament 3D overlay active")
            return true
        } catch (e: Exception) {
            DebugLog.log("Overlay", "Filament FAILED: ${e.message}")
            android.util.Log.e("Overlay", "Filament failed", e)
            settings.overlayMode = "sprite"
            filamentRenderer = null
            return initializeSpriteOverlay()
        }
    }

    private fun initializeSpriteOverlay(): Boolean {
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

        // Bind the overlay surface to the animator
        spriteAnimator.attach(OverlaySpriteSurface(windowManager, overlayView, params))

        // Backstop: the onCreate guard should prevent this, but if the window token
        // is ever rejected (e.g. permission revoked mid-flight) stop instead of crashing.
        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            DebugLog.log("Overlay", "addView failed (${e.message}) — stopping service")
            stopSelf()
            return false
        }

        overlayView.alpha = 0f
        overlayView.animate().alpha(1f).setDuration(300).start()
        return true
    }

    private fun setupTouchHandling() {
        overlayView.isClickable = true
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val swipeThreshold = SWIPE_MIN_DISTANCE_DP * density
        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPress = false
                    swipeConsumed = false
                    touchDownRawX = event.rawX
                    touchDownRawY = event.rawY
                    touchDownViewY = event.y
                    longPressHandler.postDelayed({
                        isLongPress = true
                        handleLongPress()
                    }, LONG_PRESS_TIMEOUT_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Raw deltas: view-relative coords shift when the window itself
                    // moves mid-gesture (walking sprite), faking movement.
                    val dx = event.rawX - touchDownRawX
                    val dy = event.rawY - touchDownRawY
                    // Any real movement cancels the long-press (it's a gesture, not a hold).
                    if (kotlin.math.hypot(dx, dy) > touchSlop) {
                        longPressHandler.removeCallbacksAndMessages(null)
                    }
                    // Commit a vertical-dominant swipe once — up opens the menu, down closes it.
                    if (!swipeConsumed && kotlin.math.abs(dy) > swipeThreshold &&
                        kotlin.math.abs(dy) > kotlin.math.abs(dx)
                    ) {
                        swipeConsumed = true
                        if (dy < 0) radialMenuManager.open() else radialMenuManager.close()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacksAndMessages(null)
                    // Plain tap (no long-press, no swipe) → walk. Fires immediately on UP.
                    if (!isLongPress && !swipeConsumed && event.action == MotionEvent.ACTION_UP) {
                        spriteAnimator.handleTouch()
                        // Cheap presence signal — rate-limited inside the client.
                        gateway.sendEvent("tapped")
                    }
                    true
                }
                else -> true
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Animation loop (vsync-driven via Choreographer with Handler watchdog)
    // ══════════════════════════════════════════════════════════════════════

    private var animating = false
    private var lastFrameTimeMs = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animating) return
            lastFrameTimeMs = SystemClock.elapsedRealtime()
            spriteAnimator.update()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val animWatchdog = object : Runnable {
        override fun run() {
            if (!animating) return
            if (SystemClock.elapsedRealtime() - lastFrameTimeMs > 100) {
                spriteAnimator.update()
                // Re-arm the vsync callback without duplicating it: a repeated
                // stale tick would otherwise stack a second callback, and each
                // one re-posts itself — permanently multiplying update() rate.
                Choreographer.getInstance().removeFrameCallback(frameCallback)
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
            handler.postDelayed(this, 100)
        }
    }

    private fun startAnimation() {
        // Idempotent: a duplicate SCREEN_ON (or ON without OFF) must not register
        // a second frame callback + watchdog on top of the running ones.
        if (animating) return
        spriteAnimator.startTime = SystemClock.elapsedRealtime()
        animating = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
        handler.postDelayed(animWatchdog, 100)
    }

    private fun stopAnimation() {
        animating = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        handler.removeCallbacks(animWatchdog)
        longPressHandler.removeCallbacksAndMessages(null)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Touch interactions
    // ══════════════════════════════════════════════════════════════════════

    private fun handleLongPress() {
        overlayView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        val threshold = overlayView.height * 2f / 3f
        if (touchDownViewY < threshold) {
            handleScreenshotRequest()
        } else {
            handleBubbleReopen()
        }
    }

    private fun handleScreenshotRequest() {
        when (settings.captureMode) {
            CaptureMode.OFF -> {
                handleBubbleReopen()
                return
            }
            CaptureMode.CAMERA -> {
                handleCameraRequest()
                return
            }
            CaptureMode.SCREENSHOT -> { /* fall through to screenshot below */ }
        }

        if (!coordinator.accessibilityRunning.value) {
            bubbleManager.showBrief(getString(R.string.svc_bubble_grant_screenshot_permission), 4000L)
            return
        }

        bubbleManager.showBrief(getString(R.string.svc_bubble_let_me_see), 2000L)

        handler.postDelayed({
            screenshotManager.takeScreenshot { base64 ->
                if (base64 != null) {
                    dispatchCapturedImage(base64)
                } else {
                    handler.post { bubbleManager.showBrief(getString(R.string.svc_bubble_screenshot_failed)) }
                }
            }
        }, 150)
    }

    private fun handleCameraRequest() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            bubbleManager.showBrief(getString(R.string.svc_bubble_grant_camera_permission), 4000L)
            return
        }

        bubbleManager.showBrief(getString(R.string.svc_bubble_let_me_look), 2000L)

        // Promote the foreground service to include the camera type so the OS
        // permits capture while another app is in the foreground, then revert.
        setCameraForeground(true)
        handler.postDelayed({
            cameraManager.capture { base64 ->
                setCameraForeground(false)
                if (base64 != null) {
                    dispatchCapturedImage(base64)
                } else {
                    bubbleManager.showBrief(getString(R.string.svc_bubble_camera_failed))
                }
            }
        }, 150)
    }

    /**
     * Route a freshly captured image (screenshot or camera) toward the gateway.
     * Source-agnostic: both paths produce a base64 JPEG. Safe to call from any
     * thread — the work is posted to the main handler.
     */
    private fun dispatchCapturedImage(base64: String) {
        handler.post {
            // Capture callbacks can outlive the service (the executor isn't ours
            // to cancel) — never start voice input against a destroyed host.
            if (destroyed) return@post
            if (settings.voiceScreenshotEnabled) {
                pendingScreenshotBase64 = base64
                bubbleManager.cancelPendingDismiss()
                bubbleManager.hideSpeechBubble()
                // Blind toggle() here CANCELLED an active listening session —
                // which also cleared the screenshot we just stored. If already
                // listening, the capture simply rides along with that session;
                // toggling from PROCESSING interrupts into a fresh listen.
                if (voiceController.state != VoiceInputController.State.LISTENING) {
                    voiceController.toggle()
                }
            } else {
                sendCapturedImage(base64, null)
            }
        }
    }

    @Volatile private var micFgsActive = false
    @Volatile private var cameraFgsActive = false

    // The overlay runs as a plain specialUse foreground service. The while-in-use
    // types (microphone, camera) are added ONLY while actually recording/capturing.
    // Claiming microphone at every start is what crashed on Android 14+/17 with
    // "Starting FGS with type microphone ... must be in the eligible state" — the
    // service is often started while the app isn't foreground (wake button, assistant).
    // The types stay declared in the manifest so we can promote into them on demand.
    private fun baseForegroundType(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

    private fun applyForegroundType() {
        // A late capture/recording callback must not re-enter the foreground
        // state on a service that is already shutting down.
        if (destroyed) return
        var type = baseForegroundType()
        if (micFgsActive) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (cameraFgsActive) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        try {
            startForeground(1, createNotification(), type)
        } catch (e: Exception) {
            DebugLog.log("Overlay", "FGS type change failed: ${e.message}")
        }
    }

    /** Add or drop the camera foreground-service type around a capture. */
    private fun setCameraForeground(enabled: Boolean) {
        if (cameraFgsActive == enabled) return
        cameraFgsActive = enabled
        applyForegroundType()
    }

    /** Add or drop the microphone FGS type around active voice recording (VoiceInputHost). */
    override fun setMicrophoneActive(active: Boolean) {
        if (micFgsActive == active) return
        micFgsActive = active
        applyForegroundType()
        // Same signal drives audio focus: duck/pause other audio while the mic
        // is genuinely live so the recording isn't polluted by music.
        audioCoordinator.setRecordingActive(active)
    }

    private fun handleBubbleReopen() {
        val lastMessage = lastAssistantMessage
        if (lastMessage != null) {
            bubbleManager.showResponse(lastMessage, settings.bubbleTimeoutSeconds * 1000L)
        } else {
            bubbleManager.showResponse(getString(R.string.svc_bubble_ask_me_anything), settings.bubbleTimeoutSeconds * 1000L)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Conversation flow (outbound → gateway)
    // ══════════════════════════════════════════════════════════════════════

    private fun sendCapturedImage(imageBase64: String, voiceText: String?) {
        if (settings.saveSentImages) {
            val source = if (settings.captureMode == CaptureMode.CAMERA) "cam" else "screen"
            ImageAudit.save(this, imageBase64, source)
        }
        audioCoordinator.playBeep(BeepManager.Beep.STEP)
        val kind = if (settings.captureMode == CaptureMode.CAMERA) "camera" else "screenshot"
        // Images are never queued offline, and oversize payloads are dropped
        // pre-send (the server rejects >8MB base64 with bad_message anyway) —
        // tell the user instead (protocol §1).
        when (gateway.sendImage("image/jpeg", imageBase64, kind, voiceText)) {
            GatewayClient.ImageSendResult.SENT ->
                bubbleManager.showBrief(getString(R.string.svc_bubble_thinking), 30000L)
            GatewayClient.ImageSendResult.TOO_LARGE -> {
                pendingVoiceReply = false
                bubbleManager.showBrief(getString(R.string.svc_bubble_image_too_large), 4000L)
            }
            GatewayClient.ImageSendResult.OFFLINE -> {
                pendingVoiceReply = false
                bubbleManager.showBrief(getString(R.string.svc_bubble_offline_image), 4000L)
            }
        }
    }

    private fun sendTextReply(text: String, source: String = "typed") {
        bubbleManager.cancelPendingDismiss()
        bubbleManager.hideSpeechBubble()
        audioCoordinator.playBeep(BeepManager.Beep.STEP)
        if (gateway.isConnected) {
            bubbleManager.showBrief(getString(R.string.svc_bubble_thinking), 30000L)
        } else {
            // Queued client-side (bounded, drop-oldest) and flushed on reconnect.
            bubbleManager.showBrief(getString(R.string.svc_bubble_offline_queued), 4000L)
        }
        gateway.sendText(text, source)
    }

    // ══════════════════════════════════════════════════════════════════════
    // GatewayClient.Listener (inbound ← Nexus, on the main thread)
    // ══════════════════════════════════════════════════════════════════════

    override fun onConnected(welcome: GatewayClient.Welcome) {
        DebugLog.log("Overlay", "Gateway connected: session=${welcome.sessionId} resumed=${welcome.resumed}")
        offlineHintShown = false
        // A fresh welcome re-arms the server voice path after a fallback episode.
        voiceController.onGatewayConnected()
        sendDeviceStatus()

        // Avatar sync: welcome carries the mandated sprite version — on
        // mismatch, refresh the cache off the main thread and reload in place.
        val version = welcome.avatarVersion
        serviceScope.launch(Dispatchers.IO) {
            val updated = avatarRepository.syncIfNeeded(version)
            if (updated) withContext(Dispatchers.Main) {
                if (!destroyed) reloadSprites()
            }
        }
    }

    override fun onDisconnected() {
        // Subtle, once per episode: the sprite keeps animating regardless —
        // presence never depends on connectivity, only conversation does.
        if (!offlineHintShown && !destroyed) {
            offlineHintShown = true
            bubbleManager.showBrief(getString(R.string.svc_bubble_offline), 3000L)
        }
    }

    override fun onMessage(msgId: String?, text: String) {
        if (destroyed || text.isBlank()) return
        voiceController.cancelSafetyTimeout()
        audioCoordinator.playBeep(BeepManager.Beep.STEP)
        lastAssistantMessage = text

        if (settings.autoCopy) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Senni", text).apply {
                description.extras = android.os.PersistableBundle().apply {
                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            clipboard.setPrimaryClip(clip)
        }

        // Always display — the response dialog doubles as the typed-reply
        // surface. `speak` (if any) arrives separately and overlays voice.
        bubbleManager.showResponse(text, settings.bubbleTimeoutSeconds * 1000L)
        voiceController.onVoiceResponseComplete()
    }

    override fun onSpeak(text: String, audioFormat: String?, audioBase64: String?) {
        if (destroyed) return
        // Local TTS when enabled, or when the user spoke (voice in → voice
        // out, matching the old companion behavior). Server-synthesized audio
        // rides the same gate — it replaces the local engine, not the policy.
        val shouldSpeak = settings.ttsEnabled || pendingVoiceReply
        pendingVoiceReply = false
        if (!shouldSpeak) return

        // speak.audio present → play the server's synthesis (text is display-
        // only); absent/corrupt → the local TTS path, unchanged.
        val route = SpeakRouter.route(text, audioFormat, audioBase64)
        if (route == SpeakRouter.Route.None) return

        audioCoordinator.onSpeechComplete = {
            audioCoordinator.playBeep(BeepManager.Beep.DONE)
            audioCoordinator.onSpeechComplete = null
        }
        when (route) {
            is SpeakRouter.Route.ServerAudio -> audioCoordinator.playSpeakAudio(route.audio, route.mimeType)
            is SpeakRouter.Route.LocalTts -> audioCoordinator.speak(route.text)
            SpeakRouter.Route.None -> { /* unreachable — handled above */ }
        }
    }

    override fun onTranscript(re: String?, text: String) {
        if (destroyed) return
        // The voice pipeline owns correlation with the in-flight audio
        // message; it renders what was heard (or the "didn't catch that" hint).
        voiceController.onServerTranscript(re, text)
    }

    override fun onCompanionStatus(state: String, detail: String?) {
        if (destroyed) return
        when (state) {
            "thinking" -> bubbleManager.showBrief(getString(R.string.svc_bubble_thinking), 30000L)
            "tool_running" -> bubbleManager.showBrief(
                getString(R.string.svc_bubble_tool_use, detail ?: "…"), 30000L
            )
            // idle/listening/speaking need no indicator; the brief toast
            // auto-dismisses and `message` replaces it with the response.
        }
    }

    override fun onAnimate(state: String, params: JsonObject?) {
        if (destroyed) return
        // Advisory mapping onto what the 2D sprite can do; states without a
        // local equivalent (talk/think) are ignored. Local reactive animation
        // (tap walks, escape) stays untouched underneath. `alert` is a brief
        // in-place reaction, NOT escape — it's broadcast with every async
        // job result, and bolting off-screen each time looked like a bug.
        when (state) {
            "walk" -> spriteAnimator.walk()
            "escape" -> spriteAnimator.escape()
            "alert" -> spriteAnimator.alert()
        }
    }

    override fun onSession(event: String, data: JsonObject?) {
        DebugLog.log("Overlay", "Session event: $event ${data ?: ""}")
    }

    override fun onServerError(code: String, message: String?, re: String?) {
        if (destroyed) return
        // Protocol version rejected — terminal: the gateway stays offline
        // until settings change or app restart (no reconnect loop). One clear
        // hint; also suppress the generic "lost Nexus" hint the close would
        // otherwise trigger right after.
        if (code == "version") {
            offlineHintShown = true
            audioCoordinator.playBeep(BeepManager.Beep.ERROR)
            pendingVoiceReply = false
            bubbleManager.showBrief(getString(R.string.svc_bubble_version_mismatch), 8000L)
            voiceController.onVoiceResponseComplete()
            return
        }
        // unsupported/internal on an in-flight audio message → the voice
        // pipeline engages local-STT fallback and shows its own subtle hint;
        // skip the generic error bubble for those.
        if (voiceController.onGatewayError(code, re)) {
            pendingVoiceReply = false
            return
        }
        DebugLog.log("Overlay", "Gateway error $code: $message")
        audioCoordinator.playBeep(BeepManager.Beep.ERROR)
        pendingVoiceReply = false
        bubbleManager.showBrief(getString(R.string.svc_bubble_error, message ?: code))
        voiceController.onVoiceResponseComplete()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Capability requests (server-side tool loop → this device)
    // ══════════════════════════════════════════════════════════════════════

    override fun onCapRequest(requestId: String, capability: String, params: JsonObject, timeoutMs: Long) {
        if (destroyed) return
        DebugLog.log("Overlay", "cap_request $capability ($requestId)")
        when (capability) {
            "screenshot" -> handleCapScreenshot(requestId)
            "camera" -> handleCapCamera(requestId, params)
            "notify" -> handleCapNotify(requestId, params)
            else -> capFail(requestId, "unsupported")
        }
    }

    private fun handleCapScreenshot(requestId: String) {
        if (!coordinator.accessibilityRunning.value) {
            capFail(requestId, "accessibility_unavailable")
            return
        }
        screenshotManager.takeScreenshot { base64 ->
            if (base64 != null) capImage(requestId, base64)
            else capFail(requestId, "capture_failed")
        }
    }

    private fun handleCapCamera(requestId: String, params: JsonObject) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            capFail(requestId, "permission_denied")
            return
        }
        // Default matches the server tool doc: no `facing` param means front.
        val facing = (params["facing"] as? JsonPrimitive)?.content ?: "front"
        handler.post {
            if (destroyed) return@post
            setCameraForeground(true)
            cameraManager.capture(facing) { base64 ->
                setCameraForeground(false)
                if (base64 != null) capImage(requestId, base64)
                else capFail(requestId, "capture_failed")
            }
        }
    }

    private fun handleCapNotify(requestId: String, params: JsonObject) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            capFail(requestId, "permission_denied")
            return
        }
        val title = (params["title"] as? JsonPrimitive)?.content ?: "Senni"
        val body = (params["body"] as? JsonPrimitive)?.content ?: ""
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(R.drawable.ic_notification)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(this).notify(notifyIds.incrementAndGet(), notification)
            gateway.sendCapResult(requestId, buildJsonObject { put("ok", true) })
        } catch (e: Exception) {
            capFail(requestId, "notify_failed")
        }
    }

    private fun capImage(requestId: String, base64: String) {
        gateway.sendCapResult(requestId, buildJsonObject {
            put("ok", true)
            put("format", "image/jpeg")
            put("data", base64)
        })
    }

    private fun capFail(requestId: String, error: String) {
        DebugLog.log("Overlay", "cap_result failure: $error")
        gateway.sendCapResult(requestId, buildJsonObject {
            put("ok", false)
            put("error", error)
        })
    }

    /** The capability manifest this device declares in `hello`. */
    private fun deviceCapabilities(): List<JsonObject> = listOf(
        buildJsonObject { put("name", "screenshot") },
        buildJsonObject {
            put("name", "camera")
            put("facing", kotlinx.serialization.json.buildJsonArray {
                add(JsonPrimitive("front")); add(JsonPrimitive("back"))
            })
        },
        buildJsonObject { put("name", "notify") },
        buildJsonObject { put("name", "mic") },
        buildJsonObject { put("name", "stt") },
        buildJsonObject { put("name", "tts") },
    )

    /** Battery/network snapshot, sent after each welcome. */
    private fun sendDeviceStatus() {
        val battery = try {
            (getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }
        } catch (_: Exception) { null }

        val network = try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                else -> "other"
            }
        } catch (_: Exception) { null }

        gateway.sendStatus(battery = battery, network = network, muted = !settings.ttsEnabled)
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
    }

    override fun sendVoiceInput(text: String) {
        DebugLog.log("Overlay", "Voice input: ${text.take(80)}")
        pendingVoiceReply = true

        val screenshot = pendingScreenshotBase64
        if (screenshot != null) {
            pendingScreenshotBase64 = null
            sendCapturedImage(screenshot, text)
        } else {
            sendTextReply(text, source = "voice")
        }
    }

    /** A voice utterance went up as `audio` — the transcript and reply follow. */
    override fun onVoiceAudioSent() {
        pendingVoiceReply = true
        audioCoordinator.playBeep(BeepManager.Beep.STEP)
        bubbleManager.showBrief(getString(R.string.svc_bubble_thinking), 30000L)
    }

    override fun clearPendingScreenshot() {
        pendingScreenshotBase64 = null
    }

    override fun hasPendingScreenshot(): Boolean = pendingScreenshotBase64 != null

    // ══════════════════════════════════════════════════════════════════════
    // Internal helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun setGhostMode(ghost: Boolean) {
        if (ghostActive != ghost) {
            gateway.sendEvent("keyboard_visible", buildJsonObject { put("visible", ghost) })
        }
        ghostActive = ghost
        spriteAnimator.setGhostMode(ghost)
    }

    private fun dismissAnimated() {
        spriteAnimator.dismissAnimated { stopSelf() }
    }

    // ══════════════════════════════════════════════════════════════════════
    // State persistence
    // ══════════════════════════════════════════════════════════════════════

    private fun restorePosition() {
        val savedX = settings.avatarX
        if (savedX >= 0) {
            // saveState() can run mid-escape, persisting an x beyond the right
            // edge (left-side escape saves a negative x, already rejected by the
            // guard above). Unclamped, the window spawns fully off-screen —
            // FLAG_LAYOUT_NO_LIMITS means the system won't pull it back.
            params.x = savedX.coerceIn(0, maxOf(0, screenWidth - params.width))
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
                    spriteAnimator.startTime = SystemClock.elapsedRealtime()
                    spriteAnimator.update()
                    startAnimation()
                    val keyguard = getSystemService(KeyguardManager::class.java)
                    if (keyguard?.isKeyguardLocked == true) {
                        // Hide above the lock screen; USER_PRESENT fades back in.
                        overlayView.alpha = 0f
                    } else {
                        // No keyguard — USER_PRESENT may never fire on this device,
                        // so restore directly or the overlay stays invisible.
                        overlayView.animate().alpha(restingAlpha()).setDuration(300).start()
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    overlayView.animate().alpha(restingAlpha()).setDuration(300).start()
                }
            }
        }
    }

    /** The alpha the sprite should rest at — ghost mode dims it to 0.5. */
    private fun restingAlpha(): Float = if (ghostActive) 0.5f else 1f

    // ══════════════════════════════════════════════════════════════════════
    // Bubble host interface
    // ══════════════════════════════════════════════════════════════════════

    private val bubbleHost = object : BubbleManager.Host {
        override fun onTtsStop() = stopTtsAndCancel()
        override fun onSendReply(text: String) = sendTextReply(text)
        override fun onVoiceToggle() = voiceController.toggle()

        // Both delegate to the one ghost path (flags + alpha via the sprite
        // surface). The old hand-rolled removeView/addView swap here could lose
        // the sprite entirely if the re-add failed, and re-ordered its window
        // above the bubble dialog.
        override fun onKeyboardShown() {
            handler.post { setGhostMode(true) }
        }

        // The exit that works without the accessibility service — before this,
        // typing a reply left the sprite ghosted forever unless that (optional)
        // service reported the keyboard closing.
        override fun onKeyboardHidden() {
            handler.post { setGhostMode(false) }
        }

        override val screenHeight get() = this@CompanionOverlayService.screenHeight
    }

    // ══════════════════════════════════════════════════════════════════════
    // Notification
    // ══════════════════════════════════════════════════════════════════════

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CompanionOverlayService::class.java)
            .setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.svc_notification_title))
            .setContentText(getString(R.string.svc_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            // Without IMMEDIATE the system may defer showing an FGS notification
            // for ~10s, which hides the abort-path notification entirely.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.svc_notification_action_stop), stopPendingIntent)
            .build()
    }
}
