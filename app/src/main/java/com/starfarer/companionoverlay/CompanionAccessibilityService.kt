package com.starfarer.companionoverlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.KeyEvent
import android.provider.Settings
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class CompanionAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "A11y"
        private const val DOUBLE_TAP_WINDOW_MS = 400L

        @Volatile
        var instance: CompanionAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null

        fun takeScreenshot(callback: (String?) -> Unit) {
            val service = instance
            if (service == null) {
                DebugLog.log(TAG, "Service not running!")
                callback(null)
                return
            }

            DebugLog.log(TAG, "Taking screenshot via AccessibilityService...")

            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                Executors.newSingleThreadExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val hwBuffer = result.hardwareBuffer
                            val colorSpace = result.colorSpace

                            val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                            hwBuffer.close()

                            if (bitmap == null) {
                                DebugLog.log(TAG, "Failed to create bitmap from HardwareBuffer")
                                callback(null)
                                return
                            }

                            DebugLog.log(TAG, "Got screenshot: ${bitmap.width}x${bitmap.height}")

                            val maxDim = maxOf(bitmap.width, bitmap.height)
                            val scale = if (maxDim > 1024) 1024f / maxDim else 1f
                            val scaledBitmap = if (scale < 1f) {
                                val sw = (bitmap.width * scale).toInt()
                                val sh = (bitmap.height * scale).toInt()
                                val softBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                bitmap.recycle()
                                val scaled = Bitmap.createScaledBitmap(softBitmap, sw, sh, true)
                                softBitmap.recycle()
                                scaled
                            } else {
                                val softBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                bitmap.recycle()
                                softBitmap
                            }

                            val outputStream = ByteArrayOutputStream()
                            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                            scaledBitmap.recycle()

                            val base64 = Base64.encodeToString(
                                outputStream.toByteArray(), Base64.NO_WRAP
                            )
                            DebugLog.log(TAG, "Screenshot base64 length: ${base64.length}")
                            callback(base64)

                        } catch (e: Exception) {
                            DebugLog.log(TAG, "Screenshot processing error: ${e.message}")
                            callback(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        DebugLog.log(TAG, "Screenshot failed with error code: $errorCode")
                        callback(null)
                    }
                }
            )
        }
    }

    // Double-tap detection — greedy, eats all volume-down events
    private val handler = Handler(Looper.getMainLooper())
    private var tapCount = 0
    private var pendingVolumeDown: Runnable? = null

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false

        // Only care about ACTION_DOWN — consume everything
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            tapCount++

            // Cancel any pending single-tap replay
            pendingVolumeDown?.let { handler.removeCallbacks(it) }
            pendingVolumeDown = null

            if (tapCount >= 2) {
                // Double tap — toggle, no volume change at all
                DebugLog.log(TAG, "Double-tap volume down detected!")
                tapCount = 0
                toggleOverlay()
            } else {
                // First tap — wait to see if second comes
                val replay = Runnable {
                    DebugLog.log(TAG, "Single tap — replaying volume down")
                    val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI
                    )
                    tapCount = 0
                    pendingVolumeDown = null
                }
                pendingVolumeDown = replay
                handler.postDelayed(replay, DOUBLE_TAP_WINDOW_MS)
            }
        }

        // Consume ALL volume-down events — system never sees them
        return true
    }

    private fun toggleOverlay() {
        if (CompanionOverlayService.isRunning) {
            DebugLog.log(TAG, "Hiding Senni~")
            CompanionOverlayService.dismiss()
        } else {
            if (!Settings.canDrawOverlays(this)) {
                DebugLog.log(TAG, "No overlay permission, ignoring toggle")
                Toast.makeText(this, "Grant overlay permission in the app first~", Toast.LENGTH_SHORT).show()
                return
            }
            DebugLog.log(TAG, "Summoning Senni~")
            val intent = Intent(this, CompanionOverlayService::class.java)
            startForegroundService(intent)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Reinforce key event filtering programmatically (some OEMs ignore XML)
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }

        DebugLog.log(TAG, "Senni's eyes are open~ (flags: ${serviceInfo.flags})")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Detect keyboard visibility changes
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            checkKeyboardVisibility()
        }
    }

    private fun checkKeyboardVisibility() {
        val windows = try { windows } catch (_: Exception) { return }
        
        var keyboardVisible = false
        for (window in windows) {
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                keyboardVisible = true
                break
            }
        }
        
        CompanionOverlayService.instance?.setGhostMode(keyboardVisible)
    }

    override fun onInterrupt() {
        DebugLog.log(TAG, "Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingVolumeDown?.let { handler.removeCallbacks(it) }
        instance = null
        DebugLog.log(TAG, "Senni's eyes are closed.")
    }
}
