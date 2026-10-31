package com.xwell.rpatt.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class InputSurfaceDetectorTest {
    @Test
    public void dockedImeUsesWindowTypeAndReducesUsableViewport() {
        UiNode composer = node("changed", "添加评论...", "android.widget.EditText",
                216, 1155, 1002, 1317, true, true, true);
        UiSnapshot snapshot = snapshot(Collections.singletonList(composer),
                appWindow(), window(10, "vendor.keyboard", UiWindow.TYPE_INPUT_METHOD, 12,
                        0, 1500, 1080, 2400));

        InputSurfaceDetector.Result result = InputSurfaceDetector.analyze(snapshot);

        assertEquals(InputSurfaceDetector.Kind.IME_DOCKED, result.kind);
        assertEquals(1500, result.usableBounds.bottom);
        assertTrue(result.normalizedCenterY(composer) > 0.80f);
        assertTrue(result.hasFocusedEditor());
    }

    @Test
    public void floatingImeKeepsFullViewportAndPublishesOcclusion() {
        UiSnapshot snapshot = snapshot(Collections.emptyList(),
                appWindow(), window(10, "another.keyboard", UiWindow.TYPE_INPUT_METHOD, 12,
                        420, 900, 1040, 1500));

        InputSurfaceDetector.Result result = InputSurfaceDetector.analyze(snapshot);

        assertEquals(InputSurfaceDetector.Kind.IME_FLOATING, result.kind);
        assertEquals(2400, result.usableBounds.bottom);
        assertEquals(1, result.occlusions.size());
    }

    @Test
    public void hardwareKeyboardFallsBackToFocusedEditableNode() {
        UiSnapshot snapshot = snapshot(Collections.singletonList(
                node("", "comment", "android.widget.EditText",
                        100, 1900, 980, 2050, true, true, true)),
                appWindow());

        InputSurfaceDetector.Result result = InputSurfaceDetector.analyze(snapshot);

        assertEquals(InputSurfaceDetector.Kind.EDITOR_FOCUSED_NO_IME_WINDOW, result.kind);
        assertTrue(result.inputActive());
        assertFalse(result.hasInputMethodWindow());
    }

    @Test
    public void hitPointAvoidsHigherFloatingWindow() {
        UiNode target = node("", "", "android.widget.ImageView",
                880, 900, 1060, 1080, false, false, false);
        UiSnapshot snapshot = snapshot(Collections.singletonList(target),
                appWindow(), window(20, "vendor.keyboard", UiWindow.TYPE_INPUT_METHOD, 12,
                        980, 880, 1080, 1120));

        VisibleHitRegion.Point point = VisibleHitRegion.resolve(snapshot, target);

        assertNotNull(point);
        assertTrue(point.x < 980);
        assertTrue(point.y >= 900 && point.y <= 1080);
    }

    private static UiSnapshot snapshot(java.util.List<UiNode> nodes, UiWindow... windows) {
        return new UiSnapshot(1L, PageAnalyzer.TIKTOK_PACKAGE, 1080, 2400,
                nodes, Arrays.asList(windows),
                ForegroundDecision.legacy(PageAnalyzer.TIKTOK_PACKAGE,
                        PageAnalyzer.TIKTOK_PACKAGE));
    }

    private static UiWindow appWindow() {
        return window(7, PageAnalyzer.TIKTOK_PACKAGE, UiWindow.TYPE_APPLICATION, 0,
                0, 0, 1080, 2400);
    }

    private static UiWindow window(int id, String packageName, int type, int layer,
                                   int left, int top, int right, int bottom) {
        Rect bounds = rect(left, top, right, bottom);
        return new UiWindow(id, packageName, type, layer,
                type == UiWindow.TYPE_APPLICATION, type == UiWindow.TYPE_APPLICATION,
                false, true, bounds);
    }

    private static UiNode node(String id, String text, String className,
                               int left, int top, int right, int bottom,
                               boolean focusable, boolean focused, boolean editable) {
        return new UiNode(id, text, "", className, PageAnalyzer.TIKTOK_PACKAGE,
                false, true, true, rect(left, top, right, bottom), 7, "0/1/2",
                focusable, focused, editable, 0);
    }

    private static Rect rect(int left, int top, int right, int bottom) {
        Rect bounds = new Rect();
        bounds.left = left;
        bounds.top = top;
        bounds.right = right;
        bounds.bottom = bottom;
        return bounds;
    }
}
