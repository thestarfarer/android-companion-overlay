package com.starfarer.companionoverlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import com.starfarer.companionoverlay.repository.SettingsRepository

/**
 * Owns the radial quick-access menu's overlay window — add/remove/animate — mirroring
 * [BubbleManager]'s pattern. The disk itself (drawing + toggles) is [RadialMenuView].
 *
 * Opened by a swipe-up on the avatar and closed by a swipe-down, a tap outside, or a tap
 * on empty disk space. Anchored to the center-right edge.
 */
class RadialMenuManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val settings: SettingsRepository
) {

    companion object {
        private const val TAG = "RadialMenu"
        private const val RADIUS_DP = 96f    // 0.8 × 120
        private const val PAD_DP = 9.6f      // 0.8 × 12
    }

    private val density = context.resources.displayMetrics.density

    // Kept non-null through the close fade so open() can't stack a second
    // window on top of one still animating out; `closing` tracks that fade.
    private var menuView: RadialMenuView? = null
    private var closing = false

    val isOpen: Boolean get() = menuView != null && !closing

    fun open() {
        val existing = menuView
        if (existing != null) {
            // A close fade is still running — cancel it and fade back in rather
            // than adding a second window (the old close-during-fade had no
            // reopen guard and dropped the request).
            if (closing) {
                closing = false
                existing.animate().cancel()
                existing.animate().alpha(1f).setDuration(150).start()
            }
            return
        }

        val view = RadialMenuView(context, settings, onRequestClose = { close() })
        val params = WindowManager.LayoutParams(
            ((RADIUS_DP + PAD_DP) * density).toInt(),
            ((2 * RADIUS_DP + 2 * PAD_DP) * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
        }

        try {
            view.alpha = 0f
            windowManager.addView(view, params)
            view.animate().alpha(1f).setDuration(150).start()
            menuView = view
            closing = false
        } catch (e: Exception) {
            DebugLog.log(TAG, "Failed to open: ${e.message}")
        }
    }

    fun close() {
        val view = menuView ?: return
        if (closing) return
        closing = true
        view.animate().alpha(0f).setDuration(120).withEndAction {
            // Only remove if still closing the same view — open() may have
            // revived it during the fade.
            if (closing && menuView === view) {
                try { windowManager.removeView(view) } catch (_: Exception) {}
                menuView = null
                closing = false
            }
        }.start()
    }

    fun toggle() {
        if (isOpen) close() else open()
    }
}
