package com.xwell.rpatt.config;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Configuration for the fixed safety skeleton and configurable action workflow. */
public final class WarmupConfig {
    public static final int SCHEMA_VERSION = 4;
    public static final String JOURNEY_ORDERED = "ordered";
    public static final String JOURNEY_RECOMMENDATION_LED = "recommendation_led";
    public static final String STEP_COMMENTS = "comments";
    public static final String STEP_LIKE = "like";
    public static final String STEP_FAVORITE = "favorite";
    public static final String STEP_COMMENT = "comment";
    public static final String STEP_FOLLOW = "follow";
    public static final String STEP_SHARE = "share";
    public static final String STEP_NOT_INTERESTED = "not_interested";
    public static final String SOURCE_SEARCH = "search";
    public static final String SOURCE_FEED = "feed";
    public static final List<String> WORKFLOW_STEPS = Arrays.asList(
            STEP_COMMENTS, STEP_LIKE, STEP_FAVORITE, STEP_COMMENT,
            STEP_FOLLOW, STEP_SHARE, STEP_NOT_INTERESTED);
    public static final List<String> SOURCE_STEPS = Arrays.asList(SOURCE_SEARCH, SOURCE_FEED);

    public String profile = "cold";
    public int maxMinutes = 20;
    public int videoCount = 12;
    public Long seed = null;

    public String journeyMode = JOURNEY_ORDERED;
    public int recommendationBootstrapVideos = 3;
    public int recommendationSignalHits = 2;
    public int recommendationSearchCooldownVideos = 5;

    public boolean searchEnabled = false;
    public List<String> searchKeywords = new ArrayList<>();
    public String searchPick = "sequential";
    public int maxSearches = 3;
    public int videosPerKeyword = 2;

    public int watchMinSeconds = 8;
    public int watchMaxSeconds = 20;
    public String swipePace = "natural";
    public boolean swipeRandomizationEnabled = true;
    public int pauseMinSeconds = 1;
    public int pauseMaxSeconds = 4;
    /** Skip in-feed live tiles after watch (no like/comment/share). Default on. */
    public boolean skipLive = true;
    public boolean openComments = true;
    public double commentReadRate = 1.0;
    public boolean commentsOnlyIfRelevant = false;
    public int commentMinSeconds = 2;
    public int commentMaxSeconds = 6;
    public int commentScrolls = 1;
    public boolean commentScrollRandom = true;
    public int replyThreads = 1;
    public boolean autoRecover = true;
    public int maxRecoveryActions = 3;

    public List<String> interestKeywords = new ArrayList<>();
    public boolean interestEnabled = false;
    public int interestWatchExtraMinSeconds = 0;
    public int interestWatchExtraMaxSeconds = 0;
    public List<String> notInterestedKeywords = new ArrayList<>();
    public boolean notInterestedEnabled = false;
    public double notInterestedRate = 1.0;
    public int maxNotInterested = 3;

    public boolean interactionsApply = true;
    public boolean likeEnabled = true;
    public double likeRate = 0.15;
    public boolean likeOnlyIfRelevant = true;
    public boolean favoriteEnabled = true;
    public double favoriteRate = 0.05;
    public boolean favoriteOnlyIfRelevant = true;
    public boolean commentEnabled = false;
    public double commentRate = 0.0;
    public boolean commentOnlyIfRelevant = true;
    public List<String> commentTexts = new ArrayList<>();
    public String commentOrder = "random";
    public int maxLikes = 3;
    public int maxFavorites = 2;
    public int maxComments = 1;

    public boolean followEnabled = false;
    public boolean followApply = true;
    public double followRate = 0.05;
    public int maxFollows = 1;
    public boolean followOnlyIfRelevant = true;

    public boolean shareEnabled = false;
    public double shareRate = 0.02;
    public int maxShares = 1;
    public boolean shareOnlyIfRelevant = true;

    public boolean stopOnBlockers = true;
    public boolean requireNetwork = true;
    public boolean startCollapsed = true;
    public boolean vibrateOnBlock = true;
    public List<String> sourceOrder = new ArrayList<>(SOURCE_STEPS);
    public List<String> workflowOrder = new ArrayList<>(WORKFLOW_STEPS);

