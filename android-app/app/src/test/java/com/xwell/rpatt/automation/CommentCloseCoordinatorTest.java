package com.xwell.rpatt.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class CommentCloseCoordinatorTest {
    @Test
    public void replaysSanitizedRealDeviceImeBlocker() throws Exception {
        InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("fixtures/ime-comment-panel.json");
        assertTrue(stream != null);
        String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        UiSnapshot snapshot = UiSnapshot.fromJson(new JSONObject(json));

        CommentSurfaceContract.Result comments = CommentSurfaceContract.analyze(snapshot);
        PageState page = PageAnalyzer.analyze(snapshot, true);

        assertEquals(InputSurfaceDetector.Kind.IME_DOCKED, comments.inputSurface.kind);
        assertEquals(CommentSurfaceContract.State.PANEL_EDITOR_FOCUSED, comments.state);
        assertEquals(PageState.Page.COMMENTS, page.page);
    }

    @Test
    public void realImeReflowShapeIsCommentsAndHidesInputFirst() {
        UiSnapshot snapshot = commentPanel(true, true);

        PageState page = PageAnalyzer.analyze(snapshot, true);
        CommentSurfaceContract.Result comments = CommentSurfaceContract.analyze(snapshot);
        CommentCloseCoordinator.Decision decision = CommentCloseCoordinator.decide(snapshot,
                new CommentCloseCoordinator.Attempts(false, false, false));

        assertEquals(PageState.Page.COMMENTS, page.page);
        assertEquals(CommentSurfaceContract.State.PANEL_EDITOR_FOCUSED, comments.state);
        assertEquals(CommentCloseCoordinator.Action.HIDE_INPUT_SURFACE, decision.action);
        assertTrue(page.evidence.contains("comments:editor_focused"));
    }

    @Test
    public void afterImeIsGoneCoordinatorUsesFreshCloseContract() {
        UiSnapshot snapshot = commentPanel(false, false);

        CommentCloseCoordinator.Decision decision = CommentCloseCoordinator.decide(snapshot,
                new CommentCloseCoordinator.Attempts(true, false, false));

        assertEquals(CommentSurfaceContract.State.PANEL_BROWSING,
                decision.comments.state);
        assertEquals(CommentCloseCoordinator.Action.TAP_CLOSE, decision.action);
    }

    @Test
    public void oemBackClosingKeyboardAndPanelMovesDirectlyToVerification() {
        UiSnapshot snapshot = recommendationFeed();

        CommentCloseCoordinator.Decision decision = CommentCloseCoordinator.decide(snapshot,
                new CommentCloseCoordinator.Attempts(true, false, false));

        assertEquals(CommentCloseCoordinator.Action.VERIFY_ORIGIN, decision.action);
        assertTrue(decision.comments.closedForOriginVerification());
    }

    @Test
    public void exhaustedRecoveryBlocksInsteadOfGuessingFeed() {
        UiSnapshot snapshot = commentPanel(false, false);

        CommentCloseCoordinator.Decision decision = CommentCloseCoordinator.decide(snapshot,
                new CommentCloseCoordinator.Attempts(true, true, true));

        assertEquals(CommentCloseCoordinator.Action.BLOCK, decision.action);
    }

    @Test
    public void twoEmptyWindowSamplesAreClassifiedAsObservationGap() {
        PageState first = emptyWindowPage();
        PageState second = emptyWindowPage();

        CommentCloseCoordinator.OriginStatus status =
                CommentCloseCoordinator.classifyOrigin(first, second,
                        true, false, false, false);

        assertEquals(CommentCloseCoordinator.OriginStatus.WINDOW_GAP, status);
    }

    @Test
    public void oneEmptySampleCannotAuthorizeFocusRecovery() {
        CommentCloseCoordinator.OriginStatus status =
                CommentCloseCoordinator.classifyOrigin(
                        emptyWindowPage(),
                        PageAnalyzer.analyze(recommendationFeed(), true),
                        true, false, false, false);

        assertEquals(CommentCloseCoordinator.OriginStatus.WAITING, status);
    }

    @Test
    public void postedCommentRequiresExactVisibleNonEditableRow() {
        List<UiNode> nodes = new ArrayList<>(commentPanel(false, false).nodes);
        nodes.add(node("changed_comment_text", "哈哈哈哈哈哈", "",
                "android.widget.TextView", 120, 1300, 920, 1420,
                false, "0/4/2/1", false, false));
        UiSnapshot snapshot = snapshot(nodes, Arrays.asList(
                window(7, PageAnalyzer.TIKTOK_PACKAGE,
                        UiWindow.TYPE_APPLICATION, 0, 0, 0, 1080, 2400)));

        assertTrue(CommentSurfaceContract.postedCommentVisible(
                snapshot, "哈哈哈哈哈哈"));
        assertFalse(CommentSurfaceContract.postedCommentVisible(
                snapshot, "另一条评论"));
    }

    @Test
    public void commentModalSuppressesVideoActionsAndVideoContext() {
        UiSnapshot snapshot = commentPanel(true, true);

        PageState page = PageAnalyzer.analyze(snapshot, true);
        VideoSurfaceContext.Result context = VideoSurfaceContext.analyze(snapshot);

        assertEquals(PageState.Page.COMMENTS, page.page);
        assertEquals(VideoSurfaceContext.Kind.UNKNOWN, context.kind);
        assertTrue(context.evidence.contains("context:comment_modal_precedence"));
    }

    @Test
    public void feedFirstCommentLabelIsNotEmptyPanelEvidence() {
        UiSnapshot snapshot = snapshot(Arrays.asList(
                node("", "推荐", "", "android.widget.TextView",
                        760, 100, 940, 220, false, "0/1", false, false),
                node("", "首页", "", "android.widget.TextView",
                        0, 2200, 220, 2350, false, "0/9", false, false),
                node("", "", "阅读或添加评论。抢首评 条评论", "android.widget.Button",
                        888, 1479, 1080, 1659, true, "0/3", false, false)),
                Arrays.asList(window(7, PageAnalyzer.TIKTOK_PACKAGE,
                        UiWindow.TYPE_APPLICATION, 0, 0, 0, 1080, 2400)));

        CommentSurfaceContract.Result comments = CommentSurfaceContract.analyze(snapshot);

        assertEquals(CommentSurfaceContract.State.CLOSED, comments.state);
        assertFalse(comments.evidence.contains("comments:empty_panel"));
    }

    private static UiSnapshot commentPanel(boolean withIme, boolean focusedComposer) {
        List<UiNode> nodes = new ArrayList<>();
        nodes.add(node("", "推荐", "", "android.widget.TextView",
                760, 100, 940, 220, false, "0/0/1", false, false));
        nodes.add(node("", "", "点赞视频。10 个赞", "android.widget.Button",
                888, 1299, 1080, 1479, true, "0/1/1", false, false));
        nodes.add(node("", "", "阅读或添加评论。抢首评 条评论", "android.widget.Button",
                888, 1479, 1080, 1659, true, "0/1/2", false, false));
        nodes.add(node("changed_header", "评论", "", "android.widget.TextView",
                465, 949, 615, 1082, false, "0/4/0", false, false));
        nodes.add(node("changed_close", "", "关闭", "android.widget.ImageView",
                984, 985, 1044, 1045, true, "0/4/1", false, false));
        int composerTop = withIme ? 1155 : 2195;
        int composerBottom = withIme ? 1317 : 2289;
        nodes.add(node("changed_composer", "添加评论...", "", "android.widget.EditText",
                216, composerTop, 1002, composerBottom, true, "0/4/3",
                focusedComposer, true));

        List<UiWindow> windows = new ArrayList<>();
        windows.add(window(7, PageAnalyzer.TIKTOK_PACKAGE, UiWindow.TYPE_APPLICATION,
                0, 0, 0, 1080, 2400));
        if (withIme) {
            windows.add(window(10, "com.vendor.keyboard", UiWindow.TYPE_INPUT_METHOD,
                    1, 0, 1500, 1080, 2400));
        }
        return snapshot(nodes, windows);
    }

    private static UiSnapshot recommendationFeed() {
        return snapshot(Arrays.asList(
                node("", "推荐", "", "android.widget.TextView",
                        760, 100, 940, 220, false, "0/1", false, false),
                node("", "首页", "", "android.widget.TextView",
                        0, 2200, 220, 2350, false, "0/9", false, false),
                node("", "", "点赞视频。10 个赞", "android.widget.Button",
                        888, 1299, 1080, 1479, true, "0/2", false, false),
                node("", "", "阅读或添加评论。2 条评论", "android.widget.Button",
                        888, 1479, 1080, 1659, true, "0/3", false, false)),
                Arrays.asList(window(7, PageAnalyzer.TIKTOK_PACKAGE,
                        UiWindow.TYPE_APPLICATION, 0, 0, 0, 1080, 2400)));
    }

    private static PageState emptyWindowPage() {
        return new PageState(PageState.Page.UNKNOWN, "none",
                new ArrayList<>(), new ArrayList<>(), 0d,
                Arrays.asList("windows:0", "decision:no_application_candidate",
                        "foreground:unknown", "classification:foreground_not_authorized"));
    }

    private static UiSnapshot snapshot(List<UiNode> nodes, List<UiWindow> windows) {
        return new UiSnapshot(1L, PageAnalyzer.TIKTOK_PACKAGE, 1080, 2400,
                nodes, windows, ForegroundDecision.legacy(
                PageAnalyzer.TIKTOK_PACKAGE, PageAnalyzer.TIKTOK_PACKAGE));
    }

    private static UiWindow window(int id, String packageName, int type, int layer,
                                   int left, int top, int right, int bottom) {
        return new UiWindow(id, packageName, type, layer,
                type == UiWindow.TYPE_APPLICATION, type == UiWindow.TYPE_APPLICATION,
                false, true, rect(left, top, right, bottom));
    }

    private static UiNode node(String id, String text, String description, String className,
                               int left, int top, int right, int bottom, boolean clickable,
                               String path, boolean focused, boolean editable) {
        return new UiNode(id, text, description, className, PageAnalyzer.TIKTOK_PACKAGE,
                false, clickable, true, rect(left, top, right, bottom), 7, path,
                editable, focused, editable, 0);
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
