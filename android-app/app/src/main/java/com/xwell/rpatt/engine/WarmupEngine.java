package com.xwell.rpatt.engine;

import android.content.Context;
import android.os.SystemClock;

import com.xwell.rpatt.BuildConfig;
import com.xwell.rpatt.automation.AccessibilityGateway;
import com.xwell.rpatt.automation.ControlResolver;
import com.xwell.rpatt.automation.FavoriteVisualEvidence;
import com.xwell.rpatt.automation.KeywordMatcher;
import com.xwell.rpatt.automation.PageAnalyzer;
import com.xwell.rpatt.automation.PageStability;
import com.xwell.rpatt.automation.PageState;
import com.xwell.rpatt.automation.PageTransitionPolicy;
import com.xwell.rpatt.automation.RepostSheetState;
import com.xwell.rpatt.automation.UiNode;
import com.xwell.rpatt.automation.UiSnapshot;
import com.xwell.rpatt.automation.VideoContent;
import com.xwell.rpatt.automation.VideoSurfaceContext;
import com.xwell.rpatt.automation.WarmupAccessibilityService;
import com.xwell.rpatt.config.WarmupConfig;
import com.xwell.rpatt.storage.ActionLedger;
import com.xwell.rpatt.storage.RunLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The single fixed state machine required by docs/WARMUP.md. */
public final class WarmupEngine {
    public interface Listener { void onSnapshot(RunSnapshot snapshot); }

    private static final String TIKTOK = PageAnalyzer.TIKTOK_PACKAGE;
    private static final WarmupEngine INSTANCE = new WarmupEngine();

    public static WarmupEngine instance() { return INSTANCE; }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Object control = new Object();
    private volatile Listener listener;
    private volatile RunSnapshot current = RunSnapshot.idle();
    private volatile boolean running;
    private volatile boolean paused;
    private volatile boolean pauseRequested;
    private volatile boolean stopRequested;
    private RunBudget budget;

    private WarmupEngine() {}

