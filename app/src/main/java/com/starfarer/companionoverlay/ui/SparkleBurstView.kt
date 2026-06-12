package com.starfarer.companionoverlay.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.starfarer.companionoverlay.R
import kotlin.random.Random

/**
 * One-second gold sparkle burst for the tutorial finale. Particles are pre-allocated and
 * positions are computed analytically from the animation time (p = p₀ + v·t + ½g·t²), so
 * [onDraw] allocates nothing — matching the sprite renderer's no-per-frame-allocation rule.
 */
@SuppressLint("ViewConstructor")
class SparkleBurstView(context: Context) : View(context) {

    private companion object {
        const val COUNT = 26
        const val STAR_COUNT = 8
        const val DURATION_MS = 1000L
        const val GRAVITY_DP = 900f   // px/s² after density scaling
    }

    private class Particle {
        var x0 = 0f; var y0 = 0f
        var vx = 0f; var vy = 0f
        var size = 0f
        var star = false
        var color = 0
    }

    private val density = resources.displayMetrics.density
    private val particles = Array(COUNT) { Particle() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private var animator: ValueAnimator? = null

    private val gold = context.getColor(R.color.gold)
    private val goldVariant = context.getColor(R.color.gold_variant)

    /** Four-point star, built once, drawn via translate/rotate. Unit ≈ 1dp radius. */
    private val starPath = Path().apply {
        val r = density
        moveTo(0f, -r)
        quadTo(0.18f * r, -0.18f * r, r, 0f)
        quadTo(0.18f * r, 0.18f * r, 0f, r)
        quadTo(-0.18f * r, 0.18f * r, -r, 0f)
        quadTo(-0.18f * r, -0.18f * r, 0f, -r)
    }

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    /** Reseed and fire a burst centered on (cx, cy). Restarts cleanly if already running. */
    fun burst(cx: Float, cy: Float) {
        particles.forEachIndexed { i, p ->
            val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
            val speed = (200f + Random.nextFloat() * 250f) * density
            p.x0 = cx
            p.y0 = cy
            p.vx = speed * kotlin.math.cos(angle)
            // Bias upward: sparks should fountain, not splat.
            p.vy = speed * kotlin.math.sin(angle) - 150f * density
            p.star = i < STAR_COUNT
            p.size = if (p.star) (5f + Random.nextFloat() * 2f) else (2f + Random.nextFloat() * 2f)
            p.color = if (Random.nextBoolean()) gold else goldVariant
        }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = GONE
                }
            })
            start()
        }
        visibility = VISIBLE
    }

    fun cancel() {
        animator?.cancel()
        animator = null
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        if (visibility != VISIBLE) return
        val secs = t * DURATION_MS / 1000f
        val gravity = GRAVITY_DP * density
        val fade = (1f - t) * (1f - t)
        for (p in particles) {
            val x = p.x0 + p.vx * secs
            val y = p.y0 + p.vy * secs + 0.5f * gravity * secs * secs
            paint.color = p.color
            paint.alpha = (255 * fade).toInt()
            if (p.star) {
                canvas.save()
                canvas.translate(x, y)
                canvas.rotate(t * 180f)
                canvas.scale(p.size, p.size)
                canvas.drawPath(starPath, paint)
                canvas.restore()
            } else {
                canvas.drawCircle(x, y, p.size * density, paint)
            }
        }
    }
}
