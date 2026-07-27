package com.xwell.rpatt.automation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Soft-dismiss whitelist, primary-accept blacklist, and recovery action selection.
 * Aligns with docs/POPUP_CATALOG.md and docs/INTERRUPT_AND_PREFLIGHT.md.
 */
public final class InterruptPolicy {
    /** Labels that may be auto-tapped to dismiss soft interruptions. */
    public static final String[] SOFT_DISMISS_LABELS = {
            "以后再说", "下次再说", "稍后再说", "暂不", "先不了", "不用了", "跳过", "不允许", "拒绝",
            "Not now", "Maybe later", "No thanks", "No, thanks", "Don't allow", "Don’t allow",
            "Deny", "Dismiss", "Skip", "Close", "关闭"
    };

    /** Labels that must never be used as generic dismiss CTAs. */
    public static final String[] PRIMARY_ACCEPT_BLACKLIST = {
            "Allow", "Continue", "Yes", "Update", "OK", "好的", "同意", "开启", "允许",
            "仅在使用该应用时允许", "始终允许", "更新", "登录", "注册", "继续", "确定"
    };

    public static final String[] GOT_IT_LABELS = {
            "我知道了", "知道了", "Got it"
    };

    /**
     * First-time repost education sheet uses a primary-looking CTA (好的) that is intentionally
     * allowed only for this dialog — never for permissions or soft-asks.
     */
    public static final String[] FIRST_TIME_REPOST_GUIDE_LABELS = {
            "好的", "Got it", "OK"
    };

    public static final String[] FIRST_TIME_REPOST_GUIDE_MARKERS = {
            "通过转发将该作品推荐给其他人",
            "转发会在主页上显示",
            "使用【转发】按钮进行转发",
            "recommend this post to others by reposting",
            "reposts show up on your profile",
            "use the repost button"
    };

    public static final String[] INTEREST_SKIP_LABELS = {
            "跳过", "Skip", "以后再说", "Not now", "Maybe later"
    };

    public static final String[] CONTACTS_DENY_LABELS = {
            "拒绝", "不允许", "Don't allow", "Don’t allow", "Deny", "Not now", "以后再说", "暂不"
    };

    /**
     * System GrantPermissionsActivity CTAs (OEM may use 拒绝 / Don't allow / Deny).
     * Never include Allow / 允许 — those stay on PRIMARY_ACCEPT_BLACKLIST.
     */
    public static final String[] SYSTEM_PERMISSION_DENY_LABELS = {
            "拒绝", "不允许", "Deny", "Don't allow", "Don’t allow", "Not now", "以后再说", "暂不"
    };

    private InterruptPolicy() {}

    public static boolean isSoftDismissLabel(String label) {
        return containsIgnoreCase(SOFT_DISMISS_LABELS, label);
    }

    public static boolean isPrimaryAcceptLabel(String label) {
        return containsIgnoreCase(PRIMARY_ACCEPT_BLACKLIST, label);
    }

    public static boolean isSystemPermissionDenyLabel(String label) {
        return containsIgnoreCase(SYSTEM_PERMISSION_DENY_LABELS, label);
    }

    /**
     * Drop any candidate that is empty, blank, or on the primary-accept blacklist.
     * Soft-dismiss lists should already be clean; this is a defensive filter.
     */
    public static String[] filterSoftLabels(String... labels) {
        if (labels == null || labels.length == 0) return new String[0];
        List<String> kept = new ArrayList<>(labels.length);
        for (String label : labels) {
            if (label == null) continue;
            String trimmed = label.trim();
            if (trimmed.isEmpty()) continue;
            if (isPrimaryAcceptLabel(trimmed)) continue;
            kept.add(trimmed);
        }
        return kept.toArray(new String[0]);
    }

    /** Soft labels plus got-it style confirms that are still reversible. */
    public static String[] softDismissAndGotItLabels() {
        Set<String> labels = new LinkedHashSet<>();
        labels.addAll(Arrays.asList(SOFT_DISMISS_LABELS));
        labels.addAll(Arrays.asList(GOT_IT_LABELS));
        return filterSoftLabels(labels.toArray(new String[0]));
    }

