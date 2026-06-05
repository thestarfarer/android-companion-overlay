# Architecture

Companion Overlay is a single-activity Android app backed by two long-running services:

- **CompanionOverlayService** — a foreground service that owns the sprite overlay, speech bubbles, conversation state, and voice pipeline
- **CompanionAccessibilityService** — captures screenshots, intercepts volume buttons, and detects keyboard visibility

All dependencies are wired through **Koin** DI. Cross-component communication goes through **OverlayCoordinator** (StateFlows + SharedFlows), with no static singletons.

## Layers

```
┌──────────────────────────────────────────────────────┐
│  UI                                                  │
│  MainActivity · SettingsFragment · AssistActivity     │
└────────────────────────┬─────────────────────────────┘
                         │
┌────────────────────────┴─────────────────────────────┐
│  Services                                            │
│  CompanionOverlayService · CompanionAccessibility…   │
└────────────────────────┬─────────────────────────────┘
                         │
┌────────────────────────┴─────────────────────────────┐
│  Business logic                                      │
│  ConversationManager · VoiceInputController          │
│  AudioCoordinator · SpriteAnimator · BubbleManager   │
└────────────────────────┬─────────────────────────────┘
                         │
┌────────────────────────┴─────────────────────────────┐
│  Integration                                         │
│  ClaudeApi · TtsManager · McpManager                  │
│  GeminiSpeechRecognizer · GeminiTtsManager           │
│  SpeechRecognitionManager · BluetoothAudioRouter     │
└────────────────────────┬─────────────────────────────┘
                         │
┌────────────────────────┴─────────────────────────────┐
│  Data                                                │
│  SettingsRepository · PresetRepository               │
│  ConversationStorage                                 │
└──────────────────────────────────────────────────────┘
```

## Components

### Core services

| Class | Role |
|---|---|
| `CompanionOverlayService` | Foreground service — sprite rendering, touch handling, speech bubbles, conversation orchestration, TTS routing. Implements `VoiceInputHost`. Runs as a `specialUse` FGS and promotes to the `microphone`/`camera` types only while recording/capturing (see below) |
| `CompanionAccessibilityService` | Screenshot capture via accessibility API, volume button interception (double/triple-tap detection), keyboard visibility detection |
| `AssistActivity` | Transparent trampoline for `ACTION_VOICE_COMMAND` from headset buttons — starts overlay and toggles voice |
| `OverlayController` | Single entry point for starting the overlay service — permission check (`canStart`) plus the start-then-optionally-toggle-voice ceremony. Every surface (main button, volume gestures, assistant) routes through it so the start logic can't drift |
| `CompanionApplication` | App entry point — initializes Koin, creates notification channel |

#### Foreground service types (Android 14+/17 compatibility)

`microphone` and `camera` are **while-in-use** FGS types: Android only permits a service to hold them when the app is in an eligible (foreground/visible) state. Claiming them at every service start crashes with `SecurityException: Starting FGS with type microphone … must be in the eligible state` when the overlay is started from a non-foreground surface (wake button, assistant) — strictly enforced on Android 17.

So the service starts as `specialUse` only and dynamically promotes:
- `setMicrophoneActive(true/false)` — driven by `VoiceInputController` state; the `microphone` type is added on entering `LISTENING` and dropped on every exit.
- `setCameraForeground(true/false)` — wraps a camera capture.

Promotion is permitted because the companion's visible overlay window is the while-in-use exemption. All type changes go through `applyForegroundType()`, which is wrapped so a rejected promotion degrades (logs) instead of crashing. The types remain declared in the manifest so they can be promoted into.

The service also self-guards: if "Display over other apps" is missing, `onCreate` shows a message and stops cleanly rather than throwing `BadTokenException` from `addView`.

### AI and API

| Class | Role |
|---|---|
| `ClaudeApi` | Anthropic Messages API client — multi-turn conversation, web search, image attachments, MCP tools parameter, cancellable requests |
| `api/ClaudeModels` | kotlinx.serialization models for Claude API — polymorphic content blocks (text, image, tool_use, tool_result), `Message.timestamp`, request/response types |
| `api/ClaudeBilling` | Billing header computation for Claude API requests, client version tracking |
| `ConversationManager` | In-memory conversation history (thread-safe). Builds API requests, MCP tool use loop (up to 10 iterations), Nexus session summary emission, timestamp tracking, auto-copy |

### MCP subsystem

