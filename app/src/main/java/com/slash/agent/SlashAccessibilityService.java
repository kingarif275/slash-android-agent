package com.slash.agent;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;

public final class SlashAccessibilityService extends AccessibilityService {
    private static final String TAG = "SlashAccessibility";
    private static final ScreenPerceptionReducer REDUCER = new ScreenPerceptionReducer();
    private static volatile SlashAccessibilityService instance;
    private static volatile ScreenPerceptionReducer.Observation lastObservation;

    @Override protected void onServiceConnected() {
        instance = this;
        Log.i(TAG, "ACCESSIBILITY_READY");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Element IDs are intentionally single-observation handles. Any UI mutation makes the
        // previous grounding stale and forces the controller to observe again before acting.
        lastObservation = null;
    }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { instance = null; lastObservation = null; super.onDestroy(); }

    public static boolean performGlobalActionSafe(int action) {
        return instance != null && instance.performGlobalAction(action);
    }

    public static String observeScreenSafe() {
        if (instance == null) return "ACCESSIBILITY_DISABLED";
        AccessibilityNodeInfo root = instance.getRootInActiveWindow();
        if (root == null) return "SCREEN_UNAVAILABLE";
        ScreenPerceptionReducer.Observation observation = REDUCER.reduce(root);
        lastObservation = observation;
        Log.i(TAG, "SCREEN_OBSERVATION_ID=" + observation.id + " elements=" + observation.elements.size());
        return observation.compactText();
    }

    public static String readScreenSafe() { return observeScreenSafe(); }

    public static boolean clickElementSafe(String observationId, String elementId) {
        ScreenPerceptionReducer.Element element = groundedElement(observationId, elementId);
        if (element == null || instance == null) return false;
        AccessibilityNodeInfo root = instance.getRootInActiveWindow();
        AccessibilityNodeInfo node = ScreenPerceptionReducer.resolve(root, element.path);
        if (!element.matches(node)) return false;
        AccessibilityNodeInfo target = node;
        while (target != null && !target.isClickable()) target = target.getParent();
        boolean success = target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Log.i(TAG, "GROUNDED_CLICK observation=" + observationId + " element=" + elementId + " success=" + success);
        return success;
    }

    public static boolean typeElementSafe(String observationId, String elementId, String value) {
        ScreenPerceptionReducer.Element element = groundedElement(observationId, elementId);
        if (element == null || instance == null) return false;
        AccessibilityNodeInfo root = instance.getRootInActiveWindow();
        AccessibilityNodeInfo node = ScreenPerceptionReducer.resolve(root, element.path);
        if (!element.matches(node) || !node.isEditable()) return false;
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        boolean success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        Log.i(TAG, "GROUNDED_TYPE observation=" + observationId + " element=" + elementId + " success=" + success);
        return success;
    }

    private static ScreenPerceptionReducer.Element groundedElement(String observationId, String elementId) {
        ScreenPerceptionReducer.Observation observation = lastObservation;
        if (observation == null || observationId == null || elementId == null) return null;
        if (!observation.id.equals(observationId)) return null;
        return observation.element(elementId);
    }

    public static boolean clickTextSafe(String query) {
        if (instance == null || instance.getRootInActiveWindow() == null || query == null) return false;
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
        return scrollable.performAction(backward ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }

    private static boolean clickNode(AccessibilityNodeInfo node, String query) {
        if (node == null) return false;
        String text = ScreenPerceptionReducer.value(node.getText()).toLowerCase(Locale.US);
        String description = ScreenPerceptionReducer.value(node.getContentDescription()).toLowerCase(Locale.US);
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
