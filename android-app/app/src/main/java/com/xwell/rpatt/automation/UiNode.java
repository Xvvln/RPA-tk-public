package com.xwell.rpatt.automation;

import android.graphics.Rect;

import org.json.JSONObject;
import org.json.JSONException;

public final class UiNode {
    public final String id;
    public final String text;
    public final String description;
    public final String className;
    public final String packageName;
    public final boolean selected;
    public final boolean clickable;
    public final boolean visibleToUser;
    public final Rect bounds;
    /** Accessibility window containing this node, or -1 for legacy/test snapshots. */
    public final int windowId;
    /** Stable path within the captured accessibility tree, for example 0/2/1. */
    public final String treePath;

    public UiNode(String id, String text, String description, String className,
                  String packageName, boolean selected, boolean clickable, Rect bounds) {
        this(id, text, description, className, packageName, selected, clickable, true, bounds);
    }

    public UiNode(String id, String text, String description, String className,
                  String packageName, boolean selected, boolean clickable,
                  boolean visibleToUser, Rect bounds) {
        this(id, text, description, className, packageName, selected, clickable,
                visibleToUser, bounds, -1, "");
    }

    public UiNode(String id, String text, String description, String className,
                  String packageName, boolean selected, boolean clickable,
                  boolean visibleToUser, Rect bounds, int windowId, String treePath) {
        this.id = clean(id);
        this.text = clean(text);
        this.description = clean(description);
        this.className = clean(className);
        this.packageName = clean(packageName);
        this.selected = selected;
        this.clickable = clickable;
        this.visibleToUser = visibleToUser;
        this.windowId = windowId;
        this.treePath = clean(treePath);
        // Copy fields explicitly so geometry logic is also executable in local JVM tests, where
        // android.graphics.Rect's copy-constructor body is replaced by an Android stub.
        this.bounds = new Rect();
        this.bounds.left = bounds.left;
        this.bounds.top = bounds.top;
        this.bounds.right = bounds.right;
        this.bounds.bottom = bounds.bottom;
    }

    public String searchableText() { return (text + " " + description).trim(); }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("text", text)
                .put("description", description)
                .put("class", className)
                .put("package", packageName)
                .put("selected", selected)
                .put("clickable", clickable)
                .put("visible_to_user", visibleToUser)
                .put("window_id", windowId)
                .put("tree_path", treePath)
                .put("bounds", new JSONObject()
                        .put("left", bounds.left).put("top", bounds.top)
                        .put("right", bounds.right).put("bottom", bounds.bottom));
    }

    public static UiNode fromJson(JSONObject value) {
        JSONObject geometry = value.optJSONObject("bounds");
        Rect bounds = new Rect();
        if (geometry != null) {
            bounds.left = geometry.optInt("left");
            bounds.top = geometry.optInt("top");
            bounds.right = geometry.optInt("right");
            bounds.bottom = geometry.optInt("bottom");
        }
        return new UiNode(value.optString("id"), value.optString("text"),
                value.optString("description"), value.optString("class"),
                value.optString("package"), value.optBoolean("selected"),
                value.optBoolean("clickable"), value.optBoolean("visible_to_user", true),
                bounds, value.optInt("window_id", -1), value.optString("tree_path"));
    }

    private static String clean(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }
}
