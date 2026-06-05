package com.starfarer.companionoverlay

import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Where the sprite lives and how it's moved — the seam between [SpriteAnimator]'s animation
 * state machine and the surface it's drawn on, mirroring [BubbleSurface].
 *
 * Two implementations:
 * - [OverlaySpriteSurface] drives the real `TYPE_APPLICATION_OVERLAY` window (position via
 *   `updateViewLayout`, ghost mode via window flags) — behavior identical to the original
 *   direct-[WindowManager] code.
 * - [ViewGroupSpriteSurface] drives a plain [View] inside a [FrameLayout] (position via
 *   `view.x`) — for the in-app tutorial / dev harness, no overlay permission needed.
 *
 * [SpriteAnimator] talks only to this interface and never names [WindowManager] itself.
 */
interface SpriteSurface {
    /** The view rendered into (cast to ImageView for `setImageBitmap`, animated for alpha). */
    val view: View
    /** Sprite view width — used for walk/escape geometry. */
    val spriteWidth: Int
    /** Sprite view height — used for idle-frame centering. */
    val spriteHeight: Int
    /** Logical sprite-left position. Mutated by the walk/escape math. */
    var x: Int
    /** Push the current [x] onto the surface. */
    fun commitPosition()
    /** Apply ghost mode (alpha fade + touchability). Returns true if it took effect. */
    fun setGhost(ghost: Boolean): Boolean
}

/**
 * Real overlay surface — position and ghost flags applied through [WindowManager], reproducing
 * the original [SpriteAnimator] behavior exactly (including the ghost flag-restore on failure).
 */
class OverlaySpriteSurface(
    private val windowManager: WindowManager,
    override val view: View,
    private val params: WindowManager.LayoutParams
) : SpriteSurface {

    override val spriteWidth: Int get() = params.width
    override val spriteHeight: Int get() = params.height

    override var x: Int
        get() = params.x
        set(value) { params.x = value }

    override fun commitPosition() {
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            DebugLog.log("Sprite", "updateViewLayout failed: ${e.message}")
        }
    }

    override fun setGhost(ghost: Boolean): Boolean {
        val oldFlags = params.flags
        if (ghost) {
            view.animate().alpha(0.5f).setDuration(200).start()
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            view.animate().alpha(1f).setDuration(200).start()
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        return try {
            windowManager.updateViewLayout(view, params)
            true
        } catch (e: Exception) {
            params.flags = oldFlags
            DebugLog.log("Sprite", "updateViewLayout failed: ${e.message}")
            false
        }
    }
}

/**
 * In-app surface — the sprite is a plain [View] child of a [FrameLayout]. Position is applied
 * with `view.x`; ghost mode is alpha-only (no window flags to toggle). Used by the tutorial.
 */
class ViewGroupSpriteSurface(
    override val view: View,
    override val spriteWidth: Int,
    override val spriteHeight: Int,
    initialX: Int
) : SpriteSurface {

    override var x: Int = initialX

    override fun commitPosition() {
        view.x = x.toFloat()
    }

    override fun setGhost(ghost: Boolean): Boolean {
        view.animate().alpha(if (ghost) 0.5f else 1f).setDuration(200).start()
        return true
    }
}