| Class | Role |
|---|---|
| `mcp/McpClient` | Single MCP server connection via Streamable HTTP transport — initialization handshake, tool discovery, tool execution, session management, optional client credentials auth |
| `mcp/McpManager` | Aggregates tool definitions from all connected servers, routes tool_use calls to the correct server, broadcast `emitEventToAll` for Nexus |
| `mcp/McpModels` | kotlinx.serialization models for JSON-RPC 2.0, MCP initialize params, tool definitions, server configs |
| `mcp/McpRepository` | Server config persistence (SharedPreferences) and client secrets (EncryptedSharedPreferences) |
| `mcp/AsyncResultPoller` | Polls MCP servers for completed async job results (`nexus_poll_results`), queues for conversation injection |

### Voice pipeline

| Class | Role |
|---|---|
| `VoiceInputController` | State machine (`IDLE` → `LISTENING` → `PROCESSING`). Orchestrates STT, transcription, API calls. Safety timeouts, request cancellation |
| `VoiceInputHost` | Interface defining the voice controller's contract with the service (show/hide bubbles, send input, get context) |
| `SpeechRecognitionManager` | Wraps Android `SpeechRecognizer` with manual silence timeout and segment accumulation across recognizer restarts |
| `GeminiSpeechRecognizer` | `AudioRecord` → WAV → Gemini flash-lite transcription. Conversation context injected via `systemInstruction`. Uses Silero VAD for silence detection |
| `SileroVadDetector` | ONNX Runtime wrapper for Silero VAD v5 — 16kHz mono PCM in 512-sample windows, maintains LSTM state across calls |

### Audio output

| Class | Role |
|---|---|
| `AudioCoordinator` | Routes audio to the active TTS engine. Gemini → on-device fallback on error. Beep playback, speech completion callbacks. Integrates `SilenceKeepAlive` for BT A2DP keep-alive |
| `TtsManager` | Android `TextToSpeech` wrapper — lazy init, voice selection, text chunking at sentence boundaries, utterance tracking |
| `GeminiTtsManager` | Gemini TTS API → base64 PCM → `AudioTrack` playback. Markdown cleanup, background synthesis, on-device fallback |
| `BeepManager` | Synthesized sine wave tones via persistent `SoundPool` (`USAGE_ASSISTANCE_SONIFICATION`) — ready, step, done, error, queue |
| `SilenceKeepAlive` | Near-silent audio stream keeping BT A2DP codec warm — prevents power-down and leading-edge clipping |
| `BluetoothAudioRouter` | BT headset mic routing via `setCommunicationDevice` (API 31+). mSBC wideband codec negotiation |
| `AudioUtils` | Audio format conversion utilities |

### UI and rendering

| Class | Role |
|---|---|
| `SpriteAnimator` | Animation state machine (idle breathing, walk, escape). Pre-extracts frames to avoid per-tick allocations. Ghost mode when keyboard is visible. Position/ghost applied through a `SpriteSurface`, so it runs on the overlay window or a plain view group unchanged |
| `SpriteSurface` | Seam between `SpriteAnimator` and where it draws — `OverlaySpriteSurface` (real `TYPE_APPLICATION_OVERLAY` window, position via `updateViewLayout`, ghost via window flags) vs. `ViewGroupSpriteSurface` (a `View` in a `FrameLayout`, position via `view.x`). Mirrors `BubbleSurface` |
| `BubbleManager` | Manages speech bubbles — main response dialog, brief notifications, voice indicator. Touch and keyboard handling. Placement/removal delegated to a `BubbleSurface`, so it never touches `WindowManager` directly |
| `BubbleSurface` | Seam between `BubbleManager` and its surface — `OverlayBubbleSurface` (one overlay window per bubble, incl. the reply-input focus promotion) vs. `ViewGroupBubbleSurface` (bubbles as `FrameLayout` children, for the in-app tutorial) |
| `BubbleStyle` | Centralized bubble styling — Monet Material You colors (API 31+), fallback warm cream, rounded backgrounds |
| `ScreenshotManager` | Screenshot request API — delegates to accessibility service via `OverlayCoordinator` |
| `CameraManager` | Headless back-camera still capture via CameraX — binds `ImageCapture` to a throwaway `LifecycleOwner`, waits for focus, returns a downscaled (1568px), EXIF-uprighted base64 JPEG. Same artifact as `ScreenshotManager`, so the send pipeline is shared |
| `RadialMenuManager` / `RadialMenuView` | Quick-access settings disk — a material-styled sector overlay (center-right edge) opened by swipe-up / closed by swipe-down or tap-away. Three emoji toggles (capture mode, volume shortcut, Gemini voice) write straight to `SettingsRepository`; live reads mean changes apply with no restart. `RadialMenuManager` owns the window lifecycle (mirrors `BubbleManager`); `RadialMenuView` draws the sector + glyphs and hit-tests by angle |

### Settings and storage

