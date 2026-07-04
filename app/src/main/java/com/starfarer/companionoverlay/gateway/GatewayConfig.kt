package com.starfarer.companionoverlay.gateway

/**
 * Connection configuration for [GatewayClient]. Implemented by
 * [com.starfarer.companionoverlay.repository.SettingsRepository] in the app;
 * tests provide plain values.
 *
 * Pure JVM — no Android types — so the gateway layer can be exercised in
 * plain unit tests against a real Nexus server.
 */
interface GatewayConfig {
    /** Nexus base URL (http/https/ws/wss, no trailing /ws). Null = unconfigured. */
    val serverUrl: String?

    /** Bearer token for /ws and the avatar HTTP routes. Null = unconfigured. */
    val token: String?

    /** Stable per-install device id — the server keys session resume on this. */
    val deviceId: String

    /** Human-readable device name shown in server logs. */
    val deviceName: String
}
