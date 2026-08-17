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
       -> SlashAccessibilityService / ScreenPerceptionReducer
       -> observe / act / verify / replan
  -> SQLite chats, messages, progress, and local memory
```

The application-scoped coordinator owns the warmed `EmbeddedLlamaRuntime`. `SlashRuntimeService` keeps user-initiated inference and an active agent task alive when the target app covers the chat Activity. A new text or voice turn invalidates an older task token and steers the conversation from current state.

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

- Lite: Qwen3 0.6B Q4_K_M
- Balanced: Qwen3 1.7B Q4_K_M
- High: Qwen3 4B Q4_K_M

The selected `ModelProfile` records architecture, context length, chat template, expected tool compatibility, tier, and resource requirements. Downloads are stored under Slash's private `files/models/` directory and checked for minimum size plus the GGUF header before activation.

## Reconstructed native runtime

The official llama.cpp repository is pinned as a Git submodule under `app/src/main/cpp/llama.cpp`. CMake builds `libslash_llama.so` for arm64-v8a. The JNI bridge loads GGUF models, chunks prompt prefill to `n_batch`, streams raw UTF-8 token bytes, supports cancellation, and reuses the longest valid KV/prefix cache across turns. It logs prompt totals, prefix matches, reused/new tokens, and prefill timing.

## Neural voice

Voice turns use `ChatterboxNanoVoiceRuntime` when its separately downloaded model and speaker reference are ready. It runs the published four-graph ONNX pipeline (embedding, reference encoder, autoregressive language model with a 12-layer KV cache, and conditional decoder), then plays 24 kHz mono PCM. The local-model screen verifies every artifact against its published SHA-256 and imports a private 16-bit PCM WAV speaker reference. Android `TextToSpeech` is used only if neural synthesis fails. Typed turns remain text-only.

The Chatterbox package is a community ONNX conversion of ResembleAI Chatterbox Nano, not an official Android release; its model files are intentionally not committed to Git. The UI identifies the download size and readiness state before voice mode uses it.

## Build

The project requires JDK 17 and Android SDK 35:

```powershell
.\gradlew.bat test assembleDebug
```

Clone with submodules (or run `git submodule update --init --recursive`) before building.

Install the debug APK with platform-tools:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Enable Slash under Android Accessibility settings before running phone-control tasks. The app can be used as a normal local chat/history shell without adding the Quick Settings tile.
