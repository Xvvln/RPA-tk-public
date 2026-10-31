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
    /** Whether Android reports that this node can receive input focus. */
    public final boolean focusable;
    /** Current input-focus state, distinct from accessibility focus. */
    public final boolean focused;
    /** Whether Android reports that the node accepts editable text. */
    public final boolean editable;
    /** Drawing order within the node's parent. It is supporting evidence, not a global z-index. */
    public final int drawingOrder;
    /** Android's semantic scroll capability, used to distinguish nested pager containers. */
    public final boolean scrollable;
    /** Whether the node currently exposes ACTION_SCROLL_FORWARD. */
    public final boolean canScrollForward;
    /** Whether the node currently exposes ACTION_SCROLL_BACKWARD. */
    public final boolean canScrollBackward;
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
        this(id, text, description, className, packageName, selected, clickable,
                visibleToUser, bounds, windowId, treePath,
                false, false, false, 0,
                false, false, false);
    }

    public UiNode(String id, String text, String description, String className,
                  String packageName, boolean selected, boolean clickable,
                  boolean visibleToUser, Rect bounds, int windowId, String treePath,
                  boolean focusable, boolean focused, boolean editable, int drawingOrder) {
        this(id, text, description, className, packageName, selected, clickable,
                visibleToUser, bounds, windowId, treePath,
                focusable, focused, editable, drawingOrder,
                false, false, false);
    }

    public UiNode(String id, String text, String description, String className,
                  String packageName, boolean selected, boolean clickable,
                  boolean visibleToUser, Rect bounds, int windowId, String treePath,
                  boolean focusable, boolean focused, boolean editable, int drawingOrder,
                  boolean scrollable, boolean canScrollForward,
                  boolean canScrollBackward) {
        this.id = clean(id);
        this.text = clean(text);
        this.description = clean(description);
        this.className = clean(className);
        this.packageName = clean(packageName);
        this.selected = selected;
        this.clickable = clickable;
        this.visibleToUser = visibleToUser;
        this.focusable = focusable;
        this.focused = focused;
        this.editable = editable;
        this.drawingOrder = Math.max(0, drawingOrder);
        this.scrollable = scrollable;
        this.canScrollForward = canScrollForward;
        this.canScrollBackward = canScrollBackward;
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
                .put("focusable", focusable)
                .put("focused", focused)
                .put("editable", editable)
                .put("drawing_order", drawingOrder)
                .put("scrollable", scrollable)
                .put("can_scroll_forward", canScrollForward)
                .put("can_scroll_backward", canScrollBackward)
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
                bounds, value.optInt("window_id", -1), value.optString("tree_path"),
                value.optBoolean("focusable"), value.optBoolean("focused"),
                value.optBoolean("editable"), value.optInt("drawing_order"),
                value.optBoolean("scrollable"), value.optBoolean("can_scroll_forward"),
                value.optBoolean("can_scroll_backward"));
    }

    private static String clean(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }
}
