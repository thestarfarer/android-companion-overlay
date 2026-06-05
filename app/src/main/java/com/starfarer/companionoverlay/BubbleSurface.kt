package com.starfarer.companionoverlay

import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Where a bubble view gets placed and removed — the seam between [BubbleManager]'s
 * view-building and the surface it lives on.
 *
 * Two implementations:
 * - [OverlayBubbleSurface] puts bubbles in their own `TYPE_APPLICATION_OVERLAY` windows
 *   (the real overlay), reproducing the original [BubbleStyle] params exactly.
 * - [ViewGroupBubbleSurface] adds them into a plain [FrameLayout] — for the in-app tutorial
 *   / dev harness, where no overlay permission or [WindowManager] is involved.
 *
 * [BubbleManager] talks only to this interface and never names [WindowManager] itself.
 */
interface BubbleSurface {

    /** Place [view] on the surface at the given [placement]. */
    fun attach(view: View, placement: BubblePlacement, maxWidth: Int = 0)

    /**
     * Make [view] able to receive keyboard focus (for the reply input). On an overlay this
     * promotes the window out of `FLAG_NOT_FOCUSABLE`; in a normal view hierarchy it's a no-op
     * because the view focuses on its own. Returns true if a promotion actually happened (the
     * caller skips re-running the focus handoff if not needed).
     */
    fun makeFocusable(view: View): Boolean

    /** Remove [view] from the surface. Safe to call if already detached. */
    fun detach(view: View)
}

/** Anchor positions a bubble can request, independent of the surface backing it. */
enum class BubblePlacement {
    /** Edge-anchored toast: top-right, flat against the right edge. */
    TOP_RIGHT_TOAST,
    /** Centered dialog, width-capped, raises above the keyboard when typing. */
    CENTERED_DIALOG,
}

/**
 * Real overlay surface — one `TYPE_APPLICATION_OVERLAY` window per bubble, mirroring the
 * original direct-[WindowManager] behavior of [BubbleManager].
 */
class OverlayBubbleSurface(
    private val windowManager: WindowManager,
    private val density: Float
) : BubbleSurface {

    /** Per-view params, kept so we can mutate flags for the focus promotion. */
    private val params = HashMap<View, WindowManager.LayoutParams>()

    override fun attach(view: View, placement: BubblePlacement, maxWidth: Int) {
        val lp = when (placement) {
            BubblePlacement.TOP_RIGHT_TOAST -> BubbleStyle.topRightEdgeParams(density)
            BubblePlacement.CENTERED_DIALOG -> BubbleStyle.centeredParams(maxWidth)
        }
        windowManager.addView(view, lp)
        params[view] = lp
    }

    override fun makeFocusable(view: View): Boolean {
        val lp = params[view] ?: return false
        if (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) return false
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        try { windowManager.updateViewLayout(view, lp) } catch (_: Exception) {}
        return true
    }

    override fun detach(view: View) {
        params.remove(view)
        try { windowManager.removeView(view) } catch (_: Exception) {}
    }
}

/**
 * In-app surface — bubbles are added as children of a host [FrameLayout]. Used by the tutorial
 * / dev harness so the real bubble views render with no overlay permission and no service.
 */
class ViewGroupBubbleSurface(
    private val host: FrameLayout,
    private val density: Float
) : BubbleSurface {

    override fun attach(view: View, placement: BubblePlacement, maxWidth: Int) {
        val lp = when (placement) {
            BubblePlacement.TOP_RIGHT_TOAST -> FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { topMargin = (60 * density).toInt() }
            BubblePlacement.CENTERED_DIALOG -> FrameLayout.LayoutParams(
                if (maxWidth > 0) maxWidth else FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        host.addView(view, lp)
    }

    // A normal EditText in an Activity focuses and raises the keyboard on its own.
    override fun makeFocusable(view: View): Boolean = false

    override fun detach(view: View) {
        (view.parent as? FrameLayout)?.removeView(view)
    }
}
