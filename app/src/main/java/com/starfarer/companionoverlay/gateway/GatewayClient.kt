package com.starfarer.companionoverlay.gateway

import com.starfarer.companionoverlay.DebugLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

/**
 * WebSocket client for the Nexus presence protocol v1
 * (nexus docs/PRESENCE_PROTOCOL.md). The app's only conversation channel:
 * user text/images/events go up, `message`/`speak`/`animate`/`status`
 * directives come down. All intelligence lives server-side — this class is
 * deliberately dumb plumbing.
 *
 * - Connects to `<serverUrl>/ws` with `Authorization: Bearer <token>`.
 * - Sends `hello` on open; the connection is usable after `welcome`.
 * - App-level `ping` every 30s (mobile radios and quick tunnels kill quiet
 *   connections faster than TCP notices).
 * - Reconnects with jittered exponential backoff, 1s → 60s cap, for as long
 *   as [start]ed. Reconnection is invisible above this layer except for the
 *   edge-triggered [Listener.onDisconnected]/[Listener.onConnected] pair.
 *   Exception: an `error` with code `version` is terminal — reconnecting
 *   would replay the same rejection, so the client stays offline until the
 *   next [start] (settings change or app restart).
 * - Queues outbound `text`/`event` while disconnected (bounded, drop-oldest)
 *   and flushes after `welcome`. `image`, `audio` and `cap_result` are never
 *   queued.
 *
 * Pure JVM (no Android classes) so it can be integration-tested against a
 * real Nexus server in plain unit tests. Listener callbacks are delivered on
 * [callbackExecutor] — the service passes a main-thread executor.
 */
