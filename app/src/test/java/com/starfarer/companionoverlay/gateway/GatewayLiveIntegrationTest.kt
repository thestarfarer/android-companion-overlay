package com.starfarer.companionoverlay.gateway

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Live protocol test against a real Nexus dev server. Skipped unless:
 *
 *   NEXUS_LIVE_URL=http://127.0.0.1:9597 NEXUS_LIVE_TOKEN=... \
 *     ./gradlew testDebugUnitTest --tests '*GatewayLiveIntegrationTest'
 *
 * Turns hit the Claude API through the server — this test performs a small,
 * fixed number of them (one text turn, one image turn, one capability turn)
 * and must not be looped.
 */
class GatewayLiveIntegrationTest {

    // 16x16 solid red PNG (valid, pre-encoded).
    private val redPng =
        "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAFklEQVR4nGO4o6FBEmIY1TCqYfhqAAAyBCwQhvh37QAAAABJRU5ErkJggg=="

    private class Recorder : GatewayClient.Listener {
        val welcomes = LinkedBlockingQueue<GatewayClient.Welcome>()
        val messages = LinkedBlockingQueue<String>()
        val speaks = LinkedBlockingQueue<String>()
        val statuses = LinkedBlockingQueue<String>()
        val animates = LinkedBlockingQueue<String>()
        val errors = LinkedBlockingQueue<String>()
        val capRequests = LinkedBlockingQueue<Pair<String, String>>()  // id to capability
        @Volatile var client: GatewayClient? = null

        private fun log(line: String) = println("<< $line")

        override fun onConnected(welcome: GatewayClient.Welcome) {
            log("welcome: session=${welcome.sessionId} persona=${welcome.persona} resumed=${welcome.resumed} avatar=${welcome.avatarVersion}")
            welcomes.add(welcome)
        }

        override fun onMessage(msgId: String?, text: String) {
            log("message[$msgId]: $text")
            messages.add(text)
        }

        override fun onSpeak(text: String, audioFormat: String?, audioBase64: String?) {
            log("speak: $text (audio=${audioFormat ?: "none"})")
            speaks.add(text)
        }

        override fun onCompanionStatus(state: String, detail: String?) {
            log("status: $state${detail?.let { " ($it)" } ?: ""}")
            statuses.add(state)
        }

        override fun onAnimate(state: String, params: JsonObject?) {
            log("animate: $state $params")
            animates.add(state)
        }

        override fun onServerError(code: String, message: String?, re: String?) {
            log("error: $code $message")
            errors.add(code)
        }

        override fun onCapRequest(requestId: String, capability: String, params: JsonObject, timeoutMs: Long) {
            log("cap_request[$requestId]: $capability $params")
            capRequests.add(requestId to capability)
        }
    }

    @Test
    fun `live hello, text turn, image turn, capability round trip`() {
        val url = System.getenv("NEXUS_LIVE_URL")
        val token = System.getenv("NEXUS_LIVE_TOKEN")
        assumeTrue("NEXUS_LIVE_URL / NEXUS_LIVE_TOKEN not set — skipping live test", !url.isNullOrBlank() && !token.isNullOrBlank())

        val config = object : GatewayConfig {
            override val serverUrl = url
            override val token = token
            override val deviceId = "overlay-live-test"
            override val deviceName = "GatewayClient JVM test"
        }
        val recorder = Recorder()
        val httpClient = OkHttpClient.Builder().build()
        val client = GatewayClient(config, httpClient)
        recorder.client = client
        client.capabilities = listOf(
            buildJsonObject { put("name", "screenshot") },
            buildJsonObject { put("name", "tts") },
        )
        client.listener = recorder

        try {
            // ── 1. hello → welcome ──
            println(">> connecting to $url as overlay-live-test")
            client.start()
            val welcome = recorder.welcomes.poll(20, TimeUnit.SECONDS)
            assertNotNull("no welcome within 20s", welcome)
            assertNotNull("welcome carries session_id", welcome!!.sessionId)
            assertTrue(client.isConnected)

            // ── 2. one real text turn ──
            println(">> text turn")
            client.sendText("Integration test here. Reply with the single word: pong", source = "typed")
            val reply = recorder.messages.poll(120, TimeUnit.SECONDS)
            assertNotNull("no assistant message within 120s", reply)
            assertTrue("assistant reply is non-empty", reply!!.isNotBlank())
            // tts declared → server should also send speak for the active device
            assertNotNull("no speak frame", recorder.speaks.poll(10, TimeUnit.SECONDS))

            // ── 3. one image turn ──
            println(">> image turn")
            assertTrue(client.sendImage("image/png", redPng, "other",
                "Test image. What color is this square? Answer with one word."))
            val imageReply = recorder.messages.poll(120, TimeUnit.SECONDS)
            assertNotNull("no reply to image within 120s", imageReply)
            println(">> image reply: $imageReply")

            // ── 4. capability round trip (server-requested screenshot) ──
            println(">> capability turn")
            // Answer the cap_request from a watcher thread with canned data.
            val servedCapability = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val capAnswered = Thread {
                val req = recorder.capRequests.poll(110, TimeUnit.SECONDS) ?: return@Thread
                println(">> serving cap_result for ${req.second}")
                client.sendCapResult(req.first, buildJsonObject {
                    put("ok", true)
                    put("format", "image/png")
                    put("data", redPng)
                })
                servedCapability.set(req.second)
            }.apply { start() }
            client.sendText(
                "Use your device_screenshot tool to capture my screen right now, then tell me the dominant color in one word.",
                source = "typed"
            )
            val capReply = recorder.messages.poll(150, TimeUnit.SECONDS)
            capAnswered.join(1000)
            assertNotNull("no reply to capability turn within 150s", capReply)
            assertEquals("served a screenshot cap_request", "screenshot", servedCapability.get())
            println(">> capability reply: $capReply")

            println(">> LIVE TEST COMPLETE")
        } finally {
            client.stop()
            httpClient.dispatcher.executorService.shutdown()
        }
    }
}
