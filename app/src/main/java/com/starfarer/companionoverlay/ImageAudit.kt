package com.starfarer.companionoverlay

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug helper: persists the exact image bytes sent to Claude so they can be
 * inspected after the fact. Writes the JPEG verbatim (no re-encode) to the app's
 * external files dir under "sent_images/", and logs its resolution and size.
 *
 * Gated behind [SettingsRepository.saveSentImages]; off by default.
 */
object ImageAudit {

    private const val TAG = "ImageAudit"
    private const val DIR = "sent_images"
    private const val KEEP = 30   // prune to the most recent N to bound disk use

    /** Decode-and-save the base64 JPEG. Returns the file, or null on failure. */
    fun save(context: Context, base64: String, source: String): File? {
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

            val dir = File(context.getExternalFilesDir(null), DIR).apply { mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = File(dir, "${source}_$ts.jpg")
            file.writeBytes(bytes)

            DebugLog.log(
                TAG,
                "Saved $source image: ${bounds.outWidth}x${bounds.outHeight}, " +
                    "${bytes.size / 1024} KB → ${file.absolutePath}"
            )
            prune(dir)
            file
        } catch (e: Exception) {
            DebugLog.log(TAG, "Save failed: ${e.message}")
            null
        }
    }

    /** Most recently saved image, or null if none. */
    fun latest(context: Context): File? =
        File(context.getExternalFilesDir(null), DIR)
            .listFiles()
            ?.filter { it.isFile }
            ?.maxByOrNull { it.lastModified() }

    private fun prune(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(KEEP).forEach { it.delete() }
    }
}
