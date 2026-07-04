# Architecture

Companion Overlay is a **presence endpoint** for the Nexus companion system: it renders Senni, captures voice and images, and exposes device capabilities. All intelligence — persona, conversation state, memory, tools — lives server-side in Nexus, reached over one WebSocket speaking **presence protocol v1** (`docs/PRESENCE_PROTOCOL.md` in the Nexus repo).

Two long-running services back the single-activity app:

- **CompanionOverlayService** — a foreground service that owns the sprite overlay, speech bubbles, the gateway connection, and the voice pipeline
- **CompanionAccessibilityService** — captures screenshots, intercepts volume buttons, and detects keyboard visibility

All dependencies are wired through **Koin** DI. Cross-component communication goes through **OverlayCoordinator** (StateFlows + SharedFlows). App state lives in Koin-managed singletons; the only top-level `object`s are stateless helpers (`DebugLog`, `ImageAudit`, `OverlayController`, `PromptSettings`, `TtsTextCleaner`, `BubbleStyle`, `SettingsDialogs`, `AudioUtils`).

**Design invariant:** presence never depends on connectivity. The sprite animates, reacts to taps, and escapes with the network down; only conversation needs the gateway. Avatar sprites render from a local cache (or bundled assets) indefinitely.

## Layers

```
┌──────────────────────────────────────────────────────┐
│  UI                                                  │
│  MainActivity · SettingsFragment · TutorialActivity  │
└────────────────────────┬─────────────────────────────┘
                         │
┌────────────────────────┴─────────────────────────────┐
│  Services                                            │
│  CompanionOverlayService · CompanionAccessibility…   │
└────────────────────────┬─────────────────────────────┘
                         │
┌────────────────────────┴─────────────────────────────┐
│  Presence & rendering                                │
│  GatewayClient · AvatarRepository                    │
│  SpriteAnimator · BubbleManager · VoiceInputController│
│  AudioCoordinator                                    │
└────────────────────────┬─────────────────────────────┘
                         │
┌────────────────────────┴─────────────────────────────┐
│  Device integration                                  │
│  ScreenshotManager · CameraManager · TtsManager      │
│  SpeechRecognitionManager · BluetoothAudioRouter     │
└────────────────────────┬─────────────────────────────┘
                         │
┌────────────────────────┴─────────────────────────────┐
│  Data                                                │
│  SettingsRepository (= GatewayConfig)                │
│  PresetRepository                                    │
└──────────────────────────────────────────────────────┘
```

## The gateway (presence protocol v1)

| Class | Role |
|---|---|
| `gateway/GatewayClient` | OkHttp WebSocket client, pure JVM (unit-testable off-device). `hello`/`welcome` handshake, outbound `text`/`image`/`event`/`status`/`cap_result`/`ping`, inbound `token`/`message`/`speak`/`animate`/`status`/`session`/`error`/`cap_request`/`pong`. App-level ping every 30s; jittered exponential reconnect 1s→60s; bounded drop-oldest offline queue for `text`/`event` (flushed after `welcome`); per-name event rate limiting (≤1/2s). Listener callbacks are posted to the main thread via an injected executor |
| `gateway/GatewayConfig` | Interface the client reads its server URL, bearer token, and device identity from — implemented by `SettingsRepository`, faked in tests |
| `gateway/AvatarRepository` | Versioned sprite cache under `filesDir/avatar`. On `welcome.avatar_version` mismatch: `GET /avatar/manifest`, download changed files, verify sha256, swap the cache directory atomically (with crash recovery). Frame counts come from the manifest, never constants |

### Protocol mapping in `CompanionOverlayService`

| Wire | App behavior |
|---|---|
| `hello` | Sent on every (re)connect with `kind: "phone"`, a stable per-install `device_id`, and capabilities: screenshot, camera (front/back), notify, mic, stt, tts |
| `text` → | Typed replies (`source: "typed"`) and transcribed voice (`source: "voice"`); queued while offline |
| `image` → | Long-press screenshot/camera capture (base64 JPEG q80/95, downscaled) with the spoken caption when voice+screenshot is on; never queued offline |
| `event` → | `tapped` (avatar tap) and `keyboard_visible` (ghost mode edge) |
| `status` → | Battery/network/muted snapshot after each `welcome` |
| ← `token` | Ignored — the app waits for the authoritative `message` |
| ← `message` | Speech bubble via `BubbleManager` (also auto-copy, last-reply recall) |
| ← `speak` | Local `TtsManager` speech (when TTS is enabled or the turn was voice); `speak.audio` playback is the future server-side-TTS slot |
| ← `animate` | Advisory mapping onto `SpriteAnimator`: `walk`→walk, `escape`/`alert`→escape; unrenderable states ignored. Local reactive animation continues underneath |
| ← `status` | `thinking`/`tool_running` → brief thinking/🔧 toast |
| ← `cap_request` | screenshot (accessibility path), camera (respects `facing`), notify (system notification); failures answer `{ok:false, error}` |
| ← `session`, `error` | Logged; errors surface as a brief bubble and reset voice state |

