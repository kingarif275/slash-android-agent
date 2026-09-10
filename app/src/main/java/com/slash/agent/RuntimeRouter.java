package com.slash.agent;

import android.content.Context;

import org.json.JSONArray;

/** Delegates the unchanged Slash pipeline to the user-selected local or cloud runtime. */
public final class RuntimeRouter implements LlmRuntime {
    private final EmbeddedLlamaRuntime local;
    private final VertexAiCloudRuntime cloud;
    private final CloudAiSettings settings;

    public RuntimeRouter(Context context) {
        settings = new CloudAiSettings(context);
        local = new EmbeddedLlamaRuntime(context);
        cloud = new VertexAiCloudRuntime(context, settings);
    }

    private LlmRuntime active() { return settings.cloudEnabled() ? cloud : local; }
    public LocalModelManager modelManager() { return local.modelManager(); }
    public CloudAiSettings cloudSettings() { return settings; }
    public boolean cloudEnabled() { return settings.cloudEnabled(); }
    public String displayName() { return cloudEnabled() ? "Vertex AI Gemini" : "local model"; }
    public void setCloudEnabled(boolean enabled) {
        local.cancel();
        cloud.cancel();
        settings.setCloudEnabled(enabled);
    }

    @Override public void loadModel(Callback callback) { active().loadModel(callback); }
    @Override public void generate(JSONArray messages, Callback callback) {
        active().generate(messages, callback);
    }
    @Override public void generate(JSONArray messages, int maxTokens, Callback callback) {
        active().generate(messages, maxTokens, callback);
    }
    @Override public void generateStreaming(JSONArray messages, StreamingCallback callback) {
        active().generateStreaming(messages, callback);
    }
    @Override public void cancel() { local.cancel(); cloud.cancel(); }
    @Override public void unload() { local.unload(); cloud.unload(); }
    @Override public boolean isLoaded() { return active().isLoaded(); }
}
