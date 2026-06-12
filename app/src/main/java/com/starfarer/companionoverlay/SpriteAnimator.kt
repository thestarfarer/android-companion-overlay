package com.starfarer.companionoverlay

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.SystemClock
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
     * Screen geometry, resolved at service creation. Width/height are mutable
     * so the service can re-point the walk/escape math after a rotation or
     * fold change (see [onScreenResized]); density is per-display and fixed.
     */
    data class Config(
        var screenWidth: Int,
        var screenHeight: Int,
        val density: Float
    )

    /**
     * The surface the sprite is drawn on and moved through, bound via [attach].
     * Abstracts the overlay window (real) from a plain view group (tutorial).
     */
    private var surface: SpriteSurface? = null

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

        /** Longest-edge cap for a decoded sprite sheet (OOM guard for custom PNGs). */
        private const val MAX_SPRITE_DIMENSION = 2048
    }

    // --- Enums ---
    enum class OverlayState { Idle, WalkingLeft, WalkingRight }

    /**
     * The direction the sprite walks on the next tap — note this reads inverted
     * vs its physical location: [Right] means it's anchored at its left-most
     * spot and will move right next. Persisted as "left"/"right" by the
     * service, so don't repurpose the names without migrating that.
     */
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
    // Reused across frames — drawIdle allocated a fresh Rect/RectF every tick,
    // contradicting the "no per-frame allocations" intent.
    private val idleSrcRect = Rect()
    private val idleDstRect = RectF()

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

    /** Optional hook fired when an escape sequence begins (used by the tutorial). */
    var onEscape: (() -> Unit)? = null

    // ══════════════════════════════════════════════════════════════════════
    // View binding
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Bind the surface the sprite renders on. Must be called before the first [update].
     */
    fun attach(surface: SpriteSurface) {
        this.surface = surface
        walkStartX = surface.x
        walkTargetX = surface.x
    }

    // ══════════════════════════════════════════════════════════════════════
    // Ghost mode
    // ══════════════════════════════════════════════════════════════════════

    fun setGhostMode(ghost: Boolean) {
        if (ghost == isGhostMode) return
        isGhostMode = ghost
        val s = surface ?: return
        if (!s.setGhost(ghost)) isGhostMode = !ghost
    }

    /** Update the screen bounds the walk/escape math clamps against (rotation). */
    fun onScreenResized(width: Int, height: Int) {
        config.screenWidth = width
        config.screenHeight = height
    }

    fun release() {
        releaseBitmaps()
        surface = null
    }

    /**
     * Reload sprite sheets in place — custom sprite or preset changed while the
     * overlay is running. Recycling then immediately redrawing is safe because
     * both happen in the same main-thread turn: no draw pass can observe the
     * view holding a recycled bitmap before the caller's next [update].
     */
    fun reloadSprites() {
        releaseBitmaps()
        loadSprites()
    }

    private fun releaseBitmaps() {
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
            val sub = Bitmap.createBitmap(sheet, i * walkFrameWidth, 0, walkFrameWidth, walkFrameHeight)
            // When the region covers the whole sheet (single-frame sheet),
            // createBitmap returns the SOURCE itself — releasing the frame array
            // would then recycle the still-referenced sheet. Force a copy.
            if (sub === sheet) sub.copy(sub.config ?: Bitmap.Config.ARGB_8888, false) else sub
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
                decodeCapped { context.contentResolver.openInputStream(uri) }?.let {
                    DebugLog.log("Overlay", "Loaded custom sprite from $customUri")
                    return it
                }
            } catch (e: Exception) {
                DebugLog.log("Overlay", "Failed to load custom sprite: ${e.message}")
            }
        }

        try {
            decodeCapped { context.assets.open(customAsset) }?.let {
                DebugLog.log("Overlay", "Loaded custom asset: $customAsset")
                return it
            }
        } catch (_: Exception) {}

        return decodeCapped { context.assets.open(defaultAsset) }
            ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    /**
     * Decode a sprite sheet, downsampling so its longest edge stays within
     * [MAX_SPRITE_DIMENSION]. A large user-supplied PNG would otherwise decode
     * to a full-size bitmap that [extractWalkFrames] then duplicates ~2x (right
     * + mirrored left frames) — an easy OOM. The [open] provider is called
     * twice (bounds pass, then decode), so it must reopen the stream each time.
     */
    private fun decodeCapped(open: () -> java.io.InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // The bounds pass returns null even on success (inJustDecodeBounds
        // produces no bitmap) — only a null stream is a failure here. Decode
        // failure surfaces as outWidth/outHeight staying <= 0 below.
        (open() ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > MAX_SPRITE_DIMENSION) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return open()?.use { BitmapFactory.decodeStream(it, null, opts) }
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

    /** Scripted escape — deterministic trigger for the tutorial finale (handleTouch's is randomized). */
    fun escape() = triggerEscape()

    fun dismissAnimated(onComplete: () -> Unit) {
        val view = surface?.view ?: run { onComplete(); return }
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
        val s = surface ?: return
        walkStartTime = SystemClock.elapsedRealtime()
        walkStartX = s.x
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

        // A sprite wider than the screen makes maxX < minX, which crashes
        // coerceIn ("empty range") — in that case just don't walk.
        val maxX = config.screenWidth - s.spriteWidth - marginRightPx
        walkTargetX = if (maxX <= marginRightPx) walkStartX
            else walkTargetX.coerceIn(marginRightPx, maxX)
    }

    private fun triggerEscape() {
        val s = surface ?: return
        onEscape?.invoke()
        escaping = true
        escapePhase = EscapePhase.Exit
        walkStartTime = SystemClock.elapsedRealtime()
        walkStartX = s.x

        val distanceToLeft = s.x + s.spriteWidth
        val distanceToRight = config.screenWidth - s.x

        if (distanceToRight < distanceToLeft) {
            walkTargetX = config.screenWidth + s.spriteWidth
            position = OverlayPosition.Right
            state = OverlayState.WalkingRight
        } else {
            walkTargetX = -s.spriteWidth * 2
            position = OverlayPosition.Left
            state = OverlayState.WalkingLeft
        }
    }

    private fun handleWalking(now: Long) {
        val s = surface ?: return

        val walkDuration = WALK_FRAME_DURATION_MS * walkFrameCount
        val walkProgress = (now - walkStartTime).toFloat() / walkDuration

        if (walkProgress >= 1.0f) {
            s.x = walkTargetX

            if (escaping) {
                if (escapePhase == EscapePhase.Exit) {
                    escapePhase = EscapePhase.Enter
                    walkStartTime = now

                    if (position == OverlayPosition.Right) {
                        s.x = -s.spriteWidth
                        walkStartX = -s.spriteWidth
                        walkTargetX = marginRightPx + walkDistancePx
                        state = OverlayState.WalkingRight
                    } else {
                        s.x = config.screenWidth + s.spriteWidth
                        walkStartX = config.screenWidth + s.spriteWidth
                        walkTargetX = config.screenWidth - s.spriteWidth - marginRightPx - walkDistancePx
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

            s.commitPosition()
        } else {
            val newX = walkStartX + ((walkTargetX - walkStartX) * walkProgress).toInt()
            s.x = newX
            s.commitPosition()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Drawing
    // ══════════════════════════════════════════════════════════════════════

    private fun drawIdle(t: Double) {
        val s = surface ?: return
        val view = (s.view as? ImageView) ?: return
        val idleSheet = idleSpriteSheet ?: return
        val renderBitmap = idleRenderBitmap ?: return
        val canvas = idleRenderCanvas ?: return
        val frame = ((t / (IDLE_FRAME_DURATION_MS / 1000.0)).toInt()) % idleFrameCount
        val scale = 1.0f + 0.01f * sin(2 * Math.PI * t).toFloat()
        val yOffset = (4.0 * sin(2 * Math.PI * t * 0.5)).toFloat()

        val vw = s.spriteWidth
        val vh = s.spriteHeight

        // Clear the pre-allocated buffer instead of allocating a new one
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        val xOffset = (vw - idleFrameWidth) / 2f
        val yCenter = (vh - idleFrameHeight) / 2f

        canvas.save()
        canvas.translate(vw / 2f, vh / 2f + yOffset)
        canvas.scale(1f, scale)
        canvas.translate(-vw / 2f, -vh / 2f)

        idleSrcRect.set(frame * idleFrameWidth, 0, (frame + 1) * idleFrameWidth, idleFrameHeight)
        idleDstRect.set(xOffset, yCenter, xOffset + idleFrameWidth, yCenter + idleFrameHeight)
        canvas.drawBitmap(idleSheet, idleSrcRect, idleDstRect, null)
        canvas.restore()

        view.setImageBitmap(renderBitmap)
    }

    private fun drawWalking(now: Long) {
        val view = (surface?.view as? ImageView) ?: return
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
