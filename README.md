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

## Agent execution flow

Slash treats a request as an outcome, not as a fixed app script. It captures a reduced semantic world state, checks the current objective, and uses the fastest grounded action available:

```text
user outcome
  -> objective graph / mission executor
  -> fresh accessibility observation
  -> deterministic semantic fast path when confidence is high
  -> Vertex AI or local planner fallback when the screen is ambiguous
  -> validated one-step tool action
  -> fresh observation and independent verification
  -> next objective, recovery, or final result
```

The mission layer owns multi-objective work and cross-app outputs. The navigation layer owns only the next UI action, so a completed objective cannot execute a stale action from the previous screen. `ScreenPerceptionReducer` exposes roles, labels, editability, clickability, bounds, and observation IDs instead of passing raw Android node trees to the model. Accessibility is the primary perception path; vision is a fallback for custom or unlabeled surfaces.

Simple commands such as `Open Settings`, `Go home`, and `Press back` use local deterministic execution. Longer requests still observe after every action, validate the tool contract, re-ground stale elements, and verify the requested outcome rather than treating an app launch as success.

## Cloud and local model configuration

`RuntimeRouter` keeps conversation and agent runtimes switchable. Local GGUF models remain available through the model setup screen. Vertex AI Express Mode uses the user's `AQ.…` key stored in Android Keystore; no key is committed to this repository. Choose separate conversation and agent models in the settings UI. A model that cannot serve the selected request is reported as a runtime error instead of silently falling back to an unrelated model.

On Android 13+, grant notification permission so progress can be shown while another app is in the foreground. On Android 16+ Slash additionally publishes the agent's current objective, runtime label, and elapsed time as a promoted live update/status pill. The pill is task progress only; it is not a model-download notification.

## Where to adjust the agent

- `AgentTaskController`: mission scheduling, fast paths, planner fallback, and recovery budget.
- `AgentGoalBuilder`, `AgentObjectiveGraph`, and `AgentGoalManager`: objective decomposition, dependencies, generations, and queueing.
- `ScreenPerceptionReducer`: generic semantic accessibility reduction and re-grounding inputs.
- `SlashToolExecutor` and `SlashAccessibilityService`: validated tools and Android execution.
- `AgentVerifier` and `AgentRecoveryPolicy`: outcome evidence and state-specific recovery.
- `RuntimeRouter` and `VertexAiCloudRuntime`: local/cloud model selection and request transport.
- `SlashRuntimeService` and `SlashLiveUpdate`: foreground execution and Android 16+ live progress.

After changing agent behavior, run the unit suite and a connected-device smoke test:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

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

### Local acceleration status

The current APK uses llama.cpp's ARM KleidiAI/NEON kernels as the verified local backend. This is an optimized CPU path and remains the compatibility fallback for all arm64 devices; it is not reported as GPU or NPU acceleration.

The pinned llama.cpp revision also contains experimental Qualcomm backends:

- Adreno GPU through OpenCL, which requires the Android OpenCL headers and ICD loader/runtime.
- Vulkan GPU, which requires the Vulkan shader compiler and SPIR-V toolchain at build time.
- Hexagon/HTP NPU, which requires Qualcomm Hexagon SDK Community Edition, signed HTP skeleton libraries, and a compatible device runtime.

Those optional dependencies are not bundled in this repository or APK. Slash therefore does not claim `GPU` or `NPU` in diagnostics until a backend is compiled, initialized, and proven on-device. When accelerator packaging is added, backend logs must include the selected device, accelerated layer count, CPU layer count, and inference timings before the backend is exposed as active.

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