| Class | Role |
|---|---|
| `repository/SettingsRepository` | Repository over `SharedPreferences`. Single source of truth for all settings |
| `repository/PresetRepository` | Character preset persistence — in-memory cache, JSON serialization, immutable list return |
| `ConversationStorage` | File-based JSON persistence of conversation history. Atomic writes via temp file + rename |
| `CharacterPreset` | Data class — name, system/user prompts, sprite URIs, frame counts, timestamp |
| `PromptSettings` | Constants — defaults, model IDs, timeouts, system prompt template |

### State management

| Class | Role |
|---|---|
| `event/OverlayCoordinator` | Central event hub — `StateFlow` for overlay/accessibility status, `SharedFlow` for fire-and-forget events (screenshots, voice toggle, keyboard) |
| `event/OverlayEvent` | Sealed class for pure data events — no callbacks or lambdas in event stream |
| `viewmodel/MainViewModel` | `ViewModel` for `MainActivity` state |

### UI helpers

| Class | Role |
|---|---|
| `MainActivity` | Settings UI, character preset carousel (ViewPager2), prompt/sprite editing. Launches the tutorial on first run |
| `SettingsActivity` / `SettingsFragment` | `PreferenceFragmentCompat` host — all toggles, model selection, Gemini API key, silence timeout, debug tools, tutorial replay, open-source licenses |
| `TutorialActivity` | Self-contained interactive walkthrough — hosts the real `SpriteAnimator`, `BubbleManager`, `RadialMenuView`, `BeepManager`, and on-device `TtsManager` in a normal Activity sandbox (no overlay/service/permissions/network) via the `ViewGroup*Surface` implementations. A **data-driven step machine** (`Step` objects with per-step enter/exit/gesture hooks and gating) covers tap/escape/long-press/swipe plus voice (with a real spoken reply), volume/headset shortcuts (auto-play animation), web-search/tool indicators, and ghost mode — all with scripted mock pipelines and verbatim indicator strings. Snapshots and restores the four radial-menu settings so nothing the user flips persists |
| `SettingsDialogs` | Reusable dialog builders |
| `ui/PresetPagerAdapter` | ViewPager2 adapter for preset carousel |
| `ui/PresetDialogHelper` | Dialogs for preset creation/editing |
| `ui/SpritePickerHelper` | File picker for custom sprite sheets |
| `ui/SpritePreviewAnimator` | Sprite preview animation in settings |
| `ui/TextEditorBottomSheet` | Bottom sheet for prompt/message editing |

### Android Auto

| Class | Role |
|---|---|
| `CompanionCarAppService` | `CarAppService` entry point |
| `CompanionCarSession` | Session for car app interaction |
| `CompanionMainScreen` / `CompanionModelScreen` / `CompanionResponseScreen` | Car app UI screens |
| `CarVoiceRecorder` | Voice input for automotive context |

### Dependency injection (Koin)

| Module | Provides |
|---|---|
| `di/AppModule` | `OkHttpClient`, `OverlayCoordinator`, `SettingsRepository`, `ClaudeAuth`, `ClaudeApi`, `McpRepository`, `McpManager` |
| `di/AudioModule` | `TtsManager`, `GeminiTtsManager`, `AudioCoordinator` |
| `di/StorageModule` | `SharedPreferences` (settings + auth), `PresetRepository` |
| `di/OverlayModule` | `ConversationStorage`, `ConversationManager` (factory), `ScreenshotManager`, `CameraManager`, `BeepManager` |
| `di/ViewModelModule` | `MainViewModel` |

### Debug

| Class | Role |
|---|---|
| `DebugLog` | In-memory ring buffer (50 KB), logcat output, copyable from settings UI |
| `ImageAudit` | Debug-gated — saves the exact JPEG sent to Claude (screenshot or camera) to external files, logs resolution/size, viewable on-device via `FileProvider` |

## Project structure

