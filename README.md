# Companion Overlay

An animated sprite companion that lives on your Android screen — a **presence endpoint** for the [Nexus](https://github.com/) companion system. The phone renders Senni, captures your voice, and lends its eyes (screen, camera) to the server; all intelligence — persona, conversation, memory, tools — lives server-side in Nexus, reached over a WebSocket (presence protocol v1).

<p align="center">
  <img src="docs/screenshots/screenshot-overlay.png" width="280" alt="Overlay">
  <img src="docs/screenshots/screenshot-settings.png" width="280" alt="Settings">
</p>

## Features

### Core
- **Interactive tutorial** — a hands-on walkthrough of every feature — gestures (tap, repeat-tap, long-press, swipe), voice (with a real spoken reply), volume/headset shortcuts, tool indicators, and ghost mode — running entirely in-app with mocked responses, no permissions, overlay, or network needed. Auto-shows on first launch; replay any time from Settings → About → Tutorial
- **Animated sprite overlay** — idle breathing, walks when tapped, escapes when tapped repeatedly. Presence never depends on connectivity: the sprite renders and reacts fully offline; only conversation needs Nexus
- **Nexus-mandated avatar** — sprite sheets are versioned and served by the server; the app syncs them into a local cache on connect (sha256-verified, atomically swapped) and renders from cache indefinitely when offline. Bundled sprites cover first boot
- **Screenshot + commentary** — long-press the sprite to capture the screen; the image ships to Nexus as a conversation turn and the reply lands in a speech bubble
- **Camera capture** — set long-press to snap a photo instead of a screenshot ("look at this"), sent through the same pipeline
- **Device capabilities** — Nexus can *ask* the phone mid-conversation for a screenshot, a camera shot (front or back), or to post a notification (`cap_request` → `cap_result`)
- **Radial quick menu** — swipe up on the avatar for a small quick-settings disk; swipe down (or tap away) to hide
- **Reply input** — type replies directly in the speech bubble
- **One conversation everywhere** — the session lives server-side keyed by device id; reconnects (and other devices) continue the same conversation
- **Custom sprites** — replace idle/walk sprite sheets with your own PNGs (local override of the synced avatar)
- **Character presets** — multiple characters with independent sprites (prompts are server-side flavor now)

### Voice
- **On-device speech-to-text** — transcripts ship to Nexus as `text` with `source: "voice"`. When the server grows an audio backend, VAD-cut utterances will ship as audio instead (Silero VAD is already on board)
- **On-device text-to-speech** — the server's `speak` directive is spoken locally; server-side synthesis (`speak.audio`) plugs in when Nexus grows it
- **Voice + screenshot** — capture the screen then speak; both sent together as an image turn with a caption
- **Bluetooth headset support** — registered as digital assistant; records via BT headset mic
- **Beep feedback** — synthesized tones for each voice pipeline stage

### Controls
- **Swipe gestures** — swipe up on the avatar to open the radial quick menu, swipe down to close (tap still walks her, long-press still captures — no added tap delay)
- **Radial quick menu** — three toggles you can flip without opening Settings: capture mode (off / screenshot / camera), volume-button shortcut on/off, and voice output on/off. Changes apply live
- **Volume down button shortcut** — double-press: show/hide overlay, triple-press: voice input (on by default, interjects with long press for volume down functionality)
- **Headset button** — long-press triggers voice input
- **Ghost mode** — semi-transparent and click-through when keyboard is visible (also reported to Nexus as a presence event)
- **Auto-copy** — optionally copy responses to clipboard
- **Edge-anchored toasts** — top-right notifications for thinking/tool progress and voice status
- **Screen lock awareness** — pauses on lock, fades in on unlock

## Install

**Debug APK** — download the latest build from [GitHub Actions](../../actions/workflows/build.yml) (Artifacts section), or build from source (see below).

## Setup

### Nexus gateway

Everything conversational needs a running Nexus companion server. In **Settings → Nexus Gateway** set:

1. **Server URL** — where Nexus lives (`https://your-tunnel.example.com` or `192.168.1.7:9597`; `ws://`/`wss://` also accepted). Quick tunnels rotate — the URL is editable at runtime and the app reconnects with backoff whenever it changes
2. **Access token** — the bearer token for `/ws` and the avatar routes (stored in EncryptedSharedPreferences)
3. **Device name** (optional) — how this phone introduces itself

While disconnected, typed/voice messages are queued (bounded, oldest dropped) and flushed on reconnect; images are not queued.

### Permissions

1. **Overlay** — Settings > Apps > Special Access > Display over other apps
2. **Accessibility service** — Settings > Accessibility > enable the service (for screenshots and volume button detection)
3. **Microphone** — grant via Settings > Permissions > Voice input in the app (voice input fails silently until granted)
4. **Camera** (optional) — only needed for camera capture; grant via Settings > Permissions > Camera in the app
5. **Notifications** — shows the overlay status/Stop button, and lets Nexus post notifications via the `notify` capability

Optional: **Digital assistant** — Settings > Apps > Default Apps > Digital assistant app > Companion Overlay (enables headset button support and dedicated assistant button support if present on device)

### Camera capture (optional)

Set **Settings > Display > "Long-press capture"** to **Camera** (the options are Off / Screenshot / Camera) — or flip it from the radial quick menu — to make an upper-body long-press take a back-camera photo. Grant the camera permission under **Settings > Permissions > Camera** first. Capture is headless (no viewfinder) — point the phone, then long-press. There's a brief pause while the lens focuses before the shot is taken. When set to **Off**, an upper-body long-press reopens the last response bubble instead.

## Building

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

| Requirement | Version |
|---|---|
| Min SDK | 31 (Android 12) |
| Target SDK | 34 (Android 14) |
| JDK | 17 |
| Kotlin | 2.1.0 |

Tests: `./gradlew testDebugUnitTest`. The gateway layer is pure JVM; an opt-in live protocol test runs against a real Nexus dev server with `NEXUS_LIVE_URL=... NEXUS_LIVE_TOKEN=... ./gradlew testDebugUnitTest --tests '*GatewayLiveIntegrationTest'`.

## Known Limitations

- **Headset button + fullscreen video** — long-pressing the headset button steals foreground focus, causing YouTube to enter PiP. Use volume button triple-press instead
- **Camera capture is headless** — no viewfinder, so framing is blind; the lens needs a moment to focus before the shot. On Android 14+ capture relies on the overlay's foreground service holding the camera type while another app is visible
- **No server-side voice yet** — `audio` frames and `speak.audio` are protocol-ready but the server has no transcription/synthesis backend; on-device STT/TTS carry voice for now
- **Streaming tokens are ignored** — the app waits for the authoritative `message` instead of rendering `token` deltas progressively

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for a codebase overview, and the Nexus repo's `docs/PRESENCE_PROTOCOL.md` for the wire protocol.

## License

[MIT](LICENSE) — Copyright (c) 2025-2026 TheStarfarer
