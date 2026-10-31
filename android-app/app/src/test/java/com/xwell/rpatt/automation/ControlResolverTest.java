package com.xwell.rpatt.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;

public final class ControlResolverTest {
    @Test
    public void semanticRoleWinsWhenTikTokReusesAnId() {
        UiNode like = node(PageAnalyzer.LIKE_BUTTON_ID_CURRENT, "", "点赞的视频",
                "android.widget.Button", true, 888, 1299, 1080, 1479, "0/4/2");
        UiNode shareWithSameId = node(PageAnalyzer.LIKE_BUTTON_ID_CURRENT, "", "分享视频。10 次分享",
                "android.widget.Button", true, 888, 1839, 1080, 2019, "0/4/6");
        UiSnapshot snapshot = snapshot(like, shareWithSameId);

        assertSame(like, ControlResolver.resolve(snapshot, ControlResolver.Role.VIDEO_LIKE).node);
        assertSame(shareWithSameId,
                ControlResolver.resolve(snapshot, ControlResolver.Role.VIDEO_SHARE).node);
    }

    @Test
    public void resolvesChangedInputIdFromClassAndRegion() {
        UiNode changed = node(PageAnalyzer.TIKTOK_PACKAGE + ":id/new_composer", "添加评论...", "",
                "android.widget.EditText", false, 216, 2195, 859, 2289, "0/4/2/3");

        ControlResolver.Match match = ControlResolver.resolve(
                snapshot(changed), ControlResolver.Role.COMMENT_FIELD);

        assertSame(changed, match.node);
        assertEquals(0.82d, match.confidence, 0.001d);
    }

    @Test
    public void resolvesChangedSearchSubmitIdOnlyWithSemanticAndAnchorGeometry() {
        UiNode field = node(PageAnalyzer.TIKTOK_PACKAGE + ":id/new_search", "AI", "",
                "android.widget.EditText", true, 150, 127, 780, 235, "0/2/0");
        UiNode clear = node(PageAnalyzer.TIKTOK_PACKAGE + ":id/new_clear", "", "清除搜索字段",
                "android.widget.ImageView", true, 700, 151, 770, 211, "0/2/0/2");
        UiNode submit = node(PageAnalyzer.TIKTOK_PACKAGE + ":id/new_submit", "搜索", "",
                "android.widget.Button", true, 800, 127, 1032, 235, "0/3/0");
        UiNode appendSuggestion = node(PageAnalyzer.TIKTOK_PACKAGE + ":id/h5i", "",
                "将推荐附加到搜索字段", "android.widget.ImageView", true,
                936, 283, 1032, 379, "0/4/0/0/2");

        ControlResolver.Match match = ControlResolver.resolve(
                snapshot(field, clear, submit, appendSuggestion),
                ControlResolver.Role.SEARCH_SUBMIT, field);

        assertSame(submit, match.node);
    }

    @Test
    public void rejectsAmbiguousSearchFields() {
        UiNode first = node("", "", "", "android.widget.EditText", true,
                100, 120, 700, 220, "0/2/0");
        UiNode second = node("", "", "", "android.widget.EditText", true,
                120, 130, 720, 230, "0/3/0");

        assertNull(ControlResolver.resolve(snapshot(first, second),
                ControlResolver.Role.SEARCH_FIELD));
    }

    @Test
    public void resolvesSemanticLeafToNearestClickableAncestor() {
        UiNode outer = node("", "", "", "android.widget.FrameLayout", true,
                888, 1659, 1080, 1839, "0/4");
        UiNode inner = node("", "", "", "android.widget.FrameLayout", true,
                888, 1659, 1080, 1839, "0/4/0");
        UiNode semanticLeaf = node(PageAnalyzer.TIKTOK_PACKAGE + ":id/changed_favorite",
                "", "将此视频添加到或移出收藏。", "android.widget.Button", false,
                888, 1659, 1080, 1839, "0/4/0/0");

        ControlResolver.Match match = ControlResolver.resolve(
                snapshot(outer, inner, semanticLeaf), ControlResolver.Role.VIDEO_FAVORITE);

        assertSame(inner, match.node);
        assertTrue(match.evidence.contains("semantic_descendant"));
        assertTrue(match.evidence.contains("clickable_ancestor"));
    }

