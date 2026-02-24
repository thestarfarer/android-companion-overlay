package com.starfarer.companionoverlay

import android.content.Context
import android.graphics.*
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.sin
import kotlin.random.Random

/**
 * Manages the sprite animation state machine — idle breathing, walking,
 * escape sequences, touch-triggered disturbance, and ghost mode.
 *
 * Extracted from CompanionOverlayService to separate rendering concerns
 * from conversation, TTS, and service lifecycle logic.
 */
class SpriteAnimator(private val context: Context) {

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

    // --- View references (set via init) ---
    lateinit var overlayView: ImageView
    lateinit var params: WindowManager.LayoutParams
    lateinit var windowManager: WindowManager

    // --- Screen geometry ---
    var screenWidth = 0
    var screenHeight = 0
    var density = 1f

    // --- Sprite sheets ---
    private var idleSpriteSheet: Bitmap? = null
    private var walkSpriteSheet: Bitmap? = null
    var idleFrameCount = DEFAULT_IDLE_FRAME_COUNT; private set
    var walkFrameCount = DEFAULT_WALK_FRAME_COUNT; private set
    var idleFrameWidth = 0; private set
    var idleFrameHeight = 0; private set
    var walkFrameWidth = 0; private set
    var walkFrameHeight = 0; private set

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

