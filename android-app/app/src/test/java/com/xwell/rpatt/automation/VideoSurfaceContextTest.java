package com.xwell.rpatt.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;

public final class VideoSurfaceContextTest {
    @Test
    public void recommendationRequiresTopTabAndBottomHomeNavigation() {
        UiSnapshot snapshot = snapshot(
                node("", "", "推荐", "android.widget.LinearLayout", 768, 103, 908, 277),
                node("", "", "搜索", "android.widget.ImageView", 912, 106, 1080, 274),
                node("", "", "首页", "android.widget.FrameLayout", 0, 2205, 216, 2249),
                action("fux", "点赞视频。535 万 个赞", 888, 1191, 1080, 1371),
                action("efj", "阅读或添加评论。3.6 万 条评论", 888, 1371, 1080, 1551));

        VideoSurfaceContext.Result result = VideoSurfaceContext.analyze(snapshot);
        assertEquals(VideoSurfaceContext.Kind.RECOMMENDATION_HOME, result.kind);
        assertTrue(result.confidence >= 0.90d);
    }

    @Test
    public void acceptsOemClippedBottomNavigationButNotOffscreenClone() {
        UiSnapshot clipped = snapshot(
                node("", "", "推荐", "android.widget.LinearLayout", 768, 103, 908, 277),
                node("nxy", "", "首页", "android.widget.FrameLayout", 0, 2205, 216, 2352));
        UiSnapshot offscreen = snapshot(
                node("", "", "推荐", "android.widget.LinearLayout", 768, 103, 908, 277),
                node("nxy", "", "首页", "android.widget.FrameLayout", 0, 2300, 216, 2447));

        assertEquals(VideoSurfaceContext.Kind.RECOMMENDATION_HOME,
                VideoSurfaceContext.analyze(clipped).kind);
        assertEquals(VideoSurfaceContext.Kind.UNKNOWN,
                VideoSurfaceContext.analyze(offscreen).kind);
    }

    @Test
    public void searchDetailRequiresBackVideoControlsAndSecondSearchAnchor() {
        UiSnapshot snapshot = snapshot(
                node("bna", "", "返回", "android.widget.ImageView", 18, 103, 174, 259),
                node("lv8", "", "搜索", "android.widget.ImageView", 210, 157, 258, 205),
                action("fux", "点赞视频。1.5 万 个赞", 888, 1191, 1080, 1371),
                action("efj", "阅读或添加评论。148 条评论", 888, 1371, 1080, 1551));

        assertEquals(VideoSurfaceContext.Kind.SEARCH_DETAIL,
                VideoSurfaceContext.analyze(snapshot).kind);
    }

    @Test
    public void searchDetailCanUseBottomComposerWhenTopSearchLabelIsMissing() {
        UiSnapshot snapshot = snapshot(
                node("bna", "", "Back", "android.widget.ImageView", 18, 103, 174, 259),
                action("fux", "Like video. 42 likes", 888, 1191, 1080, 1371),
                action("efj", "Read or add comments. 4 comments", 888, 1371, 1080, 1551),
                node("eb3", "Add comment...", "", "android.widget.EditText",
                        72, 2160, 1032, 2240));

        assertEquals(VideoSurfaceContext.Kind.SEARCH_DETAIL,
                VideoSurfaceContext.analyze(snapshot).kind);
    }

    @Test
    public void videoPlayerAloneCannotImpersonateRecommendationHome() {
        UiSnapshot snapshot = snapshot(
                action("fux", "点赞视频。535 万 个赞", 888, 1191, 1080, 1371),
                action("efj", "阅读或添加评论。3.6 万 条评论", 888, 1371, 1080, 1551),
                action("hrt", "将此视频添加到或移出收藏。", 888, 1551, 1080, 1731));

        assertEquals(VideoSurfaceContext.Kind.UNKNOWN,
                VideoSurfaceContext.analyze(snapshot).kind);
    }

    @Test
    public void contractMustBeStableAcrossTwoSnapshots() {
        VideoSurfaceContext.Result home = VideoSurfaceContext.analyze(snapshot(
                node("", "", "For You", "android.widget.LinearLayout", 700, 100, 900, 270),
                node("", "", "Home", "android.widget.FrameLayout", 0, 2200, 220, 2249)));
        VideoSurfaceContext.Result detail = VideoSurfaceContext.analyze(snapshot(
                node("bna", "", "Back", "android.widget.ImageView", 18, 103, 174, 259),
                node("lv8", "", "Search", "android.widget.ImageView", 210, 157, 258, 205),
                action("fux", "Like video", 888, 1191, 1080, 1371),
                action("efj", "Comments", 888, 1371, 1080, 1551)));

        assertEquals(VideoSurfaceContext.Kind.UNKNOWN,
                VideoSurfaceContext.consecutive(home, detail).kind);
    }

