package com.slash.agent;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Warm app-managed llama.cpp runtime with real native token callbacks. */
public final class EmbeddedLlamaRuntime implements LlmRuntime {
    private static final String TAG = "SlashRuntime";
    private final LocalModelManager models;
    private volatile boolean loaded;
    private final boolean nativeAvailable;
    private volatile String lastMetrics = "{}";

    public EmbeddedLlamaRuntime(Context context) {
        models = new LocalModelManager(context);
        boolean available;
        try {
            System.loadLibrary("slash_llama");
            available = true;
            Log.i(TAG, "JNI_BRIDGE_READY");
        } catch (UnsatisfiedLinkError error) {
            available = false;
            Log.e(TAG, "JNI_BRIDGE_UNAVAILABLE", error);
        }
        nativeAvailable = available;
    }

    @Override public synchronized void loadModel(Callback callback) {
        if (loaded) { callback.onComplete(null, null); return; }
        if (!nativeAvailable) {
            callback.onComplete("Slash needs its embedded local inference runtime before we can talk.", null);
            return;
        }
        if (!models.exists()) {
            callback.onComplete("Slash needs its local AI model before we can talk. Download the selected model in setup.", null);
            return;
        }
        if (!models.capabilitySummary().startsWith("RECOMMENDED")
                && !models.capabilitySummary().startsWith("BASIC_LOCAL_AI")) {
            callback.onComplete("This device does not currently have enough resources for the selected local model.", null);
            return;
        }

        ModelProfile profile = models.selectedProfile();
        int contextLength = profile.tier == ModelProfile.Tier.LITE ? 4096 : 8192;
        int threads = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 2));
        long startedAt = android.os.SystemClock.elapsedRealtime();
        Log.i(TAG, "NATIVE_MODEL_LOAD_START MODEL_TIER=" + profile.displayName
                + " bytes=" + models.modelFile().length() + " threads=" + threads);
        loaded = nativeLoad(models.modelFile().getAbsolutePath(), contextLength, 256, threads);
        if (loaded) {
            Log.i(TAG, "NATIVE_MODEL_LOAD_SUCCESS MODEL_TIER=" + profile.displayName
                    + " KV_CACHE_REUSE_ENABLED MODEL_KEEPALIVE=true load_ms="
                    + (android.os.SystemClock.elapsedRealtime() - startedAt));
            callback.onComplete(null, null);
        } else callback.onComplete("Slash could not load the local GGUF model.", null);
    }

    @Override public void generate(JSONArray messages, Callback callback) {
        run(messages, null, callback);
    }

    @Override public void generateStreaming(JSONArray messages, StreamingCallback callback) {
        run(messages, callback, null);
    }

    private void run(JSONArray messages, StreamingCallback streaming, Callback buffered) {
        if (!loaded) {
            if (streaming != null) streaming.onComplete("The local model is not loaded yet.", null);
            else buffered.onComplete("The local model is not loaded yet.", null);
            return;
        }
        String prompt = prompt(messages);
        StringBuilder raw = new StringBuilder();
        StreamingGate gate = streaming == null ? null : new StreamingGate(streaming);
        Utf8PieceAssembler utf8 = new Utf8PieceAssembler();
        try {
            long startedAt = android.os.SystemClock.elapsedRealtime();
            Log.i(TAG, "NATIVE_GENERATION_START prompt_chars=" + prompt.length() + " max_tokens=256");
            nativeGenerateStreaming(prompt, 256, new NativeCallback() {
                @Override public void onToken(byte[] bytes) {
                    String piece = utf8.offer(bytes);
                    if (piece.isEmpty()) return;
                    raw.append(piece);
                    if (gate != null) gate.offer(raw.toString());
                }

                @Override public void onComplete(String metricsJson) {
                    raw.append(utf8.finish());
                    lastMetrics = metricsJson == null ? "{}" : metricsJson;
                    Log.i(TAG, "NATIVE_GENERATION_SUCCESS elapsed_ms="
                            + (android.os.SystemClock.elapsedRealtime() - startedAt)
                            + " metrics=" + lastMetrics);
                    NativeToolCallParser.Result result = NativeToolCallParser.parse(raw.toString());
                    if (streaming != null) streaming.onComplete(result.text, result.toolCall);
                    else buffered.onComplete(result.text, result.toolCall);
                }

                @Override public void onError(String message) {
                    String safe = message == null || message.trim().isEmpty()
                            ? "Local inference failed." : message.trim();
                    Log.e(TAG, "NATIVE_GENERATION_ERROR elapsed_ms="
                            + (android.os.SystemClock.elapsedRealtime() - startedAt) + " error=" + safe);
                    if (streaming != null) streaming.onComplete(safe, null);
                    else buffered.onComplete(safe, null);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "NATIVE_GENERATION_FAILED", error);
            if (streaming != null) streaming.onComplete("Local inference failed.", null);
            else buffered.onComplete("Local inference failed.", null);
        }
    }

    private String prompt(JSONArray messages) {
        StringBuilder prompt = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            String role = message.optString("role", "user");
            String content = message.optString("content", "");
            prompt.append("<|im_start|>").append(role).append('\n')
                    .append(content).append("<|im_end|>\n");
        }
        prompt.append("<|im_start|>assistant\n");
        return prompt.toString();
    }

    @Override public void cancel() { if (loaded) nativeCancel(); }

    @Override public synchronized void unload() {
        if (loaded) nativeUnload();
        loaded = false;
    }

    @Override public boolean isLoaded() { return loaded; }
    public LocalModelManager modelManager() { return models; }
    public String lastMetrics() { return lastMetrics; }

    public interface NativeCallback {
        void onToken(byte[] piece);
        void onComplete(String metricsJson);
        void onError(String message);
    }

    /** Holds token fragments until they form complete UTF-8 code points. */
    private static final class Utf8PieceAssembler {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        String offer(byte[] bytes) {
            if (bytes != null && bytes.length > 0) pending.write(bytes, 0, bytes.length);
            byte[] value = pending.toByteArray();
            int complete = completePrefix(value);
            if (complete == 0) return "";
            String output = new String(value, 0, complete, StandardCharsets.UTF_8);
            pending.reset();
            if (complete < value.length) pending.write(value, complete, value.length - complete);
            return output;
        }

        String finish() {
            byte[] value = pending.toByteArray();
            pending.reset();
            return value.length == 0 ? "" : new String(value, StandardCharsets.UTF_8);
        }

        private int completePrefix(byte[] value) {
            int index = 0;
            while (index < value.length) {
                int first = value[index] & 0xff;
                int length = first < 0x80 ? 1 : (first & 0xe0) == 0xc0 ? 2
                        : (first & 0xf0) == 0xe0 ? 3 : (first & 0xf8) == 0xf0 ? 4 : 1;
                if (index + length > value.length) break;
                boolean valid = true;
                for (int next = 1; next < length; next++) {
                    if ((value[index + next] & 0xc0) != 0x80) { valid = false; break; }
                }
                index += valid ? length : 1;
            }
            return index;
        }
    }

    private static final class StreamingGate {
        private final StreamingCallback callback;
        private int emitted;
        private boolean safeText;

        StreamingGate(StreamingCallback callback) { this.callback = callback; }

        void offer(String raw) {
            String visible = NativeToolCallParser.removePrivateThought(raw);
            String trimmed = visible.trim();
            if (!safeText) {
                if (raw.trim().startsWith("<think>") && !raw.contains("</think>")) return;
                if (trimmed.startsWith("<tool_call") || trimmed.startsWith("{")) return;
                if (trimmed.startsWith("<") && trimmed.length() < 16) return;
                safeText = !trimmed.isEmpty();
            }
            if (!safeText || visible.length() <= emitted) return;
            String delta = visible.substring(emitted);
            emitted = visible.length();
            if (!delta.isEmpty()) callback.onDelta(delta);
        }
    }

    private native boolean nativeLoad(String path, int contextLength, int batchSize, int threads);
    private native void nativeGenerateStreaming(String prompt, int maxTokens, NativeCallback callback);
    private native void nativeCancel();
    private native void nativeUnload();
}