    fun setGhostMode(ghost: Boolean) {
        if (ghost == isGhostMode) return
        isGhostMode = ghost

        if (ghost) {
            overlayView.animate().alpha(0.5f).setDuration(200).start()
            params.flags = params.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            overlayView.animate().alpha(1f).setDuration(200).start()
            params.flags = params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        try {
            windowManager.updateViewLayout(overlayView, params)
        } catch (_: Exception) {}
    }

    fun release() {
        idleSpriteSheet?.recycle()
        walkSpriteSheet?.recycle()
        idleSpriteSheet = null
        walkSpriteSheet = null
    }

    // --- Sprite loading ---

    fun loadSprites() {
        idleFrameCount = PromptSettings.getIdleFrameCount(context)
        walkFrameCount = PromptSettings.getWalkFrameCount(context)

        idleSpriteSheet = loadSprite(
            PromptSettings.getIdleSpriteUri(context), "custom_idle_sheet.png", "idle_sheet.png"
        )
        walkSpriteSheet = loadSprite(
            PromptSettings.getWalkSpriteUri(context), "custom_walk_sheet.png", "walk_sheet.png"
        )

        idleSpriteSheet?.let {
            idleFrameWidth = it.width / idleFrameCount
            idleFrameHeight = it.height
        }
        walkSpriteSheet?.let {
            walkFrameWidth = it.width / walkFrameCount
            walkFrameHeight = it.height
        }
    }

    private fun loadSprite(customUri: String?, customAsset: String, defaultAsset: String): Bitmap {
        // Try user-picked sprite first (content URI from picker)
        if (customUri != null) {
            try {
                val uri = android.net.Uri.parse(customUri)
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

        // Try build-time custom asset bundled in APK
        try {
            context.assets.open(customAsset).use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                if (bmp != null) {
                    DebugLog.log("Overlay", "Loaded custom asset: $customAsset")
                    return bmp
                }
            }
        } catch (_: Exception) {}

        // Fall back to default
        return context.assets.open(defaultAsset).use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    // --- Animation update (called from animation runnable) ---

    fun update() {
        val now = System.currentTimeMillis()
        val elapsed = (now - startTime) / 1000.0

        when (state) {
            OverlayState.Idle -> drawIdle(elapsed)
            OverlayState.WalkingLeft, OverlayState.WalkingRight -> {
                handleWalking(now)
                drawWalking(now)
            }
        }
    }

    // --- Touch handling ---

    /**
     * Called on short tap. Tracks disturbance and triggers walk or escape.
     */
    fun handleTouch() {
        val now = System.currentTimeMillis()
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
        overlayView.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction(onComplete)
            .start()
    }

    // --- Walking ---

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

        val marginRight = (MARGIN_RIGHT_DP * density).toInt()
        walkTargetX = walkTargetX.coerceIn(marginRight, screenWidth - params.width - marginRight)
    }

    private fun triggerEscape() {
        escaping = true
        escapePhase = EscapePhase.Exit
        walkStartTime = System.currentTimeMillis()
        walkStartX = params.x

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

    private fun handleWalking(now: Long) {
        val walkDuration = WALK_FRAME_DURATION_MS * walkFrameCount
        val walkProgress = (now - walkStartTime).toFloat() / walkDuration

        if (walkProgress >= 1.0f) {
            params.x = walkTargetX

            if (escaping) {
                if (escapePhase == EscapePhase.Exit) {
                    escapePhase = EscapePhase.Enter
                    walkStartTime = now
                    val marginRight = (MARGIN_RIGHT_DP * density).toInt()
                    val walkDistance = (WALK_DISTANCE_DP * density).toInt()

                    if (position == OverlayPosition.Right) {
                        params.x = -params.width
                        walkStartX = -params.width
                        walkTargetX = marginRight + walkDistance
                        state = OverlayState.WalkingRight
                    } else {
                        params.x = screenWidth + params.width
                        walkStartX = screenWidth + params.width
                        walkTargetX = screenWidth - params.width - marginRight - walkDistance
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

            windowManager.updateViewLayout(overlayView, params)
        } else {
            val newX = walkStartX + ((walkTargetX - walkStartX) * walkProgress).toInt()
            params.x = newX
            windowManager.updateViewLayout(overlayView, params)
        }
    }

    // --- Drawing ---

    private fun drawIdle(t: Double) {
        val idleSheet = idleSpriteSheet ?: return
        val frame = ((t / (IDLE_FRAME_DURATION_MS / 1000.0)).toInt()) % idleFrameCount
        val scale = 1.0f + 0.01f * sin(2 * Math.PI * t).toFloat()
        val yOffset = (4.0 * sin(2 * Math.PI * t * 0.5)).toFloat()

        val viewWidth = params.width
        val viewHeight = params.height
        val outputBitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)

        val xOffset = (viewWidth - idleFrameWidth) / 2f
        val yCenter = (viewHeight - idleFrameHeight) / 2f

        canvas.save()
        canvas.translate(viewWidth / 2f, viewHeight / 2f + yOffset)
        canvas.scale(1f, scale)
        canvas.translate(-viewWidth / 2f, -viewHeight / 2f)

        val srcRect = Rect(frame * idleFrameWidth, 0, (frame + 1) * idleFrameWidth, idleFrameHeight)
        val dstRect = RectF(xOffset, yCenter, xOffset + idleFrameWidth, yCenter + idleFrameHeight)
        canvas.drawBitmap(idleSheet, srcRect, dstRect, null)
        canvas.restore()

        overlayView.setImageBitmap(outputBitmap)
    }

    private fun drawWalking(now: Long) {
        val walkSheet = walkSpriteSheet ?: return
        val walkElapsed = now - walkStartTime
        val frame = ((walkElapsed / WALK_FRAME_DURATION_MS).toInt()) % walkFrameCount

        var frameBitmap = Bitmap.createBitmap(
            walkSheet, frame * walkFrameWidth, 0, walkFrameWidth, walkFrameHeight
        )

        if (state == OverlayState.WalkingLeft) {
            val matrix = Matrix().apply {
                preScale(-1f, 1f)
                postTranslate(walkFrameWidth.toFloat(), 0f)
            }
            val mirrored = Bitmap.createBitmap(
                frameBitmap, 0, 0, frameBitmap.width, frameBitmap.height, matrix, true
            )
            frameBitmap.recycle()
            frameBitmap = mirrored
        }

        overlayView.setImageBitmap(frameBitmap)
    }

    // --- Utility ---

    /** View dimensions based on the larger of the two sprite sheets. */
    val viewWidth get() = maxOf(idleFrameWidth, walkFrameWidth)
    val viewHeight get() = maxOf(idleFrameHeight, walkFrameHeight)
    val marginRightPx get() = (MARGIN_RIGHT_DP * density).toInt()
    val marginBottomPx get() = (MARGIN_BOTTOM_DP * density).toInt()
    val walkDistancePx get() = (WALK_DISTANCE_DP * density).toInt()
}
