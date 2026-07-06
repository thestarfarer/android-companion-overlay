package com.starfarer.companionoverlay

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.starfarer.companionoverlay.databinding.ActivityTutorialBinding
import com.starfarer.companionoverlay.event.OverlayCoordinator
import com.starfarer.companionoverlay.repository.TutorialSettings
import com.starfarer.companionoverlay.ui.GestureHintView
import com.starfarer.companionoverlay.ui.MockAppWindowView
import com.starfarer.companionoverlay.ui.MockKeyboardToggleView
import com.starfarer.companionoverlay.ui.MockKeyboardView
import com.starfarer.companionoverlay.ui.MockVolumeKeyView
import com.starfarer.companionoverlay.ui.SparkleBurstView
import org.koin.android.ext.android.inject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

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
    private lateinit var hintView: GestureHintView
    private lateinit var sparkleView: SparkleBurstView
    private var radialView: RadialMenuView? = null
    private var badgeView: ImageView? = null

    // Stage props (per-step set dressing, torn down by resetSandbox).
    private var mockKeyboard: MockKeyboardView? = null
    private var mockWindow: MockAppWindowView? = null
    private var mockVolumeKey: MockVolumeKeyView? = null
    private var mockKbdToggle: MockKeyboardToggleView? = null

    // Volume-key press counting (production double/triple-press window).
    private var volPresses = 0
    private var volTarget = 0
    private var volAction: (() -> Unit)? = null
    private val volResolve = Runnable {
        if (volPresses == volTarget) volAction?.invoke()
        volPresses = 0
    }

    // Ghost-page playground state.
    private var mockKeyboardUp = false
    private var kbdUserPresses = 0

    /** Visibility-page state: she's mock-hidden until the next double-press. */
    private var mockHidden = false

    /** Pulses Next while we wait on a manual advance — "you can move on now". */
    private var nextPulse: ValueAnimator? = null
    private val fastOutSlowIn by lazy {
        AnimationUtils.loadInterpolator(this, android.R.interpolator.fast_out_slow_in)
    }

    /** First narration bind reveals instead of crossfading (there's nothing to fade out). */
    private var firstNarration = true

    /** She greets once per tutorial run, not on every revisit of step 1. */
    private var greeted = false

    /** Finish pressed — her exit animation is playing; ignore repeats. */
    private var finaleRunning = false

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
        // She's alive on every page by default: taps walk her, pestering bolts her
        // (the router already ignores her in ghost mode). Pages override for
        // replays (tools) or celebration (outro).
        val onTap: () -> Unit = { spriteAnimator.handleTouch() },
        val onLongPress: () -> Unit = {},
        val onSwipeUp: () -> Unit = {},
        val onSwipeDown: () -> Unit = {},
        val onEscape: () -> Unit = {},
        /** Animated affordance showing where/how to touch her; appears after a beat. */
        val hint: GestureHintView.Hint? = null,
        /** Pure-gesture step: success earns a gold ✓ + STEP beep (demo steps' spoken reply is their own reward). */
        val reward: Boolean = false,
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

        setupSprite(savedInstanceState)
        setupBubbles()
        setupOverlays()
        setupGestureRouter()
        steps = buildSteps()
        setupNarration()

        binding.skipButton.setOnClickListener { finish() }
        binding.prevButton.setOnClickListener { goBack() }
        binding.nextButton.setOnClickListener { advance() }

        lastResponse = savedInstanceState?.getString(KEY_LAST_RESPONSE)
        greeted = savedInstanceState?.getBoolean(KEY_GREETED) ?: false
        goToStep((savedInstanceState?.getInt(KEY_STEP) ?: 0).coerceIn(0, steps.lastIndex))
    }

    // ══════════════════════════════════════════════════════════════════════
    // Setup
    // ══════════════════════════════════════════════════════════════════════

    private fun setupSprite(saved: Bundle?) {
        val dm = resources.displayMetrics
        val config = SpriteAnimator.Config(dm.widthPixels, dm.heightPixels, dm.density)
        spriteAnimator = SpriteAnimator(this, config, settings).apply { loadSprites() }

        val w = spriteAnimator.viewWidth
        val h = spriteAnimator.viewHeight
        // Walk-distance inset matches the service's spawn position — without it her
        // first (rightward) walk clamps to her own start and she strides in place.
        val defaultX = dm.widthPixels - w - spriteAnimator.marginRightPx - spriteAnimator.walkDistancePx
        // A recreate (theme switch, rotation) must not teleport her home; clamp in
        // case the metrics changed — or she was saved mid-escape, off-screen.
        val savedX = saved?.getInt(KEY_SPRITE_X, -1) ?: -1
        val initialX = if (savedX >= 0) savedX.coerceIn(0, maxOf(0, dm.widthPixels - w)) else defaultX
        saved?.let {
            spriteAnimator.position = if (it.getBoolean(KEY_SPRITE_POS_LEFT))
                SpriteAnimator.OverlayPosition.Left else SpriteAnimator.OverlayPosition.Right
        }

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
            // The floor glow hugs her walk line — its Y depends on sprite metrics.
            binding.floorGlow.y = spriteView.y + h - 80 * density
            binding.floorGlow.visibility = View.VISIBLE
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
        val surface = DimmingBubbleSurface(ViewGroupBubbleSurface(binding.sandbox, density))
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

    /**
     * Senni's script bubble and the live response dialog share the same Monet color — both
     * at full brightness is clutter. The script steps back (0.25 alpha) whenever a centered
     * response bubble is on stage and returns when it leaves. Keying on the surface's
     * attach/detach catches every path: recall, reply follow-ups, TTS-fallback bubbles,
     * auto-dismiss timeouts, and tap-to-dismiss — with no timeline bookkeeping to go stale.
     */
    private inner class DimmingBubbleSurface(
        private val delegate: BubbleSurface
    ) : BubbleSurface {
        private var centered: View? = null

        override fun attach(view: View, placement: BubblePlacement, maxWidth: Int) {
            delegate.attach(view, placement, maxWidth)
            if (placement == BubblePlacement.CENTERED_DIALOG) {
                centered = view
                dimNarration(true)
            }
        }

        override fun makeFocusable(view: View): Boolean = delegate.makeFocusable(view)
        override fun makeUnfocusable(view: View) = delegate.makeUnfocusable(view)

        override fun detach(view: View) {
            delegate.detach(view)
            if (view === centered) {
                centered = null
                dimNarration(false)
            }
        }
    }

    /** Gesture hints and the finale sparkle live above the sprite; touches pass through both. */
    private fun setupOverlays() {
        hintView = GestureHintView(this)
        sparkleView = SparkleBurstView(this)
        val lp = { FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT) }
        binding.sandbox.addView(hintView, lp())
        binding.sandbox.addView(sparkleView, lp())
    }

    /**
     * Dress the narration as Senni's own speech bubble — Monet bubble colors (full-opacity,
     * vs the live bubbles' 0xFA, so script and live dialogue read differently), a tail toward
     * her corner, and the main screen's dot language for progress. Bubble text must use
     * [BubbleStyle] colors, not theme attrs: accent1_100 stays pastel-light in dark mode.
     */
    private fun setupNarration() {
        val c = BubbleStyle.colors(this)
        binding.narrationBubble.background = GradientDrawable().apply {
            setColor(c.bg)
            cornerRadius = 24f * density
        }
        binding.bubbleTail.background?.mutate()?.setTint(c.bg)
        binding.senniLabel.setTextColor(c.text)
        binding.senniLabel.alpha = 0.65f
        binding.stepTitle.setTextColor(c.text)
        binding.stepBody.setTextColor(c.text)

        val size = (8 * density).toInt()
        val margin = (4 * density).toInt()
        repeat(steps.size) {
            binding.stepDots.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = margin
                    marginEnd = margin
                }
                setBackgroundResource(R.drawable.page_indicator_unselected)
            })
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Gesture router — same thresholds as CompanionOverlayService.setupTouchHandling
    // ══════════════════════════════════════════════════════════════════════

    private fun setupGestureRouter() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val swipeThreshold = SWIPE_MIN_DISTANCE_DP * density
        spriteView.isClickable = true
        spriteView.setOnTouchListener { _, event ->
            // Production ghost mode sets FLAG_NOT_TOUCHABLE — taps pass through her.
            // Mirror it: while ghosted, her touches fall through to the keys beneath.
            if (spriteAnimator.isGhostMode) return@setOnTouchListener false
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

    /** On-device transcription label (the only STT engine on this build). */
    private fun listeningLabel() = getString(R.string.status_listening)

    /**
     * Deliver a spoken reply with production beats (gateway `message`/`speak`):
     * STEP beep, bubble down, speech, then the DONE beep on completion — not
     * at enqueue. Falls back to a bubble when the on-device engine failed so
     * the demo isn't silent.
     */
    private fun speakReply(text: String) {
        lastResponse = text
        beep(BeepManager.Beep.STEP)
        bubbleManager.hideSpeechBubble(animate = false)
        if (ttsFailed || !settings.ttsEnabled) {
            bubbleManager.showResponse(text, RESPONSE_TIMEOUT)
            beep(BeepManager.Beep.DONE)
            return
        }
        ttsManager.onSpeechDone = {
            ttsManager.onSpeechDone = null
            beep(BeepManager.Beep.DONE)
        }
        ttsManager.speak(text)
    }

    /** Step the script bubble back while a same-colored response bubble holds the stage. */
    private fun dimNarration(dim: Boolean) {
        binding.narrationBlock.animate()
            .alpha(if (dim) 0.25f else 1f).setDuration(200L).start()
    }

    /** Top ⅔ long-press: capture the screen, listen so you can talk about it, then answer aloud. */
    private fun runShowAndTell() {
        bubbleManager.showBrief(getString(R.string.svc_bubble_let_me_see), 2000L)
        // Screenshot-with-voice: she listens so you can talk about what you grabbed.
        // Production (dispatchCapturedImage) hides the capture brief before voice
        // starts, so the listening bubble takes the primary top-right slot.
        schedule(700L) {
            bubbleManager.hideSpeechBubble()
            bubbleManager.showVoice(listeningLabel())
            beep(BeepManager.Beep.READY)
        }
        // Listening → thinking has no beep in production (the STEP beep belongs
        // to the moment the utterance ships — see the voice demo and speakReply).
        schedule(1900L) {
            bubbleManager.hideVoice()
            bubbleManager.showBrief(getString(R.string.svc_bubble_thinking), 30000L)
        }
        // Reply is spoken, not bubbled (onResponseReceived → hideSpeechBubble + speak).
        // Completion is the gesture itself (onLongPress), not the demo's end.
        schedule(3100L) {
            speakReply(getString(R.string.tutorial_canned_response))
        }
    }

    /** Bottom ⅓ long-press: recall her last message as a bubble (handleBubbleReopen). */
    private fun runRecall() {
        bubbleManager.showResponse(lastResponse ?: getString(R.string.tutorial_recall_placeholder), RESPONSE_TIMEOUT)
        complete()
    }

    /** Voice round-trip mirroring the live server path: listening → utterance
     *  ships (STEP beep + thinking) → reply aloud. The server transcript is
     *  log-only in the live app now, so the demo shows no 🎙 bubble either. */
    private fun runVoiceDemo() {
        bubbleManager.showVoice(listeningLabel())
        beep(BeepManager.Beep.READY)
        schedule(800L) {
            // Utterance sent (onVoiceAudioSent): voice bubble down, STEP beep,
            // thinking brief.
            bubbleManager.hideVoice()
            beep(BeepManager.Beep.STEP)
            bubbleManager.showBrief(getString(R.string.svc_bubble_thinking), 30000L)
        }
        // Reply is spoken aloud with NO bubble (onResponseReceived → hideSpeechBubble + speak).
        // Completion belongs to the triple-press, not the demo's end.
        schedule(3200L) {
            speakReply(getString(R.string.tutorial_canned_voice_reply))   // real, on-device, permission-free
        }
    }

    /** A successful double-press on the mock key hides her; the next brings her back. */
    private fun toggleMockVisibility() {
        mockHidden = !mockHidden
        spriteView.animate().alpha(if (mockHidden) 0f else 1f).setDuration(300L).start()
        if (mockHidden) complete()   // first successful hide is the achievement
    }

    // ── Mock volume key (production press window) ──

    /**
     * Show the edge-mounted volume key. [presses] in quick succession (the real
     * [VOLUME_PRESS_WINDOW_MS] window) trigger [action]: a triple fires on the third
     * press immediately, a double resolves when the window closes — exactly the
     * accessibility service's rhythm.
     */
    private fun showVolumeKey(presses: Int, action: () -> Unit) {
        volTarget = presses
        volAction = action
        volPresses = 0
        if (mockVolumeKey != null) return
        val v = MockVolumeKeyView(this) { onVolumeKeyPress() }
        val lp = FrameLayout.LayoutParams(
            (28 * density).toInt(), (84 * density).toInt(),
            Gravity.END or Gravity.CENTER_VERTICAL
        ).apply { marginEnd = (6 * density).toInt() }
        v.alpha = 0f
        binding.sandbox.addView(v, lp)
        v.animate().alpha(1f).setDuration(200L).start()
        mockVolumeKey = v
    }

    private fun removeVolumeKey() {
        mockVolumeKey?.let { it.animate().cancel(); binding.sandbox.removeView(it) }
        mockVolumeKey = null
        volAction = null
        handler.removeCallbacks(volResolve)
    }

    private fun onVolumeKeyPress() {
        handler.removeCallbacks(volResolve)
        volPresses++
        if (volTarget >= 3 && volPresses >= volTarget) {
            volPresses = 0
            volAction?.invoke()
        } else {
            handler.postDelayed(volResolve, stepToken, VOLUME_PRESS_WINDOW_MS)
        }
    }

    // ── Ghost-page keyboard playground ──

    /** An edge key summons the keyboard; she ghosts; three real key presses through her complete it. */
    private fun setupKeyboardPlayground() {
        if (mockKbdToggle != null) return
        val v = MockKeyboardToggleView(this) {
            mockKeyboardUp = !mockKeyboardUp
            mockKbdToggle?.active = mockKeyboardUp
            if (mockKeyboardUp) {
                showMockKeyboard()
                schedule(KBD_SLIDE_MS) { spriteAnimator.setGhostMode(true) }
            } else {
                hideMockKeyboard()
                spriteAnimator.setGhostMode(false)
            }
        }
        // The same edge spot as the volume key — the tutorial's "hardware" lives there.
        val lp = FrameLayout.LayoutParams(
            (44 * density).toInt(), (44 * density).toInt(),
            Gravity.END or Gravity.CENTER_VERTICAL
        ).apply { marginEnd = (6 * density).toInt() }
        v.alpha = 0f
        binding.sandbox.addView(v, lp)
        v.animate().alpha(1f).setDuration(200L).start()
        mockKbdToggle = v
    }

    private fun removeKbdToggle() {
        mockKbdToggle?.let { it.animate().cancel(); binding.sandbox.removeView(it) }
        mockKbdToggle = null
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

    // ── Stage props ──

    /**
     * Slide the mock keyboard up behind her, its bottom edge on the stage floor (her
     * walk line) so it stays clear of the Prev/Next buttons. She stands in front,
     * ghosted — the phantom key presses read right through her.
     */
    private fun showMockKeyboard() {
        val v = mockKeyboard ?: MockKeyboardView(this).also { kb ->
            val h = (190 * density).toInt()
            // Clipping host pinned at the keyboard's final bounds: the keyboard
            // slides inside it, emerging from the stage floor — nothing ever
            // shows below the floor line (the bare slide used to be visible
            // rising past the nav buttons).
            val host = FrameLayout(this)
            host.addView(kb, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM
            ).apply {
                // Rest exactly on the floor glow's bright bottom edge.
                bottomMargin = (binding.sandbox.height
                        - (binding.floorGlow.y + binding.floorGlow.height)).toInt()
            }
            binding.sandbox.addView(host, 0, lp)   // index 0: set dressing, behind her
            kb.translationY = h.toFloat()
            kb.onUserKeyPress = {
                kbdUserPresses++
                if (kbdUserPresses == KBD_PRESSES_TO_COMPLETE) complete()
            }
            mockKeyboard = kb
        }
        v.animate().translationY(0f).setDuration(KBD_SLIDE_MS)
            .setInterpolator(fastOutSlowIn).start()
        v.startTyping(spriteView)
    }

    private fun hideMockKeyboard() {
        mockKeyboard?.let {
            it.stopTyping()
            it.animate().translationY(it.height.toFloat()).setDuration(KBD_SLIDE_MS)
                .setInterpolator(fastOutSlowIn).start()
        }
    }

    /** The capture demo's "something on screen": a little code-editor window, behind her. */
    private fun showMockWindow() {
        if (mockWindow != null) return
        val v = MockAppWindowView(this)
        val lp = FrameLayout.LayoutParams(
            (190 * density).toInt(), (130 * density).toInt(), Gravity.TOP or Gravity.START
        ).apply {
            marginStart = (20 * density).toInt()
            topMargin = (binding.sandbox.height * 0.47f).toInt()
        }
        v.alpha = 0f
        binding.sandbox.addView(v, 0, lp)   // index 0: set dressing renders behind her
        v.animate().alpha(1f).setDuration(200L).start()
        mockWindow = v
    }

    /** Restart a demo step's auto-play from a tap, clearing anything mid-flight first. */
    private fun replay(run: () -> Unit) {
        resetSandbox()
        run()
    }

    // ── Step-complete check badge ──

    /** Pop the gold checkmark in the gap between her head and the narration bubble. */
    private fun showCheckBadge() {
        val iv = badgeView ?: ImageView(this).apply {
            setImageResource(R.drawable.ic_tutorial_check)
            imageTintList = ColorStateList.valueOf(getColor(R.color.gold))
            val size = (72 * density).toInt()
            val lp = FrameLayout.LayoutParams(
                size, size, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            )
            // Anchor just above her top edge — a fixed spot sat right where she
            // stands once she's walked toward screen center.
            lp.bottomMargin =
                (binding.sandbox.height - spriteView.y + 12 * density).toInt()
            binding.sandbox.addView(this, lp)
            badgeView = this
        }
        iv.animate().cancel()
        iv.alpha = 0f
        iv.scaleX = 0.6f
        iv.scaleY = 0.6f
        iv.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200L)
            .withEndAction {
                iv.animate().alpha(0f).setStartDelay(600L).setDuration(300L).start()
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
            onEnter = {
                // She greets aloud once per run — rotation and Prev stay quiet.
                if (!greeted && !ttsFailed) {
                    greeted = true
                    ttsManager.speak(getString(R.string.tutorial_intro_spoken))
                }
            },
        ),
        // ── Core gestures ──
        Step(
            title = getString(R.string.tutorial_step_tap_title),
            body = getString(R.string.tutorial_step_tap_body),
            autoAdvance = true,
            reward = true,
            hint = GestureHintView.Hint.TAP,
            onTap = { spriteAnimator.handleTouch(); complete() },
        ),
        Step(
            title = getString(R.string.tutorial_step_escape_title),
            body = getString(R.string.tutorial_step_escape_body),
            autoAdvance = true,
            reward = true,
            hint = GestureHintView.Hint.TAP,
            onTap = { spriteAnimator.handleTouch() },
            onEscape = { complete() },
        ),
        Step(
            title = getString(R.string.tutorial_step_talk_title),
            body = getString(R.string.tutorial_step_talk_body),
            // Set dressing: something on screen for the capture to "see" — her
            // canned reply comments on a code editor, so show one.
            onEnter = { showMockWindow() },
            reward = true,
            hint = GestureHintView.Hint.LONG_PRESS_UPPER,
            onLongPress = {
                if (touchDownY < spriteView.height * 2f / 3f) {
                    runShowAndTell()
                    complete()   // the gesture is the achievement; the demo plays on
                }
            },
        ),
        Step(
            title = getString(R.string.tutorial_step_recall_title),
            body = getString(R.string.tutorial_step_recall_body),
            reward = true,
            hint = GestureHintView.Hint.LONG_PRESS_LOWER,
            onLongPress = {
                if (touchDownY >= spriteView.height * 2f / 3f) {
                    runRecall()
                }
            },
        ),
        Step(
            title = getString(R.string.tutorial_step_radial_title),
            body = getString(R.string.tutorial_step_radial_body),
            reward = true,
            hint = GestureHintView.Hint.SWIPE_UP,
            onSwipeUp = { openRadial() },
            onSwipeDown = { closeRadial() },
        ),
        // ── Hardware shortcuts (practiced on the mock edge key) ──
        Step(
            title = getString(R.string.tutorial_step_visibility_title),
            body = getString(R.string.tutorial_step_visibility_body),
            reward = true,
            onEnter = { showVolumeKey(presses = 2) { toggleMockVisibility() } },
        ),
        Step(
            title = getString(R.string.tutorial_step_voice_title),
            body = getString(R.string.tutorial_step_voice_body),
            reward = true,
            onEnter = {
                showVolumeKey(presses = 3) {
                    complete()
                    // A beat between the ✓/STEP and the demo's READY beep — and
                    // production's engine spin-up has the same small gap.
                    schedule(300L) { runVoiceDemo() }
                }
            },
            // No tap-replay here — the edge key is the replay; taps just walk her.
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
            reward = true,
            onEnter = { setupKeyboardPlayground() },
        ),
        // ── Outro ──
        Step(
            title = getString(R.string.tutorial_step_outro_title),
            body = getString(R.string.tutorial_step_outro_body),
            onEnter = {
                // The burst punctuates her sign-off — it fires when she finishes
                // speaking, not while she's mid-sentence.
                if (!ttsFailed) {
                    ttsManager.onSpeechDone = {
                        ttsManager.onSpeechDone = null
                        celebrate()
                    }
                    ttsManager.speak(getString(R.string.tutorial_outro_spoken))
                } else {
                    schedule(350L) { celebrate() }
                }
            },
            onTap = { celebrate() },   // sparkles on demand — cheap delight
        ),
    )

    /** Gold sparkle burst around her + the DONE beep — reserved for the finale. */
    private fun celebrate() {
        sparkleView.burst(
            spriteView.x + spriteView.width / 2f,
            spriteView.y + spriteView.height * 0.3f
        )
        beep(BeepManager.Beep.DONE)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Step driver
    // ══════════════════════════════════════════════════════════════════════

    private fun complete() {
        if (stepComplete) return
        stepComplete = true
        hintView.hide()
        if (current.reward) {
            // Success is legible: a gold ✓ pop + the STEP beep.
            showCheckBadge()
            beep(BeepManager.Beep.STEP)
        }
        if (current.autoAdvance) {
            // Let the ✓ land before the transition wipes it.
            schedule(REWARD_ADVANCE_DELAY_MS) { advance() }
        } else {
            startNextPulse()
        }
    }

    private fun advance() {
        if (stepIndex >= steps.lastIndex) { runFinale(); return }
        goToStep(stepIndex + 1)
    }

    private fun goBack() {
        if (stepIndex > 0) goToStep(stepIndex - 1)
    }

    /** Finish pressed: she bolts off-screen the way she escapes, then the Activity follows. */
    private fun runFinale() {
        if (finaleRunning) return
        finaleRunning = true
        beep(BeepManager.Beep.DONE)
        spriteAnimator.escape()
        binding.narrationBlock.animate().cancel()
        binding.narrationBlock.animate().alpha(0f).setDuration(300L).start()
        binding.prevButton.isEnabled = false
        binding.nextButton.isEnabled = false
        // Her exit walk is ~800ms; finish just after she clears the edge (the Enter
        // half of the escape hasn't visibly re-entered yet).
        schedule(FINALE_EXIT_MS) { finish() }
    }

    private fun goToStep(i: Int) {
        val dir = if (i >= stepIndex) 1f else -1f
        if (i != stepIndex) steps[stepIndex].onExit()
        stepIndex = i
        stepComplete = false
        finaleRunning = false
        // The edge "hardware" survives in-step replays (resetSandbox), not step changes.
        removeVolumeKey()
        removeKbdToggle()
        resetSandbox()

        binding.narrationBlock.animate().cancel()
        if (firstNarration) {
            // Nothing to fade out yet — just reveal.
            firstNarration = false
            applyNarration()
            binding.narrationBlock.alpha = 0f
            binding.narrationBlock.translationX = 20 * density
            binding.narrationBlock.animate().alpha(1f).translationX(0f)
                .setDuration(NARRATION_IN_MS).setInterpolator(fastOutSlowIn).start()
            enterStep()
        } else {
            // Two beats: narration slides out, then (token-scheduled — withEndAction is
            // skipped on cancel, the token isn't) the new step binds and slides in.
            // Next-spam is safe: each pass's resetSandbox drops the previous pending bind.
            binding.narrationBlock.animate().alpha(0f).translationX(-20 * density * dir)
                .setDuration(NARRATION_OUT_MS).setInterpolator(fastOutSlowIn).start()
            schedule(NARRATION_OUT_MS + 20) {
                applyNarration()
                binding.narrationBlock.translationX = 20 * density * dir
                binding.narrationBlock.animate().alpha(1f).translationX(0f)
                    .setDuration(NARRATION_IN_MS).setInterpolator(fastOutSlowIn).start()
                enterStep()
            }
        }
    }

    /** Bind the current step's copy, dots, and button states. */
    private fun applyNarration() {
        val s = current
        binding.stepTitle.text = s.title
        binding.stepBody.text = s.body
        binding.nextButton.text = if (stepIndex == steps.lastIndex)
            getString(R.string.tutorial_finish) else getString(R.string.tutorial_next)
        binding.nextButton.isEnabled = true
        binding.prevButton.isEnabled = stepIndex > 0
        // The static gold backgroundTint hides Material's disabled state — without
        // the alpha cue, a disabled Previous on page 1 looks perfectly pressable.
        binding.prevButton.alpha = if (stepIndex > 0) 1f else 0.4f
        updateDots()
    }

    private fun updateDots() {
        for (j in 0 until binding.stepDots.childCount) {
            val dot = binding.stepDots.getChildAt(j)
            val active = j == stepIndex
            dot.setBackgroundResource(
                if (active) R.drawable.page_indicator_selected
                else R.drawable.page_indicator_unselected
            )
            dot.animate().cancel()
            val target = if (active) 1.25f else 1f
            dot.animate().scaleX(target).scaleY(target).setDuration(150L).start()
        }
        // The dots replace the "2 / 11" text visually; keep the count for TalkBack.
        binding.stepDots.contentDescription =
            getString(R.string.tutorial_step_progress, stepIndex + 1, steps.size)
    }

    private fun enterStep() {
        current.onEnter()
        current.hint?.let { h ->
            // A beat of patience first — the hint reads as "she's waiting for you".
            schedule(HINT_DELAY_MS) { hintView.show(h, spriteView, LONG_PRESS_TIMEOUT_MS) }
        }
    }

    // ── Next-button pulse: "you can move on now" ──

    private fun startNextPulse() {
        if (nextPulse != null) return
        nextPulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val s = 1f + 0.04f * sin(Math.PI * (it.animatedValue as Float)).toFloat()
                binding.nextButton.scaleX = s
                binding.nextButton.scaleY = s
            }
            start()
        }
    }

    private fun cancelNextPulse() {
        nextPulse?.cancel()
        nextPulse = null
        binding.nextButton.scaleX = 1f
        binding.nextButton.scaleY = 1f
    }

    /** Clear all transient sandbox state between (or on replay within) steps. */
    private fun resetSandbox() {
        handler.removeCallbacksAndMessages(stepToken)
        bubbleManager.hideSpeechBubble(animate = true)   // 250ms fade; ref is nulled at once
        bubbleManager.hideVoice()
        closeRadial()
        ttsManager.onSpeechDone = null   // a stale DONE beep must not outlive its step
        ttsManager.stop()
        spriteView.animate().cancel()
        spriteAnimator.setGhostMode(false)
        // setGhostMode's alpha restore only runs when the ghost flag was actually set —
        // the visibility demo fades her with plain view.animate(), so leaving that step
        // mid-"hidden" left her invisible. Restore explicitly, softly.
        if (spriteView.alpha != 1f) {
            spriteView.animate().alpha(1f).setDuration(200L).start()
        }
        hintView.hide(animated = false)
        sparkleView.cancel()
        cancelNextPulse()
        removeBadge()
        mockKeyboard?.let {
            it.stopTyping()
            it.animate().cancel()
            binding.sandbox.removeView(it.parent as? View)   // its clipping host
        }
        mockKeyboard = null
        mockWindow?.let { it.animate().cancel(); binding.sandbox.removeView(it) }
        mockWindow = null
        mockKeyboardUp = false
        kbdUserPresses = 0
        volPresses = 0
        mockHidden = false
        mockKbdToggle?.active = false
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
        // A killed mid-transition narration would stay half-faded; snap to identity
        // (onResume's clean re-enter animates it again anyway). NOT when finishing —
        // the finale just faded it out, and this snap flashed it back during the
        // window's exit transition.
        if (!isFinishing) {
            binding.narrationBlock.animate().cancel()
            binding.narrationBlock.alpha = 1f
            binding.narrationBlock.translationX = 0f
        }
        hintView.hide(animated = false)
        sparkleView.cancel()
        cancelNextPulse()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, stepIndex)
        outState.putString(KEY_LAST_RESPONSE, lastResponse)
        outState.putBoolean(KEY_OVERLAY_WAS_RUNNING, overlayWasRunning)
        outState.putBoolean(KEY_GREETED, greeted)
        outState.putInt(KEY_SPRITE_X, spriteView.x.toInt())
        outState.putBoolean(
            KEY_SPRITE_POS_LEFT,
            spriteAnimator.position == SpriteAnimator.OverlayPosition.Left
        )
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

        // Step-transition choreography.
        const val NARRATION_OUT_MS = 160L
        const val NARRATION_IN_MS = 220L
        const val HINT_DELAY_MS = 900L
        const val REWARD_ADVANCE_DELAY_MS = 600L
        /** Escape's exit walk is ~800ms (4 × 200ms frames); finish just past the edge. */
        const val FINALE_EXIT_MS = 820L
        const val KBD_SLIDE_MS = 250L
        const val KBD_PRESSES_TO_COMPLETE = 3
        /** The accessibility service's real double/triple-press window. */
        const val VOLUME_PRESS_WINDOW_MS = CompanionAccessibilityService.DOUBLE_TAP_WINDOW_MS

        const val KEY_STEP = "tutorial_step"
        const val KEY_LAST_RESPONSE = "tutorial_last_response"
        const val KEY_OVERLAY_WAS_RUNNING = "tutorial_overlay_was_running"
        const val KEY_GREETED = "tutorial_greeted"
        const val KEY_SPRITE_X = "tutorial_sprite_x"
        const val KEY_SPRITE_POS_LEFT = "tutorial_sprite_pos_left"
    }
}