    @Test
    public void consecutiveToleratesTransientUnknownPeerOnRecommendationHome() {
        VideoSurfaceContext.Result home = VideoSurfaceContext.analyze(snapshot(
                node("", "", "推荐", "android.widget.LinearLayout", 768, 103, 908, 277),
                node("", "", "首页", "android.widget.FrameLayout", 0, 2205, 216, 2249),
                action("fux", "点赞视频。100 个赞", 888, 1191, 1080, 1371),
                action("efj", "阅读或添加评论。10 条评论", 888, 1371, 1080, 1551)));
        VideoSurfaceContext.Result blank = VideoSurfaceContext.analyze(snapshot(
                action("fux", "点赞视频。100 个赞", 888, 1191, 1080, 1371)));

        assertEquals(VideoSurfaceContext.Kind.RECOMMENDATION_HOME, home.kind);
        assertEquals(VideoSurfaceContext.Kind.UNKNOWN, blank.kind);
        VideoSurfaceContext.Result stable = VideoSurfaceContext.consecutive(home, blank);
        assertEquals(VideoSurfaceContext.Kind.RECOMMENDATION_HOME, stable.kind);
        assertTrue(stable.evidence.contains("context:tolerate_transient_unknown_peer"));
    }

    @Test
    public void consecutiveStillRejectsHomeVersusSearchConflict() {
        VideoSurfaceContext.Result home = VideoSurfaceContext.analyze(snapshot(
                node("", "", "推荐", "android.widget.LinearLayout", 768, 103, 908, 277),
                node("", "", "首页", "android.widget.FrameLayout", 0, 2205, 216, 2249)));
        VideoSurfaceContext.Result detail = VideoSurfaceContext.analyze(snapshot(
                node("bna", "", "返回", "android.widget.ImageView", 18, 103, 174, 259),
                node("lv8", "", "搜索", "android.widget.ImageView", 210, 157, 258, 205),
                action("fux", "点赞视频。1.5 万 个赞", 888, 1191, 1080, 1371),
                action("efj", "阅读或添加评论。148 条评论", 888, 1371, 1080, 1551)));

        assertEquals(VideoSurfaceContext.Kind.SEARCH_DETAIL, detail.kind);
        assertEquals(VideoSurfaceContext.Kind.UNKNOWN,
                VideoSurfaceContext.consecutive(home, detail).kind);
    }

    @Test
    public void consecutiveDoesNotTreatSearchBackFrameAsHomeFlicker() {
        VideoSurfaceContext.Result home = VideoSurfaceContext.analyze(snapshot(
                node("", "", "推荐", "android.widget.LinearLayout", 768, 103, 908, 277),
                node("", "", "首页", "android.widget.FrameLayout", 0, 2205, 216, 2249)));
        // Partial search surface: back is visible but action rail not yet resolved.
        VideoSurfaceContext.Result partialSearch = VideoSurfaceContext.analyze(snapshot(
                node("bna", "", "返回", "android.widget.ImageView", 18, 103, 174, 259),
                node("lv8", "", "搜索", "android.widget.ImageView", 210, 157, 258, 205)));

        assertEquals(VideoSurfaceContext.Kind.UNKNOWN, partialSearch.kind);
        assertTrue(partialSearch.evidence.contains("search_detail:back_present"));
        assertEquals(VideoSurfaceContext.Kind.UNKNOWN,
                VideoSurfaceContext.consecutive(home, partialSearch).kind);
    }

    private static UiSnapshot snapshot(UiNode... nodes) {
        return new UiSnapshot(1L, PageAnalyzer.TIKTOK_PACKAGE, 1080, 2249,
                Arrays.asList(nodes));
    }

    private static UiNode action(String id, String description,
                                 int left, int top, int right, int bottom) {
        return node(id, "", description, "android.widget.Button", left, top, right, bottom);
    }

    private static UiNode node(String id, String text, String description, String className,
                               int left, int top, int right, int bottom) {
        Rect bounds = new Rect();
        bounds.left = left;
        bounds.top = top;
        bounds.right = right;
        bounds.bottom = bottom;
        String resource = id.isEmpty() ? "" : PageAnalyzer.TIKTOK_PACKAGE + ":id/" + id;
        return new UiNode(resource, text, description, className, PageAnalyzer.TIKTOK_PACKAGE,
                false, true, true, bounds, 7, "0/" + id);
    }
}
