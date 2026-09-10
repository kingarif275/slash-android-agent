package com.slash.agent;

import android.util.Log;

/** Redacted, bounded observability events safe for normal debug logcat use. */
public final class SafeAgentLog {
    private static final String TAG = "SlashAgent";
    private SafeAgentLog() { }
    public static void event(String type, String details) {
        String safe = redact(details);
        if (safe.length() > 1200) safe = safe.substring(0, 1200) + "…";
        Log.i(TAG, type + " " + safe.replace('\n', ' '));
    }

    static String redact(String details) {
        return details == null ? "" : details
                .replaceAll("(?i)bearer\\s+[^\\s\"]+", "Bearer [REDACTED]")
                .replaceAll("(?i)(authorization|api[_ -]?key)\\s*[:=]\\s*[^\\s\"]+", "$1=[REDACTED]")
                .replaceAll("sk-or-v1-[A-Za-z0-9_-]+", "[REDACTED_OPENROUTER_KEY]");
    }
}
