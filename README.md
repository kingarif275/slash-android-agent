# Slash Android Agent

Slash is a Nothing Phone (2) prototype with the Figma home screen preserved as a Recent Activity viewer. The Quick Settings tile starts a foreground listening service. The model and action layer are intentionally isolated so the local Qwen runtime can be swapped without changing the UI.

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

After installation, add Slash to Quick Settings and enable the AccessibilityService in Android Settings. The Qwen GGUF and Moonshine assets are not bundled into the APK; they will be downloaded/pushed in the next integration step to keep the initial APK small.
