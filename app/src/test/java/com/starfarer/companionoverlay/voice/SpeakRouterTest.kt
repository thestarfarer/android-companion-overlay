package com.starfarer.companionoverlay.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Branching for `speak` directives: server-synthesized audio plays as-is
 * (text becomes display-only), no audio keeps the local TTS path, and a
 * corrupt payload degrades to local TTS instead of silence.
 */
class SpeakRouterTest {

    private val mp3ish = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x7F, 0x1A)
    private val mp3ishB64: String = Base64.getEncoder().encodeToString(mp3ish)

    @Test
    fun `speak with audio plays the server synthesis, text is display-only`() {
        val route = SpeakRouter.route("Moon's live~", "audio/mp3", mp3ishB64)
        val audio = route as SpeakRouter.Route.ServerAudio
        assertTrue(audio.audio.contentEquals(mp3ish))
        assertEquals("audio/mp3", audio.mimeType)
        assertEquals("Moon's live~", audio.displayText)
    }

    @Test
    fun `speak without audio uses the local TTS path unchanged`() {
        assertEquals(
            SpeakRouter.Route.LocalTts("Moon's live~"),
            SpeakRouter.route("Moon's live~", null, null)
        )
    }

    @Test
    fun `audio plays even when the spoken text is blank`() {
        val route = SpeakRouter.route("", "audio/mp3", mp3ishB64)
        assertTrue(route is SpeakRouter.Route.ServerAudio)
    }

    @Test
    fun `corrupt base64 degrades to local TTS instead of silence`() {
        assertEquals(
            SpeakRouter.Route.LocalTts("still audible"),
            SpeakRouter.route("still audible", "audio/mp3", "@@not base64@@")
        )
    }

    @Test
    fun `empty audio payload degrades to local TTS`() {
        assertEquals(
            SpeakRouter.Route.LocalTts("still audible"),
            SpeakRouter.route("still audible", "audio/mp3", Base64.getEncoder().encodeToString(ByteArray(0)))
        )
    }

    @Test
    fun `blank text with no audio voices nothing`() {
        assertEquals(SpeakRouter.Route.None, SpeakRouter.route("  ", null, null))
    }

    @Test
    fun `missing format defaults to mpeg, bare formats gain the audio prefix`() {
        val noFormat = SpeakRouter.route("x", null, mp3ishB64) as SpeakRouter.Route.ServerAudio
        assertEquals("audio/mpeg", noFormat.mimeType)
        val bare = SpeakRouter.route("x", "ogg", mp3ishB64) as SpeakRouter.Route.ServerAudio
        assertEquals("audio/ogg", bare.mimeType)
    }
}
