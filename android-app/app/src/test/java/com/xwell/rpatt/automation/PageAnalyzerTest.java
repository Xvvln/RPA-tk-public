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

    /** Labels/structure from real adb dump tools/popup-hook/out/.../sheet (46.1.3 CN). */
    @Test
    public void detectsChineseVideoOptionsSheetFromDeviceDumpShape() {
        UiSnapshot snapshot = snapshot(
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/fq6", "", "底部工作表",
                        "android.widget.FrameLayout", 0, 1228, 1080, 2249),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/uzy", "举报", "",
                        "android.widget.TextView", 27, 1456, 213, 1498),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/uzy", "不感兴趣", "",
                        "android.widget.TextView", 225, 1456, 411, 1498),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/uzy", "下载", "",
                        "android.widget.TextView", 621, 1456, 807, 1498));

        assertTrue(PageAnalyzer.analyze(snapshot, true).interruptions.contains("video_options_sheet"));
    }

    /** From lab-20260725 sheet dump: 底部工作表 + 倍速 panel. */
    @Test
    public void detectsChineseSheetWithPlaybackSpeedLabel() {
        UiSnapshot snapshot = snapshot(
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/fq6", "", "底部工作表",
                        "android.widget.FrameLayout", 0, 1228, 1080, 2249),
                node("", "倍速", "", "android.widget.TextView", 100, 1400, 300, 1480),
                node("", "1.0×", "", "android.widget.TextView", 100, 1500, 300, 1580),
                node("", "不感兴趣", "", "android.widget.TextView", 100, 1600, 300, 1680));

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

    @Test
    public void relatedSearchSectionClassifiesAsSearchResults() {
        UiSnapshot snapshot = snapshot(
                node(PageAnalyzer.SEARCH_FIELD_ID_CURRENT, "claude", "",
                        "android.widget.EditText", 150, 127, 894, 235),
                node("", "其他人搜索的内容", "", "android.widget.TextView", 80, 900, 600, 980),
                node("", "gpt trend", "", "android.widget.TextView", 80, 1000, 400, 1080));

        assertEquals(PageState.Page.SEARCH_RESULTS, PageAnalyzer.analyze(snapshot, true).page);
    }

    @Test
    public void emptyCommentPanelSofaIsComments() {
        UiSnapshot snapshot = snapshot(
                node(PageAnalyzer.COMMENT_FIELD_ID_CURRENT, "添加评论...", "",
                        "android.widget.EditText", 216, 2195, 900, 2289, "0/4/3"),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/veb", "评论", "",
                        "android.widget.TextView", 333, 823, 639, 956, "0/4/0"),
                node("", "喜欢这个作品？快来抢沙发吧。", "", "android.widget.TextView",
                        200, 1200, 900, 1300, "0/4/1"),
                node("", "抢首评", "", "android.widget.TextView", 400, 1400, 700, 1480, "0/4/2"));

        PageState state = PageAnalyzer.analyze(snapshot, true);
        assertEquals(PageState.Page.COMMENTS, state.page);
        assertTrue(state.evidence.toString().contains("comments:empty_panel")
                || state.evidence.toString().contains("comments:header"));
    }

    @Test
    public void emptyCommentPanelDetectedEvenWithHeaderFilteringSystemText() {
        // Empty-state copy sits between header and field and is stripped from systemText();
        // hasVisibleText must still see 抢沙发.
        UiSnapshot snapshot = snapshot(
                node(PageAnalyzer.COMMENT_FIELD_ID_CURRENT, "添加评论...", "",
                        "android.widget.EditText", 216, 2195, 900, 2289, "0/4/3"),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/veb", "0 条评论", "",
                        "android.widget.TextView", 333, 900, 639, 1000, "0/4/0"),
                node("", "喜欢这个作品？快来抢沙发吧。", "", "android.widget.TextView",
                        200, 1300, 900, 1400, "0/4/1"),
                node(PageAnalyzer.TIKTOK_PACKAGE + ":id/bna", "", "关闭",
                        "android.view.View", 984, 920, 1044, 980, "0/4/2"));

        PageState state = PageAnalyzer.analyze(snapshot, true);
        assertEquals(PageState.Page.COMMENTS, state.page);
    }

    @Test
    public void partialCommentComposerIsRecoverableWithoutFullCommentsPage() {
        // After post, list/header may be missing while lower "添加评论..." remains.
        UiSnapshot snapshot = snapshot(
                node(PageAnalyzer.COMMENT_FIELD_ID_CURRENT, "添加评论...", "",
                        "android.widget.EditText", 216, 2195, 558, 2289, "0/4/3"));

        PageState state = PageAnalyzer.analyze(snapshot, true);
        assertEquals(PageState.Page.UNKNOWN, state.page);
        assertTrue(state.interruptions.contains("comments_open"));
        assertTrue(state.evidence.toString().contains("comments:partial_composer_recoverable"));
        assertEquals("comments_open",
                InterruptPolicy.primaryRecoverableCode(state, PageState.Page.FEED));
    }

    @Test
    public void firstTimeRepostGuideIsRecoverableInterruption() {
        UiSnapshot snapshot = snapshot(
                node("", "通过转发将该作品推荐给其他人", "", "android.widget.TextView",
                        80, 1200, 1000, 1320),
                node("", "转发会在主页上显示", "", "android.widget.TextView",
                        80, 1400, 1000, 1480),
                node("", "好的", "", "android.widget.Button", 120, 1900, 960, 2050));

        PageState state = PageAnalyzer.analyze(snapshot, true);
        assertTrue(state.interruptions.contains("first_time_repost_guide"));
        assertEquals("first_time_repost_guide",
                InterruptPolicy.primaryRecoverableCode(state, PageState.Page.FEED));
        assertEquals("dismiss_repost_guide",
                InterruptPolicy.recoveryActionFor("first_time_repost_guide"));
    }

    @Test
    public void systemPermissionWithDenyIsInterruptionNotHardBlocker() {
        UiSnapshot snapshot = snapshot(
                nodeWithPackage("com.android.permissioncontroller", "",
                        "允许“TikTok”访问通讯录?", "", "android.widget.TextView",
                        150, 900, 930, 1040),
                nodeWithPackage("com.android.permissioncontroller", "",
                        "允许", "", "android.widget.Button", 222, 1288, 858, 1420),
                nodeWithPackage("com.android.permissioncontroller", "",
                        "拒绝", "", "android.widget.Button", 222, 1444, 858, 1576));

        PageState state = PageAnalyzer.analyze(snapshot, true);
        assertTrue(state.interruptions.contains("system_permission_prompt"));
        assertFalse(state.blockers.contains("permission_required"));
    }

    @Test
    public void systemPermissionWithoutDenyRemainsHardBlocker() {
        UiSnapshot snapshot = snapshot(
                nodeWithPackage("com.android.permissioncontroller", "",
                        "需要权限", "", "android.widget.TextView",
                        150, 900, 930, 1040));

        PageState state = PageAnalyzer.analyze(snapshot, true);
        assertTrue(state.blockers.contains("permission_required"));
        assertFalse(state.interruptions.contains("system_permission_prompt"));
    }

    @Test
    public void inFeedLiveSurfaceWithHomeChromeIsFeedNotUnknown() {
        // Live tiles keep 推荐/首页 but often lack "点赞视频" a11y labels → videoActionCount=0.
        UiSnapshot snapshot = snapshot(
                node("", "推荐", "", "android.widget.TextView", 840, 100, 1000, 180, "0/1/0"),
                node("", "首页", "", "android.widget.TextView", 40, 2240, 200, 2360, "0/9/0"),
                node("", "直播中", "", "android.widget.TextView", 80, 400, 280, 460, "0/2/0"),
                node("", "点击进入直播间", "", "android.widget.TextView", 80, 480, 500, 540, "0/2/1"),
                node("", "user4420495187914", "", "android.widget.Button", 900, 1500, 1060, 1580, "0/3/0"),
                node("", "104.3万", "", "android.widget.Button", 900, 1600, 1060, 1680, "0/3/1"));

        PageState state = PageAnalyzer.analyze(snapshot, true);
        assertEquals(PageState.Page.FEED, state.page);
        assertEquals("live", state.contentKind);
        assertTrue(state.evidence.toString().contains("feed:live_surface"));
        assertTrue(PageAnalyzer.isLiveFeedSnapshot(snapshot));
    }

    @Test
    public void liveMarkersDetectedEvenWithoutFeedClassificationChrome() {
        UiSnapshot snapshot = snapshot(
                node("", "点击进入直播间", "", "android.widget.TextView", 80, 480, 500, 540),
                node("", "直播中", "", "android.widget.TextView", 80, 400, 280, 460),
                node("", "腻腻ni", "", "android.widget.Button", 900, 1500, 1060, 1580));
        assertTrue(PageAnalyzer.isLiveFeedSnapshot(snapshot));
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
        return nodeWithPackage(PageAnalyzer.TIKTOK_PACKAGE, id, text, description, className,
                left, top, right, bottom, treePath);
    }

    private static UiNode nodeWithPackage(String packageName, String id, String text,
                                          String description, String className,
                                          int left, int top, int right, int bottom) {
        return nodeWithPackage(packageName, id, text, description, className,
                left, top, right, bottom, "");
    }

    private static UiNode nodeWithPackage(String packageName, String id, String text,
                                          String description, String className,
                                          int left, int top, int right, int bottom,
                                          String treePath) {
        Rect bounds = new Rect();
        bounds.left = left;
        bounds.top = top;
        bounds.right = right;
        bounds.bottom = bottom;
        return new UiNode(id, text, description, className, packageName,
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
