# Slash Android Agent

Slash is a Nothing Phone (2) prototype with the Figma home screen preserved as a Recent Activity viewer. The Quick Settings tile starts a foreground listening service. Natural language is handled by a local runtime abstraction; Android capabilities are deterministic tools and Accessibility provides screen context and interaction.

## Current model decision

Official Qwen3.6 starts at 27B, which is not suitable for local inference on the Phone (2). The first mobile target is Qwen3.5-0.8B or Qwen3-1.7B, quantized to 4-bit. The default plan is Qwen3-1.7B Q4 for better action reliability, with Qwen3.5-0.8B as the low-latency fallback.

## Build

```powershell
gradle :app:assembleDebug
```

Install with Android SDK platform-tools:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

After installation, add Slash to Quick Settings and enable the AccessibilityService in Android Settings. Tap the settings square on the home screen to open the local-AI setup screen. It checks Android version, ABI, RAM, and free storage, downloads the recommended GGUF into Slash's private `files/models/` directory, validates the GGUF signature, and reports progress/errors.

The production runtime boundary is `LlmRuntime`, with `EmbeddedLlamaRuntime` as the default implementation. The native `slash_llama` library is the remaining packaging step: once the llama.cpp Android JNI library is included, the downloaded GGUF is loaded directly by Slash. No localhost server, Termux session, PC, or `adb reverse` is required for the intended user flow.