    /**
     * Pick the first recoverable interruption code for the expected page, matching
     * {@code WarmupEngine.recoverOne} priority.
     */
    public static String primaryRecoverableCode(PageState state, PageState.Page expected) {
        if (state == null) return null;
        if (!state.blockers.isEmpty()) return null;
        // System runtime permission sheet must be denied before TikTok soft-asks underneath.
        if (state.interruptions.contains("system_permission_prompt")) return "system_permission_prompt";
        if (state.interruptions.contains("contacts_sync_prompt")) return "contacts_sync_prompt";
        if (state.interruptions.contains("follow_friends_popup")) return "follow_friends_popup";
        if (state.interruptions.contains("system_input_method_dialog")) return "system_input_method_dialog";
        if (state.interruptions.contains("video_options_sheet")) return "video_options_sheet";
        // Full COMMENTS page, or partial composer residue (UNKNOWN + comments_open interruption).
        if (expected != PageState.Page.COMMENTS
                && (state.page == PageState.Page.COMMENTS
                || state.interruptions.contains("comments_open"))) {
            return "comments_open";
        }
        if (state.interruptions.contains("notification_banner")) return "notification_banner";
        if (state.interruptions.contains("first_time_repost_guide")) return "first_time_repost_guide";
        if (state.interruptions.contains("got_it_popup")) return "got_it_popup";
        if (state.interruptions.contains("interest_survey")) return "interest_survey";
        if (state.interruptions.contains("collections_popup")) return "collections_popup";
        if (state.interruptions.contains("link_email_popup")) return "link_email_popup";
        if (state.interruptions.contains("notification_prompt")) return "notification_prompt";
        if (state.interruptions.contains("promo_banner")) return "promo_banner";
        if (state.interruptions.contains("generic_dismiss_popup")) return "generic_dismiss_popup";
        if (state.interruptions.contains("suggestion_page") && expected == PageState.Page.FEED) {
            return "suggestion_page";
        }
        if (expected == PageState.Page.FEED && isWrongPageForFeed(state.page)) {
            return wrongPageCode(state.page);
        }
        // After comments/search, accessibility can report UNKNOWN with no interruptions.
        // Policy: press BACK (previous page) first on any off-contract anomaly.
        if (expected == PageState.Page.FEED && state.page == PageState.Page.UNKNOWN) {
            return "anomaly_press_back";
        }
        return null;
    }

    public static String recoveryActionFor(String interruptionCode) {
        if (interruptionCode == null) return "none";
        switch (interruptionCode) {
            case "system_permission_prompt":
                return "deny_system_permission";
            case "contacts_sync_prompt":
                return "deny_contacts";
            case "system_input_method_dialog":
            case "video_options_sheet":
            case "comments_open":
            case "anomaly_press_back":
                return "press_back";
            case "notification_banner":
                return "dismiss_notification";
            case "first_time_repost_guide":
                return "dismiss_repost_guide";
            case "got_it_popup":
                return "dismiss_got_it";
            case "interest_survey":
                return "dismiss_interest_survey";
            case "collections_popup":
            case "follow_friends_popup":
            case "link_email_popup":
            case "notification_prompt":
            case "promo_banner":
            case "generic_dismiss_popup":
                return "dismiss_popup";
            case "suggestion_page":
                return "skip_suggestion";
            case "search_input":
            case "search_results":
            case "search_empty":
            case "inbox":
            case "profile":
            case "shop":
            case "settings":
            case "unknown_return_feed":
                // Prefer BACK (previous page) before jumping to 首页.
                return "press_back_then_home";
            default:
                return "none";
        }
    }

    public static boolean hasRecoverableInterruption(PageState state, PageState.Page expected) {
        return primaryRecoverableCode(state, expected) != null;
    }

    public static boolean softAndBlacklistDisjoint() {
        for (String soft : SOFT_DISMISS_LABELS) {
            if (isPrimaryAcceptLabel(soft)) return false;
        }
        for (String gotIt : GOT_IT_LABELS) {
            if (isPrimaryAcceptLabel(gotIt)) return false;
        }
        return true;
    }

    private static boolean isWrongPageForFeed(PageState.Page page) {
        return page == PageState.Page.SEARCH_INPUT
                || page == PageState.Page.SEARCH_RESULTS
                || page == PageState.Page.SEARCH_EMPTY
                || page == PageState.Page.INBOX
                || page == PageState.Page.PROFILE
                || page == PageState.Page.SHOP
                || page == PageState.Page.SETTINGS;
    }

    private static String wrongPageCode(PageState.Page page) {
        switch (page) {
            case SEARCH_INPUT: return "search_input";
            case SEARCH_RESULTS: return "search_results";
            case SEARCH_EMPTY: return "search_empty";
            case INBOX: return "inbox";
            case PROFILE: return "profile";
            case SHOP: return "shop";
            case SETTINGS: return "settings";
            default: return page.name().toLowerCase(Locale.ROOT);
        }
    }

    private static boolean containsIgnoreCase(String[] haystack, String needle) {
        if (needle == null) return false;
        String target = needle.trim();
        if (target.isEmpty()) return false;
        for (String item : haystack) {
            if (item != null && item.equalsIgnoreCase(target)) return true;
        }
        return false;
    }
}
