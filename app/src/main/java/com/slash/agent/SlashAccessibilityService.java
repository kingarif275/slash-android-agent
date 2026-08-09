package com.slash.agent;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

public final class SlashAccessibilityService extends AccessibilityService {
    private static SlashAccessibilityService instance;
    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { instance = null; super.onDestroy(); }
    public static boolean performGlobalActionSafe(int action) {
        return instance != null && instance.performGlobalAction(action);
    }
}
