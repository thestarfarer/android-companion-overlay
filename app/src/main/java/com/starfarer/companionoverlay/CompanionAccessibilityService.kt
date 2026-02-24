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
import com.starfarer.companionoverlay.event.OverlayCoordinator
import com.starfarer.companionoverlay.event.OverlayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Accessibility service for screenshot capture and hardware key interception.
 * 
 * Communicates with other components via [OverlayCoordinator] instead of
 * static instance references.
 */
class CompanionAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "A11y"
        private const val DOUBLE_TAP_WINDOW_MS = 400L
    }

    private val coordinator: OverlayCoordinator by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private val handler = Handler(Looper.getMainLooper())
    private var tapCount = 0
    private var pendingVolumeDown: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        coordinator.onAccessibilityServiceConnected()

        // Reinforce key event filtering programmatically (some OEMs ignore XML)
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }

        // Subscribe to screenshot requests
        serviceScope.launch {
            coordinator.events
                .filterIsInstance<OverlayEvent.ScreenshotRequest>()
                .collect { event ->
                    takeScreenshotInternal { base64 ->
                        coordinator.onScreenshotComplete(base64)
                    }
                }
        }

        DebugLog.log(TAG, "Senni's eyes are open~ (flags: ${serviceInfo.flags})")
        val prefs = getSharedPreferences("companion_prompts", MODE_PRIVATE)
        if (!prefs.getBoolean("accessibility_toast_shown", false)) {
            prefs.edit().putBoolean("accessibility_toast_shown", true).apply()
            Toast.makeText(this, "Accessibility enabled~ Reboot if volume button toggle doesn't work", Toast.LENGTH_LONG).show()
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        DebugLog.log(TAG, "KEY: code=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)}) action=${event.action} device=${event.device?.name ?: "none"}")
        
        // --- Shokz / headset button: toggle voice input ---
        if (event.keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (coordinator.overlayRunning.value) {
                    DebugLog.log(TAG, "Headset button → toggle voice")
                    coordinator.toggleVoice()
                    return true
                }
            }
            return false
        }

        // --- Volume down: double-tap = toggle overlay, triple-tap = toggle voice ---
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!PromptSettings.getVolumeToggle(this)) return false

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            tapCount++

            // Cancel any pending action from previous taps
            pendingVolumeDown?.let { handler.removeCallbacks(it) }
            pendingVolumeDown = null

            if (tapCount >= 3) {
                // Triple-tap — toggle voice input
                DebugLog.log(TAG, "Triple-tap volume down → toggle voice")
                tapCount = 0
                if (coordinator.overlayRunning.value) {
                    coordinator.toggleVoice()
                }
            } else {
                // Wait to see if more taps are coming
                val currentCount = tapCount
                val resolve = Runnable {
                    when (currentCount) {
                        1 -> {
                            // Single tap — replay volume down
                            DebugLog.log(TAG, "Single tap — replaying volume down")
                            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                            audio.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_LOWER,
                                AudioManager.FLAG_SHOW_UI
                            )
                        }
                        2 -> {
                            // Double-tap — toggle overlay
                            DebugLog.log(TAG, "Double-tap volume down → toggle overlay")
                            toggleOverlay()
                        }
                    }
                    tapCount = 0
                    pendingVolumeDown = null
                }
                pendingVolumeDown = resolve
                handler.postDelayed(resolve, DOUBLE_TAP_WINDOW_MS)
            }
        }

        return true
    }

    private fun toggleOverlay() {
        if (coordinator.overlayRunning.value) {
            DebugLog.log(TAG, "Hiding Senni~")
            coordinator.dismissOverlay()
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

    private fun takeScreenshotInternal(callback: (String?) -> Unit) {
        DebugLog.log(TAG, "Taking screenshot via AccessibilityService...")

        takeScreenshot(
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
        
        coordinator.notifyKeyboardVisibility(keyboardVisible)
    }

    override fun onInterrupt() {
        DebugLog.log(TAG, "Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingVolumeDown?.let { handler.removeCallbacks(it) }
        serviceScope.cancel()
        coordinator.onAccessibilityServiceDisconnected()
        DebugLog.log(TAG, "Senni's eyes are closed.")
    }
}
