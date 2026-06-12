package com.starfarer.companionoverlay.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator
import com.starfarer.companionoverlay.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * Animated gold gesture affordances for the tutorial — shows *where and how* to touch the
 * sprite instead of relying on text alone. One full-sandbox overlay view; the active hint is
 * drawn anchored to the sprite view's live position, so it follows her as she walks (the
 * repeating animator already invalidates every frame — no extra tracking hook needed).
 *
 * Non-clickable: touches fall through to the sprite underneath. All geometry derives from a
 * single phase animator; Paints/Path are pre-allocated (no per-frame allocations, matching
 * [com.starfarer.companionoverlay.SpriteAnimator]'s convention).
 */
@SuppressLint("ViewConstructor")
class GestureHintView(context: Context) : View(context) {

    enum class Hint { TAP, LONG_PRESS_UPPER, LONG_PRESS_LOWER, SWIPE_UP }

    private companion object {
        const val TAP_CYCLE_MS = 1200L
        const val PRESS_CYCLE_MS = 1400L
        const val SWIPE_CYCLE_MS = 1400L
        const val FADE_MS = 200L
        /** Glow + fade tail of the long-press cycle, after the arc finishes filling. */
        const val PRESS_GLOW_MS = 250L
        const val PRESS_FADE_MS = 250L
    }

    private val density = resources.displayMetrics.density

    private var hint: Hint? = null
    private var anchor: View? = null
    private var phase = 0f
    private var animator: ValueAnimator? = null

    /** Fraction of the long-press cycle spent filling the arc — teaches the real hold time. */
    private var pressFillFrac = 0.36f

    private val gold = context.getColor(R.color.gold)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        color = gold
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = gold
    }
    private val arcBounds = android.graphics.RectF()

    /** Up-pointing chevron, built once: 22dp wide, 10dp tall, origin at the apex. */
    private val chevron = Path().apply {
        val w = 11f * density
        val h = 10f * density
        moveTo(-w, h)
        lineTo(0f, 0f)
        lineTo(w, h)
    }

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    /**
     * Fade in and start the repeating hint. [fillDurationMs] only matters for the long-press
     * hints — pass the production long-press timeout so the ring fills in exactly that time.
     */
    fun show(hint: Hint, anchor: View, fillDurationMs: Long = 0L) {
        this.hint = hint
        this.anchor = anchor
        if (fillDurationMs > 0) pressFillFrac = fillDurationMs.toFloat() / PRESS_CYCLE_MS

        animator?.cancel()
        val cycle = when (hint) {
            Hint.TAP -> TAP_CYCLE_MS
            Hint.LONG_PRESS_UPPER, Hint.LONG_PRESS_LOWER -> PRESS_CYCLE_MS
            Hint.SWIPE_UP -> SWIPE_CYCLE_MS
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = cycle
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        animate().cancel()
        alpha = 0f
        visibility = VISIBLE
        animate().alpha(1f).setDuration(FADE_MS).start()
    }

    /** Stop and fade out (or vanish instantly on step teardown). Safe when already hidden. */
    fun hide(animated: Boolean = true) {
        if (visibility == GONE) return
        animator?.cancel()
        animator = null
        animate().cancel()
        if (animated) {
            animate().alpha(0f).setDuration(FADE_MS)
                .withEndAction { visibility = GONE }.start()
        } else {
            visibility = GONE
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        val a = anchor ?: return
        val cx = a.x + a.width / 2f
        when (hint) {
            Hint.TAP -> drawTap(canvas, cx, a.y + a.height * 0.5f)
            Hint.LONG_PRESS_UPPER -> drawPress(canvas, cx, a.y + a.height / 3f)
            Hint.LONG_PRESS_LOWER -> drawPress(canvas, cx, a.y + a.height * 5f / 6f)
            Hint.SWIPE_UP -> drawSwipe(canvas, cx, a.y + a.height * 0.6f)
            null -> {}
        }
    }

    /** Two staggered expanding ripples + a softly pulsing center dot. */
    private fun drawTap(canvas: Canvas, cx: Float, cy: Float) {
        var p = phase
        repeat(2) {
            stroke.alpha = (204 * (1f - p)).toInt()
            canvas.drawCircle(cx, cy, (8f + 28f * p) * density, stroke)
            p = (p + 0.5f) % 1f
        }
        fill.alpha = (128 + 76 * sin(2 * PI * phase)).toInt()
        canvas.drawCircle(cx, cy, 4f * density, fill)
    }

    /** A ring whose arc fills over the real hold duration, glows, then fades. */
    private fun drawPress(canvas: Canvas, cx: Float, cy: Float) {
        val r = 22f * density
        arcBounds.set(cx - r, cy - r, cx + r, cy + r)
        val glowEnd = pressFillFrac + PRESS_GLOW_MS.toFloat() / PRESS_CYCLE_MS
        val fadeEnd = glowEnd + PRESS_FADE_MS.toFloat() / PRESS_CYCLE_MS
        when {
            phase < pressFillFrac -> {
                stroke.alpha = 230
                canvas.drawArc(arcBounds, -90f, 360f * (phase / pressFillFrac), false, stroke)
            }
            phase < glowEnd -> {
                fill.alpha = 64
                canvas.drawCircle(cx, cy, r, fill)
                stroke.alpha = 230
                canvas.drawCircle(cx, cy, r, stroke)
            }
            phase < fadeEnd -> {
                val out = 1f - (phase - glowEnd) / (fadeEnd - glowEnd)
                stroke.alpha = (230 * out).toInt()
                canvas.drawCircle(cx, cy, r, stroke)
            }
            // Rest of the cycle: nothing — a beat of quiet before the next demonstration.
        }
    }

    /** Three chevrons rising and fading in sequence above her body. */
    private fun drawSwipe(canvas: Canvas, cx: Float, baseY: Float) {
        for (i in 0..2) {
            val local = (phase - i * 0.18f + 1f) % 1f
            if (local >= 0.7f) continue
            stroke.alpha = (230 * sin(PI * local / 0.7f)).toInt()
            canvas.save()
            canvas.translate(cx, baseY - (i * 18f + 10f * (local / 0.7f)) * density)
            canvas.drawPath(chevron, stroke)
            canvas.restore()
        }
    }
}
