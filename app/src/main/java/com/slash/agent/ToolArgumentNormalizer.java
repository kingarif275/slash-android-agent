package com.slash.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Canonicalizes known Qwen tool argument aliases before any executor sees them. */
public final class ToolArgumentNormalizer {
    private ToolArgumentNormalizer() { }

    public static String toolName(String raw) {
        String name = raw == null ? "" : raw.trim().toUpperCase(Locale.US);
        if ("GET_SCREEN_STATE".equals(name) || "READ_SCREEN".equals(name)) return "OBSERVE_SCREEN";
        return name;
    }

    public static JSONObject normalize(String rawTool, JSONObject raw) throws Exception {
        String tool = toolName(rawTool);
        JSONObject source = raw == null ? new JSONObject() : raw;
        JSONObject out = new JSONObject();
        switch (tool) {
            case "OPEN_APP":
                put(out, "app_query", source, "app_query", "app", "app_name", "application", "application_name");
                break;
            case "CLICK_ELEMENT":
                put(out, "target_description", source, "target_description", "target", "element", "label", "description");
                put(out, "element_id", source, "element_id", "elementId", "id");
                put(out, "observation_id", source, "observation_id", "observationId", "observation");
                break;
            case "TYPE_TEXT":
                put(out, "text", source, "text", "value", "content");
                put(out, "element_id", source, "element_id", "elementId", "id");
                put(out, "observation_id", source, "observation_id", "observationId", "observation");
                break;
            case "SCROLL":
                put(out, "direction", source, "direction", "dir");
                break;
            case "DELEGATE_TO_AGENT":
                put(out, "goal", source, "goal", "request", "task");
                break;
            case "FINISH_TASK":
                put(out, "summary", source, "summary", "result", "message");
                break;
            case "OBSERVE_SCREEN":
            case "BACK":
            case "HOME":
                break;
            default:
                return new JSONObject(source.toString());
        }
        return out;
    }

    public static String validate(String rawTool, JSONObject value) {
        String tool = toolName(rawTool);
        JSONObject args = value == null ? new JSONObject() : value;
        if ("OPEN_APP".equals(tool) && blank(args.optString("app_query"))) return "OPEN_APP requires app_query";
        if ("CLICK_ELEMENT".equals(tool) && blank(args.optString("element_id"))) {
            return "CLICK_ELEMENT requires element_id from the current observation";
        }
        if (("CLICK_ELEMENT".equals(tool) || "TYPE_TEXT".equals(tool))
                && blank(args.optString("observation_id"))) {
            return tool + " requires observation_id with element_id";
        }
        if ("TYPE_TEXT".equals(tool) && blank(args.optString("text"))) return "TYPE_TEXT requires text";
        if ("TYPE_TEXT".equals(tool) && blank(args.optString("element_id"))) {
            return "TYPE_TEXT requires element_id from the current observation";
        }
        if ("SCROLL".equals(tool)) {
            String direction = args.optString("direction");
            if (!"up".equalsIgnoreCase(direction) && !"down".equalsIgnoreCase(direction)
                    && !"left".equalsIgnoreCase(direction) && !"right".equalsIgnoreCase(direction)
                    && !"forward".equalsIgnoreCase(direction) && !"backward".equalsIgnoreCase(direction)) {
                return "SCROLL requires direction";
            }
        }
        if ("DELEGATE_TO_AGENT".equals(tool) && blank(args.optString("goal"))) return "DELEGATE_TO_AGENT requires goal";
        return null;
    }

    private static void put(JSONObject out, String canonical, JSONObject source, String... aliases) throws Exception {
        for (String alias : aliases) {
            String key = findKey(source, alias);
            if (key == null) continue;
            Object value = source.opt(key);
            if (value != null && value != JSONObject.NULL) out.put(canonical, String.valueOf(value));
            return;
        }
    }

    private static String findKey(JSONObject source, String wanted) {
        JSONArray names = source.names();
        if (names == null) return null;
        for (int index = 0; index < names.length(); index++) {
            String actual = names.optString(index);
            if (actual.replace("_", "").equalsIgnoreCase(wanted.replace("_", ""))) return actual;
        }
        return null;
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
