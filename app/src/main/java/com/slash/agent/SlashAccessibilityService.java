package com.slash.agent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Bundle;
import android.util.Log;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.content.ClipData;
import android.content.ClipboardManager;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class SlashAccessibilityService extends AccessibilityService {
    private static final String TAG = "SlashAccessibility";
    private static final ScreenPerceptionReducer REDUCER = new ScreenPerceptionReducer();
    private static volatile SlashAccessibilityService instance;
    private static volatile ScreenPerceptionReducer.Observation lastObservation;
    private static final Object EVENT_LOCK = new Object();
    private static volatile long eventSequence;
    private static volatile long lastRelevantEventAt;
    private AgentPointerOverlay pointer;
    private SlashControlBridge controlBridge;

    @Override public void onCreate() {
        super.onCreate();
        // Android can bind the accessibility service before dispatching
        // onServiceConnected(). Publish the instance at process creation so
        // the chat layer does not report a false "accessibility unavailable"
        // state during that short lifecycle window.
        instance = this;
        controlBridge = new SlashControlBridge(this);
        controlBridge.start();
        Log.i(TAG, "ACCESSIBILITY_PROCESS_CREATED");
    }

    @Override protected void onServiceConnected() {
        instance = this;
        pointer = new AgentPointerOverlay(this);
        Log.i(TAG, "ACCESSIBILITY_READY");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Keep the observation until the next explicit observe. Grounded execution validates the
        // active package, node path, and fingerprint, which rejects stale handles without racing
        // benign focus/notification events delivered just after an observation was produced.
        if (event == null) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            synchronized (EVENT_LOCK) {
                eventSequence++;
                lastRelevantEventAt = android.os.SystemClock.elapsedRealtime();
                EVENT_LOCK.notifyAll();
            }
        }
    }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { if (controlBridge != null) controlBridge.stop(); controlBridge = null; if (pointer != null) pointer.close(); pointer = null; instance = null; lastObservation = null; super.onDestroy(); }

    public static boolean isConnected() { return instance != null; }

    public static long eventToken() { return eventSequence; }

    /** Waits for a post-action accessibility change and then for a short quiet/stable window. */
    public static boolean awaitStableUi(long token, long timeoutMs) {
        long deadline = android.os.SystemClock.elapsedRealtime() + Math.max(250, timeoutMs);
        synchronized (EVENT_LOCK) {
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                long now = android.os.SystemClock.elapsedRealtime();
                boolean changed = eventSequence > token;
                boolean stable = changed && now - lastRelevantEventAt >= 300;
                if (stable) return true;
                long wait = changed ? Math.min(300, deadline - now) : Math.min(500, deadline - now);
                if (wait <= 0) return eventSequence > token;
                try { EVENT_LOCK.wait(wait); } catch (InterruptedException error) {
                    Thread.currentThread().interrupt(); return false;
                }
            }
        }
        return eventSequence > token;
    }

    public static void hideAgentPointer() {
        SlashAccessibilityService service = instance;
        if (service != null && service.pointer != null) service.pointer.hide();
    }

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
        if (instance == null) return false;
        AccessibilityNodeInfo root = instance.getRootInActiveWindow();
        // Observation IDs can become stale during a transition even when the
        // semantic label is still visible. Resolve human-readable labels against
        // the current root instead of rejecting the action at the opaque-ID gate.
        if (element == null && observationId != null && observationId.startsWith("obs-")
                && elementId != null && !elementId.matches("e\\d+")) {
            AccessibilityNodeInfo semantic = findSemanticNode(root, elementId);
            if (semantic == null) return false;
            AccessibilityNodeInfo target = semantic;
            while (target != null && !target.isClickable() && !target.isEditable()) target = target.getParent();
            if (target == null) return false;
            Rect bounds = new Rect();
            target.getBoundsInScreen(bounds);
            return !bounds.isEmpty() && dispatchGroundedTap(bounds.centerX(), bounds.centerY());
        }
        if (element == null) return false;
        if (!sameWindow(root, lastObservation)) return false;
        AccessibilityNodeInfo node = ScreenPerceptionReducer.resolve(root, element.path);
        if (!element.matches(node)) {
            // The ordinal is observation-local. If the same ordinal now points
            // at a different node, re-ground using the old element's visible
            // semantic label before giving up.
            String semanticAlias = !element.text.isEmpty() ? element.text
                    : (!element.description.isEmpty() ? element.description : elementId);
            node = findSemanticNode(root, semanticAlias);
            if (node == null) return false;
        }
        AccessibilityNodeInfo target = node;
        while (target != null && !target.isClickable()) target = target.getParent();
        Rect bounds = new Rect();
        if (target != null) {
            target.getBoundsInScreen(bounds);
            if (instance.pointer != null && instance.pointer.available()) {
                instance.pointer.moveTo(bounds, true);
                // Keep the cursor animation visible without blocking the action
                // path for nearly half a second on every click.
                android.os.SystemClock.sleep(120);
            }
        }
        // Execute the visible cursor click as a real grounded tap. Compose-heavy apps such as
        // Spotify can return true from ACTION_CLICK without navigating. Keep ACTION_CLICK only as
        // a compatibility fallback when Android rejects gesture dispatch.
        boolean success = target != null && !bounds.isEmpty()
                && dispatchGroundedTap(bounds.centerX(), bounds.centerY());
        if (!success && target != null)
            success = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Log.i(TAG, "GROUNDED_CLICK observation=" + observationId + " element=" + elementId + " success=" + success);
        return success;
    }

    private static boolean dispatchGroundedTap(float x, float y) {
        SlashAccessibilityService service = instance;
        if (service == null) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 90)).build();
        CountDownLatch completed = new CountDownLatch(1);
        boolean accepted = service.dispatchGesture(gesture,
                new AccessibilityService.GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription description) {
                        completed.countDown();
                    }
                    @Override public void onCancelled(GestureDescription description) {
                        completed.countDown();
                    }
                }, null);
        if (!accepted) return false;
        try { completed.await(300, TimeUnit.MILLISECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        return true;
    }

    public static boolean typeElementSafe(String observationId, String elementId, String value) {
        ScreenPerceptionReducer.Element element = groundedElement(observationId, elementId);
        if (element == null || instance == null) return false;
        AccessibilityNodeInfo root = instance.getRootInActiveWindow();
        if (!sameWindow(root, lastObservation)) return false;
        AccessibilityNodeInfo node = ScreenPerceptionReducer.resolve(root, element.path);
        if (!element.matches(node)) node = findSemanticEditable(root, elementId);
        // Some apps expose the search field with no text/content-description at all. If the
        // planner's alias clearly denotes an input and the current window has exactly one
        // editable node, use that single grounded target rather than stalling on an opaque id.
        if (node == null && isInputAlias(elementId)) {
            node = findSingleEditable(root);
            if (node == null) node = findFirstEditable(root);
        }
        if (node == null || !node.isEditable()) {
            // Compose/search surfaces may expose only a clickable container. Focus it with the
            // same grounded gesture used for clicks, then let the active input receive text.
            AccessibilityNodeInfo semantic = findSemanticNode(root, elementId);
            if (semantic != null) {
                Rect focusBounds = new Rect();
                semantic.getBoundsInScreen(focusBounds);
                if (!focusBounds.isEmpty()) {
                    dispatchGroundedTap(focusBounds.centerX(), focusBounds.centerY());
                    android.os.SystemClock.sleep(80);
                }
                // WebView/Compose search controls sometimes expose paste but not an editable
                // Accessibility node. Use the focused, grounded target rather than guessing a
                // coordinate or sending text to the whole screen.
                ClipboardManager clipboard = (ClipboardManager) instance.getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Slash", value));
                    if (semantic.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true;
                }
            }
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null && focused.isEditable()) node = focused;
        }
        if (node == null || !node.isEditable()) return false;
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        boolean success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        Log.i(TAG, "GROUNDED_TYPE observation=" + observationId + " element=" + elementId + " success=" + success);
        return success;
    }

    /** Submits the currently focused editable field through the platform IME action. */
    public static boolean submitImeActionSafe() {
        SlashAccessibilityService service = instance;
        if (service == null) return false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null || !focused.isEditable()) return false;
        int action = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId();
        boolean success = focused.performAction(action);
        Log.i(TAG, "IME_SUBMIT success=" + success);
        return success;
    }

    private static ScreenPerceptionReducer.Element groundedElement(String observationId, String elementId) {
        ScreenPerceptionReducer.Observation observation = lastObservation;
        if (observation == null || observationId == null || elementId == null) return null;
        ScreenPerceptionReducer.Element exact = observation.id.equals(observationId)
                ? observation.element(elementId) : null;
        if (exact != null) return exact;
        // Some compact planners emit the reducer's visible ordinal ("0", "1", ...)
        // instead of the opaque element id. Resolve it only against this exact observation.
        if (elementId.matches("\\d+")) {
            try {
                int ordinal = Integer.parseInt(elementId);
                if (ordinal >= 0 && ordinal < observation.elements.size())
                    return observation.elements.get(ordinal);
            } catch (NumberFormatException ignored) { }
        }
        // Cloud planners often preserve a semantic alias (e.g. "search_button") instead of
        // copying the reducer's opaque e7 identifier. Resolve only against the latest observation
        // and only when a meaningful label token matches; never accept an arbitrary stale path.
        String alias = elementId.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
        if (alias.isEmpty()) return null;
        String[] tokens = alias.split("\\s+");
        for (ScreenPerceptionReducer.Element candidate : observation.elements) {
            String label = canonical(candidate.text + " " + candidate.description + " " + candidate.viewId);
            for (String token : tokens) {
                if (token.length() >= 3 && label.contains(token)) return candidate;
            }
        }
        return null;
    }

    private static String canonical(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    private static AccessibilityNodeInfo findSemanticNode(AccessibilityNodeInfo node, String alias) {
        if (node == null || alias == null) return null;
        String wanted = alias.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
        String label = (ScreenPerceptionReducer.value(node.getText()) + " "
                + ScreenPerceptionReducer.value(node.getContentDescription()) + " "
                + ScreenPerceptionReducer.value(node.getViewIdResourceName()))
                .toLowerCase(Locale.US);
        boolean textualSearch = wanted.contains("search")
                && (label.contains("voice") || label.contains("microphone") || label.contains("speech"));
        if (textualSearch) {
            // Keep searching descendants: a toolbar may contain a voice icon
            // alongside the actual text-search control.
            for (int index = 0; index < node.getChildCount(); index++) {
                AccessibilityNodeInfo found = findSemanticNode(node.getChild(index), alias);
                if (found != null) return found;
            }
            return null;
        }
        // Prefer a matching editable control (for example a text search box)
        // over an earlier sibling with the same token (such as voice search).
        if (node.isEditable()) {
            for (String token : wanted.split("\\s+"))
                if (token.length() >= 3 && label.contains(token)) return node;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo found = findSemanticNode(node.getChild(index), alias);
            if (found != null) return found;
        }
        for (String token : wanted.split("\\s+")) {
            if (token.length() >= 3 && label.contains(token)) return node;
        }
        return null;
    }

    private static AccessibilityNodeInfo findSemanticEditable(AccessibilityNodeInfo node, String alias) {
        if (node == null || alias == null) return null;
        String wanted = alias.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
        String label = (ScreenPerceptionReducer.value(node.getText()) + " "
                + ScreenPerceptionReducer.value(node.getContentDescription()) + " "
                + ScreenPerceptionReducer.value(node.getViewIdResourceName()))
                .toLowerCase(Locale.US);
        if (node.isEditable()) {
            for (String token : wanted.split("\\s+"))
                if (token.length() >= 3 && label.contains(token)) return node;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo found = findSemanticEditable(node.getChild(index), alias);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isInputAlias(String alias) {
        if (alias == null) return false;
        String normalized = alias.toLowerCase(Locale.US);
        return normalized.contains("input") || normalized.contains("search")
                || normalized.contains("text") || normalized.contains("field");
    }

    private static AccessibilityNodeInfo findSingleEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        AccessibilityNodeInfo found = null;
        if (node.isEditable()) found = node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = findSingleEditable(node.getChild(index));
            if (child == null) continue;
            if (found != null) return null; // ambiguous: do not guess between fields
            found = child;
        }
        return found;
    }

    private static AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo found = findFirstEditable(node.getChild(index));
            if (found != null) return found;
        }
        return null;
    }

    private static boolean sameWindow(AccessibilityNodeInfo root,
            ScreenPerceptionReducer.Observation observation) {
        return root != null && observation != null
                && observation.packageName.equals(ScreenPerceptionReducer.value(root.getPackageName()));
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
