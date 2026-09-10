package com.slash.agent;

import org.json.JSONObject;

/** Explicit protocol boundary between an untrusted planner response and Agent Mode. */
public final class PlannerOutputContract {
    public enum Outcome { TOOL_CALL, FINISH_TASK, WAIT, CANNOT_PROCEED, PROTOCOL_ERROR }
    public static Outcome classify(String text, JSONObject toolCall) {
        if (toolCall != null) return "FINISH_TASK".equals(toolCall.optString("tool"))
                ? Outcome.FINISH_TASK : Outcome.TOOL_CALL;
        String clean = text == null ? "" : text.trim();
        if (clean.equalsIgnoreCase("WAIT")) return Outcome.WAIT;
        if (clean.toUpperCase(java.util.Locale.US).startsWith("CANNOT_PROCEED"))
            return Outcome.CANNOT_PROCEED;
        return Outcome.PROTOCOL_ERROR;
    }
}
