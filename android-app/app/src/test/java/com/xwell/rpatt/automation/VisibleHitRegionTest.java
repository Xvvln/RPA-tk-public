package com.xwell.rpatt.automation;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class VisibleHitRegionTest {
    @Test public void realFavoriteToastDoesNotHideFollowButtonAboveIt() {
        UiNode follow = node(10, "0/1/2", PageAnalyzer.TIKTOK_PACKAGE,
                899, 1088, 1080, 1191, true);
        List<UiNode> nodes = Arrays.asList(
                follow,
                node(11, "0", PageAnalyzer.TIKTOK_PACKAGE,
                        0, 0, 1080, 2400, false),
                node(11, "0/0", PageAnalyzer.TIKTOK_PACKAGE,
                        0, 0, 1080, 2400, false),
                node(11, "0/0/0", PageAnalyzer.TIKTOK_PACKAGE,
                        0, 1961, 1080, 2400, false),
                node(11, "0/0/0/0", PageAnalyzer.TIKTOK_PACKAGE,
                        24, 1961, 1056, 2093, false),
                semanticNode(11, "0/0/0/0/0", "已收藏",
                        24, 1961, 1056, 2093));
        UiSnapshot snapshot = snapshot(nodes,
                window(10, PageAnalyzer.TIKTOK_PACKAGE, 0, true, true,
                        0, 0, 1080, 2400),
                window(11, PageAnalyzer.TIKTOK_PACKAGE, 1, false, false,
                        0, 0, 1080, 2400));

        VisibleHitRegion.Point point = VisibleHitRegion.resolve(snapshot, follow);

        assertNotNull(point);
        assertTrue(VisibleHitRegion.hasPassiveSameAppTransientAbove(snapshot, follow));
        assertTrue(point.x >= 899 && point.x <= 1080);
        assertTrue(point.y >= 1088 && point.y <= 1191);
    }

    @Test public void passiveToastStillBlocksItsActualContentArea() {
        UiNode target = node(10, "0/1/2", PageAnalyzer.TIKTOK_PACKAGE,
                100, 1980, 1000, 2070, true);
        List<UiNode> nodes = Arrays.asList(
                target,
                node(11, "0", PageAnalyzer.TIKTOK_PACKAGE,
                        0, 0, 1080, 2400, false),
                node(11, "0/0", PageAnalyzer.TIKTOK_PACKAGE,
                        0, 0, 1080, 2400, false),
                node(11, "0/0/0", PageAnalyzer.TIKTOK_PACKAGE,
                        0, 1961, 1080, 2400, false));

        VisibleHitRegion.Point point = VisibleHitRegion.resolve(snapshot(nodes,
                window(10, PageAnalyzer.TIKTOK_PACKAGE, 0, true, true,
                        0, 0, 1080, 2400),
                window(11, PageAnalyzer.TIKTOK_PACKAGE, 1, false, false,
                        0, 0, 1080, 2400)), target);

        assertNull(point);
    }

    @Test public void focusedSameAppDialogRemainsAFullBlocker() {
        UiNode target = node(10, "0/1/2", PageAnalyzer.TIKTOK_PACKAGE,
                899, 1088, 1080, 1191, true);
        UiSnapshot snapshot = snapshot(Arrays.asList(target),
                window(10, PageAnalyzer.TIKTOK_PACKAGE, 0, false, false,
                        0, 0, 1080, 2400),
                window(12, PageAnalyzer.TIKTOK_PACKAGE, 2, true, true,
                        0, 0, 1080, 2400));

        assertNull(VisibleHitRegion.resolve(snapshot, target));
        assertFalse(VisibleHitRegion.hasPassiveSameAppTransientAbove(snapshot, target));
    }

    @Test public void foreignOverlayRemainsAFullBlockerEvenWhenUnfocused() {
        UiNode target = node(10, "0/1/2", PageAnalyzer.TIKTOK_PACKAGE,
                899, 1088, 1080, 1191, true);
        UiSnapshot snapshot = snapshot(Arrays.asList(target),
                window(10, PageAnalyzer.TIKTOK_PACKAGE, 0, true, true,
                        0, 0, 1080, 2400),
                window(20, "other.overlay", 3, false, false,
                        0, 0, 1080, 2400));

        assertNull(VisibleHitRegion.resolve(snapshot, target));
    }

    private static UiSnapshot snapshot(List<UiNode> nodes, UiWindow... windows) {
        return new UiSnapshot(1L, PageAnalyzer.TIKTOK_PACKAGE, 1080, 2400,
                nodes, Arrays.asList(windows),
                ForegroundDecision.legacy(PageAnalyzer.TIKTOK_PACKAGE,
                        PageAnalyzer.TIKTOK_PACKAGE));
    }

    private static UiWindow window(int id, String packageName, int layer,
                                   boolean active, boolean focused,
                                   int left, int top, int right, int bottom) {
        return new UiWindow(id, packageName, UiWindow.TYPE_APPLICATION, layer,
                active, focused, false, true, rect(left, top, right, bottom));
    }

    private static UiNode semanticNode(int windowId, String path, String description,
                                       int left, int top, int right, int bottom) {
        return new UiNode("", "", description, "android.view.View",
                PageAnalyzer.TIKTOK_PACKAGE, false, true, true,
                rect(left, top, right, bottom), windowId, path);
    }

    private static UiNode node(int windowId, String path, String packageName,
                               int left, int top, int right, int bottom, boolean clickable) {
        return new UiNode("", "", "", "android.widget.FrameLayout",
                packageName, false, clickable, true,
                rect(left, top, right, bottom), windowId, path);
    }

    private static Rect rect(int left, int top, int right, int bottom) {
        Rect value = new Rect();
        value.left = left;
        value.top = top;
        value.right = right;
        value.bottom = bottom;
        return value;
    }
}
