# Companion Overlay

Android overlay companion app — an animated sprite character powered by Claude AI that lives on your screen. Talk to her by voice, text, or screenshot. She talks back.

## Features

### Core
- **Animated sprite overlay** — Idle breathing animation, walks when tapped, escapes when tapped repeatedly. 60 FPS rendering via foreground service
- **Screenshot + AI commentary** — Long-press the sprite to capture the screen and get Claude's response in a speech bubble
- **Reply input** — Type replies directly in the speech bubble to continue conversation
- **Conversation memory** — Configurable history (5–30 turns), persists images, optionally survives restarts
- **Model selector** — Sonnet 4.5, Opus 4.1, Opus 4.6
- **Web search** — Toggle web search for Claude responses (increases max tokens to 4096)
- **Custom sprites** — Replace idle/walk sprite sheets with your own PNGs

### Voice
- **Triple-tap voice input** — Triple-tap volume down to start/stop voice recording
- **Bluetooth headset support** — Registered as device digital assistant; Shokz OpenComm long-press triggers voice input
- **Dual STT engines:**
  - **On-device** (Google SpeechRecognizer) — Zero latency, offline capable, free. Has profanity filter (mitigated with dictionary decensor + regex fallback). Grabs audio focus (pauses media)
  - **Gemini STT** (gemini-2.5-flash-lite) — Context-aware transcription using conversation history for better name/term recognition. No audio focus grab (YouTube keeps playing). No profanity filter. Requires API key
- **Dual TTS engines:**
  - **On-device** (Android TTS) — Instant, offline, configurable voice/pitch/rate. English only (mangles Cyrillic)
  - **Gemini TTS** (gemini-2.5-flash-preview-tts) — Multilingual, handles Russian↔English code-switching naturally. 24kHz HD audio via AudioTrack. Known issue: voice consistency can drift on long responses (Gemini server-side bug)
- **Segment accumulation** — Custom silence detection with configurable timeout (0.1s–5.0s). On-device recognizer restarts and accumulates segments across silence gaps for natural speech capture
- **Voice + Screenshot** — Capture screen then speak: screenshot and voice text sent together to Claude
- **TTS-aware responses** — "Thinking..." bubble during API call, auto-dismissed when speech starts. Long responses chunked at sentence boundaries with proper completion tracking

### UI & Controls
- **Volume button toggle** — Double-tap: show/hide overlay. Triple-tap: voice input
- **Headset button** — Long-press triggers voice via digital assistant registration
- **Ghost mode** — Semi-transparent and click-through when keyboard is visible
- **Auto-copy** — Optionally copy responses to clipboard
- **Configurable bubble timeout** — 15/30/60/120 seconds
- **Position persistence** — Avatar position and facing direction survive restarts
- **Screen lock awareness** — Pauses animation on screen off, fades in on unlock
- **Scrollable settings** — Gold-themed scrollbar, all options in one scrollable view
- **Silence timeout slider** — Adjustable from settings UI

## Setup

### Permissions

1. **Overlay** — Settings → Apps → Special Access → Display over other apps
2. **Accessibility Service** — Settings → Accessibility → enable the service (screenshots + volume button detection)
3. **Microphone** — Prompted on first voice input
4. **Digital Assistant** (optional) — Settings → Apps → Default Apps → Digital assistant app → set to Companion Overlay (enables headset button support)

### Authentication

Tap **Authenticate** in the app. Opens browser for Claude OAuth (PKCE). Token stored in EncryptedSharedPreferences, auto-refreshed before expiry.

### Gemini API (optional)

