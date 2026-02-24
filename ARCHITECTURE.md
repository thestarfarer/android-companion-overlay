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
│  ClaudeApi · TtsManager                              │
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
| `CompanionOverlayService` | Foreground service — sprite rendering, touch handling, speech bubbles, conversation orchestration, TTS routing. Implements `VoiceInputHost` |
| `CompanionAccessibilityService` | Screenshot capture via accessibility API, volume button interception (double/triple-tap detection), keyboard visibility detection |
| `AssistActivity` | Transparent trampoline for `ACTION_VOICE_COMMAND` from headset buttons — starts overlay and toggles voice |
| `CompanionApplication` | App entry point — initializes Koin, creates notification channel |

### AI and API

| Class | Role |
|---|---|
| `ClaudeApi` | Anthropic Messages API client — multi-turn conversation, web search, image attachments, cancellable requests |
| `api/ClaudeModels` | kotlinx.serialization models for Claude API — polymorphic content blocks, request/response types |
| `ConversationManager` | In-memory conversation history (thread-safe). Builds API requests, parses responses, auto-copies to clipboard |

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
| `AudioCoordinator` | Routes audio to the active TTS engine. Gemini → on-device fallback on error. Beep playback, speech completion callbacks |
| `TtsManager` | Android `TextToSpeech` wrapper — lazy init, voice selection, text chunking at sentence boundaries, utterance tracking |
| `GeminiTtsManager` | Gemini TTS API → base64 PCM → `AudioTrack` playback. Markdown cleanup, background synthesis, on-device fallback |
| `BeepManager` | Synthesized sine wave tones via `AudioTrack MODE_STATIC` — ready, step, done, error |
| `BluetoothAudioRouter` | BT headset mic routing via `setCommunicationDevice` (API 31+). mSBC wideband codec negotiation |
| `AudioUtils` | Audio format conversion utilities |

### UI and rendering

| Class | Role |
|---|---|
| `SpriteAnimator` | Animation state machine (idle breathing, walk, escape). Pre-extracts frames to avoid per-tick allocations. Ghost mode when keyboard is visible |
| `BubbleManager` | Manages overlay speech bubbles — main response dialog, brief notifications, voice indicator. Touch and keyboard handling |
| `BubbleStyle` | Centralized bubble styling — Monet Material You colors (API 31+), fallback warm cream, rounded backgrounds |
| `ScreenshotManager` | Screenshot request API — delegates to accessibility service via `OverlayCoordinator` |

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
| `MainActivity` | Settings UI, character preset carousel (ViewPager2), prompt/sprite editing |
| `SettingsActivity` / `SettingsFragment` | `PreferenceFragmentCompat` host — all toggles, model selection, Gemini API key, silence timeout, debug tools, open-source licenses |
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
| `di/AppModule` | `OkHttpClient`, `OverlayCoordinator`, `SettingsRepository`, `ClaudeApi` |
| `di/AudioModule` | `TtsManager`, `GeminiTtsManager`, `BeepManager`, `AudioCoordinator` |
| `di/StorageModule` | `SettingsRepository`, `PresetRepository`, `ConversationStorage` |
| `di/OverlayModule` | `ConversationManager`, `ScreenshotManager`, `VoiceInputController` |
| `di/ViewModelModule` | `MainViewModel` |

### Debug

| Class | Role |
|---|---|
| `DebugLog` | In-memory ring buffer (50 KB), logcat output, copyable from settings UI |

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
│   ├── AssistActivity.kt
│   ├── CompanionOverlayService.kt
│   ├── CompanionAccessibilityService.kt
│   ├── ClaudeApi.kt
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
│   ├── SpriteAnimator.kt
│   ├── BubbleManager.kt
│   ├── BubbleStyle.kt
│   ├── ScreenshotManager.kt
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
│   │   └── ClaudeModels.kt
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

**Claude API** — API key authentication. Models: Sonnet 4.5, Opus 4.1, Opus 4.6. Max tokens: 512 (4096 with web search). Streaming not used — single response. Read timeout 300s.

**Gemini STT** — `gemini-2.5-flash-lite` via REST. 16kHz mono PCM → WAV → base64. Conversation context injected as `systemInstruction` for better name/term recognition. Free tier: 15 RPM, 1000 RPD.

**Gemini TTS** — `gemini-2.5-flash-preview-tts` via REST. Returns 24kHz 16-bit mono PCM (base64). Default voice: Kore. Free tier: 10 RPM.

## Build configuration

| Setting | Value |
|---|---|
| Compile/Target SDK | 34 (Android 14) |
| Min SDK | 31 (Android 12) |
| Kotlin | 1.9.21 |
| JDK | 17 |
| ABI filter | `arm64-v8a` only |
| Release | ProGuard minification + resource shrinking |
| CI | GitHub Actions — debug APK on push to master |
