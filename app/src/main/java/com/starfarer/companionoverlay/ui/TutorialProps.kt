package com.starfarer.companionoverlay.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.starfarer.companionoverlay.R

/**
 * Background props for the tutorial stage — set dressing that gives the demos a visible
 * cause: a keyboard for ghost mode to react to, an app window for the capture demo to
 * "look at". Pure Canvas, theme-colored, no text, no touch (clicks pass through).
 */

/**
 * A generic soft-keyboard facsimile that slides up from the bottom edge. Drawn as a
 * panel with three rows of blank keys plus a space-bar row — recognizably "a keyboard"
 * without imitating any particular IME.
 *
 * [startTyping] runs a phantom-typing simulation: keys press in a loose human cadence,
 * and every third press lands on the top-row key directly behind the anchor view (the
 * sprite) — the visible proof that taps pass through her in ghost mode.
 */
@SuppressLint("ViewConstructor")
class MockKeyboardView(context: Context) : View(context) {

    private companion object {
        const val PRESS_HOLD_MS = 130L
        const val ANCHOR_EVERY_NTH_PRESS = 3
        /** Keys in the top row — the row her body overlaps. */
        const val TOP_ROW_KEYS = 10
        /** Unhurried cadence — a press storm reads as chaos, not typing. */
        const val PRESS_GAP_MIN_MS = 500L
        const val PRESS_GAP_MAX_MS = 850L
    }

    private val density = resources.displayMetrics.density
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.tutorial_kbd_panel)
    }
    private val key = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.tutorial_kbd_key)
    }
    private val keyPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Blend toward mid-grey: darker on the light key, lighter on the dark one.
        color = androidx.core.graphics.ColorUtils.blendARGB(
            context.getColor(R.color.tutorial_kbd_key), 0xFF888888.toInt(), 0.35f
        )
    }
    private val r = RectF()

    /** Bottom row: small keys flanking a space bar. */
    private val bottomWeights = floatArrayOf(1.2f, 1.2f, 4.8f, 1.2f, 1.2f)

    /** Key geometry, built once per size; index 0..9 is the top row. */
    private val keyRects = ArrayList<RectF>()

    private var pressedIndex = -1
    private var pressCount = 0
    private var typing = false
    private var typingAnchor: View? = null
    private var userPressing = false

    /** Set to make the keys user-tappable; invoked once per completed key press. */
    var onUserKeyPress: (() -> Unit)? = null

    private val releaseRunnable = Runnable {
        pressedIndex = -1
        invalidate()
    }

    private val typeRunnable = object : Runnable {
        override fun run() {
            if (!typing || keyRects.isEmpty()) return
            pressCount++
            pressedIndex = if (pressCount % ANCHOR_EVERY_NTH_PRESS == 0) keyBehindAnchor()
                else keyRects.indices.random()
            invalidate()
            postDelayed(releaseRunnable, PRESS_HOLD_MS)
            postDelayed(this, (PRESS_GAP_MIN_MS..PRESS_GAP_MAX_MS).random())
        }
    }

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Start the phantom typing; [anchor] is the sprite whose position draws the pointed presses. */
    fun startTyping(anchor: View) {
        typingAnchor = anchor
        if (typing) return
        typing = true
        postDelayed(typeRunnable, 350L)
    }

    fun stopTyping() {
        typing = false
        removeCallbacks(typeRunnable)
        removeCallbacks(releaseRunnable)
        pressedIndex = -1
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopTyping()
    }

    /**
     * Real key presses — the ghost-mode page lets the user type through her. A press
     * lights the key under the finger and reports up on release.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onUserKeyPress == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val i = keyRects.indexOfFirst { it.contains(event.x, event.y) }
                if (i < 0) return false
                userPressing = true
                removeCallbacks(releaseRunnable)
                pressedIndex = i
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (userPressing) {
                    userPressing = false
                    postDelayed(releaseRunnable, PRESS_HOLD_MS)
                    performClick()
                    onUserKeyPress?.invoke()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                userPressing = false
                pressedIndex = -1
                invalidate()
            }
        }
        return true
    }

    /** Top-row key whose center is nearest the anchor's center x (shared coordinate space). */
    private fun keyBehindAnchor(): Int {
        val a = typingAnchor ?: return keyRects.indices.random()
        val cx = a.x + a.width / 2f - x
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (i in 0 until minOf(TOP_ROW_KEYS, keyRects.size)) {
            val d = kotlin.math.abs(keyRects[i].centerX() - cx)
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        keyRects.clear()
        val pad = 8 * density
        val gap = 6 * density
        val keyH = (h - pad * 2 - gap * 3) / 4
        var top = pad
        intArrayOf(10, 9, 7).forEach { n ->
            val keyW = (w - pad * 2 - gap * (n - 1)) / n
            var left = pad
            repeat(n) {
                keyRects.add(RectF(left, top, left + keyW, top + keyH))
                left += keyW + gap
            }
            top += keyH + gap
        }
        val unit = (w - pad * 2 - gap * (bottomWeights.size - 1)) / bottomWeights.sum()
        var left = pad
        bottomWeights.forEach { wt ->
            keyRects.add(RectF(left, top, left + unit * wt, top + keyH))
            left += unit * wt + gap
        }
    }

    override fun onDraw(canvas: Canvas) {
        val rad = 16 * density
        // Bottom corners pushed past the view edge — only the top reads as rounded.
        r.set(0f, 0f, width.toFloat(), height + rad)
        canvas.drawRoundRect(r, rad, rad, panel)

        val keyRad = 6 * density
        keyRects.forEachIndexed { i, kr ->
            canvas.drawRoundRect(kr, keyRad, keyRad, if (i == pressedIndex) keyPressed else key)
        }
    }
}

