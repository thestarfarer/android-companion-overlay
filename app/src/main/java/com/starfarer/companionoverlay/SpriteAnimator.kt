package com.starfarer.companionoverlay

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.SystemClock
import android.view.WindowManager
import android.widget.ImageView
import com.starfarer.companionoverlay.repository.SettingsRepository
import kotlin.math.sin
import kotlin.random.Random

/**
 * Manages the sprite animation state machine — idle breathing, walking,
 * escape sequences, touch-triggered disturbance, and ghost mode.
 *
 * Extracted from CompanionOverlayService to separate rendering concerns
 * from conversation, TTS, and service lifecycle logic.
 *
 * Screen geometry is passed via [Config] at construction time. View
 * references are bound later via [attach], which must be called before
 * the first [update]. This two-phase approach is necessary because the
 * overlay view dimensions depend on sprite sheet sizes (computed during
 * [loadSprites]), but the view itself is created after the animator.
 */
class SpriteAnimator(
    private val context: Context,
    private val config: Config,
    private val settings: SettingsRepository
) {

    /**
     * Immutable screen geometry, resolved once at service creation.
     * Everything the animator needs to know about the display without
     * reaching back into the service.
     */
    data class Config(
        val screenWidth: Int,
        val screenHeight: Int,
        val density: Float
    )

    /**
     * Mutable view references, set via [attach] after the overlay view
     * is created and added to the window manager.
     */
    private var overlayView: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null

    companion object {
        private const val DEFAULT_IDLE_FRAME_COUNT = 6
        private const val DEFAULT_WALK_FRAME_COUNT = 4
        const val IDLE_FRAME_DURATION_MS = 1000L
        const val WALK_FRAME_DURATION_MS = 200L
        private const val WALK_DISTANCE_DP = 100
        private const val MARGIN_RIGHT_DP = 40
        private const val MARGIN_BOTTOM_DP = 80

        private const val DISTURBANCE_TIMEOUT_MS = 1000L
        private const val CROSSING_THRESHOLD_TIME_MS = 10000L
        private const val DISTURBANCE_MIN = 2
        private const val DISTURBANCE_MAX = 5
    }

    // --- Enums ---
    enum class OverlayState { Idle, WalkingLeft, WalkingRight }
    enum class OverlayPosition { Left, Right }
    private enum class EscapePhase { None, Exit, Enter }

    // --- Sprite sheets ---
    private var idleSpriteSheet: Bitmap? = null
    private var walkSpriteSheet: Bitmap? = null
    var idleFrameCount = DEFAULT_IDLE_FRAME_COUNT; private set
    var walkFrameCount = DEFAULT_WALK_FRAME_COUNT; private set
    var idleFrameWidth = 0; private set
    var idleFrameHeight = 0; private set
    var walkFrameWidth = 0; private set
    var walkFrameHeight = 0; private set

    // --- Pre-allocated render buffers (avoids per-frame Bitmap allocations) ---
    private var idleRenderBitmap: Bitmap? = null
    private var idleRenderCanvas: Canvas? = null

    // --- Pre-extracted walking frames (avoids per-frame createBitmap + mirror) ---
    private var walkFramesRight: Array<Bitmap>? = null
    private var walkFramesLeft: Array<Bitmap>? = null

    // --- State machine ---
    var state = OverlayState.Idle; private set
    var position = OverlayPosition.Right
    private var escapePhase = EscapePhase.None
    private var escaping = false

    // --- Animation timing ---
    var startTime = 0L
    private var walkStartTime = 0L
    var walkStartX = 0
    var walkTargetX = 0
    private var currentFrame = 0

    // --- Touch / disturbance ---
    private var lastTouchTime = 0L
    private val disturbanceTimestamps = mutableListOf<Long>()
    private var disturbanceThreshold = Random.nextInt(DISTURBANCE_MIN, DISTURBANCE_MAX + 1)

    // --- Ghost mode ---
    var isGhostMode = false
        private set

    // ══════════════════════════════════════════════════════════════════════
    // View binding
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Bind the overlay view and its layout params after creation.
     * Must be called before the first [update].
     */
    fun attach(view: ImageView, layoutParams: WindowManager.LayoutParams, wm: WindowManager) {
        overlayView = view
        params = layoutParams
        windowManager = wm
        walkStartX = layoutParams.x
        walkTargetX = layoutParams.x
    }

    // ══════════════════════════════════════════════════════════════════════
    // Ghost mode
    // ══════════════════════════════════════════════════════════════════════

    fun setGhostMode(ghost: Boolean) {
        if (ghost == isGhostMode) return
        isGhostMode = ghost
        val view = overlayView ?: return
        val p = params ?: return
        val wm = windowManager ?: return

        val oldFlags = p.flags
        if (ghost) {
            view.animate().alpha(0.5f).setDuration(200).start()
            p.flags = p.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            view.animate().alpha(1f).setDuration(200).start()
            p.flags = p.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        try {
            wm.updateViewLayout(view, p)
        } catch (e: Exception) {
            p.flags = oldFlags
            isGhostMode = !ghost
            DebugLog.log("Sprite", "updateViewLayout failed: ${e.message}")
        }
    }

    fun release() {
        idleRenderBitmap?.recycle()
        idleRenderBitmap = null
        idleRenderCanvas = null

        walkFramesRight?.forEach { it.recycle() }
        walkFramesLeft?.forEach { it.recycle() }
        walkFramesRight = null
        walkFramesLeft = null

        idleSpriteSheet?.recycle()
        walkSpriteSheet?.recycle()
        idleSpriteSheet = null
        walkSpriteSheet = null

        overlayView = null
        params = null
        windowManager = null
    }

    // ══════════════════════════════════════════════════════════════════════
    // Sprite loading
    // ══════════════════════════════════════════════════════════════════════

    fun loadSprites() {
        idleFrameCount = settings.idleFrameCount
        walkFrameCount = settings.walkFrameCount

        idleSpriteSheet = loadSprite(
            settings.idleSpriteUri, "custom_idle_sheet.png", "idle_sheet.png"
        )
        walkSpriteSheet = loadSprite(
            settings.walkSpriteUri, "custom_walk_sheet.png", "walk_sheet.png"
        )

        idleSpriteSheet?.let {
            idleFrameWidth = it.width / idleFrameCount
            idleFrameHeight = it.height
        }
        walkSpriteSheet?.let {
            walkFrameWidth = it.width / walkFrameCount
            walkFrameHeight = it.height
        }

        // Pre-allocate idle render buffer (reused every frame instead of allocating)
        val vw = viewWidth
        val vh = viewHeight
        if (vw > 0 && vh > 0) {
            idleRenderBitmap = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
            idleRenderCanvas = Canvas(idleRenderBitmap!!)
        }

        // Pre-extract and pre-mirror walking frames
        extractWalkFrames()
    }

    /**
     * Pre-extracts individual walking frames from the sprite sheet and creates
     * mirrored copies for left-walking. This moves all Bitmap.createBitmap and
     * Matrix work from the 60fps render loop to a one-time setup cost.
     */
    private fun extractWalkFrames() {
        val sheet = walkSpriteSheet ?: return
        if (walkFrameWidth <= 0 || walkFrameHeight <= 0) return

        val right = Array(walkFrameCount) { i ->
            Bitmap.createBitmap(sheet, i * walkFrameWidth, 0, walkFrameWidth, walkFrameHeight)
        }
        val mirrorMatrix = Matrix().apply {
            preScale(-1f, 1f)
            postTranslate(walkFrameWidth.toFloat(), 0f)
        }
        val left = Array(walkFrameCount) { i ->
            Bitmap.createBitmap(right[i], 0, 0, walkFrameWidth, walkFrameHeight, mirrorMatrix, true)
        }

        walkFramesRight = right
        walkFramesLeft = left
    }

    private fun loadSprite(customUri: String?, customAsset: String, defaultAsset: String): Bitmap {
        if (customUri != null) {
            try {
                val uri = Uri.parse(customUri)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) {
                        DebugLog.log("Overlay", "Loaded custom sprite from $customUri")
                        return bmp
                    }
                }
            } catch (e: Exception) {
                DebugLog.log("Overlay", "Failed to load custom sprite: ${e.message}")
            }
        }

        try {
            context.assets.open(customAsset).use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                if (bmp != null) {
                    DebugLog.log("Overlay", "Loaded custom asset: $customAsset")
                    return bmp
                }
            }
        } catch (_: Exception) {}

        return context.assets.open(defaultAsset).use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Animation update (called from animation runnable)
    // ══════════════════════════════════════════════════════════════════════

    fun update() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - startTime) / 1000.0

        when (state) {
            OverlayState.Idle -> drawIdle(elapsed)
            OverlayState.WalkingLeft, OverlayState.WalkingRight -> {
                handleWalking(now)
                drawWalking(now)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Touch handling
    // ══════════════════════════════════════════════════════════════════════

    fun handleTouch() {
        val now = SystemClock.elapsedRealtime()
        if (state != OverlayState.Idle) return
        if (now - lastTouchTime < DISTURBANCE_TIMEOUT_MS) return

        lastTouchTime = now
        disturbanceTimestamps.add(now)
        disturbanceTimestamps.removeAll { now - it > CROSSING_THRESHOLD_TIME_MS }

        if (disturbanceTimestamps.size >= disturbanceThreshold) {
            disturbanceTimestamps.clear()
            disturbanceThreshold = Random.nextInt(DISTURBANCE_MIN, DISTURBANCE_MAX + 1)
            triggerEscape()
        } else {
            triggerWalk()
        }
    }

    fun dismissAnimated(onComplete: () -> Unit) {
        val view = overlayView ?: run { onComplete(); return }
        view.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction(onComplete)
            .start()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Walking
    // ══════════════════════════════════════════════════════════════════════

    private fun triggerWalk() {
        val p = params ?: return
        walkStartTime = SystemClock.elapsedRealtime()
        walkStartX = p.x
        val walkDistance = walkDistancePx

        if (position == OverlayPosition.Right) {
            state = OverlayState.WalkingRight
            walkTargetX = walkStartX + walkDistance
            position = OverlayPosition.Left
        } else {
            state = OverlayState.WalkingLeft
            walkTargetX = walkStartX - walkDistance
            position = OverlayPosition.Right
        }

        walkTargetX = walkTargetX.coerceIn(marginRightPx, config.screenWidth - p.width - marginRightPx)
    }

    private fun triggerEscape() {
        val p = params ?: return
        escaping = true
        escapePhase = EscapePhase.Exit
        walkStartTime = SystemClock.elapsedRealtime()
        walkStartX = p.x

        val distanceToLeft = p.x + p.width
        val distanceToRight = config.screenWidth - p.x

        if (distanceToRight < distanceToLeft) {
            walkTargetX = config.screenWidth + p.width
            position = OverlayPosition.Right
            state = OverlayState.WalkingRight
        } else {
            walkTargetX = -p.width * 2
            position = OverlayPosition.Left
            state = OverlayState.WalkingLeft
        }
    }

    private fun handleWalking(now: Long) {
        val p = params ?: return
        val view = overlayView ?: return
        val wm = windowManager ?: return

        val walkDuration = WALK_FRAME_DURATION_MS * walkFrameCount
        val walkProgress = (now - walkStartTime).toFloat() / walkDuration

        if (walkProgress >= 1.0f) {
            p.x = walkTargetX

            if (escaping) {
                if (escapePhase == EscapePhase.Exit) {
                    escapePhase = EscapePhase.Enter
                    walkStartTime = now

                    if (position == OverlayPosition.Right) {
                        p.x = -p.width
                        walkStartX = -p.width
                        walkTargetX = marginRightPx + walkDistancePx
                        state = OverlayState.WalkingRight
                    } else {
                        p.x = config.screenWidth + p.width
                        walkStartX = config.screenWidth + p.width
                        walkTargetX = config.screenWidth - p.width - marginRightPx - walkDistancePx
                        state = OverlayState.WalkingLeft
                    }
                } else {
                    position = if (position == OverlayPosition.Right) OverlayPosition.Left else OverlayPosition.Right
                    state = OverlayState.Idle
                    escaping = false
                    escapePhase = EscapePhase.None
                }
            } else {
                state = OverlayState.Idle
            }

            wm.updateViewLayout(view, p)
        } else {
            val newX = walkStartX + ((walkTargetX - walkStartX) * walkProgress).toInt()
            p.x = newX
            wm.updateViewLayout(view, p)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Drawing
    // ══════════════════════════════════════════════════════════════════════

    private fun drawIdle(t: Double) {
        val view = overlayView ?: return
        val p = params ?: return
        val idleSheet = idleSpriteSheet ?: return
        val renderBitmap = idleRenderBitmap ?: return
        val canvas = idleRenderCanvas ?: return
        val frame = ((t / (IDLE_FRAME_DURATION_MS / 1000.0)).toInt()) % idleFrameCount
        val scale = 1.0f + 0.01f * sin(2 * Math.PI * t).toFloat()
        val yOffset = (4.0 * sin(2 * Math.PI * t * 0.5)).toFloat()

        val vw = p.width
        val vh = p.height

        // Clear the pre-allocated buffer instead of allocating a new one
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        val xOffset = (vw - idleFrameWidth) / 2f
        val yCenter = (vh - idleFrameHeight) / 2f

        canvas.save()
        canvas.translate(vw / 2f, vh / 2f + yOffset)
        canvas.scale(1f, scale)
        canvas.translate(-vw / 2f, -vh / 2f)

        val srcRect = Rect(frame * idleFrameWidth, 0, (frame + 1) * idleFrameWidth, idleFrameHeight)
        val dstRect = RectF(xOffset, yCenter, xOffset + idleFrameWidth, yCenter + idleFrameHeight)
        canvas.drawBitmap(idleSheet, srcRect, dstRect, null)
        canvas.restore()

        view.setImageBitmap(renderBitmap)
    }

    private fun drawWalking(now: Long) {
        val view = overlayView ?: return
        val walkElapsed = now - walkStartTime
        val frame = ((walkElapsed / WALK_FRAME_DURATION_MS).toInt()) % walkFrameCount

        // Use pre-extracted frames — no per-frame Bitmap allocation or Matrix work
        val frames = if (state == OverlayState.WalkingLeft) walkFramesLeft else walkFramesRight
        val frameBitmap = frames?.getOrNull(frame) ?: return

        view.setImageBitmap(frameBitmap)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Utility
    // ══════════════════════════════════════════════════════════════════════

    val viewWidth get() = maxOf(idleFrameWidth, walkFrameWidth)
    val viewHeight get() = maxOf(idleFrameHeight, walkFrameHeight)
    val marginRightPx get() = (MARGIN_RIGHT_DP * config.density).toInt()
    val marginBottomPx get() = (MARGIN_BOTTOM_DP * config.density).toInt()
    val walkDistancePx get() = (WALK_DISTANCE_DP * config.density).toInt()
}