class GatewayClient(
    private val config: GatewayConfig,
    private val httpClient: OkHttpClient,
    private val callbackExecutor: Executor = Executor { it.run() },
    private val kind: String = "phone",
) {
    companion object {
        const val PROTOCOL_VERSION = 1

        /**
         * Server cap on base64 image payloads — anything larger is rejected
         * with `bad_message`, so don't put it on the wire at all.
         */
        const val MAX_IMAGE_BASE64_BYTES = 8_000_000

        private const val TAG = "Gateway"
        private const val PING_INTERVAL_MS = 30_000L
        private const val RECONNECT_MIN_MS = 1_000L
        private const val RECONNECT_MAX_MS = 60_000L
        private const val OFFLINE_QUEUE_LIMIT = 64
        private const val EVENT_MIN_INTERVAL_MS = 2_000L

        /**
         * Normalize a configured base URL into the http(s) form OkHttp's
         * WebSocket API expects (it upgrades http/https itself). Accepts
         * ws/wss/http/https and bare host[:port]; strips trailing slashes.
         */
        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            url = when {
                url.startsWith("ws://") -> "http://" + url.removePrefix("ws://")
                url.startsWith("wss://") -> "https://" + url.removePrefix("wss://")
                url.startsWith("http://") || url.startsWith("https://") -> url
                else -> "http://$url"
            }
            return url
        }
    }

    /** Server's `welcome` payload. */
    data class Welcome(
        val sessionId: String?,
        val persona: String?,
        val resumed: Boolean,
        val avatarVersion: String?,
    )

    /**
     * Inbound protocol surface. All callbacks arrive on [callbackExecutor].
     * Default no-op bodies so hosts implement only what they render.
     */
    interface Listener {
        /** `welcome` received — the session (re)opened. */
        fun onConnected(welcome: Welcome) {}

        /** A live (post-welcome) connection dropped. Edge-triggered, not per retry. */
        fun onDisconnected() {}

        /** Streaming text delta. Optional to render; `message` is authoritative. */
        fun onToken(msgId: String?, text: String) {}

        /** Finalized assistant message — display it. */
        fun onMessage(msgId: String?, text: String) {}

        /** What to say aloud. When [audioBase64] is null, use local TTS. */
        fun onSpeak(text: String, audioFormat: String?, audioBase64: String?) {}

        /**
         * Server-side transcription of an `audio` message this device sent
         * ([re] = that message's id). Empty [text] means the server heard
         * nothing — no turn follows.
         */
        fun onTranscript(re: String?, text: String) {}

        /** Presence directive: idle|walk|escape|talk|think|alert. Advisory. */
        fun onAnimate(state: String, params: JsonObject?) {}

        /** Companion state: idle|listening|thinking|tool_running|speaking. */
        fun onCompanionStatus(state: String, detail: String?) {}

        /** Session lifecycle: rotated | active_device_changed. */
        fun onSession(event: String, data: JsonObject?) {}

        /** Server error frame (never closes the connection except auth/version). */
        fun onServerError(code: String, message: String?, re: String?) {}

        /**
         * Server asks this device to exercise a capability. Reply via
         * [sendCapResult] with [requestId] within [timeoutMs].
         */
        fun onCapRequest(requestId: String, capability: String, params: JsonObject, timeoutMs: Long) {}
    }

    @Volatile var listener: Listener? = null

    /**
     * Capability manifest sent in `hello`, e.g.
     * `[{"name":"screenshot"}, {"name":"camera","facing":["front","back"]}]`.
     * Set before [start].
     */
    @Volatile var capabilities: List<JsonObject> = emptyList()

    private val lock = Any()
    private var started = false
    private var webSocket: WebSocket? = null
    private var ready = false            // welcome received on the current socket
    // The server rejected our protocol version (`error` code `version`) — a
    // hard, permanent failure (§10): retrying the same hello can only be
    // rejected again. No reconnects until the next start() (settings change
    // or app restart).
    private var versionRejected = false
    private var reconnectAttempt = 0
    private var reconnectTask: ScheduledFuture<*>? = null
    private var pingTask: ScheduledFuture<*>? = null
    private val offlineQueue = ArrayDeque<JsonObject>()   // text/event only
    private val lastEventAt = HashMap<String, Long>()
    private val idCounter = AtomicLong(0)

    private val scheduler = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "gateway-timer").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    val isConnected: Boolean get() = synchronized(lock) { ready }

    /** True after a protocol-version rejection — permanently offline until restarted. */
    val isVersionRejected: Boolean get() = synchronized(lock) { versionRejected }

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    /** Begin connecting (and keep reconnecting) until [stop]. Idempotent. */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            reconnectAttempt = 0
            // A fresh start (settings change / app restart) gets a fresh
            // chance — maybe the server or the app got updated.
            versionRejected = false
        }
        scheduler.execute { connect() }
    }

    /** Close the connection and stop reconnecting. Idempotent. */
    fun stop() {
        val ws: WebSocket?
        synchronized(lock) {
            if (!started) return
            started = false
            ready = false
            ws = webSocket
            webSocket = null
            reconnectTask?.cancel(false)
            reconnectTask = null
            pingTask?.cancel(false)
            pingTask = null
        }
        try {
            ws?.send(envelope("bye") {}.toString())
        } catch (_: Exception) {}
        ws?.close(1000, "bye")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Outbound
    // ══════════════════════════════════════════════════════════════════════

    /** User utterance. Queued (drop-oldest) while offline. */
    fun sendText(text: String, source: String = "typed") {
        if (text.isBlank()) return
        sendOrQueue(envelope("text") {
            put("text", text)
            put("source", source)
        })
    }

    /**
     * Interaction/presence signal. Rate-limited to one per name per 2s;
     * excess events are dropped (they're cheap context, not conversation).
     */
    fun sendEvent(name: String, data: JsonObject = buildJsonObject {}) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val last = lastEventAt[name] ?: 0L
            if (now - last < EVENT_MIN_INTERVAL_MS) return
            lastEventAt[name] = now
        }
        sendOrQueue(envelope("event") {
            put("name", name)
            put("data", data)
        })
    }

    /** Outcome of [sendImage] — mirrors ServerVoicePipeline.SubmitResult. */
    enum class ImageSendResult { SENT, TOO_LARGE, OFFLINE }

    /**
     * User-initiated image share (enters the conversation as a user turn).
     * Never queued — returns [ImageSendResult.OFFLINE] when disconnected so
     * the caller can tell the user instead of silently holding megabytes.
     * Payloads over [MAX_IMAGE_BASE64_BYTES] are dropped pre-send
     * ([ImageSendResult.TOO_LARGE]) — the server rejects them with
     * `bad_message` anyway.
     */
    fun sendImage(format: String, base64Data: String, kind: String, caption: String?): ImageSendResult {
        if (base64Data.length > MAX_IMAGE_BASE64_BYTES) {
            DebugLog.log(TAG, "Image over the 8MB base64 cap (${base64Data.length} bytes) — dropped")
            return ImageSendResult.TOO_LARGE
        }
        val msg = envelope("image") {
            put("kind", kind)
            put("format", format)
            put("data", base64Data)
            if (!caption.isNullOrBlank()) put("caption", caption)
        }
        return if (sendIfReady(msg)) ImageSendResult.SENT else ImageSendResult.OFFLINE
    }

    /**
     * One VAD-cut voice utterance (§3 `audio`) — the primary voice path. The
     * server transcribes it and answers with `transcript`, then the normal
     * turn replies. Never queued offline (protocol §1: a stale utterance is
     * worse than a dropped one — same rule as images).
     *
     * @return the message id (for `transcript`/`error` correlation via `re`),
     *   or null when offline / the socket rejected the frame.
     */
    fun sendAudio(format: String, base64Data: String, durationMs: Long): String? {
        val msg = envelope("audio") {
            put("format", format)
            put("data", base64Data)
            put("duration_ms", durationMs)
        }
        return if (sendIfReady(msg)) msg.str("id") else null
    }

    /** Device state snapshot. Not queued. */
    fun sendStatus(battery: Int? = null, network: String? = null, muted: Boolean? = null) {
        sendIfReady(envelope("status") {
            battery?.let { put("battery", it) }
            network?.let { put("network", it) }
            muted?.let { put("muted", it) }
        })
    }

    /**
     * Reply to a `cap_request`. [payload] carries `ok` plus result fields
     * (`format`/`data` for images, or `error` on failure). Not queued — a
     * late result is useless to the server's timed-out tool call.
     */
    fun sendCapResult(requestId: String, payload: JsonObject) {
        sendIfReady(envelope("cap_result") {
            put("re", requestId)
            payload.forEach { (k, v) -> put(k, v) }
        })
    }

    // ══════════════════════════════════════════════════════════════════════
    // Connection machinery
    // ══════════════════════════════════════════════════════════════════════

    private fun connect() {
        val base = config.serverUrl?.takeIf { it.isNotBlank() } ?: run {
            DebugLog.log(TAG, "No server URL configured — staying offline")
            return
        }
        val token = config.token ?: ""
        val url = normalizeBaseUrl(base) + "/ws"

        synchronized(lock) {
            if (!started || versionRejected || webSocket != null) return
        }

        val request = try {
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()
        } catch (e: IllegalArgumentException) {
            DebugLog.log(TAG, "Bad server URL '$url': ${e.message}")
            return
        }

        DebugLog.log(TAG, "Connecting to $url (attempt ${reconnectAttempt + 1})")
        val ws = httpClient.newWebSocket(request, socketListener)
        synchronized(lock) {
            if (!started) {
                ws.close(1000, "stopped")
                return
            }
            webSocket = ws
        }
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            DebugLog.log(TAG, "Socket open — sending hello")
            webSocket.send(helloMessage().toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleFrame(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            DebugLog.log(TAG, "Socket failure: ${t.message} (http ${response?.code ?: "-"})")
            onSocketDown(webSocket)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            DebugLog.log(TAG, "Socket closed: $code $reason")
            onSocketDown(webSocket)
        }
    }

    private fun onSocketDown(ws: WebSocket) {
        val wasReady: Boolean
        synchronized(lock) {
            if (webSocket !== ws && webSocket != null) return  // an old socket's death rattle
            wasReady = ready
            ready = false
            webSocket = null
            pingTask?.cancel(false)
            pingTask = null
            if (started && !versionRejected) scheduleReconnectLocked()
        }
        if (wasReady) dispatch { it.onDisconnected() }
    }

    /** Must hold [lock]. */
    private fun scheduleReconnectLocked() {
        if (reconnectTask?.isDone == false) return
        val expo = min(RECONNECT_MAX_MS, RECONNECT_MIN_MS shl min(reconnectAttempt, 6))
        // Jitter: 50–100% of the exponential delay.
        val delay = expo / 2 + Random.nextLong(expo / 2 + 1)
        reconnectAttempt = min(reconnectAttempt + 1, 16)
        DebugLog.log(TAG, "Reconnecting in ${delay}ms")
        reconnectTask = scheduler.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun helloMessage(): JsonObject = envelope("hello") {
        put("v", PROTOCOL_VERSION)
        put("device_id", config.deviceId)
        put("device_name", config.deviceName)
        put("kind", kind)
        put("capabilities", JsonArray(capabilities))
    }

    // ══════════════════════════════════════════════════════════════════════
    // Inbound
    // ══════════════════════════════════════════════════════════════════════

    private fun handleFrame(raw: String) {
        val msg = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            DebugLog.log(TAG, "Unparseable frame: ${raw.take(120)}")
            return
        }

        when (msg.str("type")) {
            "welcome" -> {
                val toFlush: List<JsonObject>
                val ws: WebSocket?
                synchronized(lock) {
                    ready = true
                    reconnectAttempt = 0
                    ws = webSocket
                    toFlush = offlineQueue.toList()
                    offlineQueue.clear()
                    schedulePingLocked()
                }
                DebugLog.log(TAG, "Welcome: session=${msg.str("session_id")} resumed=${msg.bool("resumed")}")
                toFlush.forEach { ws?.send(it.toString()) }
                val welcome = Welcome(
                    sessionId = msg.str("session_id"),
                    persona = msg.str("persona"),
                    resumed = msg.bool("resumed") ?: false,
                    avatarVersion = msg.str("avatar_version"),
                )
                dispatch { it.onConnected(welcome) }
            }
            "token" -> dispatch { it.onToken(msg.str("msg_id"), msg.str("text") ?: "") }
            "message" -> dispatch { it.onMessage(msg.str("msg_id"), msg.str("text") ?: "") }
            "speak" -> {
                val audio = msg["audio"] as? JsonObject
                dispatch { it.onSpeak(msg.str("text") ?: "", audio?.str("format"), audio?.str("data")) }
            }
            "transcript" -> dispatch { it.onTranscript(msg.str("re"), msg.str("text") ?: "") }
            "animate" -> dispatch {
                it.onAnimate(msg.str("state") ?: "idle", msg["params"] as? JsonObject)
            }
            "status" -> dispatch {
                it.onCompanionStatus(msg.str("state") ?: "idle", msg.str("detail"))
            }
            "session" -> dispatch {
                it.onSession(msg.str("event") ?: "", msg["data"] as? JsonObject)
            }
            "error" -> {
                val code = msg.str("code") ?: "internal"
                if (code == "version") {
                    // Terminal (§10): the server refuses protocol v$PROTOCOL_VERSION.
                    // Reconnecting would just replay the same rejection forever —
                    // stop until settings change or the app restarts.
                    DebugLog.log(
                        TAG,
                        "PROTOCOL VERSION REJECTED by server (we speak v$PROTOCOL_VERSION): " +
                            "${msg.str("message")} — staying offline until settings change or app restart"
                    )
                    synchronized(lock) {
                        versionRejected = true
                        reconnectTask?.cancel(false)
                        reconnectTask = null
                    }
                } else {
                    DebugLog.log(TAG, "Server error $code: ${msg.str("message")}")
                }
                dispatch { it.onServerError(code, msg.str("message"), msg.str("re")) }
            }
            "cap_request" -> {
                val id = msg.str("id") ?: return
                val capability = msg.str("capability") ?: return
                val params = msg["params"] as? JsonObject ?: buildJsonObject {}
                val timeout = (msg["timeout_ms"] as? JsonPrimitive)?.longOrNull ?: 15_000L
                dispatch { it.onCapRequest(id, capability, params, timeout) }
            }
            "pong" -> { /* liveness ack — nothing to do */ }
            else -> { /* unknown type: ignore (forward compatibility) */ }
        }
    }

    /** Must hold [lock]. */
    private fun schedulePingLocked() {
        pingTask?.cancel(false)
        pingTask = scheduler.scheduleWithFixedDelay({
            val ws = synchronized(lock) { if (ready) webSocket else null } ?: return@scheduleWithFixedDelay
            ws.send(envelope("ping") {}.toString())
        }, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun sendOrQueue(msg: JsonObject) {
        val ws: WebSocket?
        synchronized(lock) {
            if (ready) {
                ws = webSocket
            } else {
                while (offlineQueue.size >= OFFLINE_QUEUE_LIMIT) offlineQueue.pollFirst()
                offlineQueue.addLast(msg)
                DebugLog.log(TAG, "Offline — queued ${msg.str("type")} (${offlineQueue.size} pending)")
                return
            }
        }
        ws?.send(msg.toString())
    }

    private fun sendIfReady(msg: JsonObject): Boolean {
        val ws = synchronized(lock) { if (ready) webSocket else null } ?: return false
        return ws.send(msg.toString())
    }

    private fun envelope(type: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("type", type)
            put("id", "m-${idCounter.incrementAndGet()}-${Random.nextInt(0x10000).toString(16)}")
            put("ts", System.currentTimeMillis())
            fields()
        }

    private fun dispatch(block: (Listener) -> Unit) {
        val l = listener ?: return
        callbackExecutor.execute {
            try {
                block(l)
            } catch (e: Exception) {
                DebugLog.log(TAG, "Listener error: ${e.message}")
            }
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
}
