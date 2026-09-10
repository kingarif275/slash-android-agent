package com.slash.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Vertex AI Express Mode runtime with separate conversation and agent models. */
public final class VertexAiCloudRuntime implements LlmRuntime {
    private static final String TAG = "SlashVertex";
    private static final String BASE = "https://aiplatform.googleapis.com/v1beta1/publishers/google/models/";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final CloudAiSettings settings;
    private final SharedPreferences diagnostics;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicLong generation = new AtomicLong();
    private volatile HttpURLConnection activeConnection;
    private volatile boolean loaded;

    VertexAiCloudRuntime(Context context, CloudAiSettings settings) {
        this.settings = settings;
        diagnostics = context.getSharedPreferences("slash_runtime_diagnostics", Context.MODE_PRIVATE);
    }

    @Override public void loadModel(Callback callback) {
        loaded = settings.hasApiKey();
        callback.onComplete(loaded ? null : "Vertex AI needs an Express Mode API key.", null);
    }
    @Override public void generate(JSONArray messages, Callback callback) { generate(messages, 256, callback); }
    @Override public void generate(JSONArray messages, int maxTokens, Callback callback) {
        run(messages, maxTokens, null, callback);
    }
    @Override public void generateStreaming(JSONArray messages, StreamingCallback callback) {
        run(messages, 256, callback, null);
    }

    private void run(JSONArray messages, int maxTokens, StreamingCallback streaming, Callback buffered) {
        if (!loaded || !settings.hasApiKey()) {
            complete(streaming, buffered, "Vertex AI is not configured.", null); return;
        }
        long token = generation.incrementAndGet();
        worker.execute(() -> {
            HttpURLConnection connection = null;
            long started = SystemClock.elapsedRealtime();
            boolean agentRequest = isAgent(messages);
            String selectedModel = agentRequest ? settings.agentModel() : settings.conversationModel();
            // Live-native models are served through the bidirectional Live API, not REST generateContent.
            // Keep text/agent requests functional until the persistent Live WebSocket is connected.
            String model = agentRequest && !requiresStrongAgent(messages)
                    ? "gemini-2.5-flash" : restCompatibleModel(selectedModel);
            try {
                String encodedKey = URLEncoder.encode(settings.apiKey(), StandardCharsets.UTF_8.name());
                connection = (HttpURLConnection) new URL(BASE + model + ":generateContent?key=" + encodedKey).openConnection();
                activeConnection = connection;
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(120_000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                boolean fastAgent = agentRequest && "gemini-2.5-flash".equals(model);
                byte[] payload = requestBody(messages, maxTokens, fastAgent).toString()
                        .getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
                int status = connection.getResponseCode();
                String raw = readBounded(connection, status >= 200 && status < 300);
                if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status + ": " + apiError(raw));
                if (token != generation.get()) return;
                JSONObject response = new JSONObject(raw);
                Parsed parsed = parse(response);
                long elapsed = SystemClock.elapsedRealtime() - started;
                JSONObject usage = response.optJSONObject("usageMetadata");
                diagnostics.edit().putString("last_cloud_model", model)
                        .putLong("last_cloud_generation_elapsed_ms", elapsed)
                        .putString("last_cloud_usage", usage == null ? "{}" : usage.toString())
                        .putString("last_runtime", "VERTEX_AI_EXPRESS").apply();
                Log.i(TAG, "VERTEX_GENERATION_SUCCESS model=" + model + " selected_model=" + selectedModel + " role="
                        + (isAgent(messages) ? "agent" : "conversation") + " elapsed_ms=" + elapsed
                        + " tool_call=" + (parsed.toolCall != null));
                complete(streaming, buffered, parsed.text, parsed.toolCall);
            } catch (Exception error) {
                if (token != generation.get()) return;
                String detail = safeMessage(error);
                Log.e(TAG, "VERTEX_GENERATION_FAILED model=" + model + " selected_model=" + selectedModel
                        + " error=" + error.getClass().getSimpleName() + " detail=" + detail);
                complete(streaming, buffered, "Vertex AI request failed: " + detail, null);
            } finally {
                if (connection != null) connection.disconnect();
                if (activeConnection == connection) activeConnection = null;
            }
        });
    }

    private static String restCompatibleModel(String selectedModel) {
        if (CloudAiSettings.supportsLive(selectedModel)) return "gemini-2.5-flash";
        return selectedModel;
    }

