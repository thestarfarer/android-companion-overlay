package com.starfarer.companionoverlay.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Protocol tests for [GatewayClient] against an in-JVM WebSocket server.
 * Covers the handshake, offline queueing, capability round-trips, event
 * rate limiting, and reconnect behavior.
 */
class GatewayClientTest {

    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var client: GatewayClient

    /** Server side of the socket: records frames, exposes the socket to reply on. */
    private class ServerSide : WebSocketListener() {
        val frames = LinkedBlockingQueue<String>()
        val opened = CountDownLatch(1)
        @Volatile var socket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            opened.countDown()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            frames.add(text)
        }

        // Complete the close handshake — otherwise MockWebServer.shutdown()
        // waits forever on the still-open connection.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        fun awaitFrame(type: String, timeoutSec: Long = 5): JsonObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
            while (System.nanoTime() < deadline) {
                val raw = frames.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val obj = Json.parseToJsonElement(raw).jsonObject
                if (obj["type"]!!.jsonPrimitive.content == type) return obj
            }
            throw AssertionError("no '$type' frame within ${timeoutSec}s")
        }

        fun send(json: JsonObject) {
            socket!!.send(json.toString())
        }

        fun sendWelcome(re: String? = null, avatarVersion: String? = null) {
            send(buildJsonObject {
                put("type", "welcome")
                put("id", "s-1")
                re?.let { put("re", it) }
                put("v", 1)
                put("session_id", "sess-test")
                put("persona", "senni")
                put("resumed", false)
                avatarVersion?.let { put("avatar_version", it) }
            })
        }
    }

    /** Client-side listener that records callbacks. */
    private class RecordingListener : GatewayClient.Listener {
        val welcomes = LinkedBlockingQueue<GatewayClient.Welcome>()
        val messages = LinkedBlockingQueue<String>()
        val speaks = LinkedBlockingQueue<String>()
        val animates = LinkedBlockingQueue<String>()
        val statuses = LinkedBlockingQueue<String>()
        val capRequests = LinkedBlockingQueue<Triple<String, String, JsonObject>>()
        val disconnects = LinkedBlockingQueue<Unit>()
        val transcripts = LinkedBlockingQueue<Pair<String?, String>>()

        override fun onConnected(welcome: GatewayClient.Welcome) { welcomes.add(welcome) }
        override fun onDisconnected() { disconnects.add(Unit) }
        override fun onMessage(msgId: String?, text: String) { messages.add(text) }
        override fun onSpeak(text: String, audioFormat: String?, audioBase64: String?) { speaks.add(text) }
        override fun onTranscript(re: String?, text: String) { transcripts.add(re to text) }
        override fun onAnimate(state: String, params: JsonObject?) { animates.add(state) }
        override fun onCompanionStatus(state: String, detail: String?) { statuses.add(state) }
        override fun onCapRequest(requestId: String, capability: String, params: JsonObject, timeoutMs: Long) {
            capRequests.add(Triple(requestId, capability, params))
        }
    }

    private val listener = RecordingListener()
    private val serverSide = ServerSide()

    private fun config(url: String?) = object : GatewayConfig {
        override val serverUrl = url
        override val token = "test-token"
        override val deviceId = "test-device"
        override val deviceName = "Unit Test"
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient.Builder().build()
    }

    @After
    fun tearDown() {
        if (::client.isInitialized) client.stop()
        server.shutdown()
        httpClient.dispatcher.executorService.shutdown()
    }

    private fun startClient(capabilities: List<JsonObject> = listOf(buildJsonObject { put("name", "tts") })) {
        client = GatewayClient(config(server.url("/").toString()), httpClient)
        client.capabilities = capabilities
        client.listener = listener
        client.start()
    }

    private fun handshake(): JsonObject {
        server.enqueue(MockResponse().withWebSocketUpgrade(serverSide))
        startClient()
        val hello = serverSide.awaitFrame("hello")
        serverSide.sendWelcome(re = hello["id"]!!.jsonPrimitive.content)
        assertNotNull(listener.welcomes.poll(5, TimeUnit.SECONDS))
        return hello
    }

    // ── Handshake ──

    @Test
    fun `hello carries protocol version, device identity, and capabilities`() {
        val hello = handshake()
        assertEquals(1, hello["v"]!!.jsonPrimitive.content.toInt())
        assertEquals("test-device", hello["device_id"]!!.jsonPrimitive.content)
        assertEquals("Unit Test", hello["device_name"]!!.jsonPrimitive.content)
        assertEquals("phone", hello["kind"]!!.jsonPrimitive.content)
        val caps = hello["capabilities"]!!.jsonArray
        assertEquals("tts", caps[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(client.isConnected)
    }

    @Test
    fun `authorization header carries the bearer token`() {
        handshake()
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    // ── Outbound ──

    @Test
    fun `sendText produces a text frame with source`() {
        handshake()
        client.sendText("hello there", source = "voice")
        val frame = serverSide.awaitFrame("text")
        assertEquals("hello there", frame["text"]!!.jsonPrimitive.content)
        assertEquals("voice", frame["source"]!!.jsonPrimitive.content)
        assertNotNull(frame["id"])
    }

    @Test
    fun `sendImage sends format, kind, data, caption — and fails offline`() {
        handshake()
        assertTrue(client.sendImage("image/webp", "QUJD", "screenshot", "look at this"))
        val frame = serverSide.awaitFrame("image")
        assertEquals("image/webp", frame["format"]!!.jsonPrimitive.content)
        assertEquals("screenshot", frame["kind"]!!.jsonPrimitive.content)
        assertEquals("QUJD", frame["data"]!!.jsonPrimitive.content)
        assertEquals("look at this", frame["caption"]!!.jsonPrimitive.content)

        client.stop()
        assertFalse(client.sendImage("image/webp", "QUJD", "screenshot", null))
    }

    @Test
    fun `sendAudio sends format, base64 data, duration — returns id, and fails offline`() {
        handshake()
        val base64 = java.util.Base64.getEncoder().encodeToString(ByteArray(64) { it.toByte() })
        val id = client.sendAudio("wav", base64, 2300L)
        assertNotNull(id)

        val frame = serverSide.awaitFrame("audio")
        assertEquals("wav", frame["format"]!!.jsonPrimitive.content)
        assertEquals(base64, frame["data"]!!.jsonPrimitive.content)
        assertEquals(2300, frame["duration_ms"]!!.jsonPrimitive.content.toInt())
        assertEquals(id, frame["id"]!!.jsonPrimitive.content)

        // Audio is never queued offline (protocol §1) — the send just fails.
        client.stop()
        assertNull(client.sendAudio("wav", base64, 2300L))
    }

    @Test
    fun `text queued while offline is flushed after welcome`() {
        client = GatewayClient(config(server.url("/").toString()), httpClient)
        client.listener = listener
        client.sendText("queued while offline")   // before start: goes to the queue
        server.enqueue(MockResponse().withWebSocketUpgrade(serverSide))
        client.start()
        val hello = serverSide.awaitFrame("hello")
        serverSide.sendWelcome(re = hello["id"]!!.jsonPrimitive.content)
        val frame = serverSide.awaitFrame("text")
        assertEquals("queued while offline", frame["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `events are rate limited per name`() {
        handshake()
        client.sendEvent("tapped")
        client.sendEvent("tapped")   // within 2s — dropped
        client.sendEvent("keyboard_visible", buildJsonObject { put("visible", true) })
        serverSide.awaitFrame("event") // tapped
        val second = serverSide.awaitFrame("event") // keyboard_visible, not the duplicate tap
        assertEquals("keyboard_visible", second["name"]!!.jsonPrimitive.content)
        assertEquals(true, second["data"]!!.jsonObject["visible"]!!.jsonPrimitive.content.toBoolean())
    }

    // ── Inbound ──

    @Test
    fun `inbound directives reach the listener`() {
        handshake()
        serverSide.send(buildJsonObject { put("type", "status"); put("id", "s-2"); put("state", "thinking") })
        serverSide.send(buildJsonObject { put("type", "animate"); put("id", "s-3"); put("state", "talk") })
        serverSide.send(buildJsonObject { put("type", "message"); put("id", "s-4"); put("msg_id", "a-1"); put("text", "hi!") })
        serverSide.send(buildJsonObject { put("type", "speak"); put("id", "s-5"); put("msg_id", "a-1"); put("text", "hi spoken") })

        assertEquals("thinking", listener.statuses.poll(5, TimeUnit.SECONDS))
        assertEquals("talk", listener.animates.poll(5, TimeUnit.SECONDS))
        assertEquals("hi!", listener.messages.poll(5, TimeUnit.SECONDS))
        assertEquals("hi spoken", listener.speaks.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `transcript frames reach the listener, including the empty-text case`() {
        handshake()
        serverSide.send(buildJsonObject {
            put("type", "transcript"); put("id", "s-20"); put("re", "m-7"); put("text", "what's moon doing")
        })
        assertEquals("m-7" to "what's moon doing", listener.transcripts.poll(5, TimeUnit.SECONDS))

        // Empty text = the server heard nothing; still delivered so the UI can hint.
        serverSide.send(buildJsonObject {
            put("type", "transcript"); put("id", "s-21"); put("re", "m-8"); put("text", "")
        })
        assertEquals("m-8" to "", listener.transcripts.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `unknown message types and malformed frames are ignored`() {
        handshake()
        serverSide.send(buildJsonObject { put("type", "hologram"); put("id", "s-9") })
        serverSide.socket!!.send("not json at all")
        serverSide.send(buildJsonObject { put("type", "message"); put("id", "s-10"); put("text", "still alive") })
        assertEquals("still alive", listener.messages.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `cap_request round trip carries re and payload`() {
        handshake()
        serverSide.send(buildJsonObject {
            put("type", "cap_request"); put("id", "s-12")
            put("capability", "screenshot"); put("params", buildJsonObject {})
            put("timeout_ms", 15000)
        })
        val (requestId, capability, _) = listener.capRequests.poll(5, TimeUnit.SECONDS)!!
        assertEquals("s-12", requestId)
        assertEquals("screenshot", capability)

        client.sendCapResult(requestId, buildJsonObject {
            put("ok", true); put("format", "image/webp"); put("data", "QUJD")
        })
        val result = serverSide.awaitFrame("cap_result")
        assertEquals("s-12", result["re"]!!.jsonPrimitive.content)
        assertEquals(true, result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("QUJD", result["data"]!!.jsonPrimitive.content)
    }

    // ── Reconnect ──

    @Test
    fun `client reconnects with a fresh hello after the server closes`() {
        val secondServerSide = ServerSide()
        server.enqueue(MockResponse().withWebSocketUpgrade(serverSide))
        server.enqueue(MockResponse().withWebSocketUpgrade(secondServerSide))

        startClient()
        val hello1 = serverSide.awaitFrame("hello")
        serverSide.sendWelcome(re = hello1["id"]!!.jsonPrimitive.content)
        assertNotNull(listener.welcomes.poll(5, TimeUnit.SECONDS))

        serverSide.socket!!.close(1001, "server going away")

        assertNotNull("expected onDisconnected", listener.disconnects.poll(5, TimeUnit.SECONDS))
        val hello2 = secondServerSide.awaitFrame("hello", timeoutSec = 10)
        assertEquals("test-device", hello2["device_id"]!!.jsonPrimitive.content)
        secondServerSide.sendWelcome(re = hello2["id"]!!.jsonPrimitive.content)
        assertNotNull(listener.welcomes.poll(5, TimeUnit.SECONDS))
        assertTrue(client.isConnected)
    }

    @Test
    fun `stop prevents reconnection`() {
        handshake()
        client.stop()
        assertFalse(client.isConnected)
        // No further connection attempts: the request queue stays at 1.
        Thread.sleep(1500)
        assertEquals(1, server.requestCount)
    }

    // ── URL normalization ──

    @Test
    fun `normalizeBaseUrl accepts ws, wss, http, https and bare hosts`() {
        assertEquals("http://x:9597", GatewayClient.normalizeBaseUrl("ws://x:9597/"))
        assertEquals("https://x", GatewayClient.normalizeBaseUrl("wss://x"))
        assertEquals("http://x:1", GatewayClient.normalizeBaseUrl("http://x:1"))
        assertEquals("https://tunnel.example.com", GatewayClient.normalizeBaseUrl("https://tunnel.example.com/"))
        assertEquals("http://192.168.1.7:9597", GatewayClient.normalizeBaseUrl("192.168.1.7:9597"))
    }

    @Test
    fun `unconfigured server url stays offline without crashing`() {
        client = GatewayClient(config(null), httpClient)
        client.listener = listener
        client.start()
        Thread.sleep(300)
        assertFalse(client.isConnected)
        assertNull(listener.welcomes.poll())
        client.sendText("goes to the queue silently")
    }
}
