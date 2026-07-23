package com.xwell.rpatt.automation;

/** Safety contract for rebinding a captured control immediately before a gesture write. */
public final class GestureTargetContract {
    private GestureTargetContract() {}

    public static boolean sameVisibleTarget(UiSnapshot current, UiNode captured, UiNode candidate) {
        if (current == null || captured == null || candidate == null
                || !current.isVisible(candidate)
                || !captured.packageName.equals(candidate.packageName)
                || !captured.className.equals(candidate.className)) return false;
        if (captured.windowId >= 0 && candidate.windowId >= 0
                && captured.windowId != candidate.windowId) return false;

        int capturedWidth = width(captured);
        int capturedHeight = height(captured);
        int candidateWidth = width(candidate);
        int candidateHeight = height(candidate);
        if (capturedWidth <= 0 || capturedHeight <= 0
                || candidateWidth <= 0 || candidateHeight <= 0) return false;

        int deltaX = Math.abs(centerX(captured) - centerX(candidate));
        int deltaY = Math.abs(centerY(captured) - centerY(candidate));
        int toleranceX = Math.max(36, Math.min(capturedWidth, candidateWidth) / 3);
        int toleranceY = Math.max(36, Math.min(capturedHeight, candidateHeight) / 3);
        if (deltaX > toleranceX || deltaY > toleranceY) return false;

        // A stable ID is strong evidence. If TikTok rebuilds the node without an ID, require the
        // two bounds to overlap substantially instead of accepting a nearby action-rail control.
        if (!captured.id.isEmpty() && !candidate.id.isEmpty()) {
            return captured.id.equals(candidate.id);
        }
        return overlapRatio(captured, candidate) >= 0.45d;
    }

    private static double overlapRatio(UiNode first, UiNode second) {
        int overlapWidth = Math.max(0,
                Math.min(first.bounds.right, second.bounds.right)
                        - Math.max(first.bounds.left, second.bounds.left));
        int overlapHeight = Math.max(0,
                Math.min(first.bounds.bottom, second.bounds.bottom)
                        - Math.max(first.bounds.top, second.bounds.top));
        long overlap = (long) overlapWidth * overlapHeight;
        long smaller = Math.min((long) width(first) * height(first),
                (long) width(second) * height(second));
        return smaller <= 0 ? 0d : overlap / (double) smaller;
    }

    private static int width(UiNode node) { return node.bounds.right - node.bounds.left; }
    private static int height(UiNode node) { return node.bounds.bottom - node.bounds.top; }
    private static int centerX(UiNode node) { return node.bounds.left + width(node) / 2; }
    private static int centerY(UiNode node) { return node.bounds.top + height(node) / 2; }
}
