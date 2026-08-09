package com.slash.agent;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
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
        if (instance == null) return "ACCESSIBILITY_DISABLED";
        AccessibilityNodeInfo root = instance.getRootInActiveWindow();
        if (root == null) return "SCREEN_UNAVAILABLE";
        StringBuilder result = new StringBuilder();
        appendText(root, result, 0);
        return result.length() == 0 ? "SCREEN_HAS_NO_EXPOSED_TEXT" : result.toString();
    }

    public static boolean clickTextSafe(String query) {
        if (instance == null || instance.getRootInActiveWindow() == null) return false;
        return clickNode(instance.getRootInActiveWindow(), query.toLowerCase(Locale.US));
    }

    public static boolean typeTextSafe(String value) {
        if (instance == null || instance.getRootInActiveWindow() == null) return false;
        AccessibilityNodeInfo focused = instance.getRootInActiveWindow().findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) return false;
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
    }

    public static boolean scrollSafe(String direction) {
        if (instance == null || instance.getRootInActiveWindow() == null) return false;
        AccessibilityNodeInfo scrollable = findScrollable(instance.getRootInActiveWindow());
        if (scrollable == null) return false;
        boolean backward = "up".equalsIgnoreCase(direction) || "backward".equalsIgnoreCase(direction);
        return scrollable.performAction(backward ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }

    private static void appendText(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || depth > 25 || out.length() > 1800) return;
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        if (text != null && text.length() > 0) out.append(text).append(" | ");
        else if (description != null && description.length() > 0) out.append(description).append(" | ");
        for (int i = 0; i < node.getChildCount(); i++) appendText(node.getChild(i), out, depth + 1);
    }

    private static boolean clickNode(AccessibilityNodeInfo node, String query) {
        if (node == null) return false;
        String text = node.getText() == null ? "" : node.getText().toString().toLowerCase(Locale.US);
        String description = node.getContentDescription() == null ? "" : node.getContentDescription().toString().toLowerCase(Locale.US);
        if (text.contains(query) || description.contains(query)) {
            AccessibilityNodeInfo target = node;
            while (target != null && !target.isClickable()) target = target.getParent();
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) if (clickNode(node.getChild(i), query)) return true;
        return false;
    }

    private static AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findScrollable(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }
}