## Components

### Core services

| Class | Role |
|---|---|
| `CompanionOverlayService` | Foreground service — sprite rendering, touch handling, speech bubbles, gateway lifecycle (start on create, stop on destroy), capability handlers, TTS routing. Implements `VoiceInputHost` and `GatewayClient.Listener`. Runs as a `specialUse` FGS and promotes to the `microphone`/`camera` types only while recording/capturing (see below) |
| `CompanionAccessibilityService` | Screenshot capture via accessibility API, volume button interception (double/triple-tap detection), keyboard visibility detection |
| `AssistActivity` | Transparent trampoline for `ACTION_VOICE_COMMAND` from headset buttons — starts overlay and toggles voice |
| `OverlayController` | Single entry point for starting the overlay service — permission check (`canStart`) plus the start-then-optionally-toggle-voice ceremony |
| `CompanionApplication` | App entry point — initializes Koin, creates notification channel |

#### Foreground service types (Android 14+/17 compatibility)

`microphone` and `camera` are **while-in-use** FGS types: Android only permits a service to hold them when the app is in an eligible (foreground/visible) state. Claiming them at every service start crashes with `SecurityException: Starting FGS with type microphone … must be in the eligible state` when the overlay is started from a non-foreground surface (wake button, assistant) — strictly enforced on Android 17.

So the service starts as `specialUse` only and dynamically promotes:
- `setMicrophoneActive(true/false)` — driven by `VoiceInputController` state; the `microphone` type is added on entering `LISTENING` and dropped on every exit.
- `setCameraForeground(true/false)` — wraps a camera capture (user long-press or server `cap_request`).

Promotion is permitted because the companion's visible overlay window is the while-in-use exemption. All type changes go through `applyForegroundType()`, which is wrapped so a rejected promotion degrades (logs) instead of crashing. The types remain declared in the manifest so they can be promoted into.

The service also self-guards: if "Display over other apps" is missing, `onCreate` shows a message and stops cleanly rather than throwing `BadTokenException` from `addView`.

### Voice pipeline

| Class | Role |
|---|---|
| `VoiceInputController` | State machine (`IDLE` → `LISTENING` → `PROCESSING`). Orchestrates on-device STT and ships transcripts to the gateway. Safety timeouts |
| `VoiceInputHost` | Interface defining the voice controller's contract with the service (show/hide bubbles, send input, mic FGS promotion) |
| `SpeechRecognitionManager` | Wraps Android `SpeechRecognizer` with manual silence timeout and segment accumulation across recognizer restarts. The local/fallback STT path — the protocol's primary voice path (`audio` frames, server-side transcription) activates when the server grows an audio backend |
| `SileroVadDetector` | ONNX Runtime wrapper for Silero VAD v5 — 16kHz mono PCM in 512-sample windows, maintains LSTM state across calls. Kept for the future `audio`-frame path (currently unwired) |

### Audio output

| Class | Role |
|---|---|
| `AudioCoordinator` | Routes `speak` text to the on-device TTS engine, audio focus around speech/recording, beep playback, BT A2DP keep-alive via `SilenceKeepAlive` |
| `TtsManager` | Android `TextToSpeech` wrapper — lazy init, voice selection, text chunking at sentence boundaries, utterance tracking |
| `BeepManager` | Synthesized sine wave tones via persistent `SoundPool` (`USAGE_ASSISTANCE_SONIFICATION`) — ready, step, done, error |
| `SilenceKeepAlive` | Near-silent audio stream keeping BT A2DP codec warm — prevents power-down and leading-edge clipping |
| `BluetoothAudioRouter` | Routes mic capture to a connected BT headset via `setCommunicationDevice` (API 31+), preferring BLE then classic SCO |
| `AudioUtils` | Audio format conversion utilities (PCM→WAV) — retained for the future `audio`-frame path |

### UI and rendering