    public RunSnapshot current() { return current; }
    public boolean isRunning() { return running; }
    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) listener.onSnapshot(current);
    }

    public boolean start(Context context, WarmupConfig config) {
        synchronized (control) {
            if (running) return false;
            config.validate();
            running = true;
            paused = false;
            pauseRequested = false;
            stopRequested = false;
        }
        worker.execute(() -> run(context.getApplicationContext(), config));
        return true;
    }

    public void togglePause() {
        boolean resume;
        synchronized (control) {
            if (!running) return;
            resume = paused;
            if (resume) {
                paused = false;
                pauseRequested = false;
                if (budget != null) budget.resume(SystemClock.elapsedRealtime());
                control.notifyAll();
            } else {
                pauseRequested = true;
            }
        }
        publish(RunSnapshot.Status.RUNNING, current.phase,
                resume ? "安全复检" : "正在安全暂停",
                resume ? "" : "完成当前视频后暂停");
    }

    public void requestPauseAtCheckpoint() {
        synchronized (control) {
            if (!running || paused || pauseRequested) return;
            pauseRequested = true;
        }
        publish(RunSnapshot.Status.RUNNING, current.phase,
                "正在安全暂停", "完成当前视频后暂停");
    }

    public void resumeFromCheckpoint() {
        synchronized (control) {
            if (!running || !paused) return;
            paused = false;
            pauseRequested = false;
            if (budget != null) budget.resume(SystemClock.elapsedRealtime());
            control.notifyAll();
        }
        publish(RunSnapshot.Status.RUNNING, current.phase, "安全复检", "");
    }

    public void stop() {
        synchronized (control) {
            if (!running) return;
            stopRequested = true;
            paused = false;
            pauseRequested = false;
            if (budget != null) budget.resume(SystemClock.elapsedRealtime());
            control.notifyAll();
        }
    }

    private void run(Context context, WarmupConfig config) {
        RunLogger logger = null;
        String stopReason = "completed";
        boolean blocked = false;
        Counters counters = new Counters();
        Random random = config.seed == null ? new Random() : new Random(config.seed);
        try {
            publish(RunSnapshot.Status.RUNNING, "开场检查", "检查权限", "");
            if (!WarmupAccessibilityService.isConnected()) throw new Blocked("accessibility_disabled", "无障碍服务未开启");
            AccessibilityGateway gateway = AccessibilityGateway.require();
            if (!gateway.screenInteractive()) throw new Blocked("screen_off", "屏幕未点亮");
            if (gateway.deviceLocked()) throw new Blocked("device_locked", "手机仍处于锁屏状态");
            if (config.requireNetwork && !gateway.networkAvailable()) throw new Blocked("network_unavailable", "Android 未检测到可用网络");
            logger = new RunLogger(context);
            logger.writeConfig(config.toJson());
            logger.event("opening_check_started", new JSONObject().put("dry_run", !config.interactionsApply));
            if (!gateway.overlayAllowed()) logger.event("warning", new JSONObject().put("code", "overlay_unavailable"));
            if (!gateway.launchTikTok()) throw new Blocked("tiktok_not_installed", "未找到 TikTok");
            waitForTikTokForeground(gateway, logger);

            logger.event("run_started", new JSONObject().put("dry_run", !config.interactionsApply));
            budget = new RunBudget(SystemClock.elapsedRealtime(), config.maxMinutes * 60_000L, config.videoCount);

            publish(RunSnapshot.Status.RUNNING, "开场检查", "识别可浏览页", "");
            prepareOpeningFeed(gateway, config, logger);
            returnToRecommendationHome(gateway, config, logger);
            logger.event("ready_to_work", new JSONObject());

            safeCheckpoint();
            runContentSources(gateway, config, logger, random, counters);
            if (stopRequested) stopReason = "user";
            else if (budget.exhausted(now())) stopReason = budget.exhaustionReason(now());
        } catch (Blocked error) {
            stopReason = error.code;
            blocked = true;
            if (logger != null) captureBlocker(logger, error.code, error.getMessage());
            publish(RunSnapshot.Status.BLOCKED, "收尾", "需人工处理", error.getMessage());
        } catch (UserStopped ignored) {
            stopReason = "user";
        } catch (Throwable error) {
            stopReason = "error:" + error.getClass().getSimpleName();
            blocked = true;
            if (logger != null) captureBlocker(logger, stopReason, String.valueOf(error.getMessage()));
            publish(RunSnapshot.Status.BLOCKED, "收尾", "不可恢复错误", String.valueOf(error.getMessage()));
        } finally {
            long elapsed = budget == null ? 0 : budget.elapsedMs(now());
            int videos = budget == null ? 0 : budget.videosSeen();
            if (logger != null) {
                try {
                    ActionLedger actions = logger.actions();
                    logger.summary(new JSONObject()
                            .put("ended_at", System.currentTimeMillis())
                            .put("app_version", BuildConfig.VERSION_NAME)
                            .put("app_version_code", BuildConfig.VERSION_CODE)
                            .put("config_schema_version", WarmupConfig.SCHEMA_VERSION)
                            .put("videos_seen", videos)
                            .put("elapsed_ms", elapsed)
                            .put("likes", actions.committedCount("like"))
                            .put("favorites", actions.committedCount("favorite"))
                            .put("comments", actions.committedCount("comment"))
                            .put("follows", actions.committedCount("follow"))
                            .put("shares", actions.committedCount("share"))
                            .put("not_interested", actions.committedCount("not_interested"))
                            .put("action_counts", actions.currentCountsJson())
                            .put("external_actions_committed", actions.committedTotal())
                            .put("partial_video_actions", actions.hasPartialVideoActions())
                            .put("would_likes", counters.wouldLikes)
                            .put("would_favorites", counters.wouldFavorites)
                            .put("would_comments", counters.wouldComments)
                            .put("would_not_interested", counters.wouldNotInterested)
                            .put("stop_reason", stopReason)
                            .put("requires_human", blocked));
                } catch (Exception ignored) {}
            }
            synchronized (control) {
                running = false;
                paused = false;
                pauseRequested = false;
                stopRequested = false;
                budget = null;
            }
            if (!blocked) publish(RunSnapshot.Status.FINISHED, "收尾", "已结束", stopReason);
        }
    }

    private void runSearchPhase(AccessibilityGateway gateway, WarmupConfig config, RunLogger logger,
                                Random random, Counters counters) throws Exception {
        publish(RunSnapshot.Status.RUNNING, "兴趣搜索段", "准备关键词", "");
        List<String> keywords = clean(config.searchKeywords);
        if ("random".equals(config.searchPick)) Collections.shuffle(keywords, random);
        int searches = Math.min(config.maxSearches, keywords.size());
        for (int index = 0; index < searches && !budget.exhausted(now()); index++) {
            safeCheckpoint();
            runSearchBurst(gateway, config, logger, random, counters, keywords.get(index));
        }
    }

    private void runSearchBurst(AccessibilityGateway gateway, WarmupConfig config, RunLogger logger,
                                Random random, Counters counters, String keyword) throws Exception {
        ensureExpected(gateway, PageState.Page.FEED, config, logger);
        ensureVideoContext(gateway, "feed", logger);
        publish(RunSnapshot.Status.RUNNING, "兴趣搜索段", "搜索 " + keyword, "");
        if (!gateway.clickDescriptionContains("搜索", "Search")) {
            throw new Blocked("search_entry_missing", "当前页没有可用搜索入口");
        }
        waitCooperative(800);
        awaitTransition(gateway, config, logger, PageState.Page.FEED,
                PageTransitionPolicy.Action.OPEN_SEARCH);
        UiSnapshot searchInputSnapshot = gateway.snapshot();
        ControlResolver.Match searchField = ControlResolver.resolve(
                searchInputSnapshot, ControlResolver.Role.SEARCH_FIELD);
        if (searchField == null || !gateway.setTextOnResolvedInput(searchField.node, keyword)) {
            throw new Blocked("search_input_missing", "无法写入搜索关键词");
        }
        waitCooperative(300);
        UiSnapshot readyToSubmit = gateway.snapshot();
        ControlResolver.Match refreshedField = ControlResolver.resolve(
                readyToSubmit, ControlResolver.Role.SEARCH_FIELD);
        ControlResolver.Match submit = ControlResolver.resolve(readyToSubmit,
                ControlResolver.Role.SEARCH_SUBMIT,
                refreshedField == null ? null : refreshedField.node);
        if (submit == null || !gateway.tapResolvedGesture(
                ControlResolver.Role.SEARCH_SUBMIT, submit.node,
                ControlResolver.Role.SEARCH_FIELD,
                refreshedField == null ? null : refreshedField.node)) {
            throw new Blocked("search_submit_missing", "无法提交搜索");
        }
        waitCooperative(3000);
        PageState state = awaitTransition(gateway, config, logger, PageState.Page.SEARCH_INPUT,
                PageTransitionPolicy.Action.SUBMIT_SEARCH);
        if (state.page == PageState.Page.SEARCH_EMPTY) {
            logger.event("search_empty", new JSONObject().put("keyword", keyword));
            returnToRecommendationHome(gateway, config, logger);
            return;
        }
        if (state.page != PageState.Page.SEARCH_RESULTS) {
            captureBlocker(logger, "unexpected_search_page", state.page.name());
            throw new Blocked("unexpected_search_page", "搜索后进入未知页面：" + state.page);
        }
        logger.event("search_results", new JSONObject().put("keyword", keyword));
        boolean opened = openSearchVideo(gateway, config, logger, keyword);
        if (!opened) {
            logger.event("search_result_not_opened", new JSONObject().put("keyword", keyword));
            returnToRecommendationHome(gateway, config, logger);
            return;
        }
        waitCooperative(1800);
        for (int video = 0; video < config.videosPerKeyword && !budget.exhausted(now()); video++) {
            safeCheckpoint();
            processOneVideo(gateway, config, logger, random, counters, "search", keyword);
            safeCheckpoint();
            if (video + 1 < config.videosPerKeyword && !budget.exhausted(now())) {
                swipeToNext(gateway, config, logger, random, "search");
            }
        }
        returnToRecommendationHome(gateway, config, logger);
    }

    /** Click and verify a search result before handing it to the normal video workflow. */
    private boolean openSearchVideo(AccessibilityGateway gateway, WarmupConfig config,
                                    RunLogger logger, String keyword) throws Exception {
        for (int attempt = 1; attempt <= 6; attempt++) {
            if (!gateway.openFirstSearchVideo()) {
                waitCooperative(700);
                continue;
            }
            waitCooperative(1200);
            PageState state = inspectStable(gateway, config);
            if (!state.blockers.isEmpty()) {
                throw new Blocked(state.blockers.get(0), "页面阻断：" + state.blockers);
            }
            if (state.page == PageState.Page.FEED) {
                requireTransition(PageState.Page.SEARCH_RESULTS, state,
                        PageTransitionPolicy.Action.OPEN_SEARCH_VIDEO, logger);
                VideoSurfaceContext.Result context = inspectVideoContextStable(gateway);
                if (context.kind != VideoSurfaceContext.Kind.SEARCH_DETAIL) {
                    logger.event("video_context_rejected", contextJson(context)
                            .put("expected", VideoSurfaceContext.Kind.SEARCH_DETAIL.name()));
                    throw new Blocked("search_detail_unverified",
                            "搜索结果已打开播放器，但无法确认它属于搜索详情页");
                }
                logger.event("search_video_opened", new JSONObject()
                        .put("keyword", keyword).put("attempt", attempt)
                        .put("context", contextJson(context)));
                return true;
            }
            // A comments sheet is not reachable from OPEN_SEARCH_VIDEO. Treat it as an anomalous
            // transition instead of guessing that closing it will reveal the intended video.
            if (state.page == PageState.Page.COMMENTS) {
                requireTransition(PageState.Page.SEARCH_RESULTS, state,
                        PageTransitionPolicy.Action.OPEN_SEARCH_VIDEO, logger);
            }
            logger.event("search_video_open_retry", new JSONObject()
                    .put("keyword", keyword).put("attempt", attempt)
                    .put("page", state.page.name()));
            if (state.page != PageState.Page.SEARCH_RESULTS) return false;
            // Do not hammer the same unresponsive autoplay card. Move the results grid once so
            // the next bounded attempt targets a different visible card.
            if (attempt < 6) {
                gateway.swipeUp(260, 0);
                waitCooperative(650);
            }
        }
        return false;
    }

    private void runContentSources(AccessibilityGateway gateway, WarmupConfig config,
                                   RunLogger logger, Random random, Counters counters) throws Exception {
        logger.event("journey_mode", new JSONObject().put("mode", config.journeyMode));
        if (WarmupConfig.JOURNEY_RECOMMENDATION_LED.equals(config.journeyMode)) {
            runRecommendationLedJourney(gateway, config, logger, random, counters);
            return;
        }
        logger.event("source_order", new JSONObject().put("steps", new JSONArray(config.sourceOrder)));
        int reservedForSearch = plannedSearchVideos(config);
        int searchPosition = config.sourceOrder.indexOf(WarmupConfig.SOURCE_SEARCH);
        for (int index = 0; index < config.sourceOrder.size()
                && !budget.exhausted(now()) && !stopRequested; index++) {
            safeCheckpoint();
            String source = config.sourceOrder.get(index);
            if (WarmupConfig.SOURCE_SEARCH.equals(source)) {
                if (config.searchEnabled && !config.searchKeywords.isEmpty()) {
                    runSearchPhase(gateway, config, logger, random, counters);
                }
            } else if (WarmupConfig.SOURCE_FEED.equals(source)) {
                int target = config.videoCount;
                if (config.searchEnabled && searchPosition > index) {
                    target = Math.max(0, config.videoCount - reservedForSearch);
                }
                runFeedPhase(gateway, config, logger, random, counters, target);
            }
        }
        // Empty/short search results must not silently waste the remaining session budget.
        if (!budget.exhausted(now()) && !stopRequested) {
            logger.event("source_fill", new JSONObject().put("source", WarmupConfig.SOURCE_FEED));
            runFeedPhase(gateway, config, logger, random, counters, config.videoCount);
        }
    }

    private void runRecommendationLedJourney(AccessibilityGateway gateway, WarmupConfig config,
                                             RunLogger logger, Random random,
                                             Counters counters) throws Exception {
        publish(RunSnapshot.Status.RUNNING, "推荐驱动", "返回推荐流", "");
        returnToRecommendationHome(gateway, config, logger);
        RecommendationFlowPlanner planner = new RecommendationFlowPlanner(config);
        while (!budget.exhausted(now()) && !stopRequested) {
            safeCheckpoint();
            ProcessedVideo result = processOneVideo(
                    gateway, config, logger, random, counters, "feed", "");
            planner.observeFeedVideo(result.relevant, result.matchedKeywords);
            logger.event("recommendation_signal", new JSONObject()
                    .put("feed_videos", planner.feedVideosSeen())
                    .put("feed_since_search", planner.feedVideosSinceSearch())
                    .put("relevant_signals", planner.relevantSignals())
                    .put("matched_keywords", new JSONArray(result.matchedKeywords)));

            safeCheckpoint();
            if (budget.exhausted(now()) || stopRequested) break;
            if (planner.shouldSearch()) {
                String keyword = planner.nextKeyword(random);
                if (!keyword.isEmpty()) {
                    logger.event("recommendation_search_triggered", new JSONObject()
                            .put("keyword", keyword)
                            .put("feed_since_search", planner.feedVideosSinceSearch())
                            .put("relevant_signals", planner.relevantSignals())
                            .put("burst", planner.searchBursts() + 1));
                    runSearchBurst(gateway, config, logger, random, counters, keyword);
                    planner.markSearchAttempted(keyword);
                    if (budget.exhausted(now()) || stopRequested) break;
                }
            }

            // Returning from a search burst restores the recommendation video that produced the
            // signal. Always advance once so the same item is not processed twice.
            swipeToNext(gateway, config, logger, random, "feed");
            waitCooperative(sample(random, config.pauseMinSeconds * 1000,
                    config.pauseMaxSeconds * 1000));
        }
    }

    static int plannedSearchVideos(WarmupConfig config) {
        if (!config.searchEnabled) return 0;
        int searches = Math.min(config.maxSearches, clean(config.searchKeywords).size());
        return Math.min(config.videoCount, searches * config.videosPerKeyword);
    }

    private void runFeedPhase(AccessibilityGateway gateway, WarmupConfig config, RunLogger logger,
                              Random random, Counters counters, int targetVideosSeen) throws Exception {
        if (budget.videosSeen() >= targetVideosSeen) return;
        publish(RunSnapshot.Status.RUNNING, "推荐流主段", "返回推荐流", "");
        returnToRecommendationHome(gateway, config, logger);
        while (!budget.exhausted(now()) && budget.videosSeen() < targetVideosSeen) {
            safeCheckpoint();
            processOneVideo(gateway, config, logger, random, counters, "feed", "");
            safeCheckpoint();
            if (!budget.exhausted(now()) && budget.videosSeen() < targetVideosSeen) {
                swipeToNext(gateway, config, logger, random, "feed");
                waitCooperative(sample(random, config.pauseMinSeconds * 1000,
                        config.pauseMaxSeconds * 1000));
            }
        }
    }

    private ProcessedVideo processOneVideo(AccessibilityGateway gateway, WarmupConfig config,
                                           RunLogger logger, Random random, Counters counters,
                                           String source, String searchKeyword) throws Exception {
        // A. Safety probe.
        publish(RunSnapshot.Status.RUNNING, phase(source), "A · 安全探测", "");
        ensureExpected(gateway, PageState.Page.FEED, config, logger);
        ensureVideoContext(gateway, source, logger);
        UiSnapshot before = gateway.snapshot();
        PageState beforeState = PageAnalyzer.analyze(before, !config.requireNetwork || gateway.networkAvailable());
        if (!beforeState.blockers.isEmpty()) throw new Blocked(beforeState.blockers.get(0), "页面阻断：" + beforeState.blockers);

        // B. Watch without sending gestures.
        VideoContent previewContent = VideoContent.from(before);
        List<String> previewMatches = config.interestEnabled
                ? previewContent.matchedKeywords(config.interestKeywords) : new ArrayList<>();
        int baseWatchMs = sample(random,
                config.watchMinSeconds * 1000, config.watchMaxSeconds * 1000);
        int watchExtraMs = config.interestEnabled && !previewMatches.isEmpty()
                ? sample(random, config.interestWatchExtraMinSeconds * 1000,
                config.interestWatchExtraMaxSeconds * 1000)
                : 0;
        int watchMs = baseWatchMs + watchExtraMs;
        publish(RunSnapshot.Status.RUNNING, phase(source), "B · 观看", (watchMs / 1000) + " 秒");
        waitCooperative(watchMs);
        UiSnapshot watched = gateway.snapshot();
        PageState watchedState = inspect(gateway, config);
        if (watchedState.page != PageState.Page.FEED) {
            ensureExpected(gateway, PageState.Page.FEED, config, logger);
            watched = gateway.snapshot();
        }
        VideoContent content = VideoContent.from(watched);
        if (content.author.isEmpty() && content.description.isEmpty()) {
            throw new Blocked("content_unreadable", "无法读取当前视频作者或简介");
        }

        // C. Relevance is computed once as an internal prerequisite. The visible actions below
        // run in the user-configured workflow order.
        List<String> matched = config.interestEnabled
                ? content.matchedKeywords(config.interestKeywords) : new ArrayList<>();
        List<String> matchedNotInterested = config.notInterestedEnabled
                ? content.matchedKeywords(config.notInterestedKeywords) : new ArrayList<>();
        boolean relevant = !config.interestEnabled || !matched.isEmpty();
        String feedback = "none";
        List<String> comments = new ArrayList<>();
        String likeAction = "none";
        String favoriteAction = "none";
        String commentAction = "none";
        String commentText = "";
        String followAction = "none";
        String shareAction = "none";
        String actionTarget = content.actionFingerprint();

        for (String step : config.workflowOrder) {
            checkControl();
            switch (step) {
                case WarmupConfig.STEP_COMMENTS:
                    if (config.openComments
                            && (!config.commentsOnlyIfRelevant || relevant)
                            && chance(random, config.commentReadRate)) {
                        publish(RunSnapshot.Status.RUNNING, phase(source), "评论区浏览", "按工作流执行");
                        comments = readComments(gateway, config, logger, random);
                        mergeCommentInterestSignals(matched, comments, config.interestKeywords);
                        relevant = !config.interestEnabled || !matched.isEmpty();
                    }
                    break;
                case WarmupConfig.STEP_LIKE:
                    if (config.likeEnabled) {
                        publish(RunSnapshot.Status.RUNNING, phase(source), "点赞", "按工作流执行");
                        if (plan(random, config.likeRate, counters.likes + counters.wouldLikes, config.maxLikes)
                                && (!config.likeOnlyIfRelevant || relevant)) {
                            if (config.interactionsApply && !content.hasStableActionIdentity()) {
                                likeAction = skipUnstableIdentity(logger, "like", content);
                            } else {
                                likeAction = interactButton(gateway, logger, ControlResolver.Role.VIDEO_LIKE,
                                        "like", actionTarget, config.interactionsApply, counters);
                            }
                        }
                    }
                    break;
                case WarmupConfig.STEP_FAVORITE:
                    if (config.favoriteEnabled) {
                        publish(RunSnapshot.Status.RUNNING, phase(source), "收藏", "按工作流执行");
                        if (plan(random, config.favoriteRate,
                                counters.favorites + counters.wouldFavorites, config.maxFavorites)
                                && (!config.favoriteOnlyIfRelevant || relevant)) {
                            if (config.interactionsApply && !content.hasStableActionIdentity()) {
                                favoriteAction = skipUnstableIdentity(logger, "favorite", content);
                            } else {
                                favoriteAction = interactButton(gateway, logger, ControlResolver.Role.VIDEO_FAVORITE,
                                        "favorite", actionTarget, config.interactionsApply, counters);
                            }
                        }
                    }
                    break;
                case WarmupConfig.STEP_COMMENT:
                    if (config.commentEnabled) {
                        publish(RunSnapshot.Status.RUNNING, phase(source), "评论", "按工作流执行");
                        if ((!config.commentOnlyIfRelevant || relevant)
                                && plan(random, config.commentRate,
                                counters.comments + counters.wouldComments, config.maxComments)) {
                            List<String> candidates = clean(config.commentTexts);
                            if (candidates.isEmpty()) {
                                throw new Blocked("comment_pool_empty", "抽中评论但候选文案为空");
                            }
                            int pick = "sequential".equals(config.commentOrder)
                                    ? (counters.comments + counters.wouldComments) % candidates.size()
                                    : random.nextInt(candidates.size());
                            commentText = candidates.get(pick);
                            if (config.interactionsApply && !content.hasStableActionIdentity()) {
                                commentAction = skipUnstableIdentity(logger, "comment", content);
                            } else {
                                commentAction = interactComment(gateway, config, logger, commentText,
                                        actionTarget, config.interactionsApply, counters);
                            }
                        }
                    }
                    break;
                case WarmupConfig.STEP_FOLLOW:
                    if (config.followEnabled) {
                        publish(RunSnapshot.Status.RUNNING, phase(source), "关注", "按工作流执行");
                        if ((!config.followOnlyIfRelevant || relevant)
                                && plan(random, config.followRate, counters.follows, config.maxFollows)) {
                            if (!content.hasStableActionIdentity()) {
                                followAction = skipUnstableIdentity(logger, "follow", content);
                            } else {
                                followAction = interactFollow(gateway, logger, actionTarget, counters);
                            }
                        }
                    }
                    break;
                case WarmupConfig.STEP_SHARE:
                    if (config.shareEnabled) {
                        publish(RunSnapshot.Status.RUNNING, phase(source), "Repost", "公开转发，不选择联系人");
                        if ((!config.shareOnlyIfRelevant || relevant)
                                && plan(random, config.shareRate, counters.shares, config.maxShares)) {
                            if (!content.hasStableActionIdentity()) {
                                shareAction = skipUnstableIdentity(logger, "share", content);
                            } else {
                                shareAction = interactShare(
                                        gateway, config, logger, actionTarget, counters);
                            }
                        }
                    }
                    break;
                case WarmupConfig.STEP_NOT_INTERESTED:
                    if (config.notInterestedEnabled) {
                        publish(RunSnapshot.Status.RUNNING, phase(source), "不感兴趣", "匹配负向关键词");
                        if (!matchedNotInterested.isEmpty()
                                && plan(random, config.notInterestedRate,
                                counters.notInterested + counters.wouldNotInterested,
                                config.maxNotInterested)) {
                            if (!config.interactionsApply) {
                                feedback = "would_not_interested";
                                counters.wouldNotInterested++;
                            } else {
                                if (!content.hasStableActionIdentity()) {
                                    feedback = skipUnstableIdentity(logger, "not_interested", content);
                                } else {
                                    feedback = applyNotInterested(gateway, logger, actionTarget,
                                            matchedNotInterested, counters);
                                }
                            }
                        }
                    }
                    break;
                default:
                    throw new Blocked("workflow_step_unknown", "未知工作流步骤：" + step);
            }
        }

        // E. Persist one durable record, then increment the shared budget.
        int index = budget.videosSeen() + 1;
        JSONObject record = content.toJson()
                .put("index", index)
                .put("source", source)
                .put("search_keyword", searchKeyword)
                .put("content_kind", watchedState.contentKind)
                .put("watch_ms", watchMs)
                .put("watch_base_ms", baseWatchMs)
                .put("watch_interest_extra_ms", watchExtraMs)
                .put("relevant", relevant)
                .put("matched_keywords", new JSONArray(matched))
                .put("matched_not_interested_keywords", new JSONArray(matchedNotInterested))
                .put("comments", new JSONArray(comments))
                .put("feedback", feedback)
                .put("like_action", likeAction)
                .put("favorite_action", favoriteAction)
                .put("comment_action", commentAction)
                .put("comment_text", commentText)
                .put("follow_action", followAction)
                .put("share_action", shareAction)
                .put("recorded_at", System.currentTimeMillis());
        logger.video(record);
        budget.recordVideo();
        logger.actions().markVideoCommitted(actionTarget);
        current = new RunSnapshot(current.status, current.phase, current.step,
                budget.videosSeen(), budget.maximumVideos(),
                counters.likes + counters.wouldLikes,
                counters.favorites + counters.wouldFavorites,
                counters.comments + counters.wouldComments,
                budget.elapsedMs(now()), current.message);
        publish(RunSnapshot.Status.RUNNING, phase(source), "E · 已落盘", content.author);
        return new ProcessedVideo(relevant, matched);
    }

    private List<String> readComments(AccessibilityGateway gateway, WarmupConfig config,
                                      RunLogger logger, Random random) throws Exception {
        ControlResolver.Match commentEntry = ControlResolver.resolve(
                gateway.snapshot(), ControlResolver.Role.VIDEO_COMMENTS);
        if (commentEntry == null || !gateway.tapResolvedGesture(
                ControlResolver.Role.VIDEO_COMMENTS, commentEntry.node)) {
            logger.event("comments_unavailable", new JSONObject());
            return new ArrayList<>();
        }
        waitCooperative(sample(random, config.commentMinSeconds * 1000, config.commentMaxSeconds * 1000));
        PageState state = awaitTransition(gateway, config, logger, PageState.Page.FEED,
                PageTransitionPolicy.Action.OPEN_COMMENTS);
        if (state.page != PageState.Page.COMMENTS) {
            throw new Blocked("comments_not_opened", "点击评论入口后未进入稳定评论区");
        }
        Set<String> comments = new LinkedHashSet<>();
        int expanded = 0;
        int plannedScrolls = BrowseRandomizer.commentScrollCount(
                config.commentScrolls, config.commentScrollRandom, random);
        int performedScrolls = 0;
        for (int index = 0; index <= plannedScrolls; index++) {
            collectComments(gateway.snapshot(), comments);
            if (expanded < config.replyThreads && gateway.expandOneReplyThread()) {
                expanded++;
                waitCooperative(sample(random, 700, 1400));
                collectComments(gateway.snapshot(), comments);
            }
            if (index < plannedScrolls) {
                if (!gateway.swipeUp(swipeDuration(random, config.swipePace), jitter(random))) {
                    waitCooperative(500);
                    PageState afterInterruption = inspectStable(gateway, config);
                    if (afterInterruption.page == PageState.Page.COMMENTS) {
                        logger.event("comment_scroll_interrupted", new JSONObject()
                                .put("handling", "skip_once")
                                .put("reason", "gesture_cancelled_or_user_touch"));
                        continue;
                    }
                    throw new Blocked("comment_scroll_page_changed",
                            "评论滑动被打断且页面已变化：" + afterInterruption.page);
                }
                performedScrolls++;
                waitCooperative(sample(random, 800, 1800));
            }
        }
        ControlResolver.Match close = ControlResolver.resolve(
                gateway.snapshot(), ControlResolver.Role.COMMENT_CLOSE);
        if (close == null || !gateway.tapResolvedGesture(
                ControlResolver.Role.COMMENT_CLOSE, close.node)) gateway.pressBack();
        waitCooperative(700);
        PageState closed = awaitTransition(gateway, config, logger, PageState.Page.COMMENTS,
                PageTransitionPolicy.Action.CLOSE_COMMENTS);
        if (closed.page != PageState.Page.FEED) {
            throw new Blocked("comments_not_closed", "关闭评论区后未回到稳定视频页");
        }
        logger.event("comments_read", new JSONObject()
                .put("count", comments.size())
                .put("reply_threads", expanded)
                .put("planned_scrolls", plannedScrolls)
                .put("performed_scrolls", performedScrolls)
                .put("scroll_random", config.commentScrollRandom));
        return new ArrayList<>(comments);
    }

    private String interactButton(AccessibilityGateway gateway, RunLogger logger,
                                  ControlResolver.Role role,
                                  String name, String target, boolean apply,
                                  Counters counters) throws Exception {
        UiSnapshot actionSnapshot = gateway.snapshot();
        String beforeFingerprint = VideoContent.from(actionSnapshot).fingerprint();
        ControlResolver.Match resolved = ControlResolver.resolve(actionSnapshot, role);
        UiNode before = resolved == null ? null : resolved.node;
        if (before == null) throw new Blocked(name + "_button_missing", "找不到" + name + "按钮");
        if ("like".equals(name) && isLikeSelected(before)) return "already_liked";
        FavoriteVisualEvidence.Profile favoriteBefore = "favorite".equals(name)
                ? gateway.favoriteVisualState(before) : FavoriteVisualEvidence.unavailable();
        if ("favorite".equals(name) && favoriteBefore.selected()) return "already_favorited";
        if (!apply) {
            if ("like".equals(name)) counters.wouldLikes++; else counters.wouldFavorites++;
            logger.event("would_" + name, new JSONObject());
            return "would_" + name;
        }
        JSONObject preparedEvidence = nodeDetail(before).put("dispatch", "gesture_only");
        if ("favorite".equals(name)) {
            preparedEvidence.put("favorite_before_yellow_ratio", favoriteBefore.yellowRatio)
                    .put("favorite_before_available", favoriteBefore.available);
        }
        ActionLedger.Transaction transaction = beginAction(
                logger, name, target, "", preparedEvidence);
        if (transaction == null) return "already_recorded";
        transaction.attempted(new JSONObject(preparedEvidence.toString()));
        if (!gateway.tapResolvedGesture(role, before)) {
            transaction.unknown("click_not_acknowledged", nodeDetail(before));
            throw new Blocked(name + "_result_unknown", name + "点击结果不明确，已停止且不会重试");
        }

        // A write is never repeated. Poll only the same on-screen control while TikTok updates its
        // accessibility state; adjacent videos use the same resource ID and must not be observed.
        boolean verified = false;
        FavoriteVisualEvidence.Profile favoriteAfter = FavoriteVisualEvidence.unavailable();
        for (int attempt = 0; attempt < 6 && !verified; attempt++) {
            waitCooperative(attempt == 0 ? 500 : 400);
            UiSnapshot observed = gateway.snapshot();
            PageState observedState = PageAnalyzer.analyze(observed, true);
            boolean sameVideo = observedState.page == PageState.Page.FEED
                    && beforeFingerprint.equals(VideoContent.from(observed).fingerprint());
            if ("like".equals(name)) {
                UiNode after = gateway.visibleRightActionNear(before, "点赞的视频", "liked video");
                verified = sameVideo && after != null && isLikeSelected(after);
            } else {
                // TikTok exposes the same accessibility label before and after. Require a colour
                // transition at the captured control and keep the readback on the same video.
                favoriteAfter = gateway.favoriteVisualState(before);
                verified = sameVideo && FavoriteVisualEvidence.transitionedToSelected(
                        favoriteBefore, favoriteAfter);
            }
        }
        if (!verified) {
            JSONObject failure = nodeDetail(before).put("same_video_fingerprint", beforeFingerprint);
            if ("favorite".equals(name)) {
                failure.put("favorite_before_yellow_ratio", favoriteBefore.yellowRatio)
                        .put("favorite_after_yellow_ratio", favoriteAfter.yellowRatio)
                        .put("favorite_after_available", favoriteAfter.available);
            }
            transaction.unknown("verification_failed", failure);
            throw new Blocked(name + "_result_unknown", name + "结果不明确，为避免重复操作已停止");
        }
        JSONObject evidence = nodeDetail(before).put("verified", true)
                .put("dispatch", "gesture_only");
        if ("favorite".equals(name)) {
            evidence.put("favorite_before_yellow_ratio", favoriteBefore.yellowRatio)
                    .put("favorite_after_yellow_ratio", favoriteAfter.yellowRatio);
        }
        transaction.verified(evidence);
        transaction.committed(evidence);
        if ("like".equals(name)) counters.likes++; else counters.favorites++;
        logger.event(name, evidence);
        return name + "d";
    }

    private String interactComment(AccessibilityGateway gateway, WarmupConfig config,
                                   RunLogger logger, String text,
                                   String target, boolean apply, Counters counters) throws Exception {
        if (!apply) {
            counters.wouldComments++;
            logger.event("would_comment", new JSONObject().put("text", text));
            return "would_comment";
        }
        ControlResolver.Match commentEntry = ControlResolver.resolve(
                gateway.snapshot(), ControlResolver.Role.VIDEO_COMMENTS);
        if (commentEntry == null || !gateway.tapResolvedGesture(
                ControlResolver.Role.VIDEO_COMMENTS, commentEntry.node)) {
            throw new Blocked("comment_entry_missing", "找不到评论入口");
        }
        waitCooperative(800);
        PageState comments = awaitTransition(gateway, config, logger, PageState.Page.FEED,
                PageTransitionPolicy.Action.OPEN_COMMENTS);
        if (comments.page != PageState.Page.COMMENTS) {
            throw new Blocked("comment_composer_not_ready", "评论页尚未稳定，未尝试输入文字");
        }
        ControlResolver.Match commentField = ControlResolver.resolve(
                gateway.snapshot(), ControlResolver.Role.COMMENT_FIELD);
        if (commentField == null || !gateway.setTextOnResolvedInput(commentField.node, text)) {
            gateway.pressBack();
            throw new Blocked("comment_input_failed", "评论框聚焦或文字复核失败，未发送评论");
        }
        if (!gateway.dismissInputMethodIfPresent()) {
            throw new Blocked("comment_keyboard_dismiss_failed", "草稿已写入，但无法安全收起输入法，未发送评论");
        }
        waitCooperative(500);
        UiSnapshot composerSnapshot = gateway.snapshot();
        ControlResolver.Match composerMatch = ControlResolver.resolve(
                composerSnapshot, ControlResolver.Role.COMMENT_FIELD);
        UiNode composer = composerMatch == null ? null : composerMatch.node;
        if (composer == null || !text.equals(composer.text)) {
            captureDiagnostic(logger, "comment-draft-before-cleanup");
            clearResolvedInput(gateway, ControlResolver.Role.COMMENT_FIELD);
            throw new Blocked("comment_draft_not_stable", "输入法收起后草稿状态不一致，未发送评论");
        }
        UiNode send = gateway.visibleCommentSend(composer);
        logger.event("comment_send_candidate", nodeDetail(send));
        if (send == null) {
            captureDiagnostic(logger, "comment-send-before-cleanup");
            clearResolvedInput(gateway, ControlResolver.Role.COMMENT_FIELD);
            gateway.pressBack();
            throw new Blocked("comment_send_missing", "找不到评论框右侧的发送按钮，已清空草稿");
        }
        ActionLedger.BeginResult begin = logger.actions().begin(
                "comment", target, text, nodeDetail(send));
        if (!begin.allowed()) {
            clearResolvedInput(gateway, ControlResolver.Role.COMMENT_FIELD);
            gateway.pressBack();
            if (begin.disposition == ActionLedger.BeginDisposition.DUPLICATE_COMMITTED) {
                logDuplicate(logger, "comment", begin);
                return "already_recorded";
            }
            throw duplicateUncertain("comment", begin);
        }
        ActionLedger.Transaction transaction = begin.transaction;
        transaction.attempted(nodeDetail(send));
        if (!gateway.tapResolvedGesture(ControlResolver.Role.COMMENT_SEND, send,
                ControlResolver.Role.COMMENT_FIELD, composer)) {
            transaction.unknown("click_not_acknowledged", nodeDetail(send));
            captureDiagnostic(logger, "comment-send-before-cleanup");
            clearResolvedInput(gateway, ControlResolver.Role.COMMENT_FIELD);
            gateway.pressBack();
            throw new Blocked("comment_result_unknown", "评论发送结果不明确，已停止且不会重试");
        }
        waitCooperative(1200);
        UiSnapshot resultSnapshot = gateway.snapshot();
        ControlResolver.Match resultField = ControlResolver.resolve(
                resultSnapshot, ControlResolver.Role.COMMENT_FIELD);
        UiNode field = resultField == null ? null : resultField.node;
        if (field != null && text.equals(field.text)) {
            transaction.unknown("draft_still_present", nodeDetail(field));
            captureDiagnostic(logger, "comment-result-before-cleanup");
            clearResolvedInput(gateway, ControlResolver.Role.COMMENT_FIELD);
            throw new Blocked("comment_result_unknown", "评论发送结果不明确，已停止且不会重试");
        }
        JSONObject evidence = new JSONObject().put("draft_cleared", true);
        transaction.verified(evidence);
        transaction.committed(evidence);
        counters.comments++;
        logger.event("comment", new JSONObject().put("verified", true));
        ControlResolver.Match close = ControlResolver.resolve(
                gateway.snapshot(), ControlResolver.Role.COMMENT_CLOSE);
        if (close == null || !gateway.tapResolvedGesture(
                ControlResolver.Role.COMMENT_CLOSE, close.node)) gateway.pressBack();
        return "commented";
    }

    private String applyNotInterested(AccessibilityGateway gateway, RunLogger logger,
                                      String target, List<String> matchedKeywords,
                                      Counters counters) throws Exception {
        if (!gateway.longPressCenter(800)) throw new Blocked("not_interested_menu_failed", "无法打开视频菜单");
        waitCooperative(900);
        ControlResolver.Match entry = ControlResolver.resolve(
                gateway.snapshot(), ControlResolver.Role.NOT_INTERESTED);
        if (entry == null) {
            captureDiagnostic(logger, "not-interested-entry-missing");
            gateway.pressBack();
            throw new Blocked("not_interested_missing", "菜单中没有“不感兴趣”");
        }
        ActionLedger.Transaction transaction = beginAction(logger, "not_interested",
                target, "", nodeDetail(entry.node));
        if (transaction == null) {
            gateway.pressBack();
            return "already_recorded";
        }
        transaction.attempted(nodeDetail(entry.node));
        if (!gateway.tapResolvedGesture(ControlResolver.Role.NOT_INTERESTED, entry.node)) {
            transaction.unknown("click_not_acknowledged", nodeDetail(entry.node));
            throw new Blocked("not_interested_result_unknown", "不感兴趣结果不明确，未重复操作");
        }
        waitCooperative(700);
        UiSnapshot result = gateway.snapshot();
        PageState state = PageAnalyzer.analyze(result, true);
        if (state.page != PageState.Page.FEED
                || gateway.hasVisibleExactText("不感兴趣", "Not interested")) {
            transaction.unknown("verification_failed", new JSONObject()
                    .put("page", state.page.name()));
            captureDiagnostic(logger, "not-interested-result-unknown");
            throw new Blocked("not_interested_result_unknown", "不感兴趣结果不明确，未重复操作");
        }
        JSONObject evidence = new JSONObject().put("page", state.page.name());
        transaction.verified(evidence);
        transaction.committed(evidence);
        counters.notInterested++;
        logger.event("not_interested", new JSONObject()
                .put("matched_keywords", new JSONArray(matchedKeywords)).put("verified", true));
        return "not_interested";
    }

    private String interactFollow(AccessibilityGateway gateway, RunLogger logger,
                                  String target, Counters counters) throws Exception {
        UiSnapshot beforeSnapshot = gateway.snapshot();
        String beforeFingerprint = VideoContent.from(beforeSnapshot).fingerprint();
        ControlResolver.Match follow = ControlResolver.resolve(beforeSnapshot, ControlResolver.Role.VIDEO_FOLLOW);
        UiNode before = follow == null ? null : follow.node;
        if (before == null) {
            if (gateway.hasTextOrDescription("已关注", "following")) return "already_following";
            throw new Blocked("follow_button_missing", "找不到视频作者关注按钮");
        }
        ActionLedger.Transaction transaction = beginAction(
                logger, "follow", target, "", nodeDetail(before));
        if (transaction == null) return "already_recorded";
        transaction.attempted(nodeDetail(before));
        if (!gateway.tapResolvedGesture(ControlResolver.Role.VIDEO_FOLLOW, before)) {
            transaction.unknown("click_not_acknowledged", nodeDetail(before));
            throw new Blocked("follow_result_unknown", "关注点击结果不明确，已停止且不会重试");
        }
        boolean verified = false;
        int stableAbsence = 0;
        for (int sample = 0; sample < 5 && !verified; sample++) {
            waitCooperative(sample == 0 ? 600 : 300);
            UiSnapshot observed = gateway.snapshot();
            PageState state = PageAnalyzer.analyze(observed, true);
            String fingerprint = VideoContent.from(observed).fingerprint();
            ControlResolver.Match after = ControlResolver.resolve(observed, ControlResolver.Role.VIDEO_FOLLOW);
            if (gateway.hasTextOrDescription("已关注", "following")) {
                verified = state.page == PageState.Page.FEED && beforeFingerprint.equals(fingerprint);
            } else if (state.page == PageState.Page.FEED && beforeFingerprint.equals(fingerprint)
                    && after == null) {
                stableAbsence++;
                verified = stableAbsence >= 2;
            } else {
                stableAbsence = 0;
            }
        }
        if (!verified) {
            transaction.unknown("verification_failed", nodeDetail(before));
            throw new Blocked("follow_result_unknown", "关注结果不明确，为避免重复操作已停止");
        }
        JSONObject evidence = nodeDetail(before).put("verified", true);
        transaction.verified(evidence);
        transaction.committed(evidence);
        counters.follows++;
        logger.event("follow", evidence);
        return "followed";
    }

    private String interactShare(AccessibilityGateway gateway, WarmupConfig config,
                                 RunLogger logger, String target,
                                 Counters counters) throws Exception {
        UiSnapshot beforeSnapshot = gateway.snapshot();
        String beforeFingerprint = VideoContent.from(beforeSnapshot).fingerprint();
        ControlResolver.Match shareMatch = ControlResolver.resolve(
                beforeSnapshot, ControlResolver.Role.VIDEO_SHARE);
        UiNode share = shareMatch == null ? null : shareMatch.node;
        if (share == null) throw new Blocked("share_button_missing", "找不到视频分享按钮");
        if (!gateway.tapResolvedGesture(ControlResolver.Role.VIDEO_SHARE, share)) {
            throw new Blocked("share_sheet_failed", "无法打开分享面板");
        }
        waitCooperative(900);
        RepostSheetState.Result sheet = RepostSheetState.analyze(gateway.snapshot());
        if (sheet.state == RepostSheetState.State.ALREADY_REPOSTED) {
            JSONObject evidence = repostStateDetail(sheet)
                    .put("reason", "already_reposted")
                    .put("remove_entry", nodeDetail(sheet.removeEntry.node));
            logger.event("share_skipped", evidence);
            if (!closeShareSheetAndVerify(
                    gateway, config, beforeFingerprint, logger)) {
                throw new Blocked("share_sheet_close_failed",
                        "已识别为此前转发，但关闭分享面板后无法确认仍是原视频");
            }
            return "already_reposted";
        }
        if (sheet.state != RepostSheetState.State.AVAILABLE || sheet.createEntry == null) {
            captureDiagnostic(logger, "repost-state-unknown");
            closeShareSheetAndVerify(gateway, config, beforeFingerprint, logger);
            throw new Blocked("repost_state_unknown", "分享面板中的转发状态无法确认");
        }
        ControlResolver.Match repost = sheet.createEntry;
        ActionLedger.Transaction transaction = beginAction(
                logger, "share", target, "repost",
                nodeDetail(repost.node).put("sheet_state", sheet.state.name()));
        if (transaction == null) {
            if (!closeShareSheetAndVerify(
                    gateway, config, beforeFingerprint, logger)) {
                throw new Blocked("share_sheet_close_failed",
                        "关闭分享面板后无法确认仍是原视频");
            }
            return "already_recorded";
        }
        transaction.attempted(nodeDetail(repost.node));
        if (!gateway.tapResolvedGesture(ControlResolver.Role.REPOST, repost.node)) {
            transaction.unknown("click_not_acknowledged", nodeDetail(repost.node));
            throw new Blocked("share_result_unknown", "转发结果不明确，已停止且不会重试");
        }
        waitCooperative(1000);
        UiSnapshot afterWrite = gateway.snapshot();
        PageState afterWriteState = PageAnalyzer.analyze(afterWrite, true);
        if (afterWriteState.page != PageState.Page.FEED
                || !beforeFingerprint.equals(VideoContent.from(afterWrite).fingerprint())) {
            transaction.unknown("underlying_video_changed", new JSONObject()
                    .put("page", afterWriteState.page.name()));
            throw new Blocked("share_result_unknown",
                    "转发后底层视频发生变化，已停止且不会重试");
        }
        ControlResolver.Match reopenMatch = ControlResolver.resolve(
                afterWrite, ControlResolver.Role.VIDEO_SHARE);
        UiNode reopen = reopenMatch == null ? null : reopenMatch.node;
        if (reopen == null || !gateway.tapResolvedGesture(
                ControlResolver.Role.VIDEO_SHARE, reopen)) {
            transaction.unknown("verification_entry_unavailable", nodeDetail(reopen));
            captureDiagnostic(logger, "repost-reopen-failed");
            throw new Blocked("share_result_unknown", "转发后无法复核结果，已停止且不会重试");
        }
        waitCooperative(800);
        RepostSheetState.Result verifiedState = RepostSheetState.analyze(gateway.snapshot());
        boolean verified = verifiedState.state == RepostSheetState.State.ALREADY_REPOSTED;
        if (!verified) captureDiagnostic(logger, "repost-verification-failed");
        boolean closed = closeShareSheetAndVerify(
                gateway, config, beforeFingerprint, logger);
        if (!verified) {
            transaction.unknown("verification_failed", repostStateDetail(verifiedState));
            throw new Blocked("share_result_unknown", "转发结果不明确，已停止且不会重试");
        }
        if (!closed) {
            transaction.unknown("verification_sheet_close_failed", repostStateDetail(verifiedState));
            throw new Blocked("share_result_unknown",
                    "已看到转发成功状态，但关闭面板后无法确认仍是原视频");
        }
        JSONObject evidence = repostStateDetail(verifiedState)
                .put("mode", "repost").put("verified", true);
        transaction.verified(evidence);
        transaction.committed(evidence);
        counters.shares++;
        logger.event("share", evidence);
        return "reposted";
    }

    private boolean closeShareSheetAndVerify(AccessibilityGateway gateway,
                                             WarmupConfig config,
                                             String expectedFingerprint,
                                             RunLogger logger) throws Exception {
        if (!gateway.pressBack()) return false;
        waitCooperative(650);
        PageState closed = inspectStable(gateway, config);
        UiSnapshot snapshot = gateway.snapshot();
        String observedFingerprint = VideoContent.from(snapshot).fingerprint();
        boolean verified = closed.page == PageState.Page.FEED
                && !expectedFingerprint.isEmpty()
                && expectedFingerprint.equals(observedFingerprint);
        logger.event("share_sheet_closed", new JSONObject()
                .put("verified", verified).put("page", closed.page.name())
                .put("same_video", expectedFingerprint.equals(observedFingerprint)));
        return verified;
    }

    private static JSONObject repostStateDetail(RepostSheetState.Result state) throws Exception {
        return new JSONObject().put("state", state.state.name())
                .put("evidence", new JSONArray(state.evidence));
    }

    private ActionLedger.Transaction beginAction(RunLogger logger, String action,
                                                  String target, String payload,
                                                  JSONObject evidence) throws Exception {
        ActionLedger.BeginResult begin = logger.actions().begin(
                action, target, payload, evidence);
        if (begin.allowed()) return begin.transaction;
        if (begin.disposition == ActionLedger.BeginDisposition.DUPLICATE_COMMITTED) {
            logDuplicate(logger, action, begin);
            return null;
        }
        throw duplicateUncertain(action, begin);
    }

    private static void logDuplicate(RunLogger logger, String action,
                                     ActionLedger.BeginResult begin) throws Exception {
        logger.event("action_deduplicated", new JSONObject()
                .put("action", action)
                .put("prior_state", begin.priorState == null
                        ? "unknown" : begin.priorState.name().toLowerCase(Locale.ROOT)));
    }

    private static Blocked duplicateUncertain(String action, ActionLedger.BeginResult begin) {
        String prior = begin.priorState == null
                ? "unknown" : begin.priorState.name().toLowerCase(Locale.ROOT);
        return new Blocked(action + "_duplicate_uncertain",
                action + "此前已进入“" + prior + "”状态，为避免重复操作已停止");
    }

    private static String skipUnstableIdentity(RunLogger logger, String action,
                                               VideoContent content) throws Exception {
        logger.event("action_skipped", new JSONObject()
                .put("action", action)
                .put("reason", "identity_unresolved")
                .put("author_available", !content.author.trim().isEmpty())
                .put("description_available", !content.description.trim().isEmpty()));
        return "identity_unresolved";
    }

    private void swipeToNext(AccessibilityGateway gateway, WarmupConfig config,
                             RunLogger logger, Random random, String source) throws Exception {
        ensureExpected(gateway, PageState.Page.FEED, config, logger);
        ensureVideoContext(gateway, source, logger);
        String previous = VideoContent.from(gateway.snapshot()).fingerprint();
        for (int attempt = 1; attempt <= 3; attempt++) {
            PageTurnProfile profile = PageTurnProfile.forAttempt(
                    attempt, config.swipePace, config.swipeRandomizationEnabled, random);
            boolean dispatched = gateway.swipeUp(
                    profile.durationMs, profile.startX, profile.endX,
                    profile.startY, profile.endY);
            if (!dispatched) {
                logger.event("page_turn_attempt", new JSONObject()
                        .put("attempt", attempt).put("profile", profile.name)
                        .put("duration_ms", profile.durationMs)
                        .put("start_x", profile.startX).put("end_x", profile.endX)
                        .put("start_y", profile.startY).put("end_y", profile.endY)
                        .put("randomized", profile.randomized)
                        .put("dispatched", false));
                continue;
            }
            waitCooperative(sample(random, 900, 1600));
            ensureExpected(gateway, PageState.Page.FEED, config, logger);
            ensureVideoContext(gateway, source, logger);
            String next = VideoContent.from(gateway.snapshot()).fingerprint();
            boolean changed = !next.isEmpty() && !next.equals(previous);
            logger.event("page_turn_attempt", new JSONObject()
                    .put("attempt", attempt).put("profile", profile.name)
                    .put("duration_ms", profile.durationMs)
                    .put("start_x", profile.startX).put("end_x", profile.endX)
                    .put("start_y", profile.startY).put("end_y", profile.endY)
                    .put("randomized", profile.randomized)
                    .put("dispatched", true).put("identity_changed", changed));
            if (changed) {
                logger.event("next_video", new JSONObject()
                        .put("attempt", attempt).put("profile", profile.name)
                        .put("duration_ms", profile.durationMs)
                        .put("start_x", profile.startX).put("end_x", profile.endX)
                        .put("start_y", profile.startY).put("end_y", profile.endY)
                        .put("randomized", profile.randomized));
                return;
            }
        }
        throw new Blocked("next_video_unverified", "连续三次未能确认已切换视频");
    }

    private void returnToRecommendationHome(AccessibilityGateway gateway, WarmupConfig config,
                                            RunLogger logger) throws Exception {
        PageState initial = inspectStable(gateway, config);
        int maximumActions = Math.max(6, config.maxRecoveryActions + 3);
        int actions = 0;
        long contractDeadline = now() + 6_000L;
        boolean waitLogged = false;
        while (actions <= maximumActions) {
            checkControl();
            PageState state = inspectStable(gateway, config);
            if (!state.blockers.isEmpty()) {
                throw new Blocked(state.blockers.get(0), "页面阻断：" + state.blockers);
            }
            if (PageStability.isTransientUnknown(state) && now() < contractDeadline) {
                if (!waitLogged) {
                    logger.event("return_home_contract_wait", new JSONObject()
                            .put("evidence", new JSONArray(state.evidence)));
                    waitLogged = true;
                }
                waitCooperative(350);
                continue;
            }
            if (hasRecoverableInterruption(state, PageState.Page.FEED)
                    && config.autoRecover && actions < maximumActions
                    && recoverOne(gateway, state, PageState.Page.FEED)) {
                actions++;
                logger.event("return_home_step", new JSONObject()
                        .put("page", state.page.name()).put("action", "recover_interruption")
                        .put("attempt", actions));
                contractDeadline = now() + 6_000L;
                waitLogged = false;
                waitCooperative(700);
                continue;
            }

            VideoSurfaceContext.Result context = state.page == PageState.Page.FEED
                    ? inspectVideoContextStable(gateway)
                    : VideoSurfaceContext.analyze(gateway.snapshot());
            if (state.page == PageState.Page.FEED
                    && context.kind == VideoSurfaceContext.Kind.RECOMMENDATION_HOME) {
                requireTransition(initial.page, state, PageTransitionPolicy.Action.RETURN_HOME, logger);
                logger.event("recommendation_home_verified", contextJson(context));
                return;
            }
            if (state.page == PageState.Page.FEED
                    && context.kind == VideoSurfaceContext.Kind.UNKNOWN) {
                logger.event("video_context_rejected", contextJson(context)
                        .put("expected", VideoSurfaceContext.Kind.RECOMMENDATION_HOME.name()));
                throw new Blocked("recommendation_context_unknown",
                        "当前是视频播放器，但无法确认已回到推荐首页");
            }
            if (actions >= maximumActions) break;
            if (!gateway.targetInteractionAllowed()) {
                throw new Blocked("tiktok_not_foreground",
                        "TikTok 不是可信前台，未执行恢复手势");
            }

            // Search-result descriptions can contain ordinary words such as "HOME". A generic
            // text/description click would therefore risk opening another result. Back navigation
            // is local and reversible; the recommendation contract below is the success proof.
            boolean navigated = gateway.pressBack();
            actions++;
            String action = "press_back";
            logger.event("return_home_step", contextJson(context)
                    .put("page", state.page.name()).put("action", action)
                    .put("dispatched", navigated).put("attempt", actions));
            if (!navigated) break;
            contractDeadline = now() + 6_000L;
            waitLogged = false;
            waitCooperative(750);
        }
        throw new Blocked("return_feed_failed", "无法在有限次数内回到已验证的推荐首页");
    }

    private void ensureVideoContext(AccessibilityGateway gateway, String source,
                                    RunLogger logger) throws Exception {
        VideoSurfaceContext.Kind expected = "search".equals(source)
                ? VideoSurfaceContext.Kind.SEARCH_DETAIL
                : VideoSurfaceContext.Kind.RECOMMENDATION_HOME;
        long contractDeadline = now() + 6_000L;
        boolean waitLogged = false;
        VideoSurfaceContext.Result observed;
        while (true) {
            observed = inspectVideoContextStable(gateway);
            if (observed.kind == expected) {
                logger.event("video_context_verified", contextJson(observed)
                        .put("source", source).put("expected", expected.name()));
                return;
            }
            if (observed.kind != VideoSurfaceContext.Kind.UNKNOWN
                    || now() >= contractDeadline) break;

            UiSnapshot foreground = gateway.snapshot();
            if (foreground.foregroundDecision.confirmsOtherApp(TIKTOK)) {
                throw new Blocked("tiktok_not_foreground",
                        "TikTok 已失去前台，未执行任何恢复手势");
            }
            if (!waitLogged) {
                logger.event("video_context_wait", contextJson(observed)
                        .put("source", source).put("expected", expected.name())
                        .put("foreground", foreground.foregroundDecision.toJson()));
                waitLogged = true;
            }
            waitCooperative(350);
        }
        logger.event("video_context_rejected", contextJson(observed)
                .put("source", source).put("expected", expected.name()));
        throw new Blocked("video_context_mismatch",
                "视频来源上下文不匹配：预期 " + expected + "，实际 " + observed.kind);
    }

    private VideoSurfaceContext.Result inspectVideoContextStable(AccessibilityGateway gateway)
            throws UserStopped {
        VideoSurfaceContext.Result first = VideoSurfaceContext.analyze(gateway.snapshot());
        waitCooperative(320);
        VideoSurfaceContext.Result second = VideoSurfaceContext.analyze(gateway.snapshot());
        return VideoSurfaceContext.consecutive(first, second);
    }

    private static JSONObject contextJson(VideoSurfaceContext.Result context) throws Exception {
        return new JSONObject().put("kind", context.kind.name())
                .put("confidence", context.confidence)
                .put("evidence", new JSONArray(context.evidence));
    }

    /**
     * A warm TikTok task usually resumes in under two seconds, while a cold process can take much
     * longer. Poll the actual foreground package and issue only one bounded relaunch instead of
     * treating a slow cold start as an immediate blocker.
     */
    private void waitForTikTokForeground(AccessibilityGateway gateway, RunLogger logger) throws Exception {
        for (int attempt = 0; attempt < 14; attempt++) {
            checkControl();
            UiSnapshot snapshot = gateway.snapshot();
            if (snapshot.foregroundDecision.confirmsTarget(PageAnalyzer.TIKTOK_PACKAGE)) {
                logger.event("tiktok_foreground", new JSONObject()
                        .put("attempt", attempt + 1)
                        .put("decision", snapshot.foregroundDecision.toJson()));
                return;
            }
            if (attempt == 6) {
                boolean relaunched = gateway.launchTikTok();
                logger.event("tiktok_relaunch", new JSONObject().put("success", relaunched));
            }
            waitCooperative(700);
        }
        captureBlocker(logger, "tiktok_launch_timeout", "cold_start_timeout");
        throw new Blocked("tiktok_launch_timeout", "TikTok 冷启动超时，请确认系统没有拦截后台启动");
    }

    /**
     * Opening recovery is deliberately more tolerant than mid-run recovery. TikTok can resume a
     * saved stack such as comments -> search results -> search input, which legitimately needs
     * more than three bounded back actions. Once work starts, switching away remains a blocker.
     */
    private void prepareOpeningFeed(AccessibilityGateway gateway, WarmupConfig config,
                                    RunLogger logger) throws Exception {
        int maximumActions = Math.max(6, config.maxRecoveryActions + 3);
        int actions = 0;
        long stabilityDeadline = now() + 18_000L;
        while (actions <= maximumActions && now() < stabilityDeadline) {
            checkControl();
            PageState state = inspectStable(gateway, config);
            if (state.blockers.contains("tiktok_not_foreground")) {
                if (actions >= maximumActions || !gateway.launchTikTok()) {
                    throw new Blocked("tiktok_not_foreground", "TikTok 未保持在前台");
                }
                actions++;
                logger.event("opening_recovery", new JSONObject()
                        .put("from", state.page.name()).put("action", "relaunch").put("attempt", actions));
                waitCooperative(900);
                continue;
            }
            if (!state.blockers.isEmpty()) {
                throw new Blocked(state.blockers.get(0), "页面阻断：" + state.blockers);
            }
            if (state.page == PageState.Page.FEED && !hasRecoverableInterruption(state, PageState.Page.FEED)) return;
            if (state.page == PageState.Page.UNKNOWN && now() < stabilityDeadline) {
                // Animation duration varies significantly across OEMs and cold/warm TikTok
                // processes. A wall-clock deadline is deterministic while a fixed snapshot count
                // can expire just before the page becomes stable.
                waitCooperative(650);
                continue;
            }
            if (actions < maximumActions && config.autoRecover
                    && recoverOne(gateway, state, PageState.Page.FEED)) {
                actions++;
                logger.event("opening_recovery", new JSONObject()
                        .put("from", state.page.name()).put("action", "bounded_recovery")
                        .put("attempt", actions));
                waitCooperative(700);
                continue;
            }
            captureBlocker(logger, "opening_page_unresolved", state.page.name());
            throw new Blocked("opening_page_unresolved", "启动后无法回到推荐流：" + state.page);
        }
        captureBlocker(logger, "opening_stability_timeout", "opening_deadline_exhausted");
        throw new Blocked("opening_stability_timeout", "启动页面在限定时间内未形成稳定页面契约");
    }

    private void ensureExpected(AccessibilityGateway gateway, PageState.Page expected,
                                WarmupConfig config, RunLogger logger) throws Exception {
        int recoveryActions = 0;
        long contractDeadline = now() + 6_000L;
        boolean waitLogged = false;
        while (recoveryActions <= config.maxRecoveryActions) {
            checkControl();
            if (!WarmupAccessibilityService.isConnected()) throw new Blocked("accessibility_disconnected", "无障碍服务已断开");
            PageState state = inspectStable(gateway, config);
            if (!state.blockers.isEmpty()) throw new Blocked(state.blockers.get(0), "页面阻断：" + state.blockers);
            boolean interruptionNeedsRecovery = hasRecoverableInterruption(state, expected);
            if (state.page == expected && !interruptionNeedsRecovery) return;
            if (PageStability.isTransientUnknown(state) && now() < contractDeadline) {
                if (!waitLogged) {
                    logger.event("page_contract_wait", new JSONObject()
                            .put("expected", expected.name())
                            .put("evidence", new JSONArray(state.evidence)));
                    waitLogged = true;
                }
                waitCooperative(350);
                continue;
            }
            if (config.autoRecover && recoveryActions < config.maxRecoveryActions
                    && recoverOne(gateway, state, expected)) {
                recoveryActions++;
                logger.event("recovery", new JSONObject().put("from", state.page.name())
                        .put("attempt", recoveryActions));
                contractDeadline = now() + 6_000L;
                waitLogged = false;
                waitCooperative(600);
                continue;
            }
            captureBlocker(logger, "unexpected_page", state.page.name());
            throw new Blocked("unexpected_page", "预期 " + expected + "，实际 " + state.page);
        }
    }

    private boolean hasRecoverableInterruption(PageState state, PageState.Page expected) {
        return state.interruptions.contains("contacts_sync_prompt")
                || state.interruptions.contains("system_input_method_dialog")
                || state.interruptions.contains("collections_popup")
                || state.interruptions.contains("follow_friends_popup")
                || state.interruptions.contains("link_email_popup")
                || state.interruptions.contains("notification_prompt")
                || state.interruptions.contains("promo_banner")
                || state.interruptions.contains("notification_banner")
                || state.interruptions.contains("generic_dismiss_popup")
                || state.interruptions.contains("got_it_popup")
                || state.interruptions.contains("interest_survey")
                || state.interruptions.contains("video_options_sheet")
                || (state.interruptions.contains("suggestion_page") && expected == PageState.Page.FEED)
                || (state.page == PageState.Page.COMMENTS && expected != PageState.Page.COMMENTS);
    }

    /**
     * Recover only known reversible interruptions. Never auto-accept permissions, captcha,
     * login, age/privacy consent, or unknown system dialogs (those are blockers).
     * CTA lists align with soft-dismiss labels mined from TikTok 46.1.3 string resources.
     */
    private boolean recoverOne(AccessibilityGateway gateway, PageState state, PageState.Page expected) {
        if (state.interruptions.contains("contacts_sync_prompt")) {
            return gateway.tapText("不允许", "Don't allow", "Don’t allow", "Not now", "以后再说", "暂不")
                    || dismissSoftPopup(gateway);
        }
        if (state.interruptions.contains("system_input_method_dialog")
                || state.interruptions.contains("video_options_sheet")
                || (state.page == PageState.Page.COMMENTS && expected != PageState.Page.COMMENTS)) {
            return gateway.pressBack();
        }
        if (state.interruptions.contains("notification_banner")) {
            // In-app message toast / top banner: swipe it away, then fall back to back.
            return gateway.swipeUp(180, 0) || gateway.pressBack();
        }
        if (state.interruptions.contains("got_it_popup")) {
            return gateway.clickText("我知道了", "知道了", "Got it")
                    || gateway.tapText("我知道了", "知道了", "Got it")
                    || dismissSoftPopup(gateway);
        }
        if (state.interruptions.contains("interest_survey")) {
            return gateway.clickText("跳过", "Skip", "以后再说", "Not now", "Maybe later")
                    || gateway.tapText("跳过", "Skip", "以后再说", "Not now")
                    || gateway.pressBack();
        }
        if (state.interruptions.contains("collections_popup")
                || state.interruptions.contains("follow_friends_popup")
                || state.interruptions.contains("link_email_popup")
                || state.interruptions.contains("notification_prompt")
                || state.interruptions.contains("promo_banner")
                || state.interruptions.contains("generic_dismiss_popup")) {
            return dismissSoftPopup(gateway);
        }
        if (state.interruptions.contains("suggestion_page") && expected == PageState.Page.FEED) {
            return gateway.swipeUp(350, 0);
        }
        if (expected == PageState.Page.FEED && (state.page == PageState.Page.SEARCH_INPUT
                || state.page == PageState.Page.SEARCH_RESULTS || state.page == PageState.Page.SEARCH_EMPTY
                || state.page == PageState.Page.INBOX || state.page == PageState.Page.PROFILE
                || state.page == PageState.Page.SHOP || state.page == PageState.Page.SETTINGS)) {
            return gateway.clickDescriptionContains("首页", "Home") || gateway.pressBack();
        }
        return false;
    }

    /** Soft-ask secondary buttons only — never primary accept/allow/continue. */
    private boolean dismissSoftPopup(AccessibilityGateway gateway) {
        return gateway.clickText(
                "以后再说", "下次再说", "稍后再说", "暂不", "先不了", "不用了", "跳过",
                "Not now", "Maybe later", "No thanks", "No, thanks", "Dismiss", "Skip")
                || gateway.tapText(
                "以后再说", "下次再说", "稍后再说", "暂不", "先不了", "不用了", "跳过",
                "Not now", "Maybe later", "No thanks", "No, thanks", "Dismiss", "Skip")
                || gateway.clickDescriptionContains("关闭", "Close")
                || gateway.pressBack();
    }

    private PageState inspect(AccessibilityGateway gateway, WarmupConfig config) {
        return PageAnalyzer.analyze(gateway.snapshot(), !config.requireNetwork || gateway.networkAvailable());
    }

    private PageState inspectStable(AccessibilityGateway gateway, WarmupConfig config) throws UserStopped {
        PageState first = inspect(gateway, config);
        waitCooperative(320);
        PageState second = inspect(gateway, config);
        return PageStability.consecutive(first, second);
    }

    private PageState awaitTransition(AccessibilityGateway gateway, WarmupConfig config,
                                      RunLogger logger, PageState.Page from,
                                      PageTransitionPolicy.Action action) throws Exception {
        PageState observed = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            observed = inspectStable(gateway, config);
            if (!observed.blockers.isEmpty()) {
                throw new Blocked(observed.blockers.get(0), "页面阻断：" + observed.blockers);
            }
            if (observed.page != PageState.Page.UNKNOWN) {
                requireTransition(from, observed, action, logger);
                return observed;
            }
            if (attempt < 3) waitCooperative(450);
        }
        logger.event("transition_unstable", new JSONObject()
                .put("from", from.name()).put("action", action.name())
                .put("evidence", new JSONArray(observed == null
                        ? Collections.emptyList() : observed.evidence)));
        throw new Blocked("page_unstable", "页面连续采样不稳定，已停止操作");
    }

    private void requireTransition(PageState.Page from, PageState observed,
                                   PageTransitionPolicy.Action action,
                                   RunLogger logger) throws Exception {
        if (PageTransitionPolicy.allows(from, observed.page, action)) {
            logger.event("transition_verified", new JSONObject()
                    .put("from", from.name()).put("to", observed.page.name())
                    .put("action", action.name()).put("confidence", observed.confidence)
                    .put("evidence", new JSONArray(observed.evidence)));
            return;
        }
        logger.event("transition_rejected", new JSONObject()
                .put("from", from.name()).put("to", observed.page.name())
                .put("action", action.name()).put("confidence", observed.confidence)
                .put("evidence", new JSONArray(observed.evidence)));
        throw new Blocked("unexpected_transition", "动作 " + action + " 后出现非预期页面："
                + from + " → " + observed.page);
    }

    private void captureBlocker(RunLogger logger, String code, String message) {
        try {
            AccessibilityGateway gateway = AccessibilityGateway.require();
            UiSnapshot snapshot = gateway.snapshot();
            write(logger.file("blocker-nodes.json"), snapshot.toJson().toString(2));
            gateway.saveScreenshot(logger.file("blocker-screen.png"));
            logger.event("blocked", new JSONObject().put("code", code).put("message", message));
        } catch (Throwable ignored) {}
    }

    private void captureDiagnostic(RunLogger logger, String stem) {
        try {
            AccessibilityGateway gateway = AccessibilityGateway.require();
            write(logger.file(stem + "-nodes.json"), gateway.snapshot().toJson().toString(2));
            gateway.saveScreenshot(logger.file(stem + "-screen.png"));
        } catch (Throwable ignored) {}
    }

    private static JSONObject nodeDetail(UiNode node) throws Exception {
        if (node == null) return new JSONObject().put("resolved", false);
        return new JSONObject().put("resolved", true)
                .put("id", node.id).put("class", node.className)
                .put("window_id", node.windowId).put("tree_path", node.treePath)
                .put("clickable", node.clickable).put("visible_to_user", node.visibleToUser)
                .put("bounds", new JSONObject().put("left", node.bounds.left)
                        .put("top", node.bounds.top).put("right", node.bounds.right)
                        .put("bottom", node.bounds.bottom));
    }

    private void publish(RunSnapshot.Status status, String phase, String step, String message) {
        RunBudget activeBudget = budget;
        long elapsed = activeBudget == null ? 0 : activeBudget.elapsedMs(now());
        int videos = activeBudget == null ? current.videosSeen : activeBudget.videosSeen();
        int limit = activeBudget == null ? current.videoLimit : activeBudget.maximumVideos();
        current = new RunSnapshot(status, phase, step, videos, limit,
                current.likes, current.favorites, current.comments, elapsed, message == null ? "" : message);
        Listener active = listener;
        if (active != null) active.onSnapshot(current);
    }

    private void waitCooperative(long milliseconds) throws UserStopped {
        long remaining = Math.max(0, milliseconds);
        while (remaining > 0) {
            checkControl();
            long slice = Math.min(200, remaining);
            SystemClock.sleep(slice);
            remaining -= slice;
        }
    }

    private void checkControl() throws UserStopped {
        synchronized (control) {
            while (paused && !stopRequested) {
                try { control.wait(500); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new UserStopped(); }
            }
            if (stopRequested) throw new UserStopped();
        }
    }

    /**
     * Pause only after a complete video transaction has been logged and while the engine owns a
     * stable video surface. This prevents opening the control UI from suspending a half-finished
     * comments/search transition or an external write verification.
     */
    private void safeCheckpoint() throws UserStopped {
        boolean newlyPaused = false;
        synchronized (control) {
            if (pauseRequested && !paused && !stopRequested) {
                pauseRequested = false;
                paused = true;
                if (budget != null) budget.pause(SystemClock.elapsedRealtime());
                newlyPaused = true;
            }
        }
        if (newlyPaused) {
            publish(RunSnapshot.Status.PAUSED, current.phase,
                    "已安全暂停", "当前视频已完整落盘");
        }
        checkControl();
    }

    private static void collectComments(UiSnapshot snapshot, Set<String> output) {
        ControlResolver.Match field = ControlResolver.resolve(
                snapshot, ControlResolver.Role.COMMENT_FIELD);
        UiNode header = snapshot.visibleByIdSuffix(":id/veb",
                snapshot.displayWidth, snapshot.displayHeight);
        if (header == null) header = snapshot.visibleByIdSuffix(":id/vcu",
                snapshot.displayWidth, snapshot.displayHeight);
        int top = header == null ? (int) (snapshot.displayHeight * 0.32f) : header.bounds.bottom;
        int bottom = field == null ? (int) (snapshot.displayHeight * 0.92f) : field.node.bounds.top;
        for (UiNode node : snapshot.nodes) {
            if (!snapshot.isVisible(node) || node.text.trim().length() < 2
                    || centerY(node) <= top || centerY(node) >= bottom) continue;
            boolean knownCommentText = node.id.endsWith(":id/eu1") || node.id.endsWith(":id/euj");
            boolean textShape = (node.className.contains("Text") || node.className.startsWith("X."))
                    && node.bounds.right - node.bounds.left > snapshot.displayWidth * 0.30f
                    && !node.clickable;
            if (knownCommentText || textShape) output.add(node.text);
        }
    }

    private static int centerY(UiNode node) {
        return node.bounds.top + (node.bounds.bottom - node.bounds.top) / 2;
    }

    private static void clearResolvedInput(AccessibilityGateway gateway,
                                           ControlResolver.Role role) {
        ControlResolver.Match input = ControlResolver.resolve(gateway.snapshot(), role);
        if (input != null) gateway.setTextOnResolvedInput(input.node, "");
    }

    private static boolean isLikeSelected(UiNode node) {
        String description = node.description.toLowerCase(Locale.ROOT);
        return node.description.startsWith("点赞的视频") || description.startsWith("liked video");
    }

    private static boolean plan(Random random, double rate, int used, int maximum) {
        return rate > 0 && used < maximum && random.nextDouble() < rate;
    }

    private static boolean chance(Random random, double rate) {
        if (rate <= 0) return false;
        if (rate >= 1) return true;
        return random.nextDouble() < rate;
    }

    private static int sample(Random random, int minimum, int maximum) {
        if (maximum <= minimum) return minimum;
        return minimum + random.nextInt(maximum - minimum + 1);
    }

    private static long swipeDuration(Random random, String pace) {
        if ("slow".equals(pace)) return sample(random, 650, 1050);
        if ("fast".equals(pace)) return sample(random, 180, 360);
        return sample(random, 300, 850);
    }

    private static float jitter(Random random) { return (random.nextFloat() - 0.5f) * 0.10f; }
    private static long now() { return SystemClock.elapsedRealtime(); }
    private static String phase(String source) { return "search".equals(source) ? "兴趣搜索段" : "推荐流主段"; }

    private static List<String> clean(List<String> input) {
        List<String> result = new ArrayList<>();
        for (String value : input) {
            String normalized = value == null ? "" : value.trim();
            if (!normalized.isEmpty() && !result.contains(normalized)) result.add(normalized);
        }
        return result;
    }

    static void mergeCommentInterestSignals(List<String> matched, List<String> comments,
                                            List<String> keywords) {
        if (comments == null || comments.isEmpty() || keywords == null) return;
        String haystack = String.join(" ", comments);
        for (String keyword : keywords) {
            String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
            if (KeywordMatcher.matches(haystack, normalized)
                    && !matched.contains(normalized)) {
                matched.add(normalized);
            }
        }
    }

    private static void write(File file, String value) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write((value + "\n").getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
    }

    private static final class ProcessedVideo {
        final boolean relevant;
        final List<String> matchedKeywords;

        ProcessedVideo(boolean relevant, List<String> matchedKeywords) {
            this.relevant = relevant;
            this.matchedKeywords = new ArrayList<>(matchedKeywords);
        }
    }

    private static final class Counters {
        int likes;
        int favorites;
        int comments;
        int follows;
        int shares;
        int notInterested;
        int wouldLikes;
        int wouldFavorites;
        int wouldComments;
        int wouldNotInterested;
    }

    private static final class Blocked extends Exception {
        final String code;
        Blocked(String code, String message) { super(message); this.code = code; }
    }

    private static final class UserStopped extends Exception {}
}
