package com.starfarer.companionoverlay

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.starfarer.companionoverlay.databinding.ActivityTutorialBinding
import com.starfarer.companionoverlay.repository.CaptureMode
import com.starfarer.companionoverlay.repository.SettingsRepository
import org.koin.android.ext.android.inject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Self-contained interactive tutorial. Hosts the **real** [SpriteAnimator], [BubbleManager],
 * [RadialMenuView], [BeepManager], and on-device [TtsManager] inside a normal Activity sandbox —
 * no overlay window, no service, no permissions, no network. Inputs/outputs are scripted mocks.
 *
 * Steps are data-driven [Step] objects: each owns its copy, its gesture handlers, and its
 * enter/exit/gating behavior. Gesture steps auto-advance when performed; demo steps auto-play on
 * enter and unlock Next when finished. Radial-menu toggles write to real prefs, so the four
 * affected settings are snapshotted on entry and restored on exit — nothing the user flips persists.
 */
class TutorialActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTutorialBinding
    private val settings: SettingsRepository by inject()

    private val handler = Handler(Looper.getMainLooper())
    private val density get() = resources.displayMetrics.density

    // Hosted real components.
    private lateinit var spriteAnimator: SpriteAnimator
    private lateinit var bubbleManager: BubbleManager
    private lateinit var beepManager: BeepManager
    private lateinit var ttsManager: TtsManager
    private lateinit var spriteView: ImageView
    private var radialView: RadialMenuView? = null
    private var badgeView: TextView? = null

    /** Last thing she "said" — recalled by a lower-third long-press (mirrors lastAssistantMessage). */
    private var lastResponse: String? = null

    // Gesture router state (mirrors CompanionOverlayService.setupTouchHandling).
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var isLongPress = false
    private var swipeConsumed = false

    // Settings snapshot (restored on finish so tutorial toggles don't leak).
    private var snapCapture: CaptureMode = CaptureMode.SCREENSHOT
    private var snapVolume = false
    private var snapStt = false
    private var snapTts = false

    // ── Step machine ──
    private lateinit var steps: List<Step>
    private var stepIndex = 0
    private var stepComplete = false
    private val current get() = steps[stepIndex]

    /** Token for cancellable auto-play timelines — cancelled wholesale on every step change. */
    private val stepToken = Any()

    /**
     * One tutorial step. Defaults make a plain "read and tap Next" step; override hooks for
     * gestures or auto-play. Next is always clickable; [autoAdvance] additionally jumps to the
     * next step the moment the step completes (used by the gesture steps).
     */
    private inner class Step(
        val title: String,
        val body: String,
        val onEnter: () -> Unit = {},
        val onExit: () -> Unit = {},
        val autoAdvance: Boolean = false,
        val onTap: () -> Unit = {},
        val onLongPress: () -> Unit = {},
        val onSwipeUp: () -> Unit = {},
        val onSwipeDown: () -> Unit = {},
        val onEscape: () -> Unit = {},
    )

    // ── Frame loop (mirrors the service's ~vsync ticker) ──
    private val frameRunnable = object : Runnable {
        override fun run() {
            spriteAnimator.update()
            handler.postDelayed(this, FRAME_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTutorialBinding.inflate(layoutInflater)
        setContentView(binding.root)

        snapshotSettings()
        setupSprite()
        setupBubbles()
        setupGestureRouter()
        steps = buildSteps()

        binding.skipButton.setOnClickListener { finish() }
        binding.prevButton.setOnClickListener { goBack() }
        binding.nextButton.setOnClickListener { advance() }

        goToStep(0)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Setup
    // ══════════════════════════════════════════════════════════════════════

    private fun snapshotSettings() {
        snapCapture = settings.captureMode
        snapVolume = settings.volumeToggleEnabled
        snapStt = settings.geminiSttEnabled
        snapTts = settings.geminiTtsEnabled
    }

    private fun restoreSettings() {
        settings.captureMode = snapCapture
        settings.volumeToggleEnabled = snapVolume
        settings.geminiSttEnabled = snapStt
        settings.geminiTtsEnabled = snapTts
    }

    private fun setupSprite() {
        val dm = resources.displayMetrics
        val config = SpriteAnimator.Config(dm.widthPixels, dm.heightPixels, dm.density)
        spriteAnimator = SpriteAnimator(this, config, settings).apply { loadSprites() }

        val w = spriteAnimator.viewWidth
        val h = spriteAnimator.viewHeight
        val initialX = dm.widthPixels - w - spriteAnimator.marginRightPx

        spriteView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(w, h)
            x = initialX.toFloat()
        }
        binding.sandbox.addView(spriteView)

        // Bottom-anchored once laid out, with extra margin above the Next button.
        val extraMarginPx = (48 * density).toInt()
        binding.sandbox.post {
            spriteView.y = (binding.sandbox.height - h - spriteAnimator.marginBottomPx - extraMarginPx).toFloat()
        }

        spriteAnimator.attach(ViewGroupSpriteSurface(spriteView, w, h, initialX))
        spriteAnimator.onEscape = { current.onEscape() }
    }

    private fun setupBubbles() {
        beepManager = BeepManager(this)
        ttsManager = TtsManager(this, settings)
        val surface = ViewGroupBubbleSurface(binding.sandbox, density)
        bubbleManager = BubbleManager(this, surface, handler, object : BubbleManager.Host {
            override fun onTtsStop() {}
            override fun onSendReply(text: String) {
                // Folds the reply feature into the capture step: a canned follow-up.
                bubbleManager.showResponse(CANNED_FOLLOWUP, RESPONSE_TIMEOUT)
                beepManager.play(BeepManager.Beep.DONE)
            }
            override fun onVoiceToggle() {}
            override fun onKeyboardShown() {}
            override fun onKeyboardHidden() {}
            override val screenHeight: Int get() = binding.sandbox.height
        })
    }

    // ══════════════════════════════════════════════════════════════════════
    // Gesture router — same thresholds as CompanionOverlayService.setupTouchHandling
    // ══════════════════════════════════════════════════════════════════════

    private fun setupGestureRouter() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val swipeThreshold = SWIPE_MIN_DISTANCE_DP * density
        spriteView.isClickable = true
        spriteView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPress = false
                    swipeConsumed = false
                    touchDownX = event.x
                    touchDownY = event.y
                    handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    if (hypot(dx, dy) > touchSlop) handler.removeCallbacks(longPressRunnable)
                    if (!swipeConsumed && abs(dy) > swipeThreshold && abs(dy) > abs(dx)) {
                        swipeConsumed = true
                        if (dy < 0) current.onSwipeUp() else current.onSwipeDown()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!isLongPress && !swipeConsumed && event.action == MotionEvent.ACTION_UP) {
                        current.onTap()
                    }
                    true
                }
                else -> true
            }
        }
    }

    private val longPressRunnable = Runnable {
        isLongPress = true
        current.onLongPress()
    }

    /** Post a step-scoped delayed action (cancelled when the step changes). */
    private fun schedule(delayMs: Long, action: () -> Unit) {
        handler.postDelayed(Runnable { action() }, stepToken, delayMs)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Mocked pipelines (reproduce real beat sequences with canned text)
    // ══════════════════════════════════════════════════════════════════════

    /** Top ⅔ long-press: capture the screen, listen so you can talk about it, then answer aloud. */
    private fun runShowAndTell() {
        bubbleManager.showBrief("Let me see~", 2000L)
        // Screenshot-with-voice: she listens so you can talk about what you grabbed.
        schedule(700L) {
            bubbleManager.showVoice("🎙 Listening...")
            beepManager.play(BeepManager.Beep.READY)
        }
        schedule(1900L) {
            bubbleManager.hideVoice()
            beepManager.play(BeepManager.Beep.STEP)
            bubbleManager.showBrief("🧠 Thinking…", 30000L)
        }
        // Reply is spoken, not bubbled (onResponseReceived → hideSpeechBubble + speak).
        schedule(3100L) {
            beepManager.play(BeepManager.Beep.STEP)
            bubbleManager.hideSpeechBubble(animate = false)
            ttsManager.speak(CANNED_RESPONSE)
            beepManager.play(BeepManager.Beep.DONE)
            lastResponse = CANNED_RESPONSE
            complete()
        }
    }

    /** Bottom ⅓ long-press: recall her last message as a bubble (handleBubbleReopen). */
    private fun runRecall() {
        bubbleManager.showResponse(lastResponse ?: "This is where my last reply would appear!", RESPONSE_TIMEOUT)
        complete()
    }

    /** Voice round-trip: listening → transcribing → thinking → reply, then she speaks it aloud. */
    private fun runVoiceDemo() {
        bubbleManager.showVoice("🎙 Listening...")
        beepManager.play(BeepManager.Beep.READY)
        schedule(800L) {
            bubbleManager.updateVoice("✒️ Transcribing...")
            beepManager.play(BeepManager.Beep.STEP)
        }
        schedule(2000L) {
            bubbleManager.hideVoice()
            bubbleManager.showBrief("🧠 Thinking…", 30000L)
        }
        // Reply is spoken aloud with NO bubble (onResponseReceived → hideSpeechBubble + speak).
        schedule(3200L) {
            beepManager.play(BeepManager.Beep.STEP)
            bubbleManager.hideSpeechBubble(animate = false)
            ttsManager.speak(CANNED_VOICE_REPLY)   // real, on-device, permission-free
            beepManager.play(BeepManager.Beep.DONE)
            complete()
        }
    }

    /** Looping volume ×2 hide/show demo until user advances. */
    private fun runVisibilityDemo() {
        fun loop() {
            if (stepComplete) return
            // Show ×2, then fade her out
            showBadgeCentered("×2")
            schedule(800L) {
                spriteView.animate().alpha(0f).setDuration(300L).start()
            }
            // Wait, then show ×2 again, then fade her back in
            schedule(2200L) {
                if (stepComplete) return@schedule
                showBadgeCentered("×2")
                schedule(800L) {
                    spriteView.animate().alpha(1f).setDuration(300L).start()
                }
                schedule(2200L) { loop() }
            }
        }
        loop()
    }

    /** MCP tool-call indicator (verbatim 🔧 format), then the reply spoken aloud (no bubble). */
    private fun runToolsDemo() {
        bubbleManager.showBrief("🧠 Thinking…", 30000L)
        schedule(1500L) { bubbleManager.showBrief("🔧 get_weather", 30000L) }
        schedule(3000L) {
            beepManager.play(BeepManager.Beep.STEP)
            bubbleManager.hideSpeechBubble(animate = false)
            ttsManager.speak(CANNED_SEARCH_REPLY)
            beepManager.play(BeepManager.Beep.DONE)
            complete()
        }
    }

    /** Looping ghost-mode fade until the user advances. */
    private fun runGhostDemo() {
        fun loop() {
            if (stepComplete) return
            spriteAnimator.setGhostMode(true)
            schedule(2400L) {
                spriteAnimator.setGhostMode(false)
                schedule(1200L) { loop() }
            }
        }
        loop()
    }

    private fun fadeSpriteOutIn() {
        spriteView.animate().alpha(0f).setDuration(300L).withEndAction {
            spriteView.animate().alpha(1f).setStartDelay(300L).setDuration(300L).start()
        }.start()
    }

    /** Restart a demo step's auto-play from a tap, clearing anything mid-flight first. */
    private fun replay(run: () -> Unit) {
        resetSandbox()
        run()
    }

    // ── Press-count badge (controls step) ──

    private fun showBadge(text: String) {
        val tv = badgeView ?: TextView(this).apply {
            textSize = 64f
            setTextColor(getColor(R.color.gold))
            gravity = Gravity.CENTER
            binding.sandbox.addView(this, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
            badgeView = this
        }
        tv.text = text
        tv.animate().cancel()
        tv.alpha = 0f
        tv.scaleX = 0.6f
        tv.scaleY = 0.6f
        tv.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0L).setDuration(250L)
            .withEndAction {
                tv.animate().alpha(0f).setStartDelay(750L).setDuration(400L).start()
            }.start()
    }

    /** Show badge centered in the bottom half of the screen (between narration and buttons). */
    private fun showBadgeCentered(text: String) {
        val tv = badgeView ?: TextView(this).apply {
            textSize = 64f
            setTextColor(getColor(R.color.gold))
            gravity = Gravity.CENTER
            // Position in bottom half, vertically centered between screen center and bottom
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            )
            lp.bottomMargin = (binding.sandbox.height * 0.25f).toInt()
            binding.sandbox.addView(this, lp)
            badgeView = this
        }
        tv.text = text
        tv.animate().cancel()
        tv.alpha = 0f
        tv.scaleX = 0.6f
        tv.scaleY = 0.6f
        tv.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200L)
            .withEndAction {
                tv.animate().alpha(0f).setStartDelay(600L).setDuration(300L).start()
            }.start()
    }

    private fun removeBadge() {
        badgeView?.let {
            it.animate().cancel()
            (it.parent as? FrameLayout)?.removeView(it)
        }
        badgeView = null
    }

    // ══════════════════════════════════════════════════════════════════════
    // Radial menu (hosted like RadialMenuManager.open, minus the window)
    // ══════════════════════════════════════════════════════════════════════

    private fun openRadial() {
        if (radialView != null) return
        val v = RadialMenuView(this, settings, onRequestClose = { closeRadial() })
        val w = ((RADIAL_RADIUS_DP + RADIAL_PAD_DP) * density).toInt()
        val h = ((2 * RADIAL_RADIUS_DP + 2 * RADIAL_PAD_DP) * density).toInt()
        val lp = FrameLayout.LayoutParams(w, h, Gravity.CENTER_VERTICAL or Gravity.END)
        v.alpha = 0f
        binding.sandbox.addView(v, lp)
        v.animate().alpha(1f).setDuration(150L).start()
        radialView = v
        complete()
    }

    private fun closeRadial() {
        val v = radialView ?: return
        radialView = null
        v.animate().alpha(0f).setDuration(120L).withEndAction {
            (v.parent as? FrameLayout)?.removeView(v)
        }.start()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Step definitions
    // ══════════════════════════════════════════════════════════════════════

    private fun buildSteps(): List<Step> = listOf(
        // ── Intro ──
        Step(
            title = "Meet your companion",
            body = "This is your overlay companion — she floats over other apps so you can chat any time. The next few screens teach her gestures; you can try each one live.",
        ),
        // ── Core gestures ──
        Step(
            title = "Tap to walk",
            body = "Tap her — she'll stroll across the screen. Give it a go.",
            autoAdvance = true,
            onTap = { spriteAnimator.handleTouch(); complete() },
        ),
        Step(
            title = "Pester her to escape",
            body = "Keep tapping and she gets startled, bolting off one edge and back in from the other. Pester her until she escapes.",
            autoAdvance = true,
            onTap = { spriteAnimator.handleTouch() },
            onEscape = { complete() },
        ),
        Step(
            title = "Long-press to talk",
            body = "Long-press her upper body to start a conversation — just speak and she'll answer aloud. If capture is on (set it in the quick menu), she also grabs your screen or snaps a photo so you can ask about what you see.",
            onLongPress = {
                if (touchDownY < spriteView.height * 2f / 3f) runShowAndTell()
            },
        ),
        Step(
            title = "Long-press: recall",
            body = "Missed what she said? Long-press her lower body to pop open her last reply. You can dismiss it, or type a follow-up right there.",
            onLongPress = {
                if (touchDownY >= spriteView.height * 2f / 3f) runRecall()
            },
        ),
        Step(
            title = "Swipe up: quick menu",
            body = "Swipe up to open the quick-settings dial. You can toggle capture mode, the volume-button shortcut, and Gemini vs on-device voice. Swipe down (or tap away) to close.",
            onSwipeUp = { openRadial() },
            onSwipeDown = { closeRadial() },
        ),
        // ── Hardware shortcuts ──
        Step(
            title = "Hide & show",
            body = "Double-press Volume Down to hide her; double-press again to bring her back.",
            onEnter = { runVisibilityDemo() },
        ),
        Step(
            title = "Hands-free listening",
            body = "Triple-press Volume Down or press an assistant button to start listening — no screen tap needed. She transcribes, thinks, and answers aloud. Tap her to replay.",
            onEnter = { runVoiceDemo() },
            onTap = { replay { runVoiceDemo() } },
        ),
        // ── Advanced ──
        Step(
            title = "Web search & tools",
            body = "Ask about current events and she'll search the web. If you've connected tool servers (MCP), she can call those too — watch for the 🔧 badge. Tap her to replay.",
            onEnter = { runToolsDemo() },
            onTap = { replay { runToolsDemo() } },
        ),
        Step(
            title = "Ghost mode",
            body = "When a keyboard appears she turns semi-transparent and lets taps pass through, so she never blocks what you're typing.",
            onEnter = { runGhostDemo() },
        ),
        // ── Outro ──
        Step(
            title = "You're all set",
            body = "That's the tour. Tap to walk, long-press to talk or recall, swipe up for quick settings. Have fun!",
        ),
    )

    // ══════════════════════════════════════════════════════════════════════
    // Step driver
    // ══════════════════════════════════════════════════════════════════════

    private fun complete() {
        if (stepComplete) return
        stepComplete = true
        if (current.autoAdvance) advance()
    }

    private fun advance() {
        if (stepIndex >= steps.lastIndex) { finish(); return }
        goToStep(stepIndex + 1)
    }

    private fun goBack() {
        if (stepIndex > 0) goToStep(stepIndex - 1)
    }

    private fun goToStep(i: Int) {
        if (i != stepIndex) steps[stepIndex].onExit()
        stepIndex = i
        stepComplete = false
        resetSandbox()

        val s = steps[i]
        binding.stepProgress.text = "${i + 1} / ${steps.size}"
        binding.stepTitle.text = s.title
        binding.stepBody.text = s.body
        binding.nextButton.text = if (i == steps.lastIndex) "Finish" else "Next"
        binding.nextButton.isEnabled = true
        binding.prevButton.isEnabled = i > 0

        s.onEnter()
    }

    /** Clear all transient sandbox state between (or on replay within) steps. */
    private fun resetSandbox() {
        handler.removeCallbacksAndMessages(stepToken)
        bubbleManager.hideSpeechBubble(animate = false)
        bubbleManager.hideVoice()
        closeRadial()
        ttsManager.stop()
        spriteAnimator.setGhostMode(false)
        spriteView.animate().cancel()
        spriteView.alpha = 1f
        removeBadge()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onResume() {
        super.onResume()
        spriteAnimator.startTime = SystemClock.elapsedRealtime()
        handler.post(frameRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(frameRunnable)
    }

    override fun finish() {
        restoreSettings()
        super.finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        spriteAnimator.onEscape = null
        spriteAnimator.release()
        beepManager.release()
        ttsManager.release()
    }

    private companion object {
        const val FRAME_MS = 16L
        const val LONG_PRESS_TIMEOUT_MS = 500L     // matches CompanionOverlayService
        const val SWIPE_MIN_DISTANCE_DP = 56f      // matches CompanionOverlayService
        const val RADIAL_RADIUS_DP = 96f           // matches RadialMenuManager
        const val RADIAL_PAD_DP = 9.6f
        const val RESPONSE_TIMEOUT = 60000L

        val CANNED_RESPONSE =
            "Ooh, a code editor~ Looks like you're deep in a Kotlin file. " +
            "Want me to explain what's on screen, or just keep you company?"
        val CANNED_FOLLOWUP =
            "Got it! That's exactly how a real reply works — I'd read it and answer. " +
            "Try the next gesture when you're ready. ✨"
        val CANNED_VOICE_REPLY =
            "You said that out loud and I heard you~ This very reply is my on-device voice — " +
            "no internet required. Handy when you'd rather talk than type."
        val CANNED_SEARCH_REPLY =
            "It's sunny and 72 degrees~ That came from a weather tool. In the real app " +
            "I can call tools and search the web for you."
    }
}
