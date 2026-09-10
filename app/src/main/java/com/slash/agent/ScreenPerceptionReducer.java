package com.slash.agent;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Produces compact, temporary, grounded observations instead of raw Accessibility dumps. */
public final class ScreenPerceptionReducer {
    private static final int MAX_ELEMENTS = 48;
    private static final AtomicLong IDS = new AtomicLong();

    public static final class Element {
        public final String id;
        public final String path;
        public final String text;
        public final String description;
        public final String viewId;
        public final String className;
        public final boolean clickable;
        public final boolean editable;
        public final boolean scrollable;
        public final Rect bounds;
        final int score;

        Element(String id, String path, String text, String description, String viewId,
                String className, boolean clickable, boolean editable, boolean scrollable,
                Rect bounds, int score) {
            this.id = id;
            this.path = path;
            this.text = text;
            this.description = description;
            this.viewId = viewId;
            this.className = className;
            this.clickable = clickable;
            this.editable = editable;
            this.scrollable = scrollable;
            this.bounds = bounds;
            this.score = score;
        }

        boolean matches(AccessibilityNodeInfo node) {
            return node != null
                    && text.equals(value(node.getText()))
                    && description.equals(value(node.getContentDescription()))
                    && viewId.equals(value(node.getViewIdResourceName()));
        }
    }

    public static final class Observation {
        public final String id;
        public final String packageName;
        public final String windowClass;
        public final List<Element> elements;

        Observation(String id, String packageName, String windowClass, List<Element> elements) {
            this.id = id;
            this.packageName = packageName;
            this.windowClass = windowClass;
            this.elements = elements;
        }

        public Element element(String elementId) {
            for (Element element : elements) if (element.id.equals(elementId)) return element;
            return null;
        }

        public String compactText() {
            StringBuilder output = new StringBuilder();
            output.append("OBSERVATION_ID=").append(id)
                    .append(" PACKAGE=").append(packageName)
                    .append(" WINDOW=").append(windowClass).append('\n');
            if (elements.isEmpty()) return output.append("ELEMENTS=NONE").toString();
            output.append("ELEMENTS:\n");
            for (Element element : elements) {
                output.append(element.id).append(" ")
                        .append(role(element)).append(" text=").append(JSONObject.quote(label(element)))
                        .append(" actions=")
                        .append(element.clickable ? "click" : "-")
                        .append(element.editable ? ",type" : "")
                        .append(element.scrollable ? ",scroll" : "");
                output.append('\n');
            }
            return output.toString().trim();
        }
    }

    public Observation reduce(AccessibilityNodeInfo root) {
        String observationId = "obs-" + System.currentTimeMillis() + "-" + IDS.incrementAndGet();
        String packageName = value(root == null ? null : root.getPackageName());
        String windowClass = value(root == null ? null : root.getClassName());
        List<Element> candidates = new ArrayList<>();
        if (root != null) collect(root, "", candidates, 0);
        candidates.sort(Comparator.comparingInt((Element element) -> element.score).reversed());
        if (candidates.size() > MAX_ELEMENTS) candidates = new ArrayList<>(candidates.subList(0, MAX_ELEMENTS));
        List<Element> identified = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Element item = candidates.get(i);
            identified.add(new Element("e" + i, item.path, item.text, item.description,
                    item.viewId, item.className, item.clickable, item.editable,
                    item.scrollable, item.bounds, item.score));
        }
        return new Observation(observationId, packageName, windowClass, identified);
    }

    private void collect(AccessibilityNodeInfo node, String path, List<Element> output, int depth) {
        if (node == null || depth > 28) return;
        String text = value(node.getText());
        String description = value(node.getContentDescription());
        String viewId = value(node.getViewIdResourceName());
        // Many Android settings rows expose clickability on a container while
        // the human-readable title lives on a non-clickable child. Promote that
        // visible descendant label onto the actionable row so planning remains
        // semantic without leaking resource IDs.
        if (node.isClickable() && text.isEmpty() && description.isEmpty()) {
            String descendant = descendantLabel(node, 0);
            if (!descendant.isEmpty()) text = descendant;
        }
        boolean actionable = node.isClickable() || node.isEditable() || node.isScrollable();
        boolean labeled = !text.isEmpty() || !description.isEmpty() || !viewId.isEmpty();
        if (actionable || labeled) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            int score = (node.isClickable() ? 30 : 0) + (node.isEditable() ? 35 : 0)
                    + (node.isScrollable() ? 12 : 0) + (!text.isEmpty() ? 18 : 0)
                    + (!description.isEmpty() ? 14 : 0) + (!viewId.isEmpty() ? 8 : 0)
                    - Math.min(depth, 12);
            output.add(new Element("", path, text, description, viewId,
                    value(node.getClassName()), node.isClickable(), node.isEditable(),
                    node.isScrollable(), bounds, score));
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            collect(child, path.isEmpty() ? Integer.toString(index) : path + "." + index,
                    output, depth + 1);
        }
    }

    private static String descendantLabel(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 4) return "";
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            String text = value(child.getText());
            if (!text.isEmpty()) return text;
            String description = value(child.getContentDescription());
            if (!description.isEmpty()) return description;
            String nested = descendantLabel(child, depth + 1);
            if (!nested.isEmpty()) return nested;
        }
        return "";
    }

    public static AccessibilityNodeInfo resolve(AccessibilityNodeInfo root, String path) {
        if (root == null) return null;
        if (path == null || path.isEmpty()) return root;
        AccessibilityNodeInfo current = root;
        for (String segment : path.split("\\.")) {
            int index;
            try { index = Integer.parseInt(segment); }
            catch (NumberFormatException error) { return null; }
            if (index < 0 || index >= current.getChildCount()) return null;
            current = current.getChild(index);
            if (current == null) return null;
        }
        return current;
    }

    private static String role(Element element) {
        if (element.editable) return "input";
        if (element.scrollable) return "scroll";
        if (element.clickable) return "button";
        String lower = element.className.toLowerCase(java.util.Locale.US);
        return lower.contains("image") ? "image" : "text";
    }

    private static String label(Element element) {
        if (!element.text.isEmpty()) return element.text;
        if (!element.description.isEmpty()) return element.description;
        // Custom-rendered search controls often expose no text/content
        // description, but Android still provides a semantic resource id such
        // as `search_edit_text`. Convert that stable hint into a generic label
        // so the planner/fallback can ground Search without app-specific code.
        String id = element.viewId == null ? "" : element.viewId.toLowerCase(java.util.Locale.US);
        if (id.contains("search") || id.contains("query") || id.contains("find")) {
            return element.editable ? "Search field" : "Search";
        }
        // Resource IDs and class names are implementation metadata, not labels
        // a planner can safely understand. Keep them on Element for grounding,
        // but never present them as semantic text.
        return "";
    }

    static String value(CharSequence value) { return value == null ? "" : value.toString().trim(); }
}
