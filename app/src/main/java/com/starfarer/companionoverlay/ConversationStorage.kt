package com.starfarer.companionoverlay

import android.content.Context
import com.starfarer.companionoverlay.api.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * File-based persistence for conversation history.
 *
 * Replaces the previous SharedPreferences approach. SharedPreferences
 * rewrites the entire preferences file on every [apply], dragging along
 * every other setting. When conversations include base64 screenshot data,
 * that becomes megabytes of atomic writes on every message.
 *
 * Messages are serialized as a JSON array of [Message] objects using
 * kotlinx.serialization, matching the same format used in-memory and
 * sent to the API. The custom [MessageContentSerializer] handles the
 * string-or-array polymorphism transparently.
 *
 * Writes go to a temp file first, then rename — the same atomic-write
 * pattern SharedPreferences uses, but scoped to just the conversation data.
 *
 * The file lives in app-private internal storage. No permissions needed,
 * sandboxed to the app, cleared on uninstall. Included in Auto Backup
 * by default, which is intentional.
 *
 * All I/O methods are suspend functions on [Dispatchers.IO].
 */
class ConversationStorage(private val context: Context) {

    companion object {
        private const val TAG = "ConvStorage"
        private const val FILENAME = "conversation_history.json"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val file: File get() = File(context.filesDir, FILENAME)

    // Serializes save/save and save/clear — they share one temp path, and the
    // launching scope happily interleaves them at suspension points.
    private val ioMutex = Mutex()

    /**
     * Load conversation history from disk.
     * Returns an empty list if the file doesn't exist or is corrupt.
     */
    suspend fun load(): MutableList<Message> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val f = file
            if (!f.exists()) return@withContext mutableListOf()

            try {
                val text = f.readText()
                val messages = json.decodeFromString<List<Message>>(text)
                DebugLog.log(TAG, "Loaded ${messages.size} messages from file")
                messages.toMutableList()
            } catch (e: Exception) {
                DebugLog.log(TAG, "Failed to load history: ${e.message}")
                mutableListOf()
            }
        }
    }

    /**
     * Save conversation history to disk.
     * Writes to a temp file, fsyncs, then renames. Without the sync the rename
     * can become durable before the data does — a crash then leaves a
     * truncated/empty "atomically written" file.
     */
    suspend fun save(messages: List<Message>) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            try {
                val text = json.encodeToString(messages)
                val tmp = File(context.filesDir, "$FILENAME.tmp")
                FileOutputStream(tmp).use { out ->
                    out.write(text.toByteArray())
                    out.fd.sync()
                }
                if (!tmp.renameTo(file)) {
                    DebugLog.log(TAG, "renameTo failed, falling back to copy")
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
                DebugLog.log(TAG, "Saved ${messages.size} messages to file")
            } catch (e: Exception) {
                DebugLog.log(TAG, "Failed to save history: ${e.message}")
            }
        }
    }

    /** Delete the history file. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            file.delete()
            DebugLog.log(TAG, "History file deleted")
        }
    }
}
