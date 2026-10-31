package com.xwell.rpatt.automation;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/** Chooses a point in a target node after subtracting higher accessibility-window occlusions. */
public final class VisibleHitRegion {
    public static final class Point {
        public final float x;
        public final float y;

        private Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private VisibleHitRegion() {}

    public static boolean hasPassiveSameAppTransientAbove(UiSnapshot snapshot, UiNode target) {
        if (snapshot == null || target == null) return false;
        int targetLayer = Integer.MIN_VALUE;
        for (UiWindow window : snapshot.windows) {
            if (window.id == target.windowId) {
                targetLayer = window.layer;
                break;
            }
        }
        if (targetLayer == Integer.MIN_VALUE) return false;
        for (UiWindow window : snapshot.windows) {
            if (window.id != target.windowId && window.layer > targetLayer
                    && passiveSameAppTransient(target, window)) return true;
        }
        return false;
    }

    public static Point resolve(UiSnapshot snapshot, UiNode target) {
        if (snapshot == null || target == null || empty(target.bounds)) return null;
        Rect display = new Rect();
        display.left = 0;
        display.top = 0;
        display.right = snapshot.displayWidth;
        display.bottom = snapshot.displayHeight;
        Rect visible = intersection(target.bounds, display);
        if (empty(visible)) return null;

        int targetLayer = Integer.MIN_VALUE;
        for (UiWindow window : snapshot.windows) {
            if (window.id == target.windowId) {
                targetLayer = window.layer;
                break;
            }
        }

        List<Rect> regions = new ArrayList<>();
        regions.add(visible);
        if (targetLayer != Integer.MIN_VALUE) {
            for (UiWindow window : snapshot.windows) {
                if (window.id == target.windowId || window.layer <= targetLayer
                        || empty(window.bounds)) continue;
                for (Rect occlusion : occlusions(snapshot, target, window)) {
                    regions = subtractAll(regions, occlusion);
                    if (regions.isEmpty()) return null;
                }
            }
        }

        Rect largest = null;
        long largestArea = 0;
        for (Rect region : regions) {
            long area = (long) width(region) * height(region);
            if (area > largestArea) {
                largest = region;
                largestArea = area;
            }
        }
        return largest == null ? null
                : new Point(largest.left + width(largest) / 2f,
                largest.top + height(largest) / 2f);
    }

    /**
     * Android reports some same-app transient feedback surfaces (for example TikTok's
     * "已收藏" snackbar) as full-screen application windows even though only a small content
     * subtree consumes input. Focused/active dialogs and foreign windows remain full blockers.
     */
    private static List<Rect> occlusions(UiSnapshot snapshot, UiNode target, UiWindow window) {
        if (!passiveSameAppTransient(target, window)) {
            List<Rect> full = new ArrayList<>();
            full.add(copy(window.bounds));
            return full;
        }

        List<Rect> contentRoots = new ArrayList<>();
        for (UiNode node : snapshot.nodes) {
            if (node.windowId != window.id || !node.visibleToUser || empty(node.bounds)
                    || covers(node.bounds, window.bounds, 0.90d)) {
                continue;
            }
            UiNode parent = parent(snapshot, node);
            if (parent == null || covers(parent.bounds, window.bounds, 0.90d)) {
                Rect clipped = intersection(node.bounds, window.bounds);
                if (!empty(clipped)) contentRoots.add(clipped);
            }
        }
        if (!contentRoots.isEmpty()) return contentRoots;

        // Missing content geometry is not permission to click through an unknown window.
        List<Rect> conservative = new ArrayList<>();
        conservative.add(copy(window.bounds));
        return conservative;
    }

    private static boolean passiveSameAppTransient(UiNode target, UiWindow window) {
        return window.type == UiWindow.TYPE_APPLICATION
                && !window.active && !window.focused
                && !target.packageName.isEmpty()
                && target.packageName.equals(window.packageName);
    }

    private static UiNode parent(UiSnapshot snapshot, UiNode node) {
        String parentPath = parentPath(node.treePath);
        if (parentPath.isEmpty()) return null;
        for (UiNode candidate : snapshot.nodes) {
            if (candidate.windowId == node.windowId
                    && parentPath.equals(candidate.treePath)) return candidate;
        }
        return null;
    }

    private static String parentPath(String path) {
        int separator = path == null ? -1 : path.lastIndexOf('/');
        return separator <= 0 ? "" : path.substring(0, separator);
    }

    private static boolean covers(Rect candidate, Rect container, double minimumRatio) {
        Rect overlap = intersection(candidate, container);
        long containerArea = (long) width(container) * height(container);
        long overlapArea = empty(overlap) ? 0L : (long) width(overlap) * height(overlap);
        return containerArea > 0L && overlapArea >= containerArea * minimumRatio;
    }

    private static List<Rect> subtractAll(List<Rect> regions, Rect occlusion) {
        List<Rect> result = new ArrayList<>();
        for (Rect region : regions) result.addAll(subtract(region, occlusion));
        return result;
    }

    private static List<Rect> subtract(Rect source, Rect occlusion) {
        Rect overlap = intersection(source, occlusion);
        List<Rect> parts = new ArrayList<>();
        if (empty(overlap)) {
            parts.add(copy(source));
            return parts;
        }
        add(parts, source.left, source.top, source.right, overlap.top);
        add(parts, source.left, overlap.bottom, source.right, source.bottom);
        add(parts, source.left, overlap.top, overlap.left, overlap.bottom);
        add(parts, overlap.right, overlap.top, source.right, overlap.bottom);
        return parts;
    }

    private static void add(List<Rect> parts, int left, int top, int right, int bottom) {
        if (left >= right || top >= bottom) return;
        Rect value = new Rect();
        value.left = left;
        value.top = top;
        value.right = right;
        value.bottom = bottom;
        parts.add(value);
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
        value.left = source.left;
        value.top = source.top;
        value.right = source.right;
        value.bottom = source.bottom;
        return value;
    }
}
