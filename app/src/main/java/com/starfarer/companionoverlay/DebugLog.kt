package com.starfarer.companionoverlay

import java.text.SimpleDateFormat
import java.util.*

object DebugLog {
    private val buffer = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private const val MAX_SIZE = 50000
    
    @Synchronized
    fun log(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val line = "[$timestamp] $tag: $message\n"
        buffer.append(line)
        
        // Trim if too long
        if (buffer.length > MAX_SIZE) {
            buffer.delete(0, buffer.length - MAX_SIZE / 2)
        }
    }
    
    @Synchronized
    fun getLog(): String = buffer.toString()
    
    @Synchronized
    fun clear() = buffer.clear()
}