| Class | Role |
|---|---|
| `SpriteAnimator` | Animation state machine (idle breathing, walk, escape). Sheet load order: user-picked custom URI > Nexus avatar cache (manifest frame counts) > bundled custom asset > bundled default. Pre-extracts frames to avoid per-tick allocations. Ghost mode when keyboard is visible. `walk()`/`escape()` double as `animate` directive handlers |
| `SpriteSurface` | Seam between `SpriteAnimator` and where it draws — overlay window vs. plain view group (tutorial) |
| `BubbleManager` | Manages speech bubbles — main response dialog with reply input, brief notifications, voice indicator. Placement/removal delegated to a `BubbleSurface` |
| `BubbleSurface` / `BubbleStyle` | Surface seam and centralized styling (Monet Material You, API 31+) |
| `ScreenshotManager` | Screenshot request API — delegates to accessibility service via `OverlayCoordinator`. Produces base64 JPEG (quality 80, downscaled) |
| `CameraManager` | Headless still capture via CameraX (front or back per the `facing` param) — binds `ImageCapture` to a throwaway `LifecycleOwner`, waits for focus, returns a downscaled (1568px), EXIF-uprighted base64 JPEG |
| `RadialMenuManager` / `RadialMenuView` | Quick-access settings disk — capture mode, volume shortcut, voice output toggle. Live prefs reads mean changes apply with no restart |

### Settings and storage

| Class | Role |
|---|---|
| `repository/SettingsRepository` | Repository over `SharedPreferences`; single source of truth for all settings. Implements `GatewayConfig`: server URL + device name/id in plain prefs, bearer token in `EncryptedSharedPreferences`. `deviceId` is generated once per install (`phone-xxxxxxxx`) — the server keys session resume on it |
| `repository/TutorialSettings` | Tutorial-scoped sandbox — radial-mutable settings (capture mode, volume shortcut, TTS) overridden in memory |
| `repository/PresetRepository` | Character preset persistence — in-memory cache, JSON serialization |
| `CharacterPreset` | Data class — name, prompts (local flavor; the persona lives in Nexus), sprite URIs, frame counts |
| `PromptSettings` | Constants — defaults and the preset prompt template |

### State management

| Class | Role |
|---|---|
| `event/OverlayCoordinator` | Central event hub — `StateFlow` for overlay/accessibility status, `SharedFlow` for fire-and-forget events (screenshots, voice toggle, keyboard, sprite reload, gateway config changes) |
| `event/OverlayEvent` | Sealed class for pure data events — no callbacks or lambdas in event stream |
| `viewmodel/MainViewModel` | `ViewModel` for `MainActivity` state (presets, overlay running, gateway configured) |

### UI helpers

| Class | Role |
|---|---|
| `MainActivity` | Character preset carousel (ViewPager2), prompt/sprite editing, gateway status row, overlay toggle. Launches the tutorial on first run |
| `SettingsActivity` / `SettingsFragment` | `PreferenceFragmentCompat` host — Nexus gateway (URL/token/device name), display, voice, conversation, permissions, debug tools |
| `TutorialActivity` | Self-contained interactive walkthrough with mocked pipelines — no overlay/service/permissions/network |
| `SettingsDialogs` / `ui/*` | Reusable dialog builders, preset pager/dialog/picker helpers, sprite preview animation, text editor bottom sheet |

### Dependency injection (Koin)

| Module | Provides |
|---|---|
| `di/AppModule` | `OkHttpClient`, `OverlayCoordinator`, `SettingsRepository`, `TutorialSettings`, `GatewayClient` (singleton, main-thread callback executor), `AvatarRepository` |
| `di/AudioModule` | `TtsManager`, `AudioCoordinator` |
| `di/StorageModule` | `SharedPreferences` (settings + encrypted), `PresetRepository` |
| `di/OverlayModule` | `ScreenshotManager`, `CameraManager`, `BeepManager` |
| `di/ViewModelModule` | `MainViewModel` |

### Debug

| Class | Role |
|---|---|
| `DebugLog` | In-memory ring buffer (50 KB), logcat output, copyable from settings UI |
| `ImageAudit` | Gated on the `saveSentImages` user setting (off by default) — saves the exact JPEG sent to Nexus (screenshot or camera) to external files, viewable on-device via `FileProvider` |

## Project structure

