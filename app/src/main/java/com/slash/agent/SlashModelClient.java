package com.slash.agent;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** OpenAI-compatible local Qwen client. The model remains the language brain. */
public final class SlashModelClient {
    public interface Callback { void onComplete(String text, JSONObject toolCall); }
    private static final String ENDPOINT = "http://127.0.0.1:8080/v1/chat/completions";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public void complete(JSONArray messages, Callback callback) {
        worker.execute(() -> {
            String text = null;
            JSONObject toolCall = null;
            try {
                JSONObject request = new JSONObject();
                request.put("model", "qwen");
                request.put("messages", messages);
                request.put("temperature", 0.7);
                request.put("tools", tools());
                request.put("tool_choice", "auto");
                JSONObject response = post(request);
                JSONObject message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
                JSONArray calls = message.optJSONArray("tool_calls");
                if (calls != null && calls.length() > 0) {
                    JSONObject call = calls.getJSONObject(0);
                    JSONObject function = call.getJSONObject("function");
                    toolCall = new JSONObject();
                    toolCall.put("tool", function.getString("name"));
                    toolCall.put("arguments", new JSONObject(function.optString("arguments", "{}")));
                } else {
                    text = message.optString("content", "");
                    JSONObject structured = extractStructuredCall(text);
                    if (structured != null) {
                        toolCall = structured;
                        text = null;
                    }
                }
            } catch (Exception error) {
                text = "The local model is not reachable yet. Start the Qwen OpenAI-compatible server on this phone, then try again.";
            }
            String resultText = text;
            JSONObject resultTool = toolCall;
            main.post(() -> callback.onComplete(resultText, resultTool));
        });
    }

    private JSONObject post(JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(30000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        InputStream stream = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);
            if (connection.getResponseCode() >= 400) throw new IllegalStateException(response.toString());
            return new JSONObject(response.toString());
        } finally { connection.disconnect(); }
    }

    private JSONArray tools() throws Exception {
        JSONArray tools = new JSONArray();
        tools.put(tool("OPEN_APP", "Open an installed application selected by semantic app_query.",
                new JSONObject().put("app_query", new JSONObject().put("type", "string"))));
        tools.put(tool("CLICK_ELEMENT", "Click a visible UI element selected from the current accessibility tree.",
                new JSONObject().put("target_description", new JSONObject().put("type", "string"))));
        tools.put(tool("READ_SCREEN", "Read the currently visible accessibility screen.", new JSONObject()));
        tools.put(tool("GET_SCREEN_STATE", "Inspect the current foreground package and visible controls.", new JSONObject()));
        tools.put(tool("TYPE_TEXT", "Type text into the currently focused editable control.",
                new JSONObject().put("text", new JSONObject().put("type", "string"))));
        tools.put(tool("SCROLL", "Scroll the currently visible scrollable control.",
                new JSONObject().put("direction", new JSONObject().put("type", "string"))));
        tools.put(tool("BACK", "Navigate one step back.", new JSONObject()));
        tools.put(tool("HOME", "Navigate to the Home screen.", new JSONObject()));
        return tools;
    }

    private JSONObject tool(String name, String description, JSONObject properties) throws Exception {
        return new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", name).put("description", description)
                .put("parameters", new JSONObject().put("type", "object").put("properties", properties)));
    }

    private JSONObject extractStructuredCall(String content) {
        if (content == null) return null;
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JSONObject parsed = new JSONObject(content.substring(start, end + 1));
            if (!parsed.has("tool")) return null;
            JSONObject result = new JSONObject().put("tool", parsed.getString("tool"));
            result.put("arguments", parsed.optJSONObject("arguments"));
            return result;
        } catch (Exception ignored) { return null; }
    }

    public void shutdown() { worker.shutdownNow(); }
}
