package com.slash.agent;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Locale;

public final class SlashAccessibilityService extends AccessibilityService {
    private static SlashAccessibilityService instance;
    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { instance = null; super.onDestroy(); }
    public static boolean performGlobalActionSafe(int action) {
        return instance != null && instance.performGlobalAction(action);
    }

    public static String readScreenSafe() {
        if (instance == null || instance.getRootInActiveWindow() == null) return "I need Accessibility access enabled to read your screen.";
        StringBuilder result = new StringBuilder();
        result.append("Screen: ");
        appendText(instance.getRootInActiveWindow(), result, 0);
        if (result.length() == 8) return "I can see the screen, but it does not expose any readable text.";
        return result.toString();
    }

    public static boolean clickTextSafe(String query) {
        if (instance == null || instance.getRootInActiveWindow() == null) return false;
        return clickNode(instance.getRootInActiveWindow(), query.toLowerCase(Locale.US));
    }

    private static void appendText(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || depth > 25 || out.length() > 900) return;
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        if (text != null && text.length() > 0) out.append(text).append(". ");
        else if (description != null && description.length() > 0) out.append(description).append(". ");
        for (int i = 0; i < node.getChildCount(); i++) appendText(node.getChild(i), out, depth + 1);
    }

    private static boolean clickNode(AccessibilityNodeInfo node, String query) {
        if (node == null) return false;
        String text = node.getText() == null ? "" : node.getText().toString().toLowerCase(Locale.US);
        String description = node.getContentDescription() == null ? "" : node.getContentDescription().toString().toLowerCase(Locale.US);
        if (text.contains(query) || description.contains(query)) {
            AccessibilityNodeInfo target = node;
            while (target != null && !target.isClickable()) target = target.getParent();
            if (target != null) return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (clickNode(node.getChild(i), query)) return true;
        }
        return false;
    }
}
