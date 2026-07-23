package com.xwell.rpatt.automation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PageAnalyzer {
    public static final String TIKTOK_PACKAGE = "com.zhiliaoapp.musically";
    public static final String SEARCH_FIELD_ID = TIKTOK_PACKAGE + ":id/hdj";
    public static final String SEARCH_FIELD_ID_CURRENT = TIKTOK_PACKAGE + ":id/heb";
    public static final String[] SEARCH_FIELD_IDS = {SEARCH_FIELD_ID_CURRENT, SEARCH_FIELD_ID};
    public static final String SEARCH_SUBMIT_ID = TIKTOK_PACKAGE + ":id/tv_search_textview";
    // Search cards have changed across recent TikTok builds. Keep geometry validation in the
    // gateway as well, because these short resource names may also occur in retained off-screen UI.
    public static final String[] SEARCH_RESULT_CARD_IDS = {
            TIKTOK_PACKAGE + ":id/ueu",
            TIKTOK_PACKAGE + ":id/uhi",
            TIKTOK_PACKAGE + ":id/ug2"
    };
    public static final String COMMENT_FIELD_ID = TIKTOK_PACKAGE + ":id/eal";
    public static final String COMMENT_FIELD_ID_CURRENT = TIKTOK_PACKAGE + ":id/eb3";
    public static final String[] COMMENT_FIELD_IDS = {COMMENT_FIELD_ID_CURRENT, COMMENT_FIELD_ID};
    public static final String COMMENT_BUTTON_ID = TIKTOK_PACKAGE + ":id/ef2";
    public static final String COMMENT_BUTTON_ID_CURRENT = TIKTOK_PACKAGE + ":id/efj";
    public static final String[] COMMENT_BUTTON_IDS = {COMMENT_BUTTON_ID_CURRENT, COMMENT_BUTTON_ID};
    public static final String LIKE_BUTTON_ID = TIKTOK_PACKAGE + ":id/fud";
    public static final String LIKE_BUTTON_ID_CURRENT = TIKTOK_PACKAGE + ":id/fux";
    public static final String FAVORITE_BUTTON_ID = TIKTOK_PACKAGE + ":id/hqz";
    public static final String FAVORITE_BUTTON_ID_CURRENT = TIKTOK_PACKAGE + ":id/hrt";

    private PageAnalyzer() {}

    public static PageState analyze(UiSnapshot snapshot, boolean networkAvailable) {
        Set<String> blockers = new LinkedHashSet<>();
        Set<String> interruptions = new LinkedHashSet<>();
        ForegroundDecision foreground = snapshot.foregroundDecision;
        boolean targetForeground = foreground.confirmsTarget(TIKTOK_PACKAGE);
        if (foreground.confirmsOtherApp(TIKTOK_PACKAGE)) blockers.add("tiktok_not_foreground");
        else if (!targetForeground) interruptions.add("foreground_uncertain");
        if (!networkAvailable) blockers.add("network_unavailable");

        String systemText = systemText(snapshot);
        addWhen(systemText, blockers, "verification_required", "请完成验证", "验证以继续", "verify to continue", "captcha", "拖动滑块");
        addWhen(systemText, blockers, "login_required", "登录以继续", "注册或登录", "log in to continue", "sign in to continue", "log in or sign up");
        addWhen(systemText, blockers, "network_error", "无网络连接", "网络连接不稳定", "网络不稳定",
                "网络异常", "加载失败", "点击重试", "no internet connection", "network error",
                "unstable network", "couldn't load", "tap to retry");
        addWhen(systemText, blockers, "rate_limited", "操作过于频繁", "操作太快", "请稍后再试", "too many attempts", "tapping too fast", "try again later");
        addWhen(systemText, blockers, "account_restricted", "账号已被封禁", "账号已被停用", "账号受到限制", "account suspended", "account banned", "account has been restricted");
        addWhen(systemText, blockers, "content_unavailable", "视频不可用", "内容不可用", "视频已被删除", "video unavailable", "content unavailable", "video has been removed");
        addWhen(systemText, blockers, "update_required", "请更新 tiktok", "需要更新", "update tiktok", "update required");
        addWhen(systemText, blockers, "age_gate", "确认你的年龄", "请输入你的生日", "confirm your age", "enter your birthday");
        addWhen(systemText, blockers, "privacy_consent_required", "欧洲经济区用户数据", "数据传输至中国", "eea user data");

        boolean inputMethodDialog = snapshot.hasId("android:id/select_dialog_listview")
                || contains(systemText, "选择输入法", "选择键盘", "choose input method", "select input method");
        if (inputMethodDialog) interruptions.add("system_input_method_dialog");
        boolean unknownSystemDialog = false;
        for (UiNode node : snapshot.nodes) {
            if (node.packageName.contains("permissioncontroller")) blockers.add("permission_required");
            if ("android".equals(node.packageName) && "android:id/parentPanel".equals(node.id)) unknownSystemDialog = true;
        }
        if (unknownSystemDialog && !inputMethodDialog) blockers.add("unknown_system_dialog");

        // Soft TikTok promos / soft-asks are recoverable. Markers come from runtime UI text
        // plus static strings mined from installed TikTok 46.1.3 resources (see docs/POPUP_CATALOG.md).
        // Server-driven copy is not fully enumerable; unknown high-risk dialogs stay blockers.
        addWhen(systemText, interruptions, "collections_popup", "共享收藏", "与好友创建收藏",
                "shared collections", "collections with a friend");
        addWhen(systemText, interruptions, "follow_friends_popup", "关注你的朋友", "查找联系人",
                "follow your friends", "find your contacts", "find friends");
        addWhen(systemText, interruptions, "contacts_sync_prompt", "同步手机通讯录好友", "同步通讯录好友",
                "访问你的通讯录", "sync your phone contacts", "sync your contacts", "access your contacts");
        addWhen(systemText, interruptions, "link_email_popup", "关联邮箱", "连接邮箱", "添加邮箱",
                "link email", "linking your android email");
        addWhen(systemText, interruptions, "notification_prompt", "开启通知", "打开通知", "打开推送通知",
                "允许通知", "turn on notifications", "enable notifications", "turn on notification");
        addWhen(systemText, interruptions, "promo_banner", "连续互动宠物", "孵化连续互动",
                "hatch a streak pet", "新功能");
        addWhen(systemText, interruptions, "notification_banner", "给你发来了新消息", "向你发送了消息",
                "sent you new messages", "sent you a message");
        addWhen(systemText, interruptions, "suggestion_page", "上滑跳过", "swipe up to skip");
        addWhen(systemText, interruptions, "interest_survey", "选择你感兴趣", "choose your interests",
                "内容偏好", "content preferences");
        addWhen(systemText, interruptions, "got_it_popup", "我知道了", "知道了", "got it");
        // Prefer specific popup codes above; generic is a fallback soft-ask CTA family from APK.
        addWhen(systemText, interruptions, "generic_dismiss_popup",
                "以后再说", "下次再说", "稍后再说", "暂不", "先不了", "不用了",
                "not now", "maybe later", "no thanks", "no, thanks", "dismiss");

        boolean videoOptionsSheet = false;
        for (UiNode node : snapshot.nodes) {
            if (!snapshot.isVisible(node)) continue;
            String desc = node.description == null ? "" : node.description.trim().toLowerCase(Locale.ROOT);
            if (("bottom sheet".equals(desc) || "底部工作表".equals(desc))
                    && contains(systemText, "下载", "举报", "播放速度", "不感兴趣",
                    "download", "report", "speed", "not interested")) {
                videoOptionsSheet = true;
                break;
            }
        }
        if (videoOptionsSheet) interruptions.add("video_options_sheet");

        ControlResolver.Match commentFieldMatch = ControlResolver.resolve(
                snapshot, ControlResolver.Role.COMMENT_FIELD);
        UiNode commentField = commentFieldMatch == null ? null : commentFieldMatch.node;
        UiNode commentHeader = commentHeader(snapshot);
        UiNode commentList = commentList(snapshot, commentHeader, commentField);
        boolean commentClose = ControlResolver.resolve(
                snapshot, ControlResolver.Role.COMMENT_CLOSE) != null;
        boolean lowerCommentField = commentField != null && snapshot.displayHeight > 0
                && commentField.bounds.top + (commentField.bounds.bottom - commentField.bounds.top) / 2
                >= snapshot.displayHeight * 0.55f;
        boolean sameCommentPanel = sharesPanelAncestor(commentField, commentList, commentHeader);

        ControlResolver.Match searchFieldMatch = ControlResolver.resolve(
                snapshot, ControlResolver.Role.SEARCH_FIELD);
        UiNode searchFieldNode = searchFieldMatch == null ? null : searchFieldMatch.node;
        boolean searchField = searchFieldNode != null;
        boolean searchSubmit = ControlResolver.resolve(snapshot,
                ControlResolver.Role.SEARCH_SUBMIT, searchFieldNode) != null;
        boolean searchResultAnchor = hasSearchResultContract(snapshot, searchFieldNode);
        boolean searchEmpty = contains(systemText, "未找到相关结果", "没有找到相关结果", "no results found", "couldn't find any results");
        int videoActionCount = videoActionCount(snapshot);

        List<Candidate> candidates = new ArrayList<>();
        boolean fullCommentContract = commentHeader != null && commentList != null
                && lowerCommentField && sameCommentPanel && !(searchField && searchResultAnchor);
        if (fullCommentContract) {
            candidates.add(candidate(PageState.Page.COMMENTS, commentClose ? 0.98d : 0.94d,
                    "comments:header", "comments:list", "comments:lower_input",
                    "comments:shared_panel", commentClose ? "comments:close" : "comments:no_close"));
        }
        if (searchField && searchEmpty && videoActionCount < 2 && !fullCommentContract) {
            candidates.add(candidate(PageState.Page.SEARCH_EMPTY, 0.94d,
                    "search:field", "search:empty_message"));
        }
        if (searchField && searchSubmit && videoActionCount < 2 && !fullCommentContract) {
            candidates.add(candidate(PageState.Page.SEARCH_INPUT, 0.91d,
                    "search:field", "search:submit"));
        }
        if (searchField && searchResultAnchor && videoActionCount < 2 && !fullCommentContract) {
            candidates.add(candidate(PageState.Page.SEARCH_RESULTS, 0.90d,
                    "search:field", "search:results_anchor"));
        }
        // A video opened from search may retain a top search field. Two independent visible
        // actions are required, and a live results grid is strong counter-evidence.
        if (videoActionCount >= 2 && !searchResultAnchor && !fullCommentContract) {
            candidates.add(candidate(PageState.Page.FEED, videoActionCount >= 3 ? 0.92d : 0.86d,
                    "feed:video_actions=" + videoActionCount));
        }
        if (selected(snapshot, "收件箱", "inbox")) {
            candidates.add(candidate(PageState.Page.INBOX, 0.78d, "nav:inbox_selected"));
        }
        if (selected(snapshot, "商城", "shop")) {
            candidates.add(candidate(PageState.Page.SHOP, 0.78d, "nav:shop_selected"));
        }
        if (contains(systemText, "设置与隐私", "settings and privacy")) {
            candidates.add(candidate(PageState.Page.SETTINGS, 0.80d, "settings:title"));
        }
        if (contains(systemText, "主页菜单", "profile menu")) {
            candidates.add(candidate(PageState.Page.PROFILE, 0.76d, "profile:menu"));
        }

        Candidate selected = select(candidates);
        PageState.Page page = selected == null ? PageState.Page.UNKNOWN : selected.page;
        double confidence = selected == null ? 0d : selected.score;
        List<String> evidence = selected == null
                ? unresolvedEvidence(commentField, commentList, commentHeader, lowerCommentField,
                sameCommentPanel, searchField, searchSubmit, searchResultAnchor, videoActionCount)
                : new ArrayList<>(selected.evidence);
        evidence.add("foreground:" + foreground.source.name().toLowerCase(Locale.ROOT));
        evidence.add(String.format(Locale.ROOT, "foreground_confidence:%.2f", foreground.confidence));
        evidence.addAll(foreground.evidence);
        // A recognizable stale TikTok tree is not sufficient authorization for a write. If the
        // window arbiter cannot identify the underlying app, preserve the evidence but classify
        // the page as unknown until a later stable sample resolves the window state.
        if (!targetForeground && !foreground.confirmsOtherApp(TIKTOK_PACKAGE)) {
            page = PageState.Page.UNKNOWN;
            confidence = 0d;
            evidence.add("classification:foreground_not_authorized");
        }

        if (page == PageState.Page.COMMENTS) interruptions.add("comments_open");
        if (page == PageState.Page.INBOX) interruptions.add("inbox_page");
        String contentKind = "none";
        if (page == PageState.Page.FEED) {
            if (contains(systemText, "照片", "photo")) contentKind = "photo";
            else if (contains(systemText, "直播中", "live now")) contentKind = "live";
            else if (contains(systemText, "赞助内容", "sponsored")) contentKind = "advertisement";
            else contentKind = "video";
        }
        return new PageState(page, contentKind, new ArrayList<>(blockers),
                new ArrayList<>(interruptions), confidence, evidence);
    }

    private static String systemText(UiSnapshot snapshot) {
        StringBuilder value = new StringBuilder();
        ControlResolver.Match field = ControlResolver.resolve(
                snapshot, ControlResolver.Role.COMMENT_FIELD);
        UiNode header = commentHeader(snapshot);
        for (UiNode node : snapshot.nodes) {
            if (!snapshot.isVisible(node)) continue;
            // User comments and descriptions must not trigger system blocker markers.
            boolean insideCommentBody = field != null && header != null
                    && node.windowId == field.node.windowId
                    && centerY(node) > header.bounds.bottom
                    && centerY(node) < field.node.bounds.top
                    && !node.id.endsWith(":id/message_tv");
            if (node.id.endsWith(":id/desc") || insideCommentBody) continue;
            value.append(node.searchableText()).append(' ');
        }
        return value.toString().toLowerCase(Locale.ROOT);
    }

    private static void addWhen(String text, Set<String> target, String code, String... markers) {
        if (contains(text, markers)) target.add(code);
    }

    private static boolean contains(String text, String... markers) {
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String marker : markers) if (normalized.contains(marker.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static boolean selected(UiSnapshot snapshot, String... markers) {
        for (UiNode node : snapshot.nodes) {
            if (snapshot.isVisible(node) && node.selected && contains(node.searchableText(), markers)) return true;
        }
        return false;
    }

    private static boolean hasDescription(UiSnapshot snapshot, String... labels) {
        for (UiNode node : snapshot.nodes) {
            if (!snapshot.isVisible(node)) continue;
            for (String label : labels) if (contains(node.description, label)) return true;
        }
        return false;
    }

    private static boolean hasVisibleId(UiSnapshot snapshot, String... ids) {
        for (UiNode node : snapshot.nodes) {
            if (!snapshot.isVisible(node)) continue;
            for (String id : ids) if (id.equals(node.id)) return true;
        }
        return false;
    }

    private static boolean hasVisibleSuffix(UiSnapshot snapshot, String... suffixes) {
        for (UiNode node : snapshot.nodes) {
            if (!snapshot.isVisible(node)) continue;
            for (String suffix : suffixes) if (node.id.endsWith(suffix)) return true;
        }
        return false;
    }

    private static UiNode visibleNodeBySuffix(UiSnapshot snapshot, String... suffixes) {
        for (UiNode node : snapshot.nodes) {
            if (!snapshot.isVisible(node)) continue;
            for (String suffix : suffixes) if (node.id.endsWith(suffix)) return node;
        }
        return null;
    }

    private static UiNode commentHeader(UiSnapshot snapshot) {
        for (UiNode node : snapshot.nodes) {
            if (!snapshot.isVisible(node)) continue;
            int centerY = centerY(node);
            boolean headerRegion = centerY > snapshot.displayHeight * 0.25f
                    && centerY < snapshot.displayHeight * 0.48f;
            boolean semantic = contains(node.searchableText(), "评论", "comment");
            boolean knownId = node.id.endsWith(":id/vcu") || node.id.endsWith(":id/veb");
            if (headerRegion && semantic && (knownId || node.className.contains("Text"))) return node;
        }
        return null;
    }

    private static UiNode commentList(UiSnapshot snapshot, UiNode header, UiNode field) {
        if (header == null || field == null || header.windowId != field.windowId) return null;
        for (UiNode node : snapshot.nodes) {
            if (!snapshot.isVisible(node) || node.windowId != field.windowId || node.text.trim().length() < 2
                    || centerY(node) <= header.bounds.bottom || centerY(node) >= field.bounds.top) continue;
            boolean knownId = node.id.endsWith(":id/eu1") || node.id.endsWith(":id/euj");
            boolean textShape = (node.className.contains("Text") || node.className.startsWith("X."))
                    && node.bounds.right - node.bounds.left > snapshot.displayWidth * 0.25f;
            if (knownId || textShape) return node;
        }
        return null;
    }

    private static boolean hasSearchResultContract(UiSnapshot snapshot, UiNode field) {
        if (field == null) return false;
        if (hasVisibleSuffix(snapshot, ":id/mui", ":id/viewpager_search")
                || hasVisibleId(snapshot, SEARCH_RESULT_CARD_IDS)) return true;
        int cards = 0;
        for (UiNode node : snapshot.nodes) {
            int nodeWidth = node.bounds.right - node.bounds.left;
            int nodeHeight = node.bounds.bottom - node.bounds.top;
            if (snapshot.isVisible(node) && node.clickable && node.bounds.top > field.bounds.bottom
                    && nodeWidth > snapshot.displayWidth * 0.30f
                    && nodeWidth < snapshot.displayWidth * 0.62f
                    && nodeHeight > snapshot.displayHeight * 0.12f) cards++;
        }
        return cards >= 2;
    }

    private static int centerY(UiNode node) {
        return node.bounds.top + (node.bounds.bottom - node.bounds.top) / 2;
    }

    private static int videoActionCount(UiSnapshot snapshot) {
        boolean like = ControlResolver.resolve(snapshot, ControlResolver.Role.VIDEO_LIKE) != null;
        boolean comments = ControlResolver.resolve(snapshot, ControlResolver.Role.VIDEO_COMMENTS) != null;
        boolean favorite = ControlResolver.resolve(snapshot, ControlResolver.Role.VIDEO_FAVORITE) != null;
        boolean share = ControlResolver.resolve(snapshot, ControlResolver.Role.VIDEO_SHARE) != null;
        int count = 0;
        if (like) count++;
        if (comments) count++;
        if (favorite) count++;
        if (share) count++;
        return count;
    }

    /** Nodes must belong to one window and share a non-root container in the captured tree. */
    private static boolean sharesPanelAncestor(UiNode... nodes) {
        if (nodes.length == 0 || nodes[0] == null || nodes[0].treePath.isEmpty()) return false;
        int windowId = nodes[0].windowId;
        String[] common = nodes[0].treePath.split("/");
        int commonLength = common.length;
        for (int index = 1; index < nodes.length; index++) {
            UiNode node = nodes[index];
            if (node == null || node.windowId != windowId || node.treePath.isEmpty()) return false;
            String[] path = node.treePath.split("/");
            commonLength = Math.min(commonLength, path.length);
            int part = 0;
            while (part < commonLength && common[part].equals(path[part])) part++;
            commonLength = part;
        }
        // "0" is the window root. Requiring one additional shared component proves that the
        // anchors live in the same child container rather than merely somewhere in the window.
        return commonLength >= 2;
    }

    private static Candidate candidate(PageState.Page page, double score, String... evidence) {
        List<String> values = new ArrayList<>();
        for (String value : evidence) values.add(value);
        return new Candidate(page, score, values);
    }

    private static Candidate select(List<Candidate> candidates) {
        Candidate best = null;
        Candidate second = null;
        for (Candidate candidate : candidates) {
            if (best == null || candidate.score > best.score) {
                second = best;
                best = candidate;
            } else if (second == null || candidate.score > second.score) {
                second = candidate;
            }
        }
        if (best == null || best.score < 0.72d) return null;
        if (second != null && best.page != second.page && best.score - second.score < 0.12d) return null;
        return best;
    }

    private static List<String> unresolvedEvidence(UiNode field, UiNode list, UiNode header,
                                                    boolean lowerField, boolean samePanel,
                                                    boolean searchField, boolean searchSubmit,
                                                    boolean searchResults, int videoActions) {
        List<String> evidence = new ArrayList<>();
        if (field != null) evidence.add("observed:comment_input");
        if (list != null) evidence.add("observed:comment_list");
        if (header != null) evidence.add("observed:comment_header");
        if (lowerField) evidence.add("observed:comment_input_lower_screen");
        if (samePanel) evidence.add("observed:comment_shared_panel");
        if (searchField) evidence.add("observed:search_field");
        if (searchSubmit) evidence.add("observed:search_submit");
        if (searchResults) evidence.add("observed:search_results");
        if (videoActions > 0) evidence.add("observed:video_actions=" + videoActions);
        evidence.add("classification:insufficient_or_conflicting_evidence");
        return evidence;
    }

    private static final class Candidate {
        final PageState.Page page;
        final double score;
        final List<String> evidence;

        Candidate(PageState.Page page, double score, List<String> evidence) {
            this.page = page;
            this.score = score;
            this.evidence = evidence;
        }
    }
}
