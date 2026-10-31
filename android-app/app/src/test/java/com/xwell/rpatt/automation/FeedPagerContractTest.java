package com.xwell.rpatt.automation;

import android.graphics.Rect;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class FeedPagerContractTest {
    @Test public void resolvesVerticalFeedAmongReusedAndNestedPagers() {
        List<UiNode> nodes = new ArrayList<>();
        nodes.add(pager("0/0", "X.HomePager", true));
        nodes.add(child("0/0/0", 0, 0, 1080, 2205));
        nodes.add(child("0/0/1", 1080, 0, 2160, 2205));

        nodes.add(pager("0/0/1/0", "X.VerticalPager", true));
        nodes.add(child("0/0/1/0/0", 0, -2205, 1080, 0));
        nodes.add(child("0/0/1/0/1", 0, 0, 1080, 2205));
        nodes.add(child("0/0/1/0/2", 0, 2205, 1080, 4410));

        nodes.add(pager("0/0/1/0/1/4", "androidx.viewpager.widget.ViewPager", true));
        nodes.add(child("0/0/1/0/1/4/0", -1080, 0, 0, 2205));
        nodes.add(child("0/0/1/0/1/4/1", 0, 0, 1080, 2205));
        nodes.add(child("0/0/1/0/1/4/2", 1080, 0, 2160, 2205));

        FeedPagerContract.Result result = FeedPagerContract.analyze(snapshot(nodes));

        assertTrue(result.usable());
        assertEquals("0/0/1/0", result.pager.treePath);
        assertTrue(result.evidence.contains("feed_pager:vertical_direct_child_layout"));
    }

    @Test public void refusesIdOnlyCandidateWithoutSemanticCapability() {
        List<UiNode> nodes = new ArrayList<>();
        nodes.add(pager("0/0", "androidx.viewpager.widget.ViewPager", false));
        nodes.add(child("0/0/0", 0, 0, 1080, 2205));
        nodes.add(child("0/0/1", 0, 2205, 1080, 4410));

        FeedPagerContract.Result result = FeedPagerContract.analyze(snapshot(nodes));

        assertFalse(result.usable());
        assertEquals(null, result.pager);
    }

    @Test public void scrollCapabilitiesSurviveArtifactRoundTrip() throws Exception {
        UiNode original = pager("0/1/2", "X.VerticalPager", true);

        UiNode restored = UiNode.fromJson(original.toJson());

        assertTrue(restored.scrollable);
        assertTrue(restored.canScrollForward);
        assertFalse(restored.canScrollBackward);
        assertEquals(original.treePath, restored.treePath);
    }

    private static UiSnapshot snapshot(List<UiNode> nodes) {
        return new UiSnapshot(1L, PageAnalyzer.TIKTOK_PACKAGE, 1080, 2400, nodes);
    }

    private static UiNode pager(String path, String className, boolean canScrollForward) {
        return node(PageAnalyzer.TIKTOK_PACKAGE + ":id/viewpager", className,
                0, 0, 1080, 2205, path,
                canScrollForward, canScrollForward, false);
    }

    private static UiNode child(String path, int left, int top, int right, int bottom) {
        return node("", "android.widget.FrameLayout", left, top, right, bottom, path,
                false, false, false);
    }

    private static UiNode node(String id, String className,
                               int left, int top, int right, int bottom, String path,
                               boolean scrollable, boolean canScrollForward,
                               boolean canScrollBackward) {
        Rect bounds = new Rect();
        bounds.left = left;
        bounds.top = top;
        bounds.right = right;
        bounds.bottom = bottom;
        return new UiNode(id, "", "", className, PageAnalyzer.TIKTOK_PACKAGE,
                false, false, true, bounds, 7, path,
                false, false, false, 0,
                scrollable, canScrollForward, canScrollBackward);
    }
}
