package com.slash.agent;

import org.json.JSONArray;
import org.json.JSONObject;

public interface LlmRuntime {
    interface Callback { void onComplete(String text, JSONObject toolCall); }
    interface StreamingCallback {
        void onDelta(String delta);
        void onComplete(String text, JSONObject toolCall);
    }
    void loadModel(Callback callback);
    void generate(JSONArray messages, Callback callback);
    default void generateStreaming(JSONArray messages, StreamingCallback callback) {
        generate(messages, (text, toolCall) -> {
            if (toolCall == null && text != null && !text.isEmpty()) callback.onDelta(text);
            callback.onComplete(text, toolCall);
        });
    }
    default void cancel() { }
    void unload();
    boolean isLoaded();
}
