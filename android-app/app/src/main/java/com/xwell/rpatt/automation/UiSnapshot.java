package com.xwell.rpatt.automation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class UiSnapshot {
    public final long capturedAt;
    public final String foregroundPackage;
    public final int displayWidth;
    public final int displayHeight;
    public final List<UiNode> nodes;
    public final List<UiWindow> windows;
    public final ForegroundDecision foregroundDecision;

    public UiSnapshot(long capturedAt, String foregroundPackage, List<UiNode> nodes) {
        this(capturedAt, foregroundPackage, 0, 0, nodes);
    }

    public UiSnapshot(long capturedAt, String foregroundPackage, int displayWidth,
                      int displayHeight, List<UiNode> nodes) {
        this(capturedAt, foregroundPackage, displayWidth, displayHeight, nodes,
                Collections.emptyList(),
                ForegroundDecision.legacy(foregroundPackage, PageAnalyzer.TIKTOK_PACKAGE));
    }

    public UiSnapshot(long capturedAt, String foregroundPackage, int displayWidth,
                      int displayHeight, List<UiNode> nodes, List<UiWindow> windows,
                      ForegroundDecision foregroundDecision) {
        this.capturedAt = capturedAt;
        this.foregroundPackage = foregroundPackage == null ? "" : foregroundPackage;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
        this.windows = Collections.unmodifiableList(new ArrayList<>(windows));
        this.foregroundDecision = foregroundDecision == null
                ? ForegroundDecision.unknown(Collections.singletonList("decision:null"))
                : foregroundDecision;
    }

    public boolean hasId(String id) {
        for (UiNode node : nodes) if (id.equals(node.id)) return true;
        return false;
    }

    public boolean hasIdSuffix(String suffix) {
        for (UiNode node : nodes) if (node.id.endsWith(suffix)) return true;
        return false;
    }

    public UiNode firstByIdSuffix(String suffix) {
        for (UiNode node : nodes) if (node.id.endsWith(suffix)) return node;
        return null;
    }

    /**
     * TikTok keeps adjacent pager items in the accessibility tree. Those items reuse the same
     * resource IDs and may even be reported as visible, despite their bounds being above or below
     * the display. Only return a control whose centre is inside the current display.
     */
    public UiNode visibleByIdSuffix(String suffix, int displayWidth, int displayHeight) {
        for (UiNode node : nodes) {
            if (node.id.endsWith(suffix) && isVisible(node, displayWidth, displayHeight)) return node;
        }
        return null;
    }

    /** Return the on-screen matching control nearest to a previously captured control. */
    public UiNode visibleByIdSuffixNear(String suffix, UiNode anchor, int displayWidth, int displayHeight) {
        UiNode nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (UiNode node : nodes) {
            if (!node.id.endsWith(suffix) || !isVisible(node, displayWidth, displayHeight)) continue;
            if (anchor == null) return node;
            long deltaX = (long) centerX(node) - centerX(anchor);
            long deltaY = (long) centerY(node) - centerY(anchor);
            long distance = deltaX * deltaX + deltaY * deltaY;
            if (distance < nearestDistance) {
                nearest = node;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /**
     * Resolve the send control as the clickable Button sibling immediately to the right of the
     * comment composer. TikTok frequently renames its short resource ID and may expose an
     * unresolved numeric content description, so parent relationship and geometry are the stable
     * contract here.
     */
    public UiNode visibleCommentSendNear(UiNode input) {
        if (input == null || input.treePath.isEmpty() || !isVisible(input)) return null;
        String parent = parentPath(input.treePath);
        UiNode best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (UiNode node : nodes) {
            if (node == input || !node.clickable || !boundsIntersectDisplay(node, 0.20f)
                    || node.windowId != input.windowId
                    || !"android.widget.Button".equals(node.className)
                    || !parent.equals(parentPath(node.treePath))) continue;
            int inputCenterY = input.bounds.top + (input.bounds.bottom - input.bounds.top) / 2;
            int nodeCenterY = node.bounds.top + (node.bounds.bottom - node.bounds.top) / 2;
            int verticalDistance = Math.abs(inputCenterY - nodeCenterY);
            if (node.bounds.left < input.bounds.right - 24
                    || verticalDistance > Math.max(input.bounds.bottom - input.bounds.top,
                    node.bounds.bottom - node.bounds.top)) continue;
            int horizontalDistance = Math.abs(node.bounds.left - input.bounds.right);
            if (horizontalDistance < bestDistance) {
                best = node;
                bestDistance = horizontalDistance;
            }
        }
        return best;
    }

    /**
     * Return the same logical input captured before a UI reflow. TikTok can expose a new visible
     * composer while retaining the focused original behind the input method; nearest-visible
     * matching would then verify the wrong clone. Window and tree path are the identity contract,
     * while visibility is intentionally not required for readback.
     */
    public UiNode inputByIdentity(UiNode anchor) {
        if (anchor == null || anchor.treePath.isEmpty()
                || !"android.widget.EditText".equals(anchor.className)) return null;
        return nodeByIdentity(anchor);
    }

    public UiNode nodeByIdentity(UiNode anchor) {
        if (anchor == null || anchor.treePath.isEmpty()) return null;
        for (UiNode node : nodes) {
            if (node.windowId == anchor.windowId
                    && node.treePath.equals(anchor.treePath)
                    && node.packageName.equals(anchor.packageName)
                    && node.className.equals(anchor.className)
                    && (anchor.id.isEmpty() || node.id.equals(anchor.id))) return node;
        }
        return null;
    }

    public boolean hasExactInputText(String expected) {
        for (UiNode node : nodes) {
            if ("android.widget.EditText".equals(node.className)
                    && expected.equals(node.text)) return true;
        }
        return false;
    }

    /** Accept partially visible bottom-sheet controls while rejecting retained off-screen pages. */
    private boolean intersectsDisplay(UiNode node, float minimumVisibleFraction) {
        return node.visibleToUser && boundsIntersectDisplay(node, minimumVisibleFraction);
    }

    private boolean boundsIntersectDisplay(UiNode node, float minimumVisibleFraction) {
        if (displayWidth <= 0 || displayHeight <= 0) return false;
        int nodeWidth = node.bounds.right - node.bounds.left;
        int nodeHeight = node.bounds.bottom - node.bounds.top;
        if (nodeWidth <= 0 || nodeHeight <= 0) return false;
        int overlapWidth = Math.max(0, Math.min(node.bounds.right, displayWidth)
                - Math.max(node.bounds.left, 0));
        int overlapHeight = Math.max(0, Math.min(node.bounds.bottom, displayHeight)
                - Math.max(node.bounds.top, 0));
        long visibleArea = (long) overlapWidth * overlapHeight;
        long nodeArea = (long) nodeWidth * nodeHeight;
        return visibleArea >= nodeArea * minimumVisibleFraction;
    }

    public boolean isVisible(UiNode node) {
        return isVisible(node, displayWidth, displayHeight);
    }

    private static boolean isVisible(UiNode node, int displayWidth, int displayHeight) {
        if (!node.visibleToUser) return false;
        if (node.bounds.left >= node.bounds.right || node.bounds.top >= node.bounds.bottom
                || displayWidth <= 0 || displayHeight <= 0) return false;
        int centerX = centerX(node);
        int centerY = centerY(node);
        return centerX >= 0 && centerX < displayWidth && centerY >= 0 && centerY < displayHeight;
    }

    private static int centerX(UiNode node) {
        return node.bounds.left + (node.bounds.right - node.bounds.left) / 2;
    }

    private static int centerY(UiNode node) {
        return node.bounds.top + (node.bounds.bottom - node.bounds.top) / 2;
    }

    private static String parentPath(String path) {
        int separator = path.lastIndexOf('/');
        return separator <= 0 ? "" : path.substring(0, separator);
    }

    public boolean containsAny(String... markers) {
        String value = textBlob();
        for (String marker : markers) {
            if (value.contains(marker.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    public String textBlob() {
        StringBuilder builder = new StringBuilder();
        for (UiNode node : nodes) {
            if (!node.text.isEmpty()) builder.append(node.text).append(' ');
            if (!node.description.isEmpty()) builder.append(node.description).append(' ');
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    public JSONObject toJson() throws JSONException {
        JSONArray values = new JSONArray();
        for (UiNode node : nodes) values.put(node.toJson());
        JSONArray windowValues = new JSONArray();
        for (UiWindow window : windows) windowValues.put(window.toJson());
        return new JSONObject()
                .put("captured_at", capturedAt)
                .put("foreground_package", foregroundPackage)
                .put("foreground_decision", foregroundDecision.toJson())
                .put("display_width", displayWidth)
                .put("display_height", displayHeight)
                .put("windows", windowValues)
                .put("nodes", values);
    }

    public static UiSnapshot fromJson(JSONObject value) {
        List<UiNode> nodes = new ArrayList<>();
        JSONArray values = value.optJSONArray("nodes");
        if (values != null) {
            for (int index = 0; index < values.length(); index++) {
                JSONObject node = values.optJSONObject(index);
                if (node != null) nodes.add(UiNode.fromJson(node));
            }
        }
        List<UiWindow> windows = new ArrayList<>();
        JSONArray windowValues = value.optJSONArray("windows");
        if (windowValues != null) {
            for (int index = 0; index < windowValues.length(); index++) {
                JSONObject window = windowValues.optJSONObject(index);
                if (window != null) windows.add(UiWindow.fromJson(window));
            }
        }
        String foreground = value.optString("foreground_package");
        JSONObject decisionValue = value.optJSONObject("foreground_decision");
        ForegroundDecision decision = decisionValue == null
                ? ForegroundDecision.legacy(foreground, PageAnalyzer.TIKTOK_PACKAGE)
                : ForegroundDecision.fromJson(decisionValue);
        return new UiSnapshot(value.optLong("captured_at"), foreground,
                value.optInt("display_width"), value.optInt("display_height"), nodes,
                windows, decision);
    }
}
