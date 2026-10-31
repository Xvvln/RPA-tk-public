package com.xwell.rpatt.automation;

import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Detects the generic Android input surface without knowing the keyboard package or its controls.
 *
 * <p>The input-method window type is the primary signal. Focused/editable application nodes are
 * the fallback for hardware keyboards and OEM accessibility bridges that omit the IME window.</p>
 */
public final class InputSurfaceDetector {
    public enum Kind {
        NONE,
        IME_DOCKED,
        IME_FLOATING,
        EDITOR_FOCUSED_NO_IME_WINDOW,
        UNKNOWN_OBSTRUCTION
    }

    public static final class Result {
        public final Kind kind;
        public final double confidence;
        public final Rect applicationBounds;
        public final Rect usableBounds;
        public final List<Rect> occlusions;
        public final UiNode focusedEditor;
        public final List<String> evidence;

        private Result(Kind kind, double confidence, Rect applicationBounds, Rect usableBounds,
                       List<Rect> occlusions, UiNode focusedEditor, List<String> evidence) {
            this.kind = kind;
            this.confidence = Math.max(0d, Math.min(1d, confidence));
            this.applicationBounds = copy(applicationBounds);
            this.usableBounds = copy(usableBounds);
            List<Rect> copied = new ArrayList<>();
            for (Rect bounds : occlusions) copied.add(copy(bounds));
            this.occlusions = Collections.unmodifiableList(copied);
            this.focusedEditor = focusedEditor;
            this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
        }

        public boolean hasInputMethodWindow() {
            return kind == Kind.IME_DOCKED || kind == Kind.IME_FLOATING;
        }

        public boolean hasFocusedEditor() {
            return focusedEditor != null;
        }

        public boolean inputActive() {
            return hasInputMethodWindow() || hasFocusedEditor();
        }

        /** Position relative to the application area that remains usable above a docked IME. */
        public float normalizedCenterY(UiNode node) {
            int height = height(usableBounds);
            if (node == null || height <= 0) return -1f;
            int centerY = node.bounds.top + (node.bounds.bottom - node.bounds.top) / 2;
            return (centerY - usableBounds.top) / (float) height;
        }

        public boolean isFocusedEditor(UiNode node) {
            if (node == null || focusedEditor == null) return false;
            if (node == focusedEditor) return true;
            return node.windowId == focusedEditor.windowId
                    && !node.treePath.isEmpty()
                    && node.treePath.equals(focusedEditor.treePath);
        }

        public JSONObject toJson() throws JSONException {
            JSONArray regions = new JSONArray();
            for (Rect bounds : occlusions) regions.put(rectJson(bounds));
            return new JSONObject()
                    .put("kind", kind.name())
                    .put("confidence", confidence)
                    .put("application_bounds", rectJson(applicationBounds))
                    .put("usable_bounds", rectJson(usableBounds))
                    .put("occlusions", regions)
                    .put("focused_editor", focusedEditor == null
                            ? JSONObject.NULL : focusedEditor.toJson())
                    .put("evidence", new JSONArray(evidence));
        }
    }

    private InputSurfaceDetector() {}

    public static Result analyze(UiSnapshot snapshot) {
        Rect application = targetApplicationBounds(snapshot);
        Rect usable = copy(application);
        List<Rect> inputRegions = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        boolean docked = false;
        int dockedTop = application.bottom;

        for (UiWindow window : snapshot.windows) {
            if (window.type != UiWindow.TYPE_INPUT_METHOD || empty(window.bounds)) continue;
            Rect overlap = intersection(application, window.bounds);
            if (empty(overlap)) continue;
            inputRegions.add(overlap);
            evidence.add("input_window:type_input_method");
            float widthCoverage = width(overlap) / (float) Math.max(1, width(application));
            int bottomTolerance = Math.max(8, Math.round(height(application) * 0.03f));
            boolean touchesBottom = window.bounds.bottom >= application.bottom - bottomTolerance;
            if (touchesBottom && widthCoverage >= 0.70f) {
                docked = true;
                dockedTop = Math.min(dockedTop, overlap.top);
            }
        }

        if (docked && dockedTop > application.top) {
            usable.bottom = dockedTop;
            evidence.add("input_window:docked");
            evidence.add("viewport:reduced_above_input");
        } else if (!inputRegions.isEmpty()) {
            evidence.add("input_window:floating_or_split");
            evidence.add("viewport:region_occlusion");
        }

        UiNode focusedEditor = null;
        for (UiNode node : snapshot.nodes) {
            if (!PageAnalyzer.TIKTOK_PACKAGE.equals(node.packageName)
                    || !snapshot.isVisible(node)
                    || !isEditable(node)
                    || !node.focused) continue;
            focusedEditor = node;
            evidence.add("editor:focused_editable");
            break;
        }

        Kind kind;
        double confidence;
        if (!inputRegions.isEmpty()) {
            kind = docked ? Kind.IME_DOCKED : Kind.IME_FLOATING;
            confidence = focusedEditor == null ? 0.96d : 0.99d;
        } else if (focusedEditor != null) {
            kind = Kind.EDITOR_FOCUSED_NO_IME_WINDOW;
            confidence = 0.90d;
            evidence.add("input_window:not_exposed");
        } else {
            kind = Kind.NONE;
            confidence = 1d;
            evidence.add("input_surface:none");
        }
        return new Result(kind, confidence, application, usable,
                inputRegions, focusedEditor, evidence);
    }

    static boolean isEditable(UiNode node) {
        return node != null && (node.editable
                || "android.widget.EditText".equals(node.className));
    }

    private static Rect targetApplicationBounds(UiSnapshot snapshot) {
        UiWindow best = null;
        for (UiWindow window : snapshot.windows) {
            if (window.type != UiWindow.TYPE_APPLICATION
                    || !PageAnalyzer.TIKTOK_PACKAGE.equals(window.packageName)
                    || empty(window.bounds)) continue;
            if (best == null || window.focused && !best.focused
                    || window.active && !best.active
                    || window.layer > best.layer) {
                best = window;
            }
        }
        if (best != null) return copy(best.bounds);
        Rect fallback = new Rect();
        fallback.left = 0;
        fallback.top = 0;
        fallback.right = Math.max(0, snapshot.displayWidth);
        fallback.bottom = Math.max(0, snapshot.displayHeight);
        return fallback;
    }

    private static Rect intersection(Rect first, Rect second) {
        Rect value = new Rect();
        value.left = Math.max(first.left, second.left);
        value.top = Math.max(first.top, second.top);
        value.right = Math.min(first.right, second.right);
        value.bottom = Math.min(first.bottom, second.bottom);
        if (value.left >= value.right || value.top >= value.bottom) {
            value.left = 0;
            value.top = 0;
            value.right = 0;
            value.bottom = 0;
        }
        return value;
    }

    private static boolean empty(Rect bounds) {
        return bounds == null || bounds.left >= bounds.right || bounds.top >= bounds.bottom;
    }

    private static int width(Rect bounds) {
        return bounds.right - bounds.left;
    }

    private static int height(Rect bounds) {
        return bounds.bottom - bounds.top;
    }

    private static Rect copy(Rect source) {
        Rect value = new Rect();
        if (source != null) {
            value.left = source.left;
            value.top = source.top;
            value.right = source.right;
            value.bottom = source.bottom;
        }
        return value;
    }

    private static JSONObject rectJson(Rect bounds) throws JSONException {
        return new JSONObject()
                .put("left", bounds.left).put("top", bounds.top)
                .put("right", bounds.right).put("bottom", bounds.bottom);
    }
}
