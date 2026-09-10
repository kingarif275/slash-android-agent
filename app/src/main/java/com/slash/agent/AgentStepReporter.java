package com.slash.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

/**
 * Slash-owned step reporting boundary. It keeps a durable local queue and exposes an optional
 * remote sink without coupling the planner to transport or credentials.
 */
public final class AgentStepReporter {
    private static final String TAG = "SlashAgentRemote";
    private static final String PREFS = "slash_agent_runs";
    private static final String EVENTS = "pending_step_reports";
    private final SharedPreferences prefs;

    public AgentStepReporter(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void report(String runId, int step, String phase, JSONObject payload) {
        try {
            JSONObject event = new JSONObject().put("runId", runId).put("step", step)
                    .put("phase", phase).put("payload", payload == null ? new JSONObject() : payload)
                    .put("reportedAt", System.currentTimeMillis());
            String old = prefs.getString(EVENTS, "");
            String next = old.isEmpty() ? event.toString() : old + "\n" + event;
            String[] lines = next.split("\\n");
            int start = Math.max(0, lines.length - 100);
            StringBuilder bounded = new StringBuilder();
            for (int i = start; i < lines.length; i++) {
                if (bounded.length() > 0) bounded.append('\n');
                bounded.append(lines[i]);
            }
            prefs.edit().putString(EVENTS, bounded.toString()).apply();
            Log.i(TAG, "STEP_REPORTED run=" + runId + " step=" + step + " phase=" + phase);
        } catch (Exception error) {
            Log.w(TAG, "STEP_REPORT_FAILED", error);
        }
    }

    public String pendingReports() { return prefs.getString(EVENTS, ""); }
}
