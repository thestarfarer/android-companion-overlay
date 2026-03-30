package com.starfarer.companionoverlay.avatar3d

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.TextureView
import android.view.WindowManager

/**
 * Manages the 3D avatar overlay using Filament.
 *
 * Creates a TextureView, adds it to WindowManager with TYPE_APPLICATION_OVERLAY
 * (same mechanism as sprite mode), and starts the Filament renderer.
 *
 * No Activity. No Fragment. No green screen. Just a TextureView overlay
 * with native transparent rendering.
 */
class FilamentOverlayManager(private val context: Context) {

    companion object {
        private const val AVATAR_WIDTH_DP = 200
        private const val AVATAR_HEIGHT_DP = 300
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var renderer: FilamentAvatarRenderer? = null
    private var surfaceView: TextureView? = null
    private var isActive = false

    fun show(screenWidth: Int, screenHeight: Int) {
        if (isActive) return

        val density = context.resources.displayMetrics.density
        val widthPx = (AVATAR_WIDTH_DP * density).toInt()
        val heightPx = (AVATAR_HEIGHT_DP * density).toInt()

        // Create the Filament renderer and get its TextureView
        renderer = FilamentAvatarRenderer(context).also {
            surfaceView = it.createTextureView()
        }

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Position bottom-right, matching sprite default
            x = screenWidth - widthPx - (40 * density).toInt()
            y = screenHeight - heightPx - (80 * density).toInt()
        }

        windowManager.addView(surfaceView, params)
        renderer?.start()
        isActive = true
    }

    fun hide() {
        surfaceView?.visibility = android.view.View.INVISIBLE
        renderer?.stop()
    }

    fun showAgain() {
        surfaceView?.visibility = android.view.View.VISIBLE
        renderer?.start()
    }


    fun destroy() {
        if (!isActive) return
        renderer?.destroy()
        surfaceView?.let { runCatching { windowManager.removeView(it) } }
        surfaceView = null
        renderer = null
        isActive = false
    }
}
