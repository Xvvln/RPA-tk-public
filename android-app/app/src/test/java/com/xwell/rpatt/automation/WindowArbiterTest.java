package com.xwell.rpatt.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class WindowArbiterTest {
    private static final String TARGET = PageAnalyzer.TIKTOK_PACKAGE;
    private static final String ASSISTANT = "com.xwell.rpatt";

    @Test
    public void assistantOverlayCannotReplaceVisibleTikTok() {
        UiWindow tiktok = window(7, TARGET, UiWindow.TYPE_APPLICATION, 2,
                false, false, true);
        // Some OEMs expose an application overlay as an application-like window. Package
        // ownership still prevents it from becoming foreground evidence against the target.
        UiWindow overlay = window(9, ASSISTANT, UiWindow.TYPE_APPLICATION, 8,
                true, true, true);

        ForegroundDecision decision = WindowArbiter.decide(
                Arrays.asList(overlay, tiktok), TARGET, ASSISTANT);

        assertEquals(TARGET, decision.packageName);
        assertEquals(ForegroundDecision.Source.TARGET_UNDER_BENIGN_OVERLAY, decision.source);
        assertTrue(decision.confirmsTarget(TARGET));
        assertFalse(decision.confirmsOtherApp(TARGET));
    }

    @Test
    public void inputMethodPreservesUnderlyingTarget() {
        UiWindow tiktok = window(7, TARGET, UiWindow.TYPE_APPLICATION, 2,
                true, false, true);
        UiWindow ime = window(10, "com.example.ime", UiWindow.TYPE_INPUT_METHOD, 12,
                true, true, true);

        ForegroundDecision decision = WindowArbiter.decide(
                Arrays.asList(ime, tiktok), TARGET, ASSISTANT);

        assertEquals(TARGET, decision.packageName);
        assertEquals(ForegroundDecision.Source.TARGET_ACTIVE, decision.source);
        assertTrue(WindowArbiter.allowsTargetInteraction(
                decision, Arrays.asList(tiktok, ime), TARGET));
    }

    @Test
    public void activeTargetWithoutInputMethodIsNotSafeForWrites() {
        UiWindow transitioningTarget = window(7, TARGET, UiWindow.TYPE_APPLICATION, 2,
                true, false, true);
        ForegroundDecision decision = WindowArbiter.decide(
                Collections.singletonList(transitioningTarget), TARGET, ASSISTANT);

        assertEquals(ForegroundDecision.Source.TARGET_ACTIVE, decision.source);
        assertFalse(WindowArbiter.allowsTargetInteraction(
                decision, Collections.singletonList(transitioningTarget), TARGET));
    }

    @Test
    public void focusedExternalApplicationWinsOverRetainedTargetTree() {
        UiWindow tiktok = window(7, TARGET, UiWindow.TYPE_APPLICATION, 2,
                false, false, true);
        UiWindow settings = window(11, "com.android.settings", UiWindow.TYPE_APPLICATION, 5,
                true, true, true);

        ForegroundDecision decision = WindowArbiter.decide(
                Arrays.asList(tiktok, settings), TARGET, ASSISTANT);

        assertEquals("com.android.settings", decision.packageName);
        assertEquals(ForegroundDecision.Source.EXTERNAL_FOCUSED, decision.source);
        assertTrue(decision.confirmsOtherApp(TARGET));
        assertFalse(WindowArbiter.allowsTargetInteraction(
                decision, Arrays.asList(tiktok, settings), TARGET));
    }

    @Test
    public void traversalOrderDoesNotChangeDecision() {
        UiWindow tiktok = window(7, TARGET, UiWindow.TYPE_APPLICATION, 2,
                false, false, true);
        UiWindow overlay = window(9, ASSISTANT, UiWindow.TYPE_ACCESSIBILITY_OVERLAY, 8,
                true, true, true);
        UiWindow system = window(12, "com.android.systemui", UiWindow.TYPE_SYSTEM, 20,
                false, false, true);

        ForegroundDecision first = WindowArbiter.decide(
                Arrays.asList(tiktok, overlay, system), TARGET, ASSISTANT);
        ForegroundDecision second = WindowArbiter.decide(
                Arrays.asList(system, overlay, tiktok), TARGET, ASSISTANT);

        assertEquals(first.packageName, second.packageName);
        assertEquals(first.source, second.source);
        assertEquals(first.confidence, second.confidence, 0d);
    }

    @Test
    public void noApplicationCandidateIsUnknown() {
        ForegroundDecision decision = WindowArbiter.decide(Collections.singletonList(
                window(12, "com.android.systemui", UiWindow.TYPE_SYSTEM, 20,
                        true, true, true)), TARGET, ASSISTANT);

        assertEquals(ForegroundDecision.Source.UNKNOWN, decision.source);
        assertFalse(decision.confirmsTarget(TARGET));
        assertFalse(decision.confirmsOtherApp(TARGET));
    }

    @Test
    public void unclassifiedApplicationAboveVisibleTargetIsNotAssumedBenign() {
        UiWindow tiktok = window(7, TARGET, UiWindow.TYPE_APPLICATION, 2,
                false, false, true);
        UiWindow unknown = window(15, "com.example.unknown", UiWindow.TYPE_APPLICATION, 8,
                false, false, true);

        ForegroundDecision decision = WindowArbiter.decide(
                Arrays.asList(tiktok, unknown), TARGET, ASSISTANT);

        assertEquals(ForegroundDecision.Source.UNKNOWN, decision.source);
        assertFalse(decision.confirmsTarget(TARGET));
        assertFalse(decision.confirmsOtherApp(TARGET));
    }

    private static UiWindow window(int id, String packageName, int type, int layer,
                                   boolean active, boolean focused, boolean rootAvailable) {
        Rect bounds = new Rect();
        bounds.left = 0;
        bounds.top = 0;
        bounds.right = 1080;
        bounds.bottom = 2400;
        return new UiWindow(id, packageName, type, layer, active, focused,
                false, rootAvailable, bounds);
    }
}
