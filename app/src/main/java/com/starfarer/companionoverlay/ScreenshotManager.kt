package com.starfarer.companionoverlay

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

/**
 * Thin wrapper around CompanionAccessibilityService screenshot capability.
 * No more MediaProjection, no more permission tokens, no more tears.
 */
class ScreenshotManager(private val context: Context) {

    companion object {
        /**
         * Check if the accessibility service is enabled in system settings.
         * This queries the actual system setting, not a static variable that
         * dies with the process.
         */
        fun isAccessibilityEnabled(context: Context): Boolean {
            val serviceName = "${context.packageName}/${CompanionAccessibilityService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            
            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                if (colonSplitter.next().equals(serviceName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        /** Service is enabled AND actually connected (ready to take screenshots) */
        fun hasPermission(context: Context): Boolean {
            return isAccessibilityEnabled(context) && CompanionAccessibilityService.isRunning
        }
    }

    private fun log(msg: String) = DebugLog.log("Screenshot", msg)

    fun getAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun takeScreenshot(callback: (String?) -> Unit) {
        if (!CompanionAccessibilityService.isRunning) {
            log("Accessibility service not running!")
            callback(null)
            return
        }
        log("Delegating screenshot to AccessibilityService...")
        CompanionAccessibilityService.takeScreenshot(callback)
    }
}
