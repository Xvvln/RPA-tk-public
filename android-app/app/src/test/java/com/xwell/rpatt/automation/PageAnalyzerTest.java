package com.xwell.rpatt.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;

public final class PageAnalyzerTest {
    @Test
    public void recognizesCurrentSearchInputId() {
        UiSnapshot snapshot = snapshot(
                node(PageAnalyzer.SEARCH_FIELD_ID_CURRENT, "GPT", "", "android.widget.EditText", 150, 127, 894, 235, "0/2/0"),
                node(PageAnalyzer.SEARCH_SUBMIT_ID, "搜索", "", "android.widget.TextView", 912, 106, 1080, 274, "0/3/0"));

        assertEquals(PageState.Page.SEARCH_INPUT, PageAnalyzer.analyze(snapshot, true).page);
    }

    @Test
    public void recognizesLegacySearchInputId() {
        UiSnapshot snapshot = snapshot(
                node(PageAnalyzer.SEARCH_FIELD_ID, "GPT", "", "android.widget.EditText", 150, 127, 894, 235, "0/2/0"),
                node(PageAnalyzer.SEARCH_SUBMIT_ID, "搜索", "", "android.widget.TextView", 912, 106, 1080, 274, "0/3/0"));

        assertEquals(PageState.Page.SEARCH_INPUT, PageAnalyzer.analyze(snapshot, true).page);
    }

    @Test
    public void recognizesCurrentCommentsPanelAndCommentTextId() {
        UiSnapshot snapshot = snapshot(
                node(PageAnalyzer.COMMENT_FIELD_ID_CURRENT, "添加评论...", "", "android.widget.EditText", 216, 2195, 558, 2289, "0/4/3"),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/veb", "1128 条评论", "", "android.widget.TextView", 333, 823, 639, 956, "0/4/0"),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/euj", "Should I watch it?", "", "android.widget.TextView", 192, 1034, 1044, 1103, "0/4/2/0"),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/bna", "", "关闭", "android.view.View", 984, 859, 1044, 919, "0/4/1"));

        assertEquals(PageState.Page.COMMENTS, PageAnalyzer.analyze(snapshot, true).page);
    }

