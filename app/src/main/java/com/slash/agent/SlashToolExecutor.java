package com.slash.agent;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

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
            tool = ToolArgumentNormalizer.toolName(tool);
            args = ToolArgumentNormalizer.normalize(tool, args);
            String validation = ToolArgumentNormalizer.validate(tool, args);
            if (validation != null) return new Result(false, "INVALID_TOOL_ARGUMENTS: " + validation);
            switch (tool) {
                case "OPEN_APP": return openApp(args.optString("app_query"));
                case "CLICK_ELEMENT": return click(args);
                case "OBSERVE_SCREEN":
                case "READ_SCREEN":
                case "GET_SCREEN_STATE": return new Result(true, SlashAccessibilityService.observeScreenSafe());
                case "TYPE_TEXT": return type(args);
                case "SCROLL": return new Result(SlashAccessibilityService.scrollSafe(args.optString("direction")), "Scroll action completed.");
                case "BACK": return new Result(SlashAccessibilityService.performGlobalActionSafe(AccessibilityService.GLOBAL_ACTION_BACK), "Back action completed.");
                case "HOME": return new Result(SlashAccessibilityService.performGlobalActionSafe(AccessibilityService.GLOBAL_ACTION_HOME), "Home action completed.");
                default: return new Result(false, "UNKNOWN_TOOL: " + tool);
            }
        } catch (Exception error) { return new Result(false, error.getClass().getSimpleName() + ": " + error.getMessage()); }
    }

    private Result openApp(String query) {
        ResolveInfo best = null;
        String wanted = compact(query);
        int score = 0;
        PackageManager pm = context.getPackageManager();
        Intent launcherQuery = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo app : pm.queryIntentActivities(launcherQuery, PackageManager.MATCH_ALL)) {
            String packageId = app.activityInfo.packageName;
            String label = compact(app.loadLabel(pm).toString());
            String packageName = compact(packageId);
            int candidate = label.equals(wanted) ? 100 : label.contains(wanted) ? 80 : 0;
            if (candidate == 0 && !wanted.isEmpty() && packageName.contains(wanted)) candidate = 60;
            if (candidate == 0) candidate = semanticLabelScore(query, app.loadLabel(pm).toString());
            if (candidate > score) { best = app; score = candidate; }
        }
        if (best == null) return new Result(false, "APP_NOT_FOUND: " + query);
        Intent launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
        if (launch == null) return new Result(false, "APP_NOT_LAUNCHABLE: " + query);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);
        return new Result(true, "OPENED_APP: " + best.loadLabel(pm));
    }

    private Result click(JSONObject args) {
        String observationId = args.optString("observation_id");
        String elementId = args.optString("element_id");
        boolean success = SlashAccessibilityService.clickElementSafe(observationId, elementId);
        String label = args.optString("label").trim();
        if (!success && !label.isEmpty()) {
            // Re-ground by the current semantic label when the opaque element
            // identity belongs to an older accessibility observation.
            success = SlashAccessibilityService.clickElementSafe(observationId, label);
        }
        String target = observationId + "/" + elementId;
        return new Result(success, success ? "CLICKED_ELEMENT: " + target : "ELEMENT_NOT_FOUND_OR_STALE: " + target);
    }

    private Result type(JSONObject args) {
        String observationId = args.optString("observation_id");
        String elementId = args.optString("element_id");
        String text = args.optString("text");
        boolean success = SlashAccessibilityService.typeElementSafe(observationId, elementId, text);
        return new Result(success, success ? "TEXT_ENTERED" : "TEXT_TARGET_NOT_FOUND_OR_STALE");
    }

    private String compact(String value) { return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", ""); }

    private int semanticLabelScore(String query, String label) {
        String[] wanted = query.toLowerCase(Locale.US).split("[^a-z0-9]+");
        String[] offered = label.toLowerCase(Locale.US).split("[^a-z0-9]+");
        int score = 0;
        for (String candidate : offered) for (String token : wanted) {
            if (candidate.length() < 3 || !candidate.equals(token)) continue;
            boolean genericPublisher = token.equals("google") || token.equals("microsoft")
                    || token.equals("meta") || token.equals("samsung");
            score += genericPublisher ? 8 : 35;
        }
        return score;
    }
}
