package com.starfarer.companionoverlay

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

/**
 * In-memory debug log with logcat output.
 *
 * Messages are always written to the in-memory buffer (accessible via [getLog]).
 * Log.d calls are stripped in release builds via the -assumenosideeffects
 * rule in proguard-rules.pro.
 *
 * The buffer is capped at [MAX_SIZE] characters and trims from the front
 * when exceeded, keeping the most recent entries.
 */
object DebugLog {
    private val buffer = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private const val MAX_SIZE = 50000
    private const val LOG_TAG = "Companion"

    @Synchronized
    fun log(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val line = "[$timestamp] $tag: $message\n"
        buffer.append(line)

        if (buffer.length > MAX_SIZE) {
            buffer.delete(0, buffer.length - MAX_SIZE / 2)
        }

        Log.d("$LOG_TAG/$tag", message)
    }

    @Synchronized
    fun getLog(): String = buffer.toString()

    @Synchronized
    fun clear() = buffer.clear()
}
