package com.starfarer.companionoverlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.starfarer.companionoverlay.repository.CaptureMode
import com.starfarer.companionoverlay.repository.SettingsRepository
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The radial quick-access menu: a material-styled **donut** (hollow center) split into three
 * separate annular segments — one per toggle — with transparent gaps between them. Anchored to
 * the right edge, the arc opens leftward.
 *
 * Toggles write straight to [SettingsRepository] (live prefs reads, so changes apply
 * immediately) and the disk redraws in place — it stays open across taps. A tap on empty space
 * (the hole, a gap, or outside the window) asks [onRequestClose].
 */
@SuppressLint("ViewConstructor")
class RadialMenuView(
    context: Context,
    private val settings: SettingsRepository,
    private val onRequestClose: () -> Unit
) : View(context) {

    private companion object {
        const val PRESS_SCALE = 0.8f   // petal shrink on press
    }

    private val density = resources.displayMetrics.density
    private val padPx = RadialMenuManager.PAD_DP * density

    // Arc on the left side (screen coords: 0°=right, +clockwise). 100°..260° = lower-left → upper-left.
    private val arcStart = 100f
    private val arcTotal = 160f
    private val innerRatio = 0.45f
    private val segSweep = arcTotal / 3f
    // Separators are a constant physical width: the angular inset shrinks with radius.
    private val gapHalfPx = 3f * density + 0.5f   // half of a 6dp gap, +1px total

    // Buttons in arc order (bottom → top), each owning one segment.
    private enum class Button(val segIndex: Int) {
        VOICE(0), VOLUME(1), CAPTURE(2),
    }

    private val colors = BubbleStyle.colors(context)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.bgWithAlpha }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 19.2f * density   // 0.8 × 24
    }
    private val phonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = colors.text
        strokeWidth = 2f * density
    }

    private val path = Path()
    private val outerRect = RectF()
    private val innerRect = RectF()

    // Press feedback: the touched petal shrinks slightly toward its own center, springs back.
    private var pressedButton: Button? = null
    private var pressScale = 1f
    private var pressAnimator: ValueAnimator? = null

    private val cx get() = width.toFloat()
    private val cy get() = height / 2f
    private val outerRadius get() = width - padPx
    private val innerRadius get() = outerRadius * innerRatio
    private val iconRadius get() = (innerRadius + outerRadius) / 2f

    private fun segStart(i: Int) = arcStart + i * segSweep
    private fun midAngle(i: Int) = arcStart + (i + 0.5f) * segSweep

    override fun onDraw(canvas: Canvas) {
        for (button in Button.entries) {
            val pressed = button == pressedButton && pressScale != 1f
            if (pressed) {
                val rad = Math.toRadians(midAngle(button.segIndex).toDouble())
                val px = cx + iconRadius * cos(rad).toFloat()
                val py = cy + iconRadius * sin(rad).toFloat()
                canvas.save()
                canvas.scale(pressScale, pressScale, px, py)
            }
            drawSegment(canvas, button.segIndex)
            drawGlyph(canvas, button)
            if (pressed) canvas.restore()
        }
    }

    /**
     * Filled annular sector (donut slice) with constant-width gaps. Each shared boundary is
     * inset by `asin(halfGap / r)`, larger near the hole and smaller at the rim, so the two
     * straight edges flanking a gap are parallel — a uniform-width separator. The arc's outer
     * ends (the menu's start/finish) are not inset.
     */
    private fun drawSegment(canvas: Canvas, i: Int) {
        val o = outerRadius
        val inr = innerRadius
        outerRect.set(cx - o, cy - o, cx + o, cy + o)
        innerRect.set(cx - inr, cy - inr, cx + inr, cy + inr)

        val dO = Math.toDegrees(kotlin.math.asin((gapHalfPx / o).toDouble())).toFloat()
        val dI = Math.toDegrees(kotlin.math.asin((gapHalfPx / inr).toDouble())).toFloat()
        val lo = segStart(i)
        val hi = lo + segSweep
        val loGap = i > 0
        val hiGap = i < Button.entries.size - 1

        val oLo = lo + if (loGap) dO else 0f
        val oHi = hi - if (hiGap) dO else 0f
        val iLo = lo + if (loGap) dI else 0f
        val iHi = hi - if (hiGap) dI else 0f

        path.reset()
        path.arcTo(outerRect, oLo, oHi - oLo, true)   // outer arc lo → hi
        path.arcTo(innerRect, iHi, iLo - iHi, false)  // line to inner-hi, inner arc hi → lo
        path.close()                                  // straight edge inner-lo → outer-lo
        canvas.drawPath(path, fillPaint)
    }

    private fun drawGlyph(canvas: Canvas, button: Button) {
        val rad = Math.toRadians(midAngle(button.segIndex).toDouble())
        val x = cx + iconRadius * cos(rad).toFloat()
        val y = cy + iconRadius * sin(rad).toFloat()

        when (button) {
            Button.CAPTURE -> when (settings.captureMode) {
                CaptureMode.OFF -> emoji(canvas, "🚫", x, y, bright = true)        // 🚫
                CaptureMode.SCREENSHOT -> phoneSlate(canvas, x, y)                  // drawn slate phone
                CaptureMode.CAMERA -> emoji(canvas, "📷", x, y, bright = true)      // 📷
            }
            Button.VOLUME -> emoji(canvas, "🔊", x, y, bright = settings.volumeToggleEnabled)
            Button.VOICE -> emoji(canvas, "🗣", x, y, bright = settings.ttsEnabled)
        }
    }

    private fun emoji(canvas: Canvas, glyph: String, x: Float, y: Float, bright: Boolean) {
        glyphPaint.alpha = if (bright) 255 else 70
        val fm = glyphPaint.fontMetrics
        canvas.drawText(glyph, x, y - (fm.ascent + fm.descent) / 2f, glyphPaint)
    }

    /** A clean modern slate phone outline (portrait rounded rectangle). */
    private fun phoneSlate(canvas: Canvas, x: Float, y: Float) {
        val h = 17.6f * density   // 0.8 × 22
        val w = h * 0.56f
        val r = w * 0.22f
        canvas.drawRoundRect(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f, r, r, phonePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> onRequestClose()
            MotionEvent.ACTION_DOWN -> {
                buttonAt(event.x, event.y)?.let { pressDown(it) }
            }
            MotionEvent.ACTION_MOVE -> {
                // If the finger slides off the pressed petal, release the press feedback.
                if (pressedButton != null && buttonAt(event.x, event.y) != pressedButton) pressUp()
            }
            MotionEvent.ACTION_UP -> {
                val hit = buttonAt(event.x, event.y)
                pressUp()
                if (hit != null) toggle(hit) else onRequestClose()
            }
            MotionEvent.ACTION_CANCEL -> pressUp()
        }
        return true
    }

    private fun pressDown(button: Button) {
        pressedButton = button
        animatePressTo(PRESS_SCALE)
    }

    private fun pressUp() {
        if (pressedButton == null) return
        animatePressTo(1f) { pressedButton = null }
    }

    private fun animatePressTo(target: Float, onEnd: (() -> Unit)? = null) {
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressScale, target).apply {
            duration = 90
            addUpdateListener {
                pressScale = it.animatedValue as Float
                invalidate()
            }
            if (onEnd != null) addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd()
                    invalidate()
                }
            })
            start()
        }
    }

    /** Which button (if any) the point lands on — within the donut band and a segment's wedge. */
    private fun buttonAt(x: Float, y: Float): Button? {
        val dist = hypot(x - cx, y - cy)
        if (dist < innerRadius || dist > outerRadius * 1.05f) return null
        val angle = ((Math.toDegrees(atan2(y - cy, x - cx).toDouble()) + 360.0) % 360.0).toFloat()
        return Button.entries.firstOrNull { b ->
            val s = segStart(b.segIndex)
            angle >= s && angle <= s + segSweep
        }
    }

    private fun toggle(button: Button) {
        when (button) {
            Button.CAPTURE -> settings.captureMode = settings.captureMode.next()
            Button.VOLUME -> settings.volumeToggleEnabled = !settings.volumeToggleEnabled
            Button.VOICE -> settings.ttsEnabled = !settings.ttsEnabled
        }
        invalidate()
    }
}
