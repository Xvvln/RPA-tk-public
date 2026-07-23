package com.xwell.rpatt.automation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;

public final class GestureTargetContractTest {
    @Test
    public void acceptsSameVisibleControlAfterFreshResolution() {
        UiNode captured = node("favorite", 888, 1500, 1080, 1680, 7, "0/1/4");
        UiNode current = node("favorite", 890, 1504, 1080, 1684, 7, "0/1/4");
        assertTrue(GestureTargetContract.sameVisibleTarget(snapshot(current), captured, current));
    }

    @Test
    public void rejectsAdjacentActionRailControl() {
        UiNode captured = node("favorite", 888, 1500, 1080, 1680, 7, "0/1/4");
        UiNode adjacent = node("favorite", 888, 1680, 1080, 1860, 7, "0/1/5");
        assertFalse(GestureTargetContract.sameVisibleTarget(
                snapshot(adjacent), captured, adjacent));
    }

    @Test
    public void rejectsRetainedOffscreenPagerControl() {
        UiNode captured = node("favorite", 888, 1500, 1080, 1680, 7, "0/1/4");
        UiNode retained = node("favorite", 888, 3800, 1080, 3980, 7, "0/2/4");
        assertFalse(GestureTargetContract.sameVisibleTarget(
                snapshot(retained), captured, retained));
    }

    @Test
    public void rejectsDifferentWindowOrResourceIdentity() {
        UiNode captured = node("favorite", 888, 1500, 1080, 1680, 7, "0/1/4");
        UiNode otherWindow = node("favorite", 888, 1500, 1080, 1680, 8, "0/1/4");
        UiNode otherId = node("share", 888, 1500, 1080, 1680, 7, "0/1/4");
        assertFalse(GestureTargetContract.sameVisibleTarget(
                snapshot(otherWindow), captured, otherWindow));
        assertFalse(GestureTargetContract.sameVisibleTarget(
                snapshot(otherId), captured, otherId));
    }

    private static UiSnapshot snapshot(UiNode node) {
        return new UiSnapshot(1L, PageAnalyzer.TIKTOK_PACKAGE, 1080, 2400,
                Arrays.asList(node));
    }

    private static UiNode node(String id, int left, int top, int right, int bottom,
                               int windowId, String path) {
        Rect bounds = new Rect();
        bounds.left = left;
        bounds.top = top;
        bounds.right = right;
        bounds.bottom = bottom;
        return new UiNode(PageAnalyzer.TIKTOK_PACKAGE + ":id/" + id, "", "favorite",
                "android.widget.FrameLayout", PageAnalyzer.TIKTOK_PACKAGE,
                false, true, true, bounds, windowId, path);
    }
}
