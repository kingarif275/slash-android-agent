package com.slash.agent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/** Embedded-runtime boundary. The native llama.cpp Android library is loaded when packaged. */
public final class EmbeddedLlamaRuntime implements LlmRuntime {
    private final LocalModelManager models;
    private boolean loaded;
    private boolean nativeAvailable;
    private String modelPath;

    public EmbeddedLlamaRuntime(Context context) {
        models = new LocalModelManager(context);
        try { System.loadLibrary("slash_llama"); nativeAvailable = true; }
        catch (UnsatisfiedLinkError ignored) { nativeAvailable = false; }
    }

    @Override public void loadModel(Callback callback) {
        if (!nativeAvailable) { callback.onComplete("Slash needs its embedded local inference runtime before we can talk.", null); return; }
        if (!models.exists()) { callback.onComplete("Slash needs its local AI model before we can talk. Download the recommended model in setup.", null); return; }
        if (!models.capabilitySummary().startsWith("RECOMMENDED") && !models.capabilitySummary().startsWith("BASIC_LOCAL_AI")) {
            callback.onComplete("This device does not currently have enough resources for the recommended local model.", null); return;
        }
        modelPath = models.modelFile().getAbsolutePath();
        loaded = nativeLoad(modelPath);
        callback.onComplete(loaded ? null : "Slash could not load the local GGUF model.", null);
    }

    @Override public void generate(JSONArray messages, Callback callback) {
        if (!loaded) { callback.onComplete("The local model is not loaded yet.", null); return; }
        try {
            String result = nativeGenerate(messages.toString());
            JSONObject parsed = new JSONObject(result);
            callback.onComplete(parsed.optString("text", null), parsed.optJSONObject("tool_call"));
        } catch (Exception error) { callback.onComplete("Local inference failed: " + error.getMessage(), null); }
    }

    @Override public void unload() { if (loaded) nativeUnload(); loaded = false; }
    @Override public boolean isLoaded() { return loaded; }
    public LocalModelManager modelManager() { return models; }

    private native boolean nativeLoad(String path);
    private native String nativeGenerate(String messagesJson);
    private native void nativeUnload();
}