```
app/src/main/
├── AndroidManifest.xml
├── assets/
│   ├── idle_sheet.png                 # Default idle sprite (6 frames)
│   ├── walk_sheet.png                 # Default walk sprite (4 frames)
│   ├── custom_idle_sheet.png          # First-boot default (pre-avatar-sync)
│   ├── custom_walk_sheet.png          # First-boot default (pre-avatar-sync)
│   └── silero_vad.onnx                # Silero VAD v5 model
├── java/com/starfarer/companionoverlay/
│   ├── CompanionApplication.kt
│   ├── MainActivity.kt
│   ├── SettingsActivity.kt / SettingsFragment.kt / SettingsDialogs.kt
│   ├── TutorialActivity.kt / AssistActivity.kt
│   ├── CompanionOverlayService.kt
│   ├── CompanionAccessibilityService.kt
│   ├── VoiceInputController.kt / VoiceInputHost.kt
│   ├── SpeechRecognitionManager.kt / SileroVadDetector.kt
│   ├── AudioCoordinator.kt / AudioUtils.kt / TtsManager.kt / TtsTextCleaner.kt
│   ├── BeepManager.kt / BluetoothAudioRouter.kt / SilenceKeepAlive.kt
│   ├── SpriteAnimator.kt / SpriteSurface.kt
│   ├── BubbleManager.kt / BubbleStyle.kt / BubbleSurface.kt
│   ├── ScreenshotManager.kt / CameraManager.kt / ImageAudit.kt
│   ├── OverlayController.kt
│   ├── RadialMenuManager.kt / RadialMenuView.kt
│   ├── CharacterPreset.kt / PromptSettings.kt / DebugLog.kt
│   ├── gateway/
│   │   ├── GatewayClient.kt           # presence protocol v1 WebSocket client
│   │   ├── GatewayConfig.kt
│   │   └── AvatarRepository.kt        # versioned sprite cache + sync
│   ├── avatar3d/                      # Experimental 3D Filament avatar (hidden overlayMode pref)
│   ├── di/                            # Koin modules
│   ├── event/                         # OverlayCoordinator + OverlayEvent
│   ├── repository/                    # SettingsRepository, TutorialSettings, PresetRepository
│   ├── ui/                            # Dialog/pager/picker helpers
│   └── viewmodel/MainViewModel.kt
└── res/                               # layouts, strings, prefs XML, icons
```

## Design patterns

- **Thin client / server brain** — the app holds no conversation state; the gateway is a dumb pipe and everything above it renders or captures
- **Dependency injection** — Koin modules wire all stateful components
- **Repository pattern** — `SettingsRepository`, `PresetRepository` abstract storage behind clean interfaces
- **State machine** — `VoiceInputController` transitions through `IDLE` → `LISTENING` → `PROCESSING` with explicit state guards
- **Coordinator / event bus** — `OverlayCoordinator` uses `StateFlow` for observable state and `SharedFlow` for fire-and-forget events; pure data events only
- **Seams for testability** — `GatewayClient`/`AvatarRepository` are Android-free and covered by JVM tests (MockWebServer protocol suite + an opt-in live test against a real Nexus dev server)

## Threading

| Context | Used for |
|---|---|
| Main thread | UI, gateway listener callbacks (posted via executor), animation (Choreographer + Handler watchdog) |
| `Dispatchers.IO` | Avatar sync downloads, file I/O |
| OkHttp threads | WebSocket reads/writes; a single daemon scheduler inside `GatewayClient` drives pings and reconnect backoff |
| Background threads | TTS synthesis callbacks (engine-owned) |

Thread-safety primitives: a single lock guarding `GatewayClient` connection state and queue, `AtomicBoolean`/`AtomicInteger` latches, `@Volatile` shared flags, coroutine scopes with lifecycle-aware cancellation.

## Server integration

**Presence protocol v1** — see `docs/PRESENCE_PROTOCOL.md` in the Nexus repo. JSON text frames over WebSocket at `<server>/ws`, `Authorization: Bearer <token>`; binary payloads base64-inline in v1. The server registers each declared capability as a Claude tool (`device_screenshot`, `device_camera`, `device_notify`) for the duration of the connection.

**Avatar assets** — `GET /avatar/manifest` and `GET /avatar/asset/<file>` (same bearer token) with sha256-verified, version-keyed caching.

## Build configuration

| Setting | Value |
|---|---|
| Compile SDK | 35 (Android 15) |
| Target SDK | 34 (Android 14) |
| Min SDK | 31 (Android 12) |
| Kotlin | 2.1.0 |
| JDK | 17 |
| ABI filter | `arm64-v8a` only |
| Release | ProGuard minification + resource shrinking |
| CI | GitHub Actions (`workflow_dispatch`) — runs `testDebugUnitTest`, then `assembleDebug` and uploads the debug APK |