    public static WarmupConfig defaultsFor(String profile) {
        WarmupConfig config = new WarmupConfig();
        config.profile = profile;
        if ("normal".equals(profile)) {
            config.maxMinutes = 30;
            config.videoCount = 20;
            config.likeRate = 0.20;
            config.favoriteRate = 0.08;
            config.workflowOrder = new ArrayList<>(Arrays.asList(
                    STEP_LIKE, STEP_COMMENTS, STEP_FAVORITE, STEP_FOLLOW,
                    STEP_COMMENT, STEP_SHARE, STEP_NOT_INTERESTED));
        } else if ("active".equals(profile)) {
            config.maxMinutes = 45;
            config.videoCount = 30;
            config.likeRate = 0.25;
            config.favoriteRate = 0.10;
            config.workflowOrder = new ArrayList<>(Arrays.asList(
                    STEP_LIKE, STEP_FAVORITE, STEP_COMMENTS, STEP_FOLLOW,
                    STEP_COMMENT, STEP_SHARE, STEP_NOT_INTERESTED));
        }
        // The current product flow executes the configured interactions directly.
        config.interactionsApply = true;
        config.followApply = true;
        config.notInterestedEnabled = false;
        return config;
    }

    public void validate() {
        if (!Arrays.asList("cold", "normal", "active", "custom").contains(profile)) {
            throw new IllegalArgumentException("未知方案档位");
        }
        requireRange(maxMinutes, 1, 240, "max_minutes");
        requireRange(videoCount, 1, 500, "video_count");
        if (!Arrays.asList(JOURNEY_ORDERED, JOURNEY_RECOMMENDATION_LED).contains(journeyMode)) {
            throw new IllegalArgumentException("journey.mode 只能是 ordered 或 recommendation_led");
        }
        requireRange(recommendationBootstrapVideos, 1, 100, "recommendation_bootstrap_videos");
        requireRange(recommendationSignalHits, 1, 20, "recommendation_signal_hits");
        requireRange(recommendationSearchCooldownVideos, 0, 100,
                "recommendation_search_cooldown_videos");
        requireRange(watchMinSeconds, 1, 600, "watch_min");
        requireRange(watchMaxSeconds, watchMinSeconds, 900, "watch_max");
        if (!Arrays.asList("slow", "natural", "fast").contains(swipePace)) {
            throw new IllegalArgumentException("swipe_pace 只能是 slow、natural 或 fast");
        }
        requireRange(pauseMinSeconds, 0, 30, "pause_min");
        requireRange(pauseMaxSeconds, pauseMinSeconds, 60, "pause_max");
        requireRange(commentMinSeconds, 0, 120, "comment_min");
        requireRange(commentMaxSeconds, commentMinSeconds, 300, "comment_max");
        requireRate(commentReadRate, "comment_read_rate");
        requireRange(commentScrolls, 0, 10, "comment_scrolls");
        requireRange(replyThreads, 0, 10, "reply_threads");
        requireRange(interestWatchExtraMinSeconds, 0, 120, "interest_watch_extra_min");
        requireRange(interestWatchExtraMaxSeconds, interestWatchExtraMinSeconds, 300,
                "interest_watch_extra_max");
        requireRange(maxRecoveryActions, 0, 10, "max_recovery_actions");
        requireRange(maxSearches, 0, 20, "max_searches");
        requireRange(videosPerKeyword, 1, 50, "videos_per_keyword");
        requireRate(likeRate, "like_rate");
        requireRate(favoriteRate, "favorite_rate");
        requireRate(commentRate, "comment_rate");
        requireRate(followRate, "follow_rate");
        requireRate(shareRate, "share_rate");
        requireRate(notInterestedRate, "not_interested_rate");
        requireRange(maxLikes, 0, 100, "max_likes");
        requireRange(maxFavorites, 0, 100, "max_favorites");
        requireRange(maxComments, 0, 100, "max_comments");
        requireRange(maxFollows, 0, 100, "max_follows");
        requireRange(maxShares, 0, 100, "max_shares");
        requireRange(maxNotInterested, 0, 100, "max_not_interested");
        if (searchEnabled && clean(searchKeywords).isEmpty()) {
            throw new IllegalArgumentException("开启搜索后至少需要一个关键词");
        }
        if (interestEnabled && clean(interestKeywords).isEmpty()) {
            throw new IllegalArgumentException("开启感兴趣判定后至少需要一个正向关键词");
        }
        if (JOURNEY_RECOMMENDATION_LED.equals(journeyMode) && searchEnabled && !interestEnabled) {
            throw new IllegalArgumentException("推荐驱动模式要触发搜索，需同时开启感兴趣判定");
        }
        if (notInterestedEnabled && clean(notInterestedKeywords).isEmpty()) {
            throw new IllegalArgumentException("开启不感兴趣后至少需要一个负向关键词");
        }
        if (commentEnabled && commentRate > 0 && clean(commentTexts).isEmpty()) {
            throw new IllegalArgumentException("评论概率大于 0 时必须填写候选评论");
        }
        if (!"random".equals(searchPick) && !"sequential".equals(searchPick)) {
            throw new IllegalArgumentException("search.pick 只能是 random 或 sequential");
        }
        if (workflowOrder == null || workflowOrder.size() != WORKFLOW_STEPS.size()
                || !workflowOrder.containsAll(WORKFLOW_STEPS)
                || clean(workflowOrder).size() != WORKFLOW_STEPS.size()) {
            throw new IllegalArgumentException("执行工作流必须且只能包含每个可编排模块一次");
        }
        if (sourceOrder == null || sourceOrder.size() != SOURCE_STEPS.size()
                || !sourceOrder.containsAll(SOURCE_STEPS)
                || clean(sourceOrder).size() != SOURCE_STEPS.size()) {
            throw new IllegalArgumentException("内容来源顺序必须包含兴趣搜索和推荐流各一次");
        }
    }