    @Test
    public void recognizesCurrentUnstableNetworkBanner() {
        UiSnapshot snapshot = snapshot(
                node(PageAnalyzer.COMMENT_FIELD_ID_CURRENT, "添加评论...", "", "android.widget.EditText", 216, 2195, 558, 2289, "0/4/3"),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/veb", "254 条评论", "", "android.widget.TextView", 333, 823, 639, 956, "0/4/0"),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/euj", "评论", "", "android.widget.TextView", 192, 1034, 1044, 1103, "0/4/2/0"),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/message_tv", "网络不稳定，请重试。", "", "android.widget.TextView", 330, 1599, 750, 1653, "0/4/2/1"),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/bna", "", "关闭", "android.view.View", 984, 859, 1044, 919, "0/4/1"));

        assertTrue(PageAnalyzer.analyze(snapshot, true).blockers.contains("network_error"));
    }

    @Test
    public void recognizesSearchVideoPlayerWithoutFeedTitleNode() {
        UiSnapshot snapshot = snapshot(
                node(PageAnalyzer.SEARCH_FIELD_ID_CURRENT, "AI", "",
                        "android.widget.EditText", 150, 127, 894, 235),
                node(PageAnalyzer.LIKE_BUTTON_ID_CURRENT, "", "点赞视频。208 个赞",
                        "android.widget.Button", 888, 1299, 1080, 1479),
                node(PageAnalyzer.COMMENT_BUTTON_ID_CURRENT, "", "阅读或添加评论。69 条评论",
                        "android.widget.Button", 888, 1479, 1080, 1659));

        assertEquals(PageState.Page.FEED, PageAnalyzer.analyze(snapshot, true).page);
    }

    @Test
    public void commentTextCannotImpersonateNetworkBanner() {
        UiSnapshot snapshot = snapshot(
                node(PageAnalyzer.COMMENT_FIELD_ID_CURRENT, "添加评论...", "", "android.widget.EditText", 216, 2195, 558, 2289, "0/4/3"),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/veb", "254 条评论", "", "android.widget.TextView", 333, 823, 639, 956, "0/4/0"),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/euj", "我的网络不稳定", "", "android.widget.TextView", 192, 1034, 1044, 1103, "0/4/2/0"),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/bna", "", "关闭", "android.view.View", 984, 859, 1044, 919, "0/4/1"));

        assertFalse(PageAnalyzer.analyze(snapshot, true).blockers.contains("network_error"));
    }

    @Test
    public void commentInputAloneIsUnknown() {
        UiSnapshot snapshot = snapshot(
                node(PageAnalyzer.COMMENT_FIELD_ID_CURRENT, "添加评论...", "",
                        "android.widget.EditText", 216, 2195, 558, 2289, "0/4/3"));

        assertEquals(PageState.Page.UNKNOWN, PageAnalyzer.analyze(snapshot, true).page);
    }

    @Test
    public void commentAnchorsInDifferentContainersAreUnknown() {
        UiSnapshot snapshot = snapshot(
                node(PageAnalyzer.COMMENT_FIELD_ID_CURRENT, "添加评论...", "", "android.widget.EditText", 216, 2195, 558, 2289, "0/4/3"),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/veb", "1128 条评论", "", "android.widget.TextView", 333, 823, 639, 956, "0/5/0"),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/euj", "Comment", "", "android.widget.TextView", 192, 1034, 1044, 1103, "0/6/0"));

        assertEquals(PageState.Page.UNKNOWN, PageAnalyzer.analyze(snapshot, true).page);
    }

    @Test
    public void searchResultsRejectCommentContract() {
        UiSnapshot snapshot = snapshot(
                node(PageAnalyzer.SEARCH_FIELD_ID_CURRENT, "GPT", "", "android.widget.EditText", 150, 127, 894, 235, "0/2/0"),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/mui", "", "", "android.view.ViewGroup", 0, 280, 1080, 2300, "0/2/1"),
                node(PageAnalyzer.COMMENT_FIELD_ID_CURRENT, "添加评论...", "", "android.widget.EditText", 216, 2195, 558, 2289, "0/4/3"),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/veb", "1128 条评论", "", "android.widget.TextView", 333, 823, 639, 956, "0/4/0"),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/euj", "Comment", "", "android.widget.TextView", 192, 1034, 1044, 1103, "0/4/2"));

        assertEquals(PageState.Page.SEARCH_RESULTS, PageAnalyzer.analyze(snapshot, true).page);
    }

    @Test
    public void resolvesRenamedCommentSendButtonFromSiblingContract() {
        UiNode input = node(PageAnalyzer.COMMENT_FIELD_ID_CURRENT, "哈哈哈哈哈", "",
                "android.widget.EditText", 216, 2195, 859, 2289, "0/4/2/3");
        UiNode send = node(PageAnalyzer.TIKTOK_PACKAGE + ":id/edq", "", "@2131823239",
                "android.widget.Button", 871, 2215, 1027, 2311, "0/4/2/4");
        UiNode unrelated = node(PageAnalyzer.TIKTOK_PACKAGE + ":id/edg", "回复", "",
                "android.widget.Button", 413, 2120, 491, 2168, "0/4/1/8");
        UiSnapshot snapshot = snapshot(input, unrelated, send);

        assertSame(send, snapshot.visibleCommentSendNear(input));
    }

    @Test
    public void resolvesPartiallyVisibleBottomSheetSendButton() {
        UiNode input = node(PageAnalyzer.COMMENT_FIELD_ID_CURRENT, "哈哈哈哈哈", "",
                "android.widget.EditText", 216, 2195, 859, 2289, "0/4/2/3");
        UiNode send = node(PageAnalyzer.TIKTOK_PACKAGE + ":id/edq", "", "@2131823239",
                "android.widget.Button", 871, 2215, 1027, 2311, "0/4/2/4");
        UiSnapshot snapshot = new UiSnapshot(1, PageAnalyzer.TIKTOK_PACKAGE,
                1080, 2249, Arrays.asList(input, send));

        assertSame(send, snapshot.visibleCommentSendNear(input));
    }

    @Test
    public void inputIdentitySurvivesKeyboardReflowAndIgnoresVisibleBlankClone() {
        UiNode original = nodeWithState(PageAnalyzer.COMMENT_FIELD_ID_CURRENT, "添加评论...",
                "android.widget.EditText", true, 216, 2195, 859, 2289, "0/4/2/3");
        UiNode committedOriginal = nodeWithState(PageAnalyzer.COMMENT_FIELD_ID_CURRENT,
                "哈哈哈哈哈", "android.widget.EditText", false,
                216, 2195, 859, 2289, "0/4/2/3");
        UiNode visibleClone = nodeWithState(PageAnalyzer.COMMENT_FIELD_ID_CURRENT, "添加评论...",
                "android.widget.EditText", true, 216, 1155, 1002, 1317, "0/8/2/3");
        UiSnapshot reflowed = new UiSnapshot(1, PageAnalyzer.TIKTOK_PACKAGE,
                1080, 2249, Arrays.asList(committedOriginal, visibleClone));

        assertSame(committedOriginal, reflowed.inputByIdentity(original));
        assertTrue(reflowed.hasExactInputText("哈哈哈哈哈"));
    }

    @Test
    public void resolvesClippedSendSiblingEvenWhenAccessibilityMarksItNotVisible() {
        UiNode input = nodeWithState(PageAnalyzer.COMMENT_FIELD_ID_CURRENT, "哈哈哈哈哈",
                "android.widget.EditText", true, 216, 2195, 859, 2289, "0/4/2/3");
        UiNode clippedSend = nodeWithState(PageAnalyzer.TIKTOK_PACKAGE + ":id/changed_send", "",
                "android.widget.Button", false, 871, 2215, 1027, 2311, "0/4/2/4");
        UiSnapshot snapshot = new UiSnapshot(1, PageAnalyzer.TIKTOK_PACKAGE,
                1080, 2249, Arrays.asList(input, clippedSend));

        assertSame(clippedSend, snapshot.visibleCommentSendNear(input));
    }

    @Test
    public void rejectsFullyOffscreenSendSibling() {
        UiNode input = nodeWithState(PageAnalyzer.COMMENT_FIELD_ID_CURRENT, "哈哈哈哈哈",
                "android.widget.EditText", true, 216, 2195, 859, 2289, "0/4/2/3");
        UiNode offscreen = nodeWithState(PageAnalyzer.TIKTOK_PACKAGE + ":id/changed_send", "",
                "android.widget.Button", false, 871, 2300, 1027, 2396, "0/4/2/4");
        UiSnapshot snapshot = new UiSnapshot(1, PageAnalyzer.TIKTOK_PACKAGE,
                1080, 2249, Arrays.asList(input, offscreen));

        assertNull(snapshot.visibleCommentSendNear(input));
    }

    @Test
    public void detectsGenericDismissPopupFromApkCtaFamily() {
        UiSnapshot snapshot = snapshot(
                node("", "发现新功能", "", "android.widget.TextView", 100, 800, 980, 900),
                node("", "Maybe later", "", "android.widget.Button", 200, 1100, 500, 1200));

        assertTrue(PageAnalyzer.analyze(snapshot, true).interruptions.contains("generic_dismiss_popup"));
    }

    @Test
    public void detectsNotificationPromptAndGotItPopup() {
        UiSnapshot notify = snapshot(
                node("", "开启通知，不错过互动", "", "android.widget.TextView", 100, 800, 980, 900),
                node("", "以后再说", "", "android.widget.Button", 200, 1100, 500, 1200));
        UiSnapshot gotIt = snapshot(
                node("", "功能已更新", "", "android.widget.TextView", 100, 800, 980, 900),
                node("", "我知道了", "", "android.widget.Button", 200, 1100, 500, 1200));

        assertTrue(PageAnalyzer.analyze(notify, true).interruptions.contains("notification_prompt"));
        assertTrue(PageAnalyzer.analyze(gotIt, true).interruptions.contains("got_it_popup"));
    }

    @Test
    public void detectsVideoOptionsSheetAsRecoverableInterruption() {
        UiSnapshot snapshot = snapshot(
                node("", "", "Bottom sheet", "android.widget.FrameLayout", 0, 1200, 1080, 2400),
                node("", "Download", "", "android.widget.TextView", 80, 1400, 400, 1480),
                node("", "Report", "", "android.widget.TextView", 80, 1500, 400, 1580));

        assertTrue(PageAnalyzer.analyze(snapshot, true).interruptions.contains("video_options_sheet"));
    }

    @Test
    public void captchaRemainsHardBlockerNotAutoRecoverable() {
        UiSnapshot snapshot = snapshot(
                node("", "请完成验证", "", "android.widget.TextView", 100, 800, 980, 900),
                node("", "拖动滑块", "", "android.widget.TextView", 100, 950, 980, 1050));

        PageState state = PageAnalyzer.analyze(snapshot, true);
        assertTrue(state.blockers.contains("verification_required"));
        assertFalse(state.interruptions.contains("generic_dismiss_popup"));
    }

    private static UiSnapshot snapshot(UiNode... nodes) {
        return new UiSnapshot(1, PageAnalyzer.TIKTOK_PACKAGE, 1080, 2400, Arrays.asList(nodes));
    }

    private static UiNode node(String id, String text, String description, String className,
                               int left, int top, int right, int bottom) {
        return node(id, text, description, className, left, top, right, bottom, "");
    }

    private static UiNode node(String id, String text, String description, String className,
                               int left, int top, int right, int bottom, String treePath) {
        Rect bounds = new Rect();
        bounds.left = left;
        bounds.top = top;
        bounds.right = right;
        bounds.bottom = bottom;
        return new UiNode(id, text, description, className, PageAnalyzer.TIKTOK_PACKAGE,
                false, true, true, bounds, 7, treePath);
    }

    private static UiNode nodeWithState(String id, String text, String className,
                                        boolean visible, int left, int top, int right, int bottom,
                                        String treePath) {
        Rect bounds = new Rect();
        bounds.left = left;
        bounds.top = top;
        bounds.right = right;
        bounds.bottom = bottom;
        return new UiNode(id, text, "", className, PageAnalyzer.TIKTOK_PACKAGE,
                false, true, visible, bounds, 7, treePath);
    }
}