/**
 * A small abstract code-editor window — the "something on screen" the capture demo looks
 * at (her canned reply comments on a code editor). Traffic-light window dots and a few
 * syntax-tinted line blobs; deliberately abstract so it never needs localized text.
 */
@SuppressLint("ViewConstructor")
class MockAppWindowView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.surface)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1 * density
        color = context.getColor(R.color.card_stroke)
    }
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    private val dotColors = intArrayOf(
        context.getColor(R.color.status_error),
        context.getColor(R.color.status_warning),
        context.getColor(R.color.status_connected),
    )

    private val keywordColor = context.getColor(R.color.gold)
    private val plainColor = context.getColor(R.color.text_hint)
    private val stringColor = context.getColor(R.color.status_connected)

    /** "Code" lines: indent fraction, width fraction, color. */
    private val lines = arrayOf(
        Triple(0.00f, 0.55f, keywordColor),
        Triple(0.08f, 0.70f, plainColor),
        Triple(0.16f, 0.45f, stringColor),
        Triple(0.08f, 0.62f, plainColor),
        Triple(0.08f, 0.30f, plainColor),
        Triple(0.00f, 0.48f, keywordColor),
    )

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rad = 12 * density
        r.set(0.5f * density, 0.5f * density, w - 0.5f * density, h - 0.5f * density)
        canvas.drawRoundRect(r, rad, rad, bg)
        canvas.drawRoundRect(r, rad, rad, stroke)

        // Window dots.
        val dotR = 3 * density
        val dotY = 12 * density
        dotColors.forEachIndexed { i, c ->
            blob.color = c
            blob.alpha = 160
            canvas.drawCircle(14 * density + i * 12 * density, dotY, dotR, blob)
        }

        // Muted syntax-blob lines.
        val pad = 14 * density
        val lineH = 6 * density
        val lineGap = 11 * density
        var top = 24 * density
        for ((indent, frac, color) in lines) {
            if (top + lineH > h - pad / 2) break
            blob.color = color
            blob.alpha = 110
            val left = pad + indent * w
            r.set(left, top, left + frac * w, top + lineH)
            canvas.drawRoundRect(r, lineH / 2, lineH / 2, blob)
            top += lineH + lineGap
        }
    }
}

/**
 * The ghost page's keyboard toggle — same edge placement and visual language as
 * [MockVolumeKeyView], with a drawn keyboard glyph (a stroked mini-keyboard stays crisp
 * where a font glyph goes grainy). Lightly gold-filled while the keyboard is up.
 */
@SuppressLint("ViewConstructor")
class MockKeyboardToggleView(
    context: Context,
    private val onPress: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.surface)
    }
    private val activeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.gold)
        alpha = 36
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = context.getColor(R.color.gold)
    }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.gold)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.gold)
    }
    private val r = RectF()

    /** Keyboard currently up — shown as a light gold fill. */
    var active = false
        set(value) {
            field = value
            invalidate()
        }

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val inset = 1.5f * density
        r.set(inset, inset, width - inset, height - inset)
        val rad = 12 * density
        canvas.drawRoundRect(r, rad, rad, bg)
        if (active) canvas.drawRoundRect(r, rad, rad, activeFill)
        canvas.drawRoundRect(r, rad, rad, stroke)

        // Mini keyboard: outline, two rows of key dots, a space-bar line.
        val cx = width / 2f
        val cy = height / 2f
        val kw = 22 * density
        val kh = 14 * density
        r.set(cx - kw / 2, cy - kh / 2, cx + kw / 2, cy + kh / 2)
        canvas.drawRoundRect(r, 3 * density, 3 * density, mark)
        val dotR = 0.9f * density
        for (row in 0..1) {
            val y = cy - kh / 2 + (3.5f + row * 3.5f) * density
            for (col in 0..2) {
                canvas.drawCircle(cx + (col - 1) * 5.5f * density, y, dotR, dot)
            }
        }
        val spaceY = cy + kh / 2 - 3 * density
        canvas.drawLine(cx - 4 * density, spaceY, cx + 4 * density, spaceY, mark)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                animate().scaleX(0.88f).scaleY(0.88f).setDuration(60L).start()
            MotionEvent.ACTION_UP -> {
                animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
                performClick()
                onPress()
            }
            MotionEvent.ACTION_CANCEL ->
                animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
        }
        return true
    }
}

/**
 * A tappable stand-in for the phone's physical Volume Down key — a gold-outlined
 * vertical pill at the screen edge with a minus mark. The host counts presses with the
 * production double/triple-press window, so the rhythm the user practices is the real one.
 */
@SuppressLint("ViewConstructor")
class MockVolumeKeyView(
    context: Context,
    private val onPress: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.surface)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = context.getColor(R.color.gold)
    }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.gold)
    }
    private val r = RectF()

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val inset = 1.5f * density
        r.set(inset, inset, width - inset, height - inset)
        val rad = (width - 2 * inset) / 2f
        canvas.drawRoundRect(r, rad, rad, bg)
        canvas.drawRoundRect(r, rad, rad, stroke)
        val half = 5f * density
        canvas.drawLine(width / 2f - half, height / 2f, width / 2f + half, height / 2f, mark)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                animate().scaleX(0.88f).scaleY(0.88f).setDuration(60L).start()
            MotionEvent.ACTION_UP -> {
                animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
                performClick()
                onPress()
            }
            MotionEvent.ACTION_CANCEL ->
                animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
        }
        return true
    }
}
