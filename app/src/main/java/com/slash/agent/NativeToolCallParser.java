package com.slash.agent;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses structured model tool output; it never infers actions from natural-language phrases. */
public final class NativeToolCallParser {
    private static final Set<String> KNOWN_TOOLS = new HashSet<>(Arrays.asList(
            "DELEGATE_TO_AGENT", "NETWORK_STATUS", "WEB_SEARCH", "FETCH_URL",
            "OPEN_APP", "OBSERVE_SCREEN", "READ_SCREEN", "GET_SCREEN_STATE",
            "CLICK_ELEMENT", "TYPE_TEXT", "SCROLL", "BACK", "HOME", "FINISH_TASK"));
    private static final Pattern FUNCTION_CALL = Pattern.compile(
            "(?s)^([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*$");
    private static final Pattern FUNCTION_ARGUMENT = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:\\\"((?:\\\\.|[^\\\"])*)\\\"|'((?:\\\\.|[^'])*)'|([^,]+))");
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
        if (call == null) call = parseFunctionCall(withoutThought);
        if (call == null) call = parseSentinel(withoutThought);
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
            if (!KNOWN_TOOLS.contains(name)) name = "INVALID_TOOL";

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

    private static JSONObject parseFunctionCall(String value) {
        Matcher call = FUNCTION_CALL.matcher(value.trim());
        if (!call.matches()) return null;
        String name = ToolArgumentNormalizer.toolName(call.group(1));
        if (!KNOWN_TOOLS.contains(name)) return null;
        try {
            JSONObject arguments = new JSONObject();
            String rawArguments = call.group(2).trim();
            Matcher matcher = FUNCTION_ARGUMENT.matcher(rawArguments);
            int consumed = 0;
            while (matcher.find()) {
                String between = rawArguments.substring(consumed, matcher.start()).trim();
                if (!between.isEmpty() && !",".equals(between)) return null;
                String valuePart = matcher.group(2) != null ? matcher.group(2)
                        : matcher.group(3) != null ? matcher.group(3) : matcher.group(4).trim();
                arguments.put(matcher.group(1), valuePart.replace("\\\"", "\"")
                        .replace("\\'", "'").replace("\\\\", "\\"));
                consumed = matcher.end();
            }
            if (!rawArguments.substring(consumed).trim().isEmpty()) return null;
            arguments = ToolArgumentNormalizer.normalize(name, arguments);
            return new JSONObject().put("tool", name).put("arguments", arguments);
        } catch (Exception ignored) { return null; }
    }

    private static JSONObject parseSentinel(String value) {
        String normalized = value.trim().toUpperCase(java.util.Locale.US)
                .replaceAll("[.!]+$", "").replaceAll("\\s+", "_");
        String candidate = normalized.startsWith("CALL_")
                ? normalized.substring("CALL_".length()) : normalized;
        if (!KNOWN_TOOLS.contains(candidate)) return null;
        try {
            return new JSONObject().put("tool", candidate)
                    .put("arguments", new JSONObject());
        } catch (Exception ignored) { return null; }
    }

    public static boolean looksLikeStructuredToolPrefix(String raw) {
        String value = removePrivateThought(raw == null ? "" : raw).trim().toUpperCase(java.util.Locale.US);
        if (value.isEmpty()) return false;
        String normalized = value.replaceAll("\\s+", "_").replaceAll("[.!]+$", "");
        for (String tool : KNOWN_TOOLS) {
            if (tool.startsWith(normalized) || ("CALL_" + tool).startsWith(normalized)
                    || value.startsWith(tool + "(")
                    || value.startsWith("<TOOL_CALL>")) return true;
        }
        return false;
    }

    private static String cleanText(String value) {
        return value.replace("<|im_end|>", "").replace("<|endoftext|>", "").trim();
    }
}
