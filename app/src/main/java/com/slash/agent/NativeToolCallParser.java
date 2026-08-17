package com.slash.agent;

import org.json.JSONObject;

/** Parses structured model tool output; it never infers actions from natural-language phrases. */
public final class NativeToolCallParser {
    public static final class Result {
        public final String text;
        public final JSONObject toolCall;
        Result(String text, JSONObject toolCall) { this.text = text; this.toolCall = toolCall; }
    }

    private NativeToolCallParser() { }

    public static Result parse(String raw) {
        String withoutThought = removePrivateThought(raw == null ? "" : raw).trim();
        String payload = toolPayload(withoutThought);
        JSONObject call = parseObject(payload);
        if (call != null) return new Result("", call);
        return new Result(cleanText(withoutThought), null);
    }

    public static String removePrivateThought(String raw) {
        String result = raw == null ? "" : raw;
        int start;
        while ((start = result.indexOf("<think>")) >= 0) {
            int end = result.indexOf("</think>", start + 7);
            if (end < 0) return result.substring(0, start);
            result = result.substring(0, start) + result.substring(end + 8);
        }
        return result;
    }

    private static String toolPayload(String value) {
        int start = value.indexOf("<tool_call>");
        if (start >= 0) {
            int end = value.indexOf("</tool_call>", start);
            return end > start ? value.substring(start + 11, end).trim() : value.substring(start + 11).trim();
        }
        String trimmed = value.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}") ? trimmed : null;
    }

    private static JSONObject parseObject(String payload) {
        if (payload == null || payload.isEmpty()) return null;
        try {
            JSONObject parsed = new JSONObject(payload);
            String name = parsed.optString("tool");
            if (name.isEmpty()) name = parsed.optString("name");
            JSONObject function = parsed.optJSONObject("function");
            if (name.isEmpty() && function != null) name = function.optString("name");
            if (name.isEmpty()) return null;
            name = ToolArgumentNormalizer.toolName(name);

            Object argumentsValue = parsed.opt("arguments");
            if (argumentsValue == null && function != null) argumentsValue = function.opt("arguments");
            JSONObject arguments;
            if (argumentsValue instanceof JSONObject) arguments = (JSONObject) argumentsValue;
            else if (argumentsValue instanceof String && !((String) argumentsValue).trim().isEmpty()) {
                arguments = new JSONObject((String) argumentsValue);
            } else arguments = new JSONObject();
            arguments = ToolArgumentNormalizer.normalize(name, arguments);
            return new JSONObject().put("tool", name).put("arguments", arguments);
        } catch (Exception ignored) { return null; }
    }

    private static String cleanText(String value) {
        return value.replace("<|im_end|>", "").replace("<|endoftext|>", "").trim();
    }
}
