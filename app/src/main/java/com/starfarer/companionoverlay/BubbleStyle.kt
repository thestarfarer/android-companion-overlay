package com.starfarer.companionoverlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager

/**
 * Shared styling for overlay bubbles — speech, voice, and brief notifications.
 *
 * Centralizes Monet/Material You color resolution and the common window
 * parameters so the three bubble types stay visually consistent without
 * duplicating the same Android 12 check in every method.
 */
object BubbleStyle {

    data class Colors(val bg: Int, val text: Int, val bgWithAlpha: Int)

    /** Resolve Monet accent colors, falling back to warm cream on pre-S devices. */
    fun colors(context: Context): Colors {
        val bgColor: Int
        val textColor: Int
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            bgColor = context.resources.getColor(android.R.color.system_accent1_100, context.theme)
            textColor = context.resources.getColor(android.R.color.system_accent1_900, context.theme)
        } else {
            bgColor = 0xFFF5E6D3.toInt()
            textColor = 0xFF2D1B0E.toInt()
        }
        val bgWithAlpha = (bgColor and 0x00FFFFFF) or 0xFA000000.toInt()
        return Colors(bgColor, textColor, bgWithAlpha)
    }

    /** Create the standard rounded background drawable. */
    fun background(colors: Colors, cornerRadiusDp: Float, density: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(colors.bgWithAlpha)
            cornerRadius = cornerRadiusDp * density
        }
    }

    /** Edge-anchored toast background: rounded on left, flat on right. */
    fun toastBackground(colors: Colors, cornerRadiusDp: Float, density: Float): GradientDrawable {
        val r = cornerRadiusDp * density
        return GradientDrawable().apply {
            setColor(colors.bgWithAlpha)
            cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        }
    }

    /** Top-right edge-anchored params for brief/voice toasts. */
    fun topRightEdgeParams(density: Float): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            y = (60 * density).toInt()
        }
    }

    /** Standard bottom-center overlay params for brief/voice bubbles. */
    fun bottomCenterParams(density: Float): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (120 * density).toInt()
        }
    }

    /** Centered overlay params for the main speech dialog. */
    fun centeredParams(maxWidth: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            maxWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
    }

    /** Monet-tinted scrollbar thumb for ScrollViews. */
    fun scrollbarThumb(context: Context, density: Float): GradientDrawable? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return null
        val thumbColor = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.resources.getColor(android.R.color.system_accent1_300, context.theme)
        } else {
            0xFFBFA68E.toInt()
        }
        return GradientDrawable().apply {
            setColor(thumbColor)
            cornerRadius = 4f * density
            setSize((4 * density).toInt(), 0)
        }
    }
}