```
app/src/main/
├── AndroidManifest.xml
├── assets/
│   ├── idle_sheet.png                 # Default idle sprite (6 frames)
│   ├── walk_sheet.png                 # Default walk sprite (4 frames)
│   ├── custom_idle_sheet.png          # User-editable idle sprite
│   ├── custom_walk_sheet.png          # User-editable walk sprite
│   ├── custom_prompt.txt              # Custom system prompt
│   └── silero_vad.onnx               # Silero VAD v5 model
├── java/com/starfarer/companionoverlay/
│   ├── CompanionApplication.kt
│   ├── MainActivity.kt
│   ├── SettingsActivity.kt
│   ├── SettingsFragment.kt
│   ├── SettingsDialogs.kt
│   ├── TutorialActivity.kt
│   ├── AssistActivity.kt
│   ├── CompanionOverlayService.kt
│   ├── CompanionAccessibilityService.kt
│   ├── ClaudeApi.kt
│   ├── ClaudeAuth.kt
│   ├── ConversationManager.kt
│   ├── ConversationStorage.kt
│   ├── VoiceInputController.kt
│   ├── VoiceInputHost.kt
│   ├── SpeechRecognitionManager.kt
│   ├── GeminiSpeechRecognizer.kt
│   ├── SileroVadDetector.kt
│   ├── AudioCoordinator.kt
│   ├── AudioUtils.kt
│   ├── TtsManager.kt
│   ├── GeminiTtsManager.kt
│   ├── BeepManager.kt
│   ├── BluetoothAudioRouter.kt
│   ├── SilenceKeepAlive.kt
│   ├── SpriteAnimator.kt
│   ├── SpriteSurface.kt
│   ├── BubbleManager.kt
│   ├── BubbleStyle.kt
│   ├── BubbleSurface.kt
│   ├── ScreenshotManager.kt
│   ├── CameraManager.kt
│   ├── ImageAudit.kt
│   ├── OverlayController.kt
│   ├── RadialMenuManager.kt
│   ├── RadialMenuView.kt
│   ├── CharacterPreset.kt
│   ├── PromptSettings.kt
│   ├── DebugLog.kt
│   ├── CompanionCarAppService.kt
│   ├── CompanionCarSession.kt
│   ├── CompanionMainScreen.kt
│   ├── CompanionModelScreen.kt
│   ├── CompanionResponseScreen.kt
│   ├── CarVoiceRecorder.kt
│   ├── api/
│   │   ├── ClaudeBilling.kt
│   │   └── ClaudeModels.kt
│   ├── mcp/
│   │   ├── AsyncResultPoller.kt
│   │   ├── McpClient.kt
│   │   ├── McpManager.kt
│   │   ├── McpModels.kt
│   │   └── McpRepository.kt
│   ├── di/
│   │   ├── AppModule.kt
│   │   ├── AudioModule.kt
│   │   ├── StorageModule.kt
│   │   ├── OverlayModule.kt
│   │   └── ViewModelModule.kt
│   ├── event/
│   │   ├── OverlayCoordinator.kt
│   │   └── OverlayEvent.kt
│   ├── repository/
│   │   ├── SettingsRepository.kt
│   │   └── PresetRepository.kt
│   ├── ui/
│   │   ├── PresetPagerAdapter.kt
│   │   ├── PresetDialogHelper.kt
│   │   ├── SpritePickerHelper.kt
│   │   ├── SpritePreviewAnimator.kt
│   │   └── TextEditorBottomSheet.kt
│   └── viewmodel/
│       └── MainViewModel.kt
└── res/
    ├── drawable/                       # Icons, backgrounds, scrollbar
    ├── layout/                         # activity_main, activity_settings, dialogs, preset card
    ├── mipmap-*/                       # Launcher icons
    ├── transition/                     # Fade in/out, shared element
    ├── values/                         # strings, colors, themes, arrays
    ├── values-night/                   # Dark theme overrides
    └── xml/                            # Accessibility config, settings preferences, automotive
```

## Design patterns

- **Dependency injection** — Koin modules wire all components; no static singletons except `DebugLog`
- **Repository pattern** — `SettingsRepository`, `PresetRepository`, `ConversationStorage` abstract storage behind clean interfaces
- **State machine** — `VoiceInputController` transitions through `IDLE` → `LISTENING` → `PROCESSING` with explicit state guards
- **Coordinator / event bus** — `OverlayCoordinator` uses `StateFlow` for observable state and `SharedFlow` for fire-and-forget events; pure data events only (no lambdas)
- **Strategy** — dual STT and TTS engines selected at runtime based on user settings, with automatic fallback

## Threading

| Context | Used for |
|---|---|
| Main thread | UI, callbacks, animation (Choreographer + Handler watchdog) |
| `Dispatchers.IO` | File I/O, API calls, disk storage |
| Background threads | Audio recording (`GeminiSpeechRecognizer`), TTS synthesis (`GeminiTtsManager`) |

Thread-safety primitives: `AtomicReference` (API call cancellation), `AtomicBoolean` (recording state), `Collections.synchronizedList` (conversation history), `@Volatile` (shared mutable state), coroutine scopes with lifecycle-aware cancellation.

## API integration

**Claude API** — API key authentication (OAuth fallback). Models: Sonnet 4.5, Opus 4.1, Opus 4.6. Max tokens: 512 (4096 with tools/web search). Streaming not used — single response. Read timeout 300s.

**Gemini STT** — `gemini-2.5-flash-lite` via REST. 16kHz mono PCM → WAV → base64. Conversation context injected as `systemInstruction` for better name/term recognition. Free tier: 15 RPM, 1000 RPD.

**Gemini TTS** — `gemini-2.5-flash-preview-tts` via REST. Returns 24kHz 16-bit mono PCM (base64). Default voice: Kore. Free tier: 10 RPM.

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
| CI | GitHub Actions — debug APK on push to master |
