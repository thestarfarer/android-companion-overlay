# CompanionOverlay

Android overlay app with an animated sprite character that uses Claude AI to comment on your screen via screenshots.

## Features

- **Animated sprite overlay** — Idle breathing/floating animation, walks when tapped, escapes when tapped repeatedly
- **Screenshot + AI commentary** — Long-press the sprite to capture the screen and get a Claude response in a speech bubble
- **Reply input** — Type replies directly in the speech bubble to continue the conversation without taking another screenshot
- **Conversation memory** — Configurable history length (5–30 turns), maintains images across interactions; optionally persists across restarts
- **Model selector** — Choose between Sonnet 4.5, Opus 4.1, and Opus 4.6 from the UI
- **OAuth authentication** — PKCE flow via browser, encrypted token storage, automatic refresh
- **Customizable prompts** — Edit system prompt and user message from the app
- **Custom sprites** — Replace idle/walk sprite sheets with your own PNGs, configurable frame counts
- **Volume button toggle** — Double-tap volume down to show/hide the overlay; can be disabled from settings (when enabled, intercepts all volume-down events, preventing long-press volume decrease)
- **Ghost mode** — Becomes semi-transparent and click-through when keyboard is visible
- **Auto-copy** — Optionally copy Claude responses to clipboard
- **Configurable bubble timeout** — Reply bubble auto-dismisses after 15/30/60/120 seconds
- **Position persistence** — Avatar resumes at the same position and facing direction after overlay restart
- **Screen lock awareness** — Pauses animation on screen off, fades in on unlock

## Setup

### Permissions

1. **Overlay** — Settings → Apps → Special Access → Display over other apps
2. **Accessibility Service** — Settings → Accessibility → enable the app's service (required for screenshots and volume button detection)

### Authentication

Tap **Authenticate** in the app. A browser opens for Claude OAuth login. After authorization, the callback returns to the app automatically.

## Architecture

| Class | Role |
|---|---|
| `MainActivity` | Config UI, auth flow, prompt/sprite editing, model/timeout/history settings, test messages |
| `CompanionOverlayService` | Foreground service, sprite animation (60 FPS), touch handling, speech bubbles with reply input |
| `CompanionAccessibilityService` | Screenshot capture, volume button interception, keyboard detection |
| `ClaudeAuth` | OAuth 2.0 PKCE, token storage (EncryptedSharedPreferences), refresh, profile fetch |
| `ClaudeApi` | Anthropic Messages API client, configurable model |
| `ScreenshotManager` | Thin wrapper around accessibility screenshot API |
| `PromptSettings` | SharedPreferences for prompts, sprites, model, bubble timeout, history length, auto-copy, dialogue persistence, avatar position |
| `DebugLog` | In-memory log buffer (50KB), copyable from UI |

## Project Structure

```
app/src/main/
├── AndroidManifest.xml
├── assets/
│   ├── idle_sheet.png          # Default idle sprite (6 frames)
│   └── walk_sheet.png          # Default walk sprite (4 frames)
├── java/com/starfarer/companionoverlay/
│   ├── MainActivity.kt
│   ├── CompanionOverlayService.kt
│   ├── CompanionAccessibilityService.kt
│   ├── ClaudeAuth.kt
│   ├── ClaudeApi.kt
│   ├── ScreenshotManager.kt
│   ├── PromptSettings.kt
│   └── DebugLog.kt
└── res/
    ├── layout/activity_main.xml
    ├── values/{strings,themes}.xml
    ├── xml/accessibility_service_config.xml
    ├── drawable/                # Notification and launcher icons
    └── mipmap-*/               # Launcher icons (all densities)
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
| Gradle Plugin | 8.2.0 |

## API Details

**Models:** Sonnet 4.5, Opus 4.1, Opus 4.6
**Max tokens:** 512

Uses Claude Code OAuth (PKCE) for authentication. Tokens are stored in EncryptedSharedPreferences and auto-refreshed 5 minutes before expiry.