    @Test
    public void sameActionNodeFromWrapperAndLeafIsNotAmbiguous() {
        UiNode wrapper = node("", "", "favorite", "android.widget.FrameLayout", true,
                888, 1659, 1080, 1839, "0/4/0");
        UiNode leaf = node("", "", "favorite", "android.widget.Button", false,
                888, 1659, 1080, 1839, "0/4/0/0");

        ControlResolver.Match match = ControlResolver.resolve(
                snapshot(wrapper, leaf), ControlResolver.Role.VIDEO_FAVORITE);

        assertSame(wrapper, match.node);
    }

    @Test
    public void resolvesActionSheetEntriesBySemanticsAndClickableContainer() {
        UiNode repostWrapper = node("", "", "", "android.widget.FrameLayout", true,
                40, 1500, 280, 1760, "0/8/1");
        UiNode repostLabel = node("", "Repost", "", "android.widget.TextView", false,
                80, 1560, 240, 1640, "0/8/1/0");
        UiNode dismiss = node("", "Cancel", "", "android.widget.Button", true,
                300, 1500, 540, 1760, "0/8/2");

        ControlResolver.Match match = ControlResolver.resolve(
                snapshot(repostWrapper, repostLabel, dismiss), ControlResolver.Role.REPOST);

        assertSame(repostWrapper, match.node);
        assertTrue(match.evidence.contains("semantic_role"));
        assertTrue(match.evidence.contains("clickable_ancestor"));
    }

    @Test
    public void neverTreatsUndoRepostAsPositiveRepost() {
        UiNode undo = node("", "Undo repost", "", "android.widget.Button", true,
                40, 1500, 280, 1760, "0/8/1");

        assertNull(ControlResolver.resolve(snapshot(undo), ControlResolver.Role.REPOST));
        assertSame(undo, ControlResolver.resolve(
                snapshot(undo), ControlResolver.Role.REPOST_REMOVE).node);
    }

    @Test
    public void detectsEmptyCommentCountFromSemanticDescendantBeforeOpeningPanel() {
        UiNode wrapper = node("", "", "", "android.widget.FrameLayout", true,
                888, 1479, 1080, 1659, "0/4/2");
        UiNode label = node("", "", "阅读或添加评论。抢首评 条评论",
                "android.widget.Button", false,
                900, 1490, 1060, 1640, "0/4/2/0");
        UiSnapshot snapshot = snapshot(wrapper, label);

        assertTrue(ControlResolver.isDefinitelyEmptyCommentEntry(snapshot, wrapper));
    }

    @Test
    public void doesNotTreatNonZeroCommentCountAsEmpty() {
        UiNode comments = node("", "", "阅读或添加评论。12 条评论",
                "android.widget.Button", true,
                888, 1479, 1080, 1659, "0/4/2");

        assertTrue(!ControlResolver.isDefinitelyEmptyCommentEntry(
                snapshot(comments), comments));
    }

    private static UiSnapshot snapshot(UiNode... nodes) {
        return new UiSnapshot(1, PageAnalyzer.TIKTOK_PACKAGE,
                1080, 2400, Arrays.asList(nodes));
    }

    private static UiNode node(String id, String text, String description, String className,
                               boolean clickable, int left, int top, int right, int bottom,
                               String treePath) {
        Rect bounds = new Rect();
        bounds.left = left;
        bounds.top = top;
        bounds.right = right;
        bounds.bottom = bottom;
        return new UiNode(id, text, description, className, PageAnalyzer.TIKTOK_PACKAGE,
                false, clickable, true, bounds, 7, treePath);
    }
}
