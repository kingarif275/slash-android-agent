package com.slash.agent;

import org.json.JSONArray;
import org.json.JSONObject;

public interface LlmRuntime {
    interface Callback { void onComplete(String text, JSONObject toolCall); }
    void loadModel(Callback callback);
    void generate(JSONArray messages, Callback callback);
    void unload();
    boolean isLoaded();
}