    public JSONObject toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("schema_version", SCHEMA_VERSION);
            root.put("profile", profile);
            root.put("source_order", array(sourceOrder));
            root.put("workflow_order", array(workflowOrder));
            root.put("session", new JSONObject()
                    .put("max_minutes", maxMinutes)
                    .put("video_count", videoCount)
                    .put("seed", seed == null ? JSONObject.NULL : seed));
            root.put("journey", new JSONObject()
                    .put("mode", journeyMode)
                    .put("bootstrap_feed_videos", recommendationBootstrapVideos)
                    .put("signal_hits", recommendationSignalHits)
                    .put("search_cooldown_feed_videos", recommendationSearchCooldownVideos));
            root.put("search", new JSONObject()
                    .put("enabled", searchEnabled)
                    .put("keywords", array(clean(searchKeywords)))
                    .put("pick", searchPick)
                    .put("max_searches", maxSearches)
                    .put("videos_per_keyword", videosPerKeyword));
            root.put("browse", new JSONObject()
                    .put("watch_seconds", new JSONArray().put(watchMinSeconds).put(watchMaxSeconds))
                    .put("swipe_pace", swipePace)
                    .put("swipe_randomization_enabled", swipeRandomizationEnabled)
                    .put("pause_seconds", new JSONArray().put(pauseMinSeconds).put(pauseMaxSeconds))
                    .put("skip_live", skipLive)
                    .put("open_comments", openComments)
                    .put("comment_rate", commentReadRate)
                    .put("comments_only_if_relevant", commentsOnlyIfRelevant)
                    .put("comment_seconds", new JSONArray().put(commentMinSeconds).put(commentMaxSeconds))
                    .put("comment_scrolls", commentScrolls)
                    .put("comment_scroll_random", commentScrollRandom)
                    .put("reply_threads", replyThreads)
                    .put("auto_recover", autoRecover)
                    .put("max_recovery_actions", maxRecoveryActions));
            root.put("interest", new JSONObject()
                    .put("enabled", interestEnabled)
                    .put("keywords", array(clean(interestKeywords)))
                    .put("extra_watch_seconds", new JSONArray()
                            .put(interestWatchExtraMinSeconds)
                            .put(interestWatchExtraMaxSeconds)));
            root.put("not_interested", new JSONObject()
                    .put("enabled", notInterestedEnabled)
                    .put("keywords", array(clean(notInterestedKeywords)))
                    .put("rate", notInterestedRate)
                    .put("max", maxNotInterested));
            root.put("interactions", new JSONObject()
                    .put("apply", interactionsApply)
                    .put("like_enabled", likeEnabled)
                    .put("like_rate", likeRate)
                    .put("like_only_if_relevant", likeOnlyIfRelevant)
                    .put("favorite_enabled", favoriteEnabled)
                    .put("favorite_rate", favoriteRate)
                    .put("favorite_only_if_relevant", favoriteOnlyIfRelevant)
                    .put("comment_enabled", commentEnabled)
                    .put("comment_rate", commentRate)
                    .put("comment_only_if_relevant", commentOnlyIfRelevant)
                    .put("comment_texts", array(clean(commentTexts)))
                    .put("comment_order", commentOrder)
                    .put("max_likes", maxLikes)
                    .put("max_favorites", maxFavorites)
                    .put("max_comments", maxComments));
            root.put("follow", new JSONObject()
                    .put("enabled", followEnabled)
                    .put("apply", followApply)
                    .put("rate", followRate)
                    .put("max_follows", maxFollows)
                    .put("only_if_relevant", followOnlyIfRelevant));
            root.put("share", new JSONObject()
                    .put("enabled", shareEnabled)
                    .put("rate", shareRate)
                    .put("max_shares", maxShares)
                    .put("only_if_relevant", shareOnlyIfRelevant));
            root.put("safety", new JSONObject()
                    .put("stop_on_blockers", stopOnBlockers)
                    .put("require_network", requireNetwork));
            root.put("overlay", new JSONObject()
                    .put("start_collapsed", startCollapsed)
                    .put("vibrate_on_block", vibrateOnBlock));
            return root;
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    public static WarmupConfig fromJson(JSONObject root) {
        WarmupConfig config = defaultsFor(root.optString("profile", "cold"));
        config.sourceOrder = normalizeOrder(
                strings(root.optJSONArray("source_order")), SOURCE_STEPS, config.sourceOrder);
        config.workflowOrder = normalizeWorkflowOrder(
                strings(root.optJSONArray("workflow_order")), config.workflowOrder);
        JSONObject session = root.optJSONObject("session");
        JSONObject journey = root.optJSONObject("journey");
        JSONObject search = root.optJSONObject("search");
        JSONObject browse = root.optJSONObject("browse");
        JSONObject interest = root.optJSONObject("interest");
        JSONObject notInterested = root.optJSONObject("not_interested");
        JSONObject interactions = root.optJSONObject("interactions");
        JSONObject follow = root.optJSONObject("follow");
        JSONObject share = root.optJSONObject("share");
        JSONObject safety = root.optJSONObject("safety");
        JSONObject overlay = root.optJSONObject("overlay");
        if (session != null) {
            config.maxMinutes = session.optInt("max_minutes", config.maxMinutes);
            config.videoCount = session.optInt("video_count", config.videoCount);
            if (!session.isNull("seed")) config.seed = session.optLong("seed");
        }
        if (journey != null) {
            config.journeyMode = journey.optString("mode", config.journeyMode);
            config.recommendationBootstrapVideos = journey.optInt(
                    "bootstrap_feed_videos", config.recommendationBootstrapVideos);
            config.recommendationSignalHits = journey.optInt(
                    "signal_hits", config.recommendationSignalHits);
            config.recommendationSearchCooldownVideos = journey.optInt(
                    "search_cooldown_feed_videos", config.recommendationSearchCooldownVideos);
        }
        if (search != null) {
            config.searchEnabled = search.optBoolean("enabled", config.searchEnabled);
            config.searchKeywords = strings(search.optJSONArray("keywords"));
            config.searchPick = search.optString("pick", config.searchPick);
            config.maxSearches = search.optInt("max_searches", config.maxSearches);
            config.videosPerKeyword = search.optInt("videos_per_keyword", config.videosPerKeyword);
        }
        if (browse != null) {
            int[] watch = pair(browse.optJSONArray("watch_seconds"), config.watchMinSeconds, config.watchMaxSeconds);
            int[] comments = pair(browse.optJSONArray("comment_seconds"), config.commentMinSeconds, config.commentMaxSeconds);
            config.watchMinSeconds = watch[0];
            config.watchMaxSeconds = watch[1];
            config.swipePace = browse.optString("swipe_pace", config.swipePace);
            config.swipeRandomizationEnabled = browse.optBoolean(
                    "swipe_randomization_enabled", config.swipeRandomizationEnabled);
            int[] pauses = pair(browse.optJSONArray("pause_seconds"), config.pauseMinSeconds, config.pauseMaxSeconds);
            config.pauseMinSeconds = pauses[0];
            config.pauseMaxSeconds = pauses[1];
            config.skipLive = browse.optBoolean("skip_live", config.skipLive);
            config.openComments = browse.optBoolean("open_comments", config.openComments);
            config.commentReadRate = browse.optDouble("comment_rate", config.commentReadRate);
            config.commentsOnlyIfRelevant = browse.optBoolean(
                    "comments_only_if_relevant", config.commentsOnlyIfRelevant);
            config.commentMinSeconds = comments[0];
            config.commentMaxSeconds = comments[1];
            config.commentScrolls = browse.optInt("comment_scrolls", config.commentScrolls);
            config.commentScrollRandom = browse.optBoolean(
                    "comment_scroll_random", config.commentScrollRandom);
            config.replyThreads = browse.optInt("reply_threads", config.replyThreads);
            config.autoRecover = browse.optBoolean("auto_recover", config.autoRecover);
            config.maxRecoveryActions = browse.optInt("max_recovery_actions", config.maxRecoveryActions);
        }
        if (notInterested != null) {
            if (interest != null) {
                config.interestKeywords = strings(interest.optJSONArray("keywords"));
                config.interestEnabled = interest.optBoolean("enabled", !config.interestKeywords.isEmpty());
                int[] extraWatch = pair(interest.optJSONArray("extra_watch_seconds"),
                        config.interestWatchExtraMinSeconds, config.interestWatchExtraMaxSeconds);
                config.interestWatchExtraMinSeconds = extraWatch[0];
                config.interestWatchExtraMaxSeconds = extraWatch[1];
            }
            config.notInterestedKeywords = strings(notInterested.optJSONArray("keywords"));
            config.notInterestedEnabled = notInterested.optBoolean("enabled", !config.notInterestedKeywords.isEmpty());
            config.notInterestedRate = notInterested.optDouble("rate", config.notInterestedRate);
            config.maxNotInterested = notInterested.optInt("max", config.maxNotInterested);
        } else if (interest != null && interest.optBoolean("apply_not_interested", false)) {
            // Legacy screens described these as "not interested keywords" even though the old
            // engine treated them as positive interests and rejected every non-match. Preserve
            // what the user entered, but migrate it to the safe match-only negative list.
            migrateLegacyNotInterested(config, strings(interest.optJSONArray("keywords")));
        } else if (interest != null) {
            config.interestKeywords = strings(interest.optJSONArray("keywords"));
            config.interestEnabled = interest.optBoolean("enabled", !config.interestKeywords.isEmpty());
            int[] extraWatch = pair(interest.optJSONArray("extra_watch_seconds"),
                    config.interestWatchExtraMinSeconds, config.interestWatchExtraMaxSeconds);
            config.interestWatchExtraMinSeconds = extraWatch[0];
            config.interestWatchExtraMaxSeconds = extraWatch[1];
        }
        if (interactions != null) {
            // Preview mode has been removed; legacy saved values cannot disable interactions.
            config.interactionsApply = true;
            config.likeEnabled = interactions.optBoolean("like_enabled",
                    interactions.optDouble("like_rate", config.likeRate) > 0);
            config.likeRate = interactions.optDouble("like_rate", config.likeRate);
            config.likeOnlyIfRelevant = interactions.optBoolean("like_only_if_relevant", true);
            config.favoriteEnabled = interactions.optBoolean("favorite_enabled",
                    interactions.optDouble("favorite_rate", config.favoriteRate) > 0);
            config.favoriteRate = interactions.optDouble("favorite_rate", config.favoriteRate);
            config.favoriteOnlyIfRelevant = interactions.optBoolean("favorite_only_if_relevant", true);
            config.commentEnabled = interactions.optBoolean("comment_enabled",
                    interactions.optDouble("comment_rate", config.commentRate) > 0);
            config.commentRate = interactions.optDouble("comment_rate", config.commentRate);
            config.commentOnlyIfRelevant = interactions.optBoolean("comment_only_if_relevant", true);
            config.commentTexts = strings(interactions.optJSONArray("comment_texts"));
            config.commentOrder = interactions.optString("comment_order", config.commentOrder);
            config.maxLikes = interactions.optInt("max_likes", config.maxLikes);
            config.maxFavorites = interactions.optInt("max_favorites", config.maxFavorites);
            config.maxComments = interactions.optInt("max_comments", config.maxComments);
        }
        if (follow != null) {
            config.followEnabled = follow.optBoolean("enabled", false);
            config.followApply = true;
            config.followRate = follow.optDouble("rate", config.followRate);
            config.maxFollows = follow.optInt("max_follows", config.maxFollows);
            config.followOnlyIfRelevant = follow.optBoolean("only_if_relevant", true);
        }
        if (share != null) {
            config.shareEnabled = share.optBoolean("enabled", false);
            config.shareRate = share.optDouble("rate", config.shareRate);
            config.maxShares = share.optInt("max_shares", config.maxShares);
            config.shareOnlyIfRelevant = share.optBoolean("only_if_relevant", true);
        }
        if (safety != null) {
            config.stopOnBlockers = safety.optBoolean("stop_on_blockers", true);
            config.requireNetwork = safety.optBoolean("require_network", true);
        }
        if (overlay != null) {
            config.startCollapsed = overlay.optBoolean("start_collapsed", true);
            config.vibrateOnBlock = overlay.optBoolean("vibrate_on_block", true);
        }
        config.validate();
        return config;
    }

