package com.starfarer.companionoverlay.voice

import java.util.Base64

/**
 * Decides how a `speak` directive is voiced (presence protocol §4): when the
 * server ships synthesized audio (`speak.audio`), play that and use the text
 * only for display; otherwise the local TTS engine speaks the text. A
 * malformed/empty audio payload degrades to local TTS rather than silence.
 *
 * Pure JVM so the branching is unit testable; actual playback lives in
 * [com.starfarer.companionoverlay.AudioCoordinator].
 */
object SpeakRouter {

    sealed interface Route {
        /** Play the server-synthesized audio; [displayText] is not spoken locally. */
        data class ServerAudio(val audio: ByteArray, val mimeType: String, val displayText: String) : Route {
            // ByteArray needs content (not reference) semantics for equals/tests.
            override fun equals(other: Any?): Boolean =
                other is ServerAudio && audio.contentEquals(other.audio) &&
                    mimeType == other.mimeType && displayText == other.displayText
            override fun hashCode(): Int =
                31 * (31 * audio.contentHashCode() + mimeType.hashCode()) + displayText.hashCode()
        }

        /** No server audio — speak the text with the on-device engine. */
        data class LocalTts(val text: String) : Route

        /** Nothing to voice. */
        data object None : Route
    }

    fun route(text: String, audioFormat: String?, audioBase64: String?): Route {
        if (!audioBase64.isNullOrBlank()) {
            val bytes = try {
                Base64.getDecoder().decode(audioBase64)
            } catch (_: IllegalArgumentException) {
                null // corrupt payload — fall through to local TTS
            }
            if (bytes != null && bytes.isNotEmpty()) {
                return Route.ServerAudio(bytes, normalizeMime(audioFormat), text)
            }
        }
        if (text.isBlank()) return Route.None
        return Route.LocalTts(text)
    }

    /** `"audio/mp3"`-style values pass through; a bare `"mp3"` gains the prefix. */
    private fun normalizeMime(format: String?): String = when {
        format.isNullOrBlank() -> "audio/mpeg"
        format.contains('/') -> format
        else -> "audio/$format"
    }
}
