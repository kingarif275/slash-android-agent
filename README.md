# Slash Android Agent

Slash is a local-first Android chat app with phone-control tools inside the conversation. Launching the app opens a persistent chat; typed and spoken messages share the same downstream pipeline. Normal conversation stays on the compact companion path, while tool calls enter a bounded observe/act/verify loop and return a human-readable result to the same chat.

## Current architecture

```text
MainActivity composer (TEXT or VOICE)
  -> UserTurn
  -> ChatCoordinator / fast companion context
  -> normal streamed chat response
     or AgentTaskController
       -> SlashToolExecutor
       -> SlashAccessibilityService
       -> observe / act / verify / replan
  -> SQLite chats, messages, progress, and local memory
```

The application-scoped coordinator owns the warmed `EmbeddedLlamaRuntime`, so inference and an active agent task are not tied to the chat Activity lifecycle. A new text or voice turn invalidates an older task token and steers the conversation from current state.

The Quick Settings tile is optional. It opens the same chat composer in voice-input state; chat, agent work, history, memory, and screen control do not depend on it.

## UI

The main composer is adapted from the linked ChatGPT Apps UI Kit Figma node (`4420:3719`) into the existing Java/Android Views stack. It uses the reference's 44dp controls, neutral `#F3F3F3` pills, Inter typography, and exported icon paths. Android owns the status bar, navigation area, keyboard, and permission sheets; no mock system chrome or Figma HomeIndicator is drawn.

## Local data

`ChatRepository` uses the platform SQLite APIs and stores:

- chats and titles
- user, assistant, progress, and error messages
- explicit cross-chat memories (for example, “remember that …”)

Only recent chat messages and a small lexical retrieval of relevant memories enter companion inference.

## Local models

The model screen currently offers:

- Lite: Qwen3 0.6B Q8_0
- Balanced: Qwen3 1.7B Q4_K_M

The selected `ModelProfile` records architecture, context length, chat template, expected tool compatibility, tier, and resource requirements. Downloads are stored under Slash's private `files/models/` directory and checked for minimum size plus the GGUF header before activation.

## Important runtime limitation

`LlmRuntime` and `EmbeddedLlamaRuntime` are present, but this repository does **not** contain the `slash_llama` JNI implementation, llama.cpp native sources/libraries, native token streaming, KV/prefix-cache code, or a packaged GGUF. Until that native library is added, the UI, persistent chats, history, voice transcription, and model setup work, while model replies and agent execution report that local inference is unavailable.

The repository also does not currently contain a Chatterbox Nano voice runtime. The legacy, now-unwired voice service used Android `TextToSpeech`; the chat composer uses Android speech recognition for voice input and does not force spoken output for typed turns.

## Build

The project requires JDK 17 and Android SDK 35:

```powershell
.\gradlew.bat test assembleDebug
```

Install the debug APK with platform-tools:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Enable Slash under Android Accessibility settings before running phone-control tasks. The app can be used as a normal local chat/history shell without adding the Quick Settings tile.
