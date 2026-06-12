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
import com.starfarer.companionoverlay.event.OverlayCoordinator
import com.starfarer.companionoverlay.repository.TutorialSettings
import org.koin.android.ext.android.inject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Self-contained interactive tutorial. Hosts the **real** [SpriteAnimator], [BubbleManager],
 * [RadialMenuView], [BeepManager], and on-device [TtsManager] inside a normal Activity sandbox —
 * no overlay window, no service, no permissions, no network. Inputs/outputs are scripted mocks.
 *
 * Steps are data-driven [Step] objects: each owns its copy, its gesture handlers, and its
 * enter/exit behavior. Next is always available; gesture steps additionally auto-advance the
 * moment the gesture lands. Settings are sandboxed through [TutorialSettings] — radial-menu
 * toggles mutate in-memory copies, never real prefs, so nothing the user flips can leak no
 * matter how the Activity dies. A running real overlay is dismissed for the duration (two
 * sprites otherwise) and brought back on finish.
 */
class TutorialActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTutorialBinding
    private val settings: TutorialSettings by inject()
    private val coordinator: OverlayCoordinator by inject()

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

    /** The real overlay was up when the tutorial opened — bring it back on exit. */
    private var overlayWasRunning = false

    /** Set when leaving the foreground; the step is cleanly re-entered on return. */
    private var pausedMidTutorial = false

    /** On-device TTS engine init failed — spoken-reply demos fall back to bubbles. */
    private var ttsFailed = false

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

        // The real overlay would float over the sandbox — two sprites. Dismiss it
        // for the duration; onDestroy brings it back on a true finish.
        overlayWasRunning = savedInstanceState?.getBoolean(KEY_OVERLAY_WAS_RUNNING)
            ?: coordinator.overlayRunning.value
        if (coordinator.overlayRunning.value) coordinator.dismissOverlay()

        setupSprite()
        setupBubbles()
        setupGestureRouter()
        steps = buildSteps()

        binding.skipButton.setOnClickListener { finish() }
        binding.prevButton.setOnClickListener { goBack() }
        binding.nextButton.setOnClickListener { advance() }

        lastResponse = savedInstanceState?.getString(KEY_LAST_RESPONSE)
        goToStep((savedInstanceState?.getInt(KEY_STEP) ?: 0).coerceIn(0, steps.lastIndex))
    }

    // ══════════════════════════════════════════════════════════════════════
    // Setup
    // ══════════════════════════════════════════════════════════════════════

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
        // Engine missing/broken: flag it so speakReply uses bubbles, and surface a reply
        // already queued into the failed engine instead of letting it die silently.
        ttsManager.onInitFailed = {
            ttsFailed = true
            lastResponse?.let { bubbleManager.showResponse(it, RESPONSE_TIMEOUT) }
        }
        val surface = ViewGroupBubbleSurface(binding.sandbox, density)
        bubbleManager = BubbleManager(this, surface, handler, object : BubbleManager.Host {
            override fun onTtsStop() {}
            override fun onSendReply(text: String) {
                // Folds the reply feature into the capture step: a canned follow-up.
                bubbleManager.showResponse(getString(R.string.tutorial_canned_followup), RESPONSE_TIMEOUT)
                beep(BeepManager.Beep.DONE)
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
                    // Step-token-scoped so a pending long-press dies with the step.
                    handler.postDelayed(longPressRunnable, stepToken, LONG_PRESS_TIMEOUT_MS)
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

        // FLAG_WATCH_OUTSIDE_TOUCH stand-in: ACTION_OUTSIDE never reaches a child view,
        // so taps on empty sandbox space close the dial here instead.
        binding.sandbox.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && radialView != null) closeRadial()
            false
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

    /** Beep only if enabled — mirrors VoiceInputController's gating. */
    private fun beep(b: BeepManager.Beep) {
        if (settings.beepsEnabled) beepManager.play(b)
    }

    /** Production picks the label by engine: Gemini transcription "records", on-device "listens". */
    private fun listeningLabel() = getString(
        if (settings.geminiSttEnabled) R.string.status_recording else R.string.status_listening
    )

    /**
     * Deliver a spoken reply with production beats (onResponseReceived): STEP beep, bubble
     * down, a "Generating voice..." brief when the ✨ toggle selected the Gemini voice,
     * speech, then the DONE beep on completion — not at enqueue. Falls back to a bubble
     * when the on-device engine failed so the demo isn't silent.
     */
    private fun speakReply(text: String) {
        lastResponse = text
        beep(BeepManager.Beep.STEP)
        bubbleManager.hideSpeechBubble(animate = false)
        if (ttsFailed) {
            bubbleManager.showResponse(text, RESPONSE_TIMEOUT)
            beep(BeepManager.Beep.DONE)
            return
        }
        if (settings.geminiTtsEnabled) {
            bubbleManager.showBrief(getString(R.string.svc_bubble_generating_voice), 2000L)
        }
        ttsManager.onSpeechDone = {
            ttsManager.onSpeechDone = null
            beep(BeepManager.Beep.DONE)
        }
        ttsManager.speak(text)
    }

    /** Top ⅔ long-press: capture the screen, listen so you can talk about it, then answer aloud. */
    private fun runShowAndTell() {
        bubbleManager.showBrief(getString(R.string.svc_bubble_let_me_see), 2000L)
        // Screenshot-with-voice: she listens so you can talk about what you grabbed.
        schedule(700L) {
            bubbleManager.showVoice(listeningLabel())
            beep(BeepManager.Beep.READY)
        }
        schedule(1900L) {
            bubbleManager.hideVoice()
            beep(BeepManager.Beep.STEP)
            bubbleManager.showBrief(getString(R.string.svc_bubble_thinking), 30000L)
        }
        // Reply is spoken, not bubbled (onResponseReceived → hideSpeechBubble + speak).
        schedule(3100L) {
            speakReply(getString(R.string.tutorial_canned_response))
            complete()
        }
    }

    /** Bottom ⅓ long-press: recall her last message as a bubble (handleBubbleReopen). */
    private fun runRecall() {
        bubbleManager.showResponse(lastResponse ?: getString(R.string.tutorial_recall_placeholder), RESPONSE_TIMEOUT)
        complete()
    }

    /** Voice round-trip: listening → transcribing → thinking → reply, then she speaks it aloud. */
    private fun runVoiceDemo() {
        bubbleManager.showVoice(listeningLabel())
        beep(BeepManager.Beep.READY)
        schedule(800L) {
            bubbleManager.updateVoice(getString(R.string.status_transcribing))
            beep(BeepManager.Beep.STEP)
        }
        schedule(2000L) {
            bubbleManager.hideVoice()
            bubbleManager.showBrief(getString(R.string.svc_bubble_thinking), 30000L)
        }
        // Reply is spoken aloud with NO bubble (onResponseReceived → hideSpeechBubble + speak).
        schedule(3200L) {
            speakReply(getString(R.string.tutorial_canned_voice_reply))   // real, on-device, permission-free
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

    /** MCP tool-call indicator (production's 🔧 format string), then the reply spoken aloud. */
    private fun runToolsDemo() {
        bubbleManager.showBrief(getString(R.string.svc_bubble_thinking), 30000L)
        schedule(1500L) {
            bubbleManager.showBrief(getString(R.string.svc_bubble_tool_use, "get_weather"), 30000L)
        }
        schedule(3000L) {
            speakReply(getString(R.string.tutorial_canned_search_reply))
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

    /** Restart a demo step's auto-play from a tap, clearing anything mid-flight first. */
    private fun replay(run: () -> Unit) {
        resetSandbox()
        run()
    }

    // ── Press-count badge (visibility demo) ──

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
        val w = ((RadialMenuManager.RADIUS_DP + RadialMenuManager.PAD_DP) * density).toInt()
        val h = ((2 * RadialMenuManager.RADIUS_DP + 2 * RadialMenuManager.PAD_DP) * density).toInt()
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
            title = getString(R.string.tutorial_step_intro_title),
            body = getString(R.string.tutorial_step_intro_body),
        ),
        // ── Core gestures ──
        Step(
            title = getString(R.string.tutorial_step_tap_title),
            body = getString(R.string.tutorial_step_tap_body),
            autoAdvance = true,
            onTap = { spriteAnimator.handleTouch(); complete() },
        ),
        Step(
            title = getString(R.string.tutorial_step_escape_title),
            body = getString(R.string.tutorial_step_escape_body),
            autoAdvance = true,
            onTap = { spriteAnimator.handleTouch() },
            onEscape = { complete() },
        ),
        Step(
            title = getString(R.string.tutorial_step_talk_title),
            body = getString(R.string.tutorial_step_talk_body),
            onLongPress = {
                if (touchDownY < spriteView.height * 2f / 3f) runShowAndTell()
            },
        ),
        Step(
            title = getString(R.string.tutorial_step_recall_title),
            body = getString(R.string.tutorial_step_recall_body),
            onLongPress = {
                if (touchDownY >= spriteView.height * 2f / 3f) runRecall()
            },
        ),
        Step(
            title = getString(R.string.tutorial_step_radial_title),
            body = getString(R.string.tutorial_step_radial_body),
            onSwipeUp = { openRadial() },
            onSwipeDown = { closeRadial() },
        ),
        // ── Hardware shortcuts ──
        Step(
            title = getString(R.string.tutorial_step_visibility_title),
            body = getString(R.string.tutorial_step_visibility_body),
            onEnter = { runVisibilityDemo() },
        ),
        Step(
            title = getString(R.string.tutorial_step_voice_title),
            body = getString(R.string.tutorial_step_voice_body),
            onEnter = { runVoiceDemo() },
            onTap = { replay { runVoiceDemo() } },
        ),
        // ── Advanced ──
        Step(
            title = getString(R.string.tutorial_step_tools_title),
            body = getString(R.string.tutorial_step_tools_body),
            onEnter = { runToolsDemo() },
            onTap = { replay { runToolsDemo() } },
        ),
        Step(
            title = getString(R.string.tutorial_step_ghost_title),
            body = getString(R.string.tutorial_step_ghost_body),
            onEnter = { runGhostDemo() },
        ),
        // ── Outro ──
        Step(
            title = getString(R.string.tutorial_step_outro_title),
            body = getString(R.string.tutorial_step_outro_body),
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
        binding.stepProgress.text = getString(R.string.tutorial_step_progress, i + 1, steps.size)
        binding.stepTitle.text = s.title
        binding.stepBody.text = s.body
        binding.nextButton.text = if (i == steps.lastIndex) getString(R.string.tutorial_finish) else getString(R.string.tutorial_next)
        binding.prevButton.isEnabled = i > 0

        s.onEnter()
    }

    /** Clear all transient sandbox state between (or on replay within) steps. */
    private fun resetSandbox() {
        handler.removeCallbacksAndMessages(stepToken)
        bubbleManager.hideSpeechBubble(animate = false)
        bubbleManager.hideVoice()
        closeRadial()
        ttsManager.onSpeechDone = null   // a stale DONE beep must not outlive its step
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
        if (pausedMidTutorial) {
            pausedMidTutorial = false
            goToStep(stepIndex)   // clean re-enter: demos restart instead of resuming half-done
        }
    }

    override fun onPause() {
        super.onPause()
        pausedMidTutorial = true
        handler.removeCallbacks(frameRunnable)
        // Demos must not keep talking/beeping while backgrounded.
        handler.removeCallbacksAndMessages(stepToken)
        ttsManager.onSpeechDone = null
        ttsManager.stop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, stepIndex)
        outState.putString(KEY_LAST_RESPONSE, lastResponse)
        outState.putBoolean(KEY_OVERLAY_WAS_RUNNING, overlayWasRunning)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        spriteAnimator.onEscape = null
        spriteAnimator.release()
        beepManager.release()
        ttsManager.release()
        // Bring the real overlay back only on a true exit, not a rotation.
        if (isFinishing && overlayWasRunning && OverlayController.canStart(this)) {
            OverlayController.ensureRunning(this, coordinator)
        }
    }

    private companion object {
        const val FRAME_MS = 16L
        // Gesture thresholds and dial geometry come from the production classes
        // (CompanionOverlayService / RadialMenuManager) so the sandbox can't drift.
        const val LONG_PRESS_TIMEOUT_MS = CompanionOverlayService.LONG_PRESS_TIMEOUT_MS
        const val SWIPE_MIN_DISTANCE_DP = CompanionOverlayService.SWIPE_MIN_DISTANCE_DP
        const val RESPONSE_TIMEOUT = 60000L
        const val KEY_STEP = "tutorial_step"
        const val KEY_LAST_RESPONSE = "tutorial_last_response"
        const val KEY_OVERLAY_WAS_RUNNING = "tutorial_overlay_was_running"
    }
}
