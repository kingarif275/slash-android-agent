package com.slash.agent;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.util.Locale;

public final class SlashToolExecutor {
    public static final class Result {
        public final boolean success;
        public final String result;
        Result(boolean success, String result) { this.success = success; this.result = result; }
    }

    private final Context context;
    public SlashToolExecutor(Context context) { this.context = context; }

    public Result execute(String tool, JSONObject args) {
        try {
            switch (tool) {
                case "OPEN_APP": return openApp(args.optString("app_query"));
                case "CLICK_ELEMENT": return click(args.optString("target_description"));
                case "READ_SCREEN": return new Result(true, SlashAccessibilityService.readScreenSafe());
                case "GET_SCREEN_STATE": return new Result(true, SlashAccessibilityService.readScreenSafe());
                case "TYPE_TEXT": return new Result(SlashAccessibilityService.typeTextSafe(args.optString("text")), "Text input action completed.");
                case "SCROLL": return new Result(SlashAccessibilityService.scrollSafe(args.optString("direction")), "Scroll action completed.");
                case "BACK": return new Result(SlashAccessibilityService.performGlobalActionSafe(AccessibilityService.GLOBAL_ACTION_BACK), "Back action completed.");
                case "HOME": return new Result(SlashAccessibilityService.performGlobalActionSafe(AccessibilityService.GLOBAL_ACTION_HOME), "Home action completed.");
                default: return new Result(false, "UNKNOWN_TOOL: " + tool);
            }
        } catch (Exception error) { return new Result(false, error.getClass().getSimpleName() + ": " + error.getMessage()); }
    }

    private Result openApp(String query) {
        ApplicationInfo best = null;
        String wanted = compact(query);
        int score = 0;
        PackageManager pm = context.getPackageManager();
        for (ApplicationInfo app : pm.getInstalledApplications(PackageManager.MATCH_ALL)) {
            if (pm.getLaunchIntentForPackage(app.packageName) == null) continue;
            String label = compact(pm.getApplicationLabel(app).toString());
            String packageName = compact(app.packageName);
            int candidate = label.equals(wanted) ? 100 : label.contains(wanted) ? 80 : wanted.contains(label) ? 70 : 0;
            if (candidate == 0 && !wanted.isEmpty() && packageName.contains(wanted)) candidate = 60;
            if (candidate > score) { best = app; score = candidate; }
        }
        if (best == null) return new Result(false, "APP_NOT_FOUND: " + query);
        Intent launch = pm.getLaunchIntentForPackage(best.packageName);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);
        return new Result(true, "OPENED_APP: " + pm.getApplicationLabel(best));
    }

    private Result click(String description) {
        boolean success = SlashAccessibilityService.clickTextSafe(description);
        return new Result(success, success ? "CLICKED_ELEMENT: " + description : "ELEMENT_NOT_FOUND: " + description);
    }

    private String compact(String value) { return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", ""); }
}