    static void migrateLegacyNotInterested(WarmupConfig config, List<String> legacyKeywords) {
        config.notInterestedKeywords = clean(legacyKeywords);
        config.notInterestedEnabled = !config.notInterestedKeywords.isEmpty();
        config.interestKeywords = new ArrayList<>();
        config.interestEnabled = false;
    }

    private static void requireRange(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " 超出范围");
    }

    private static void requireRate(double value, String name) {
        if (Double.isNaN(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " 必须在 0 到 1 之间");
        }
    }

    private static List<String> clean(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String normalized = value == null ? "" : value.trim();
            if (!normalized.isEmpty() && !result.contains(normalized)) result.add(normalized);
        }
        return result;
    }

    private static JSONArray array(List<String> values) {
        JSONArray result = new JSONArray();
        for (String value : values) result.put(value);
        return result;
    }

    private static List<String> strings(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array == null) return result;
        for (int index = 0; index < array.length(); index++) {
            String value = array.optString(index, "").trim();
            if (!value.isEmpty() && !result.contains(value)) result.add(value);
        }
        return result;
    }

    private static List<String> normalizeWorkflowOrder(List<String> saved, List<String> fallback) {
        return normalizeOrder(saved, WORKFLOW_STEPS, fallback);
    }

    private static List<String> normalizeOrder(List<String> saved, List<String> allowed,
                                               List<String> fallback) {
        List<String> result = new ArrayList<>();
        for (String step : saved) {
            if (allowed.contains(step) && !result.contains(step)) result.add(step);
        }
        for (String step : fallback) {
            if (allowed.contains(step) && !result.contains(step)) result.add(step);
        }
        for (String step : allowed) {
            if (!result.contains(step)) result.add(step);
        }
        return result;
    }

    private static int[] pair(JSONArray array, int first, int second) {
        if (array == null || array.length() < 2) return new int[]{first, second};
        return new int[]{array.optInt(0, first), array.optInt(1, second)};
    }
}