    private static boolean requiresStrongAgent(JSONArray messages) {
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            String content = message.optString("content", "");
            if (content.contains("FINISH_REJECTED")
                    || content.matches("(?s).*\\\"recoveryAttempts\\\":(?:[1-9]|[1-9][0-9]+).*")
                    || content.matches("(?s).*\\\"plannerMisses\\\":(?:[1-9]|[1-9][0-9]+).*"))
                return true;
        }
        return false;
    }

    static JSONObject requestBody(JSONArray messages, int maxTokens) throws Exception {
        return requestBody(messages, maxTokens, false);
    }

    static JSONObject requestBody(JSONArray messages, int maxTokens, boolean fastAgent) throws Exception {
        JSONArray contents = new JSONArray();
        StringBuilder system = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            String role = message.optString("role");
            String text = message.optString("content");
            if ("system".equals(role)) {
                int tools = text.indexOf("<tools>");
                if (tools >= 0) text = text.substring(0, tools).trim();
                if (!text.isEmpty()) system.append(text).append('\n');
                continue;
            }
            contents.put(new JSONObject().put("role", "assistant".equals(role) ? "model" : "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text", text))));
        }
        int outputTokens = fastAgent ? Math.max(512, maxTokens) : maxTokens;
        JSONObject generationConfig = new JSONObject().put("temperature", 0.2)
                .put("maxOutputTokens", Math.max(16, Math.min(1024, outputTokens)));
        // A routine tool decision should not spend its entire small response budget on hidden
        // reasoning and then return an empty candidate. Gemini 2.5 Flash supports budget zero.
        if (fastAgent) generationConfig.put("thinkingConfig",
                new JSONObject().put("thinkingBudget", 0));
        JSONObject body = new JSONObject().put("contents", contents)
                .put("generationConfig", generationConfig);
        if (system.length() > 0) body.put("systemInstruction", new JSONObject()
                .put("parts", new JSONArray().put(new JSONObject().put("text", system.toString()))));
        JSONArray openAiTools = extractTools(messages);
        JSONArray declarations = new JSONArray();
        for (int i = 0; i < openAiTools.length(); i++) {
            JSONObject function = openAiTools.optJSONObject(i).optJSONObject("function");
            if (function != null) declarations.put(new JSONObject(function.toString()));
        }
        if (declarations.length() > 0) {
            body.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", declarations)));
            if (isAgent(messages)) body.put("toolConfig", new JSONObject().put("functionCallingConfig",
                    new JSONObject().put("mode", "ANY")));
        }
        return body;
    }

    static JSONArray extractTools(JSONArray messages) {
        JSONArray tools = new JSONArray();
        for (int index = 0; index < messages.length(); index++) {
            JSONObject message = messages.optJSONObject(index);
            if (message == null || !"system".equals(message.optString("role"))) continue;
            String content = message.optString("content", "");
            int start = content.indexOf("<tools>");
            int end = content.indexOf("</tools>", start + 7);
            if (start < 0 || end < 0) continue;
            for (String line : content.substring(start + 7, end).trim().split("\\r?\\n")) {
                String value = line.trim();
                if (!value.startsWith("{")) continue;
                try { tools.put(new JSONObject(value)); } catch (Exception ignored) { }
            }
        }
        return tools;
    }

    private static Parsed parse(JSONObject response) throws Exception {
        JSONArray candidates = response.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) return new Parsed("", null);
        JSONObject content = candidates.optJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        StringBuilder text = new StringBuilder();
        JSONObject tool = null;
        if (parts != null) for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) continue;
            if (!part.optBoolean("thought", false) && part.has("text"))
                text.append(part.optString("text"));
            JSONObject call = part.optJSONObject("functionCall");
            if (call != null) tool = new JSONObject().put("tool", ToolArgumentNormalizer.toolName(call.optString("name")))
                    .put("arguments", ToolArgumentNormalizer.normalize(call.optString("name"),
                            call.optJSONObject("args") == null ? new JSONObject() : call.optJSONObject("args")));
        }
        if (tool == null) {
            NativeToolCallParser.Result fallback = NativeToolCallParser.parse(text.toString());
            return new Parsed(fallback.text, fallback.toolCall);
        }
        return new Parsed(text.toString().trim(), tool);
    }

    private static boolean isAgent(JSONArray messages) {
        for (int i = 0; i < messages.length(); i++) if (messages.optJSONObject(i) != null
                && messages.optJSONObject(i).optString("content").contains("AGENT_TASK_STATE")) return true;
        return false;
    }

    private String readBounded(HttpURLConnection connection, boolean success) throws Exception {
        java.io.InputStream raw = success ? connection.getInputStream() : connection.getErrorStream();
        if (raw == null) return "";
        try (BufferedInputStream input = new BufferedInputStream(raw); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) >= 0) { if (output.size() + read > MAX_RESPONSE_BYTES) throw new IllegalStateException("response too large"); output.write(buffer, 0, read); }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
    private String apiError(String raw) { try { return new JSONObject(raw).optJSONObject("error").optString("message", "request rejected"); } catch (Exception ignored) { return "request rejected"; } }
    private String safeMessage(Exception error) {
        String value = error.getMessage();
        if (value == null) return error.getClass().getSimpleName();
        if (value.contains("dunning decision is deny")) {
            return "Vertex project billing is denied. Check billing status, payment method, and quota for the configured Google Cloud project.";
        }
        return SafeAgentLog.redact(value.length() > 280 ? value.substring(0, 280) : value);
    }
    private void complete(StreamingCallback streaming, Callback buffered, String text, JSONObject tool) {
        if (streaming != null) { if (tool == null && text != null && !text.isEmpty()) streaming.onDelta(text); streaming.onComplete(text, tool); }
        else buffered.onComplete(text, tool);
    }
    @Override public void cancel() { generation.incrementAndGet(); HttpURLConnection connection = activeConnection; if (connection != null) connection.disconnect(); }
    @Override public void unload() { cancel(); loaded = false; }
    @Override public boolean isLoaded() { return loaded && settings.hasApiKey(); }
    private static final class Parsed { final String text; final JSONObject toolCall; Parsed(String text, JSONObject toolCall) { this.text = text; this.toolCall = toolCall; } }
}