For Gemini STT/TTS: get a free API key from [Google AI Studio](https://aistudio.google.com/apikey), paste it in settings. Same key powers both STT and TTS.

Free tier limits:
- STT (flash-lite): 15 RPM, 1000 RPD
- TTS (flash-preview-tts): 10 RPM

## Architecture

| Class | Role |
|---|---|
| `MainActivity` | Settings UI, auth flow, prompt/sprite editing, all toggles |
| `CompanionOverlayService` | Foreground service, sprite rendering, touch handling, speech bubbles, conversation management, TTS routing |
| `CompanionAccessibilityService` | Screenshot capture, volume button interception (double/triple-tap), keyboard detection |
| `AssistActivity` | Transparent trampoline for ACTION_VOICE_COMMAND from headset buttons |
| `ClaudeAuth` | OAuth 2.0 PKCE, EncryptedSharedPreferences, token refresh |
| `ClaudeApi` | Anthropic Messages API, multi-turn conversation, web search support |
| `VoiceInputController` | State machine (IDLE→LISTENING→PROCESSING), dual-engine routing, safety timeouts |
| `SpeechRecognitionManager` | On-device STT with segment accumulation and silence detection |
| `GeminiSpeechRecognizer` | AudioRecord → WAV → Gemini flash-lite transcription with conversation context |
| `TtsManager` | Android TTS with voice selection, text chunking, utterance tracking |
| `GeminiTtsManager` | Gemini TTS API → base64 PCM → AudioTrack playback |
| `ScreenshotManager` | Accessibility screenshot API wrapper |
| `PromptSettings` | All SharedPreferences: prompts, models, voices, toggles, timeouts |
| `DebugLog` | In-memory ring buffer (50KB), copyable from UI |
| `SenniVoiceInteraction*` | Digital assistant registration (VoiceInteractionService/Session) |

## Project Structure

```
app/src/main/
├── AndroidManifest.xml
├── assets/
│   ├── idle_sheet.png              # Default idle sprite (6 frames)
│   └── walk_sheet.png              # Default walk sprite (4 frames)
├── java/com/starfarer/companionoverlay/
│   ├── MainActivity.kt
│   ├── CompanionOverlayService.kt
│   ├── CompanionAccessibilityService.kt
│   ├── AssistActivity.kt
│   ├── ClaudeAuth.kt
│   ├── ClaudeApi.kt
│   ├── VoiceInputController.kt
│   ├── SpeechRecognitionManager.kt
│   ├── GeminiSpeechRecognizer.kt
│   ├── TtsManager.kt
│   ├── GeminiTtsManager.kt
│   ├── ScreenshotManager.kt
│   ├── PromptSettings.kt
│   ├── DebugLog.kt
│   ├── SenniVoiceInteractionService.kt
│   ├── SenniVoiceInteractionSessionService.kt
│   ├── SenniVoiceInteractionSession.kt
│   └── SenniRecognitionService.kt
└── res/
    ├── layout/activity_main.xml
    ├── values/{strings,themes}.xml
    ├── xml/{accessibility_service_config,voice_interaction_service,recognition_service}.xml
    └── drawable/                    # Notification icons, scrollbar drawables
```

## Building

**Requirements:** Android SDK 34, JDK 17

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

| Setting | Value |
|---|---|
| Min SDK | 30 (Android 11) |
| Target/Compile SDK | 34 (Android 14) |
| Kotlin | 1.9.21 |

## Known Limitations

- **Headset button + fullscreen video** — Long-pressing Shokz headset button triggers AssistActivity, which steals foreground focus and causes YouTube to enter PiP. This is an Android platform limitation: ACTION_VOICE_COMMAND requires an Activity target. Use triple-tap volume down instead when watching fullscreen video.
- **Gemini TTS voice drift** — Voice can change mid-utterance on long responses. Server-side Gemini bug, no client workaround.
- **Gemini free tier quotas** — STT and TTS share the same API key but have separate rate limits. TTS (10 RPM) is tighter than STT (15 RPM).
- **On-device profanity filter** — Google SpeechRecognizer censors profanity. Mitigated with dictionary decensor but some words may still appear as `[?]`.

## API Details

**Claude:** OAuth PKCE via Claude Code flow. Models: Sonnet 4.5, Opus 4.1, Opus 4.6. Max tokens: 512 (4096 with web search). Read timeout: 300s.

**Gemini STT:** gemini-2.5-flash-lite via REST API. 16kHz mono PCM → WAV → base64. Conversation context injected via systemInstruction.

**Gemini TTS:** gemini-2.5-flash-preview-tts via REST API. Returns 24kHz 16-bit mono PCM. Default voice: Kore.
