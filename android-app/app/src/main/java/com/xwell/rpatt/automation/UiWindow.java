package com.xwell.rpatt.automation;

import android.graphics.Rect;

import org.json.JSONException;
import org.json.JSONObject;

/** Immutable accessibility-window evidence captured alongside the node hierarchy. */
public final class UiWindow {
    public static final int TYPE_APPLICATION = 1;
    public static final int TYPE_INPUT_METHOD = 2;
    public static final int TYPE_SYSTEM = 3;
    public static final int TYPE_ACCESSIBILITY_OVERLAY = 4;

    public final int id;
    public final String packageName;
    public final int type;
    public final int layer;
    public final boolean active;
    public final boolean focused;
    public final boolean accessibilityFocused;
    public final boolean rootAvailable;
    public final Rect bounds;

    public UiWindow(int id, String packageName, int type, int layer,
                    boolean active, boolean focused, boolean accessibilityFocused,
                    boolean rootAvailable, Rect bounds) {
        this.id = id;
        this.packageName = clean(packageName);
        this.type = type;
        this.layer = layer;
        this.active = active;
        this.focused = focused;
        this.accessibilityFocused = accessibilityFocused;
        this.rootAvailable = rootAvailable;
        this.bounds = new Rect();
        if (bounds != null) {
            this.bounds.left = bounds.left;
            this.bounds.top = bounds.top;
            this.bounds.right = bounds.right;
            this.bounds.bottom = bounds.bottom;
        }
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("package", packageName)
                .put("type", type)
                .put("layer", layer)
                .put("active", active)
                .put("focused", focused)
                .put("accessibility_focused", accessibilityFocused)
                .put("root_available", rootAvailable)
                .put("bounds", new JSONObject()
                        .put("left", bounds.left).put("top", bounds.top)
                        .put("right", bounds.right).put("bottom", bounds.bottom));
    }

    public static UiWindow fromJson(JSONObject value) {
        JSONObject geometry = value.optJSONObject("bounds");
        Rect bounds = new Rect();
        if (geometry != null) {
            bounds.left = geometry.optInt("left");
            bounds.top = geometry.optInt("top");
            bounds.right = geometry.optInt("right");
            bounds.bottom = geometry.optInt("bottom");
        }
        return new UiWindow(value.optInt("id", -1), value.optString("package"),
                value.optInt("type"), value.optInt("layer"),
                value.optBoolean("active"), value.optBoolean("focused"),
                value.optBoolean("accessibility_focused"),
                value.optBoolean("root_available"), bounds);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
