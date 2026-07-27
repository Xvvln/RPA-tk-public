package com.xwell.rpatt.engine;

import android.content.Context;
import android.os.SystemClock;

import com.xwell.rpatt.BuildConfig;
import com.xwell.rpatt.automation.AccessibilityGateway;
import com.xwell.rpatt.automation.ControlResolver;
import com.xwell.rpatt.automation.FavoriteVisualEvidence;
import com.xwell.rpatt.automation.InterruptPolicy;
import com.xwell.rpatt.automation.KeywordMatcher;
import com.xwell.rpatt.automation.PageAnalyzer;
import com.xwell.rpatt.automation.TikTokPermissionProbe;
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
            publish(RunSnapshot.Status.RUNNING, "开场检查", "环境预检", "");
            logger = new RunLogger(context);
            logger.writeConfig(config.toJson());
            logger.event("preflight_started", new JSONObject().put("dry_run", !config.interactionsApply));
            logger.event("opening_check_started", new JSONObject().put("dry_run", !config.interactionsApply));

            boolean accessibilityOk = WarmupAccessibilityService.isConnected();
            AccessibilityGateway gateway = null;
            boolean screenOk = false;
            boolean unlocked = false;
            boolean networkOk = !config.requireNetwork;
            boolean launchOk = false;
            boolean overlayOk = false;
            if (accessibilityOk) {
                gateway = AccessibilityGateway.require();
                screenOk = gateway.screenInteractive();
                unlocked = !gateway.deviceLocked();
                networkOk = !config.requireNetwork || gateway.networkAvailable();
                overlayOk = gateway.overlayAllowed();
                launchOk = gateway.launchTikTok();
            }
            TikTokPermissionProbe.Report tiktokPermissions = TikTokPermissionProbe.probe(context);
            logger.event("tiktok_permissions", tiktokPermissions.toJson());
            PreflightReport preflight = PreflightReport.evaluateEnvironment(
                    accessibilityOk,
                    screenOk,
                    !unlocked && accessibilityOk,
                    config.requireNetwork,
                    networkOk,
                    launchOk,
                    overlayOk,
                    true,
                    tiktokPermissions);
            logger.event(preflight.passed() ? "preflight_passed" : "preflight_failed", preflight.toJson());
            PreflightReport.Check hard = preflight.firstHardFailure();
            if (hard != null) {
                throw new Blocked(hard.code.isEmpty() ? "preflight_failed" : hard.code, hard.message);
            }
            if (gateway == null) throw new Blocked("accessibility_disabled", "无障碍服务未开启");
            waitForTikTokForeground(gateway, logger);
            logger.event("preflight_check", new JSONObject()
                    .put("id", "P06")
                    .put("severity", "hard")
                    .put("ok", true)
                    .put("code", "")
                    .put("message", "TikTok 已在前台"));

            logger.event("run_started", new JSONObject().put("dry_run", !config.interactionsApply));
            budget = new RunBudget(SystemClock.elapsedRealtime(), config.maxMinutes * 60_000L, config.videoCount);

            publish(RunSnapshot.Status.RUNNING, "开场检查", "识别可浏览页", "");
            prepareOpeningFeed(gateway, config, logger);
            returnToRecommendationHome(gateway, config, logger);
            logger.event("preflight_check", new JSONObject()
                    .put("id", "P10")
                    .put("severity", "hard")
                    .put("ok", true)
                    .put("code", "")
                    .put("message", "开场已回到可浏览推荐流"));
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
            watchedState = inspect(gateway, config);
        }
        VideoContent content = VideoContent.from(watched);
        // Live a11y often omits profile/description contracts VideoContent expects. Detect live
        // before content_unreadable so skip_live can slide past without stopping the run.
        boolean liveFeed = "live".equals(watchedState.contentKind)
                || PageAnalyzer.isLiveFeedSnapshot(watched);
        if (config.skipLive && liveFeed) {
            int index = budget.videosSeen() + 1;
            String author = content.author.isEmpty() ? "live" : content.author;
            JSONObject liveRecord = content.toJson()
                    .put("author", author)
                    .put("index", index)
                    .put("source", source)
                    .put("search_keyword", searchKeyword)
                    .put("content_kind", "live")
                    .put("watch_ms", watchMs)
                    .put("watch_base_ms", baseWatchMs)
                    .put("watch_interest_extra_ms", watchExtraMs)
                    .put("relevant", false)
                    .put("matched_keywords", new JSONArray())
                    .put("matched_not_interested_keywords", new JSONArray())
                    .put("comments", new JSONArray())
                    .put("feedback", "live_skipped")
                    .put("like_action", "skipped_live")
                    .put("favorite_action", "skipped_live")
                    .put("comment_action", "skipped_live")
                    .put("comment_text", "")
                    .put("follow_action", "skipped_live")
                    .put("share_action", "skipped_live")
                    .put("recorded_at", System.currentTimeMillis());
            logger.video(liveRecord);
            logger.event("live_feed_skipped", new JSONObject()
                    .put("index", index)
                    .put("source", source)
                    .put("author", author)
                    .put("content_kind_state", watchedState.contentKind)
                    .put("skip_live", true));
            budget.recordVideo();
            current = new RunSnapshot(current.status, current.phase, current.step,
                    budget.videosSeen(), budget.maximumVideos(),
                    counters.likes + counters.wouldLikes,
                    counters.favorites + counters.wouldFavorites,
                    counters.comments + counters.wouldComments,
                    budget.elapsedMs(now()), current.message);
            publish(RunSnapshot.Status.RUNNING, phase(source), "E · 直播跳过", author);
            return new ProcessedVideo(false, new ArrayList<>());
        }

        if (content.author.isEmpty() && content.description.isEmpty()) {
            // Live with skip_live=false still should not hard-stop; treat as soft-skip one item.
            if (liveFeed) {
                logger.event("live_content_unreadable_soft_skip", new JSONObject()
                        .put("source", source)
                        .put("skip_live_config", config.skipLive));
                budget.recordVideo();
                publish(RunSnapshot.Status.RUNNING, phase(source), "E · 直播无文案跳过", "");
                return new ProcessedVideo(false, new ArrayList<>());
            }
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
        boolean liveSoftMissing = liveFeed
                || "live".equals(watchedState.contentKind)
                || PageAnalyzer.isLiveFeedSnapshot(watched);

        for (String step : config.workflowOrder) {
            checkControl();
            switch (step) {
                case WarmupConfig.STEP_COMMENTS:
                    if (config.openComments
                            && (!config.commentsOnlyIfRelevant || relevant)
                            && chance(random, config.commentReadRate)) {
                        publish(RunSnapshot.Status.RUNNING, phase(source), "评论区浏览", "按工作流执行");
                        try {
                            comments = readComments(gateway, config, logger, random);
                        } catch (Blocked error) {
                            if (!liveSoftMissing) throw error;
                            logger.event("live_comments_unavailable", new JSONObject()
                                    .put("code", error.code)
                                    .put("message", error.getMessage()));
                            comments = new ArrayList<>();
                        }
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
                                        "like", actionTarget, config.interactionsApply, counters,
                                        liveSoftMissing);
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
                                        "favorite", actionTarget, config.interactionsApply, counters,
                                        liveSoftMissing);
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
                                try {
                                    commentAction = interactComment(gateway, config, logger, commentText,
                                            actionTarget, config.interactionsApply, counters);
                                } catch (Blocked error) {
                                    // Write-comment is optional for the session: open failures / UI
                                    // races must not stop the whole run (same spirit as readComments).
                                    if (liveSoftMissing || isSoftCommentFailure(error)) {
                                        commentAction = "skipped_" + error.code;
                                        logger.event("comment_skipped", new JSONObject()
                                                .put("code", error.code)
                                                .put("message", error.getMessage())
                                                .put("live", liveSoftMissing));
                                        gateway.pressBack();
                                        waitCooperative(400);
                                    } else {
                                        throw error;
                                    }
                                }
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
                                try {
                                    followAction = interactFollow(gateway, logger, actionTarget, counters);
                                } catch (Blocked error) {
                                    if (!liveSoftMissing) throw error;
                                    followAction = "unavailable";
                                    logger.event("live_follow_unavailable", new JSONObject()
                                            .put("code", error.code));
                                }
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
                                try {
                                    shareAction = interactShare(
                                            gateway, config, logger, actionTarget, counters);
                                } catch (Blocked error) {
                                    if (!liveSoftMissing) throw error;
                                    shareAction = "unavailable";
                                    logger.event("live_share_unavailable", new JSONObject()
                                            .put("code", error.code));
                                }
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
                                    try {
                                        feedback = applyNotInterested(gateway, logger, actionTarget,
                                                matchedNotInterested, counters);
                                    } catch (Blocked error) {
                                        if (!liveSoftMissing) throw error;
                                        feedback = "unavailable";
                                        logger.event("live_not_interested_unavailable", new JSONObject()
                                                .put("code", error.code));
                                    }
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
        // Short settle then open-contract wait. Empty "抢沙发" panels are COMMENTS once recognized.
        waitCooperative(sample(random, 600, 1200));
        PageState state;
        try {
            state = awaitTransition(gateway, config, logger, PageState.Page.FEED,
                    PageTransitionPolicy.Action.OPEN_COMMENTS);
        } catch (Blocked openError) {
            // Optional browse step: do not kill the whole run when open fails (missed tap / photo UI).
            logger.event("comments_open_failed_soft", new JSONObject()
                    .put("code", openError.code)
                    .put("message", openError.getMessage()));
            gateway.pressBack();
            waitCooperative(400);
            return new ArrayList<>();
        }
        if (state.page != PageState.Page.COMMENTS) {
            logger.event("comments_not_opened_soft", new JSONObject()
                    .put("page", state.page.name())
                    .put("evidence", new JSONArray(state.evidence)));
            gateway.pressBack();
            waitCooperative(400);
            return new ArrayList<>();
        }
        // Optional dwell after open (config range), without blocking if already empty panel.
        waitCooperative(sample(random, config.commentMinSeconds * 1000, config.commentMaxSeconds * 1000));
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
                                  Counters counters, boolean softMissing) throws Exception {
        UiSnapshot actionSnapshot = gateway.snapshot();
        String beforeFingerprint = VideoContent.from(actionSnapshot).fingerprint();
        ControlResolver.Match resolved = ControlResolver.resolve(actionSnapshot, role);
        UiNode before = resolved == null ? null : resolved.node;
        if (before == null) {
            if (softMissing) {
                logger.event(name + "_unavailable", new JSONObject().put("reason", "button_missing"));
                return "unavailable";
            }
            throw new Blocked(name + "_button_missing", "找不到" + name + "按钮");
        }
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
        waitCooperative(900);
        PageState comments;
        try {
            comments = awaitTransition(gateway, config, logger, PageState.Page.FEED,
                    PageTransitionPolicy.Action.OPEN_COMMENTS);
        } catch (Blocked openError) {
            throw new Blocked("comment_composer_not_ready",
                    "评论页打开失败，未尝试输入 · " + openError.getMessage());
        }
        if (comments.page != PageState.Page.COMMENTS) {
            // One more direct re-inspect: empty panels may classify late.
            waitCooperative(500);
            comments = inspect(gateway, config);
        }
        if (comments.page != PageState.Page.COMMENTS) {
            throw new Blocked("comment_composer_not_ready",
                    "评论页尚未稳定，未尝试输入 · " + describePageMismatch(
                            PageState.Page.FEED, comments));
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
        // Sending a comment often leaves the panel open (or mid-animation). Always return to a
        // stable FEED contract so the next workflow step does not hit unexpected_page UNKNOWN.
        boolean closed = false;
        for (int attempt = 0; attempt < 3 && !closed; attempt++) {
            ControlResolver.Match close = ControlResolver.resolve(
                    gateway.snapshot(), ControlResolver.Role.COMMENT_CLOSE);
            if (close != null) {
                gateway.tapResolvedGesture(ControlResolver.Role.COMMENT_CLOSE, close.node);
            } else {
                gateway.pressBack();
            }
            waitCooperative(attempt == 0 ? 700 : 500);
            PageState afterClose = inspect(gateway, config);
            if (afterClose.page == PageState.Page.FEED
                    && !afterClose.interruptions.contains("comments_open")) {
                closed = true;
                break;
            }
            // Partial composer / UNKNOWN: keep pressing back / close.
            if (afterClose.page == PageState.Page.COMMENTS
                    || afterClose.interruptions.contains("comments_open")) {
                logger.event("comment_panel_still_open", new JSONObject()
                        .put("attempt", attempt + 1)
                        .put("page", afterClose.page.name())
                        .put("evidence", new JSONArray(afterClose.evidence)));
            }
        }
        if (!closed) {
            try {
                ensureExpected(gateway, PageState.Page.FEED, config, logger);
                closed = true;
            } catch (Blocked closeError) {
                logger.event("comment_close_unverified", new JSONObject()
                        .put("code", closeError.code)
                        .put("message", closeError.getMessage()));
                // Comment already committed — soft-continue if we can still see video actions.
                PageState soft = inspect(gateway, config);
                if (soft.page == PageState.Page.FEED) closed = true;
            }
        }
        logger.event("comment_closed", new JSONObject().put("closed_to_feed", closed));
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
        waitCooperative(900);
        // First-time repost shows an education sheet ("好的") that covers the feed and breaks
        // fingerprint checks. Dismiss it, then continue verification.
        boolean firstTimeGuide = dismissFirstTimeRepostGuide(gateway, logger);
        waitCooperative(firstTimeGuide ? 700 : 400);
        UiSnapshot afterWrite = gateway.snapshot();
        PageState afterWriteState = PageAnalyzer.analyze(afterWrite, true);
        // Guide may still be mid-dismiss; treat first-time coachmark as non-fatal.
        if (isFirstTimeRepostGuide(afterWrite)) {
            dismissFirstTimeRepostGuide(gateway, logger);
            waitCooperative(700);
            afterWrite = gateway.snapshot();
            afterWriteState = PageAnalyzer.analyze(afterWrite, true);
        }
        String afterFingerprint = VideoContent.from(afterWrite).fingerprint();
        boolean sameVideo = !beforeFingerprint.isEmpty()
                && beforeFingerprint.equals(afterFingerprint);
        boolean onFeed = afterWriteState.page == PageState.Page.FEED;
        if (!onFeed && !firstTimeGuide && !isFirstTimeRepostGuide(afterWrite)) {
            // Share sheet may still be open after repost — not a video change.
            if (!hasShareSheetChrome(afterWrite) && !hasRepostSuccessChrome(afterWrite)) {
                transaction.unknown("underlying_video_changed", new JSONObject()
                        .put("page", afterWriteState.page.name())
                        .put("first_time_guide", firstTimeGuide));
                throw new Blocked("share_result_unknown",
                        "转发后底层视频发生变化，已停止且不会重试");
            }
        }
        // Prefer reopening share sheet to confirm ALREADY_REPOSTED; if first-time guide already
        // confirmed success copy, accept that as verification.
        boolean verifiedByGuide = firstTimeGuide || hasRepostSuccessChrome(afterWrite);
        ControlResolver.Match reopenMatch = ControlResolver.resolve(
                afterWrite, ControlResolver.Role.VIDEO_SHARE);
        UiNode reopen = reopenMatch == null ? null : reopenMatch.node;
        if (reopen == null) {
            // Sheet may still be open; try closing guide/sheet first then find share again.
            gateway.pressBack();
            waitCooperative(500);
            afterWrite = gateway.snapshot();
            reopenMatch = ControlResolver.resolve(afterWrite, ControlResolver.Role.VIDEO_SHARE);
            reopen = reopenMatch == null ? null : reopenMatch.node;
        }
        boolean verified = verifiedByGuide;
        RepostSheetState.Result verifiedState = null;
        if (reopen != null && gateway.tapResolvedGesture(
                ControlResolver.Role.VIDEO_SHARE, reopen)) {
            waitCooperative(800);
            // Guide can reappear when reopening sheet on first success.
            dismissFirstTimeRepostGuide(gateway, logger);
            waitCooperative(400);
            verifiedState = RepostSheetState.analyze(gateway.snapshot());
            verified = verified || verifiedState.state == RepostSheetState.State.ALREADY_REPOSTED;
            if (!verified) captureDiagnostic(logger, "repost-verification-failed");
        } else if (!verified) {
            transaction.unknown("verification_entry_unavailable",
                    reopen == null ? new JSONObject() : nodeDetail(reopen));
            captureDiagnostic(logger, "repost-reopen-failed");
            throw new Blocked("share_result_unknown", "转发后无法复核结果，已停止且不会重试");
        }
        boolean closed = closeShareSheetAndVerify(
                gateway, config, beforeFingerprint, logger);
        // After first-time guide, fingerprint may be weak briefly; if closed back to FEED and
        // repost was verified by sheet or guide, do not hard-stop on same_video miss once.
        if (!closed && verified && (verifiedByGuide || sameVideo)) {
            waitCooperative(500);
            closed = closeShareSheetAndVerify(gateway, config, beforeFingerprint, logger)
                    || inspectStable(gateway, config).page == PageState.Page.FEED;
        }
        if (!verified) {
            transaction.unknown("verification_failed",
                    verifiedState == null ? new JSONObject() : repostStateDetail(verifiedState));
            throw new Blocked("share_result_unknown", "转发结果不明确，已停止且不会重试");
        }
        if (!closed) {
            transaction.unknown("verification_sheet_close_failed",
                    verifiedState == null
                            ? new JSONObject().put("verified_by_guide", verifiedByGuide)
                            : repostStateDetail(verifiedState));
            // Soft-complete: repost almost certainly landed; avoid killing the whole run.
            logger.event("share_close_unverified", new JSONObject()
                    .put("verified_by_guide", verifiedByGuide)
                    .put("same_video", sameVideo));
            gateway.pressBack();
            waitCooperative(400);
        }
        JSONObject evidence = (verifiedState == null
                ? new JSONObject().put("state", "GUIDE_OR_SUCCESS_CHROME")
                : repostStateDetail(verifiedState))
                .put("mode", "repost")
                .put("verified", true)
                .put("first_time_guide", firstTimeGuide)
                .put("verified_by_guide", verifiedByGuide)
                .put("same_video", sameVideo);
        transaction.verified(evidence);
        transaction.committed(evidence);
        counters.shares++;
        logger.event("share", evidence);
        return "reposted";
    }

    /** First-time "通过转发将该作品推荐给其他人" sheet with primary CTA 好的. */
    private boolean dismissFirstTimeRepostGuide(AccessibilityGateway gateway, RunLogger logger)
            throws Exception {
        UiSnapshot snap = gateway.snapshot();
        if (!isFirstTimeRepostGuide(snap)) return false;
        // 好的 is primary-accept blacklisted for generic soft-ask; allowed only here.
        boolean ok = gateway.clickText(InterruptPolicy.FIRST_TIME_REPOST_GUIDE_LABELS)
                || gateway.tapText(InterruptPolicy.FIRST_TIME_REPOST_GUIDE_LABELS);
        logger.event("first_time_repost_guide", new JSONObject()
                .put("dismissed", ok)
                .put("labels", new JSONArray(InterruptPolicy.FIRST_TIME_REPOST_GUIDE_LABELS)));
        if (ok) waitCooperative(500);
        return ok;
    }

    private static boolean isFirstTimeRepostGuide(UiSnapshot snapshot) {
        if (snapshot == null) return false;
        StringBuilder blob = new StringBuilder();
        for (UiNode node : snapshot.nodes) {
            if (!snapshot.isVisible(node)) continue;
            blob.append(node.searchableText()).append(' ');
        }
        String text = blob.toString().toLowerCase(java.util.Locale.ROOT);
        int hits = 0;
        for (String marker : InterruptPolicy.FIRST_TIME_REPOST_GUIDE_MARKERS) {
            if (marker != null && text.contains(marker.toLowerCase(java.util.Locale.ROOT))) hits++;
        }
        boolean hasCta = text.contains("好的") || text.contains("got it");
        return hits >= 1 && hasCta;
    }

    private static boolean hasRepostSuccessChrome(UiSnapshot snapshot) {
        if (snapshot == null) return false;
        for (UiNode node : snapshot.nodes) {
            if (!snapshot.isVisible(node)) continue;
            String t = node.searchableText().toLowerCase(java.util.Locale.ROOT);
            if (t.contains("已转发") || t.contains("reposted") || t.contains("取消转发")
                    || t.contains("remove repost") || t.contains("undo repost")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasShareSheetChrome(UiSnapshot snapshot) {
        if (snapshot == null) return false;
        RepostSheetState.Result sheet = RepostSheetState.analyze(snapshot);
        return sheet.state != RepostSheetState.State.UNKNOWN
                || sheet.createEntry != null
                || sheet.removeEntry != null;
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
        // Opening after cold start needs more wall-clock than mid-run page turns: TikTok often
        // blanks the top tabs / action rail for a few frames while the feed paints.
        long contractDeadline = now() + 14_000L;
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
                            .put("reason", "page_unknown")
                            .put("evidence", new JSONArray(state.evidence)));
                    waitLogged = true;
                }
                waitCooperative(400);
                continue;
            }
            if (hasRecoverableInterruption(state, PageState.Page.FEED)
                    && config.autoRecover && actions < maximumActions) {
                RecoveryStep step = recoverOne(gateway, state, PageState.Page.FEED);
                if (step.success) {
                    actions++;
                    logger.event("return_home_step", new JSONObject()
                            .put("page", state.page.name())
                            .put("interruption_code", step.interruptionCode)
                            .put("action", step.action)
                            .put("attempt", actions)
                            .put("expected", PageState.Page.FEED.name()));
                    contractDeadline = now() + 10_000L;
                    waitLogged = false;
                    waitCooperative(700);
                    continue;
                }
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
            // Do not hard-fail on the first unknown context sample. Wait for the feed chrome to
            // finish painting; only then treat unresolved FEED+UNKNOWN as a blocker.
            if (state.page == PageState.Page.FEED
                    && context.kind == VideoSurfaceContext.Kind.UNKNOWN
                    && now() < contractDeadline) {
                if (!waitLogged) {
                    logger.event("return_home_contract_wait", contextJson(context)
                            .put("reason", "video_context_unknown")
                            .put("expected", VideoSurfaceContext.Kind.RECOMMENDATION_HOME.name()));
                    waitLogged = true;
                }
                waitCooperative(450);
                continue;
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
            contractDeadline = now() + 10_000L;
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
        // Feed paint after swipe / cold open is slower than a mid-video interaction check.
        long contractDeadline = now() + ("search".equals(source) ? 8_000L : 10_000L);
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
            waitCooperative(400);
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
            if (actions < maximumActions && config.autoRecover) {
                RecoveryStep step = recoverOne(gateway, state, PageState.Page.FEED);
                if (step.success) {
                    actions++;
                    logger.event("opening_recovery", new JSONObject()
                            .put("from", state.page.name())
                            .put("interruption_code", step.interruptionCode)
                            .put("action", step.action)
                            .put("attempt", actions)
                            .put("expected", PageState.Page.FEED.name()));
                    waitCooperative(700);
                    continue;
                }
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
        long contractDeadline = now() + 8_000L;
        boolean waitLogged = false;
        while (recoveryActions <= config.maxRecoveryActions) {
            checkControl();
            if (!WarmupAccessibilityService.isConnected()) throw new Blocked("accessibility_disconnected", "无障碍服务已断开");
            PageState state = inspectStable(gateway, config);
            if (!state.blockers.isEmpty()) throw new Blocked(state.blockers.get(0), "页面阻断：" + state.blockers);
            boolean interruptionNeedsRecovery = hasRecoverableInterruption(state, expected);
            if (state.page == expected && !interruptionNeedsRecovery) return;
            boolean emptyWindows = PageStability.isEmptyWindowObservation(state);
            // Empty accessibility windows after pause/notification shade need a longer budget than
            // a normal page-turn animation. Do not promote that gap to unexpected_page.
            long effectiveDeadline = emptyWindows ? Math.max(contractDeadline, now() + 12_000L)
                    : contractDeadline;
            if (emptyWindows) contractDeadline = effectiveDeadline;
            // Pure UNKNOWN: observe briefly (~2.5s of the 8s budget), then recover via
            // unknown_return_feed instead of waiting out the full deadline with 0 recoveries.
            if (PageStability.isTransientUnknown(state) && now() < contractDeadline) {
                if (!waitLogged) {
                    logger.event("page_contract_wait", new JSONObject()
                            .put("expected", expected.name())
                            .put("empty_windows", emptyWindows)
                            .put("evidence", new JSONArray(state.evidence)));
                    waitLogged = true;
                }
                boolean earlyObserve = emptyWindows || (now() + 5_500L < contractDeadline);
                if (earlyObserve) {
                    waitCooperative(emptyWindows ? 500 : 350);
                    continue;
                }
            }
            if (config.autoRecover && recoveryActions < config.maxRecoveryActions
                    && !emptyWindows) {
                RecoveryStep step = recoverOne(gateway, state, expected);
                if (step.success) {
                    recoveryActions++;
                    logger.event("recovery", new JSONObject()
                            .put("from", state.page.name())
                            .put("interruption_code", step.interruptionCode)
                            .put("action", step.action)
                            .put("attempt", recoveryActions)
                            .put("expected", expected.name()));
                    contractDeadline = now() + 8_000L;
                    waitLogged = false;
                    waitCooperative(600);
                    continue;
                }
                // Explicit fallback when recoverOne returns none/failed: still try BACK once.
                if (recoveryActions < config.maxRecoveryActions) {
                    boolean back = gateway.pressBackAlways() || gateway.pressBack();
                    recoveryActions++;
                    logger.event("recovery", new JSONObject()
                            .put("from", state.page.name())
                            .put("interruption_code", "anomaly_press_back_fallback")
                            .put("action", "press_back")
                            .put("attempt", recoveryActions)
                            .put("expected", expected.name())
                            .put("dispatched", back));
                    contractDeadline = now() + 8_000L;
                    waitLogged = false;
                    waitCooperative(600);
                    continue;
                }
            }
            if (emptyWindows || PageStability.isEmptyWindowObservation(state)) {
                String gapDetail = describePageMismatch(expected, state);
                captureBlocker(logger, "accessibility_window_gap", gapDetail);
                throw new Blocked("accessibility_window_gap",
                        "无障碍暂时读不到窗口（常见于暂停/通知栏后），请回到 TikTok 后重试 · " + gapDetail);
            }
            String detail = describePageMismatch(expected, state);
            captureBlocker(logger, "unexpected_page", detail);
            logger.event("unexpected_page_detail", new JSONObject()
                    .put("expected", expected.name())
                    .put("actual", state.page.name())
                    .put("content_kind", state.contentKind)
                    .put("blockers", new JSONArray(state.blockers))
                    .put("interruptions", new JSONArray(state.interruptions))
                    .put("evidence", new JSONArray(state.evidence))
                    .put("recovery_actions_used", recoveryActions)
                    .put("message", detail));
            throw new Blocked("unexpected_page", detail);
        }
    }

    /** Optional comment-write failures that should not abort the whole session. */
    private static boolean isSoftCommentFailure(Blocked error) {
        if (error == null || error.code == null) return false;
        switch (error.code) {
            case "comment_entry_missing":
            case "comment_composer_not_ready":
            case "comment_input_failed":
            case "comment_keyboard_dismiss_failed":
            case "comment_send_missing":
            case "comment_draft_not_stable":
            case "comment_result_unknown":
            case "unexpected_transition":
            case "page_unstable":
                return true;
            default:
                return error.code.startsWith("comment_");
        }
    }

    /** Human-readable page contract failure for UI/log — includes interruptions and top evidence. */
    static String describePageMismatch(PageState.Page expected, PageState state) {
        if (state == null) return "预期 " + expected + "，实际 null";
        StringBuilder sb = new StringBuilder();
        sb.append("预期 ").append(expected.name())
                .append("，实际 ").append(state.page.name());
        if (state.contentKind != null && !state.contentKind.isEmpty()
                && !"none".equals(state.contentKind)) {
            sb.append(" · 内容类型=").append(state.contentKind);
        }
        if (state.blockers != null && !state.blockers.isEmpty()) {
            sb.append(" · 阻断=").append(state.blockers);
        }
        if (state.interruptions != null && !state.interruptions.isEmpty()) {
            sb.append(" · 可恢复中断=").append(state.interruptions);
        }
        if (state.evidence != null && !state.evidence.isEmpty()) {
            int n = Math.min(6, state.evidence.size());
            sb.append(" · 证据=").append(state.evidence.subList(0, n));
        }
        // Common case hints
        if (state.page == PageState.Page.UNKNOWN
                && state.interruptions != null
                && state.interruptions.contains("comments_open")) {
            sb.append(" · 提示=评论面板可能未关闭");
        }
        if (state.page == PageState.Page.COMMENTS && expected == PageState.Page.FEED) {
            sb.append(" · 提示=仍在评论区，应关闭后回视频");
        }
        if (state.page == PageState.Page.UNKNOWN
                && state.evidence != null
                && state.evidence.toString().contains("comments:partial")) {
            sb.append(" · 提示=评论框残留/半开状态");
        }
        return sb.toString();
    }

    private boolean hasRecoverableInterruption(PageState state, PageState.Page expected) {
        return InterruptPolicy.hasRecoverableInterruption(state, expected);
    }

    /**
     * Recover only known reversible interruptions. Never auto-accept permissions, captcha,
     * login, age/privacy consent, or unknown system dialogs without a Deny CTA (those stay blockers).
     * System GrantPermissions with 拒绝/Don't allow is denied automatically and the run continues.
     * CTA lists align with soft-dismiss labels mined from TikTok 46.1.3 string resources.
     */
    private RecoveryStep recoverOne(AccessibilityGateway gateway, PageState state, PageState.Page expected)
            throws Exception {
        String code = InterruptPolicy.primaryRecoverableCode(state, expected);
        if (code == null) {
            // No named interruption: still try BACK for off-contract pages.
            if (expected != null && state != null && state.page != expected
                    && (state.blockers == null || state.blockers.isEmpty())) {
                boolean back = gateway.pressBackAlways() || gateway.pressBack();
                return back
                        ? RecoveryStep.ok("anomaly_press_back", "press_back")
                        : RecoveryStep.failed("anomaly_press_back", "press_back");
            }
            return RecoveryStep.none();
        }
        String action = InterruptPolicy.recoveryActionFor(code);
        boolean ok;
        switch (code) {
            case "system_permission_prompt":
                ok = gateway.denySystemPermissionDialog(
                        InterruptPolicy.filterSoftLabels(InterruptPolicy.SYSTEM_PERMISSION_DENY_LABELS));
                break;
            case "contacts_sync_prompt":
                ok = gateway.denySystemPermissionDialog(
                        InterruptPolicy.filterSoftLabels(InterruptPolicy.CONTACTS_DENY_LABELS))
                        || gateway.tapText(InterruptPolicy.filterSoftLabels(InterruptPolicy.CONTACTS_DENY_LABELS))
                        || dismissSoftPopup(gateway);
                break;
            case "system_input_method_dialog":
            case "video_options_sheet":
            case "comments_open":
            case "anomaly_press_back":
                // Anomalies: always try system BACK first (does not require TikTok focus).
                ok = gateway.pressBackAlways() || gateway.pressBack();
                break;
            case "notification_banner":
                ok = gateway.swipeUp(180, 0) || gateway.pressBack();
                break;
            case "first_time_repost_guide":
                // 好的 is intentionally not in SOFT_DISMISS (primary-looking CTA); allow only here.
                ok = gateway.clickText(InterruptPolicy.FIRST_TIME_REPOST_GUIDE_LABELS)
                        || gateway.tapText(InterruptPolicy.FIRST_TIME_REPOST_GUIDE_LABELS)
                        || gateway.pressBack();
                break;
            case "got_it_popup":
                ok = gateway.clickText(InterruptPolicy.filterSoftLabels(InterruptPolicy.GOT_IT_LABELS))
                        || gateway.tapText(InterruptPolicy.filterSoftLabels(InterruptPolicy.GOT_IT_LABELS))
                        || dismissSoftPopup(gateway);
                break;
            case "interest_survey":
                ok = gateway.clickText(InterruptPolicy.filterSoftLabels(InterruptPolicy.INTEREST_SKIP_LABELS))
                        || gateway.tapText(InterruptPolicy.filterSoftLabels(InterruptPolicy.INTEREST_SKIP_LABELS))
                        || gateway.pressBack();
                break;
            case "collections_popup":
            case "follow_friends_popup":
            case "link_email_popup":
            case "notification_prompt":
            case "promo_banner":
            case "generic_dismiss_popup":
                ok = gateway.denySystemPermissionDialog(
                        InterruptPolicy.filterSoftLabels(InterruptPolicy.SOFT_DISMISS_LABELS))
                        || dismissSoftPopup(gateway);
                break;
            case "suggestion_page":
                ok = gateway.swipeUp(350, 0);
                break;
            case "search_input":
            case "search_results":
            case "search_empty":
            case "inbox":
            case "profile":
            case "shop":
            case "settings":
            case "unknown_return_feed":
                // User policy: on anomaly, try 返回上一页 first, then 首页.
                ok = recoverByBackThenHome(gateway);
                break;
            default:
                // Last resort for any coded interruption we forgot to map: still press BACK.
                ok = gateway.pressBackAlways() || gateway.pressBack();
                break;
        }
        return ok ? RecoveryStep.ok(code, action) : RecoveryStep.failed(code, action);
    }

    /**
     * Preferred anomaly recovery: system BACK (previous page), then home tab if still off-feed.
     */
    private boolean recoverByBackThenHome(AccessibilityGateway gateway) throws Exception {
        boolean back = gateway.pressBackAlways() || gateway.pressBack();
        waitCooperative(450);
        PageState afterBack = PageAnalyzer.analyze(gateway.snapshot(), true);
        if (afterBack.page == PageState.Page.FEED
                && (afterBack.interruptions == null
                || !afterBack.interruptions.contains("comments_open"))) {
            return true;
        }
        boolean home = gateway.clickDescriptionContains("首页", "Home")
                || gateway.clickText("首页", "Home");
        if (!home) {
            // Second BACK as soft fallback when home tab is not hit.
            home = gateway.pressBackAlways() || gateway.pressBack();
        }
        return back || home;
    }

    /** Soft-ask secondary buttons only — never primary accept/allow/continue. */
    private boolean dismissSoftPopup(AccessibilityGateway gateway) {
        String[] labels = InterruptPolicy.filterSoftLabels(InterruptPolicy.SOFT_DISMISS_LABELS);
        return gateway.clickText(labels)
                || gateway.tapText(labels)
                || gateway.clickDescriptionContains("关闭", "Close")
                || gateway.pressBack();
    }

    /** One bounded recovery attempt with structured logging fields. */
    static final class RecoveryStep {
        final boolean success;
        final String interruptionCode;
        final String action;

        private RecoveryStep(boolean success, String interruptionCode, String action) {
            this.success = success;
            this.interruptionCode = interruptionCode == null ? "" : interruptionCode;
            this.action = action == null ? "none" : action;
        }

        static RecoveryStep none() {
            return new RecoveryStep(false, "", "none");
        }

        static RecoveryStep ok(String code, String action) {
            return new RecoveryStep(true, code, action);
        }

        static RecoveryStep failed(String code, String action) {
            return new RecoveryStep(false, code, action);
        }
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
        for (int attempt = 0; attempt < 6; attempt++) {
            observed = inspectStable(gateway, config);
            if (!observed.blockers.isEmpty()) {
                throw new Blocked(observed.blockers.get(0),
                        "页面阻断：" + observed.blockers + " · 动作=" + action
                                + " · " + describePageMismatch(from, observed));
            }
            if (observed.page != PageState.Page.UNKNOWN) {
                if (PageTransitionPolicy.allows(from, observed.page, action)) {
                    logger.event("transition_verified", new JSONObject()
                            .put("from", from.name()).put("to", observed.page.name())
                            .put("action", action.name()).put("confidence", observed.confidence)
                            .put("evidence", new JSONArray(observed.evidence)));
                    return observed;
                }
                // OPEN_COMMENTS often needs an extra frame for empty "抢沙发" panels / photo posts.
                if (action == PageTransitionPolicy.Action.OPEN_COMMENTS
                        && observed.page == PageState.Page.FEED
                        && attempt < 5) {
                    logger.event("transition_wait_comments", new JSONObject()
                            .put("attempt", attempt + 1)
                            .put("page", observed.page.name())
                            .put("evidence", new JSONArray(observed.evidence)));
                    waitCooperative(450);
                    continue;
                }
                requireTransition(from, observed, action, logger);
                return observed;
            }
            if (attempt < 5) waitCooperative(450);
        }
        logger.event("transition_unstable", new JSONObject()
                .put("from", from.name()).put("action", action.name())
                .put("evidence", new JSONArray(observed == null
                        ? Collections.emptyList() : observed.evidence)));
        throw new Blocked("page_unstable",
                "页面连续采样不稳定 · 动作=" + action + " · 来源=" + from
                        + (observed == null ? "" : " · " + describePageMismatch(from, observed)));
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
        String detail = "动作 " + action + " 后非预期页面：" + from + " → " + observed.page
                + " · " + describePageMismatch(from, observed);
        logger.event("transition_rejected", new JSONObject()
                .put("from", from.name()).put("to", observed.page.name())
                .put("action", action.name()).put("confidence", observed.confidence)
                .put("evidence", new JSONArray(observed.evidence))
                .put("message", detail));
        throw new Blocked("unexpected_transition", detail);
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
        boolean wasPaused;
        synchronized (control) {
            wasPaused = paused;
            while (paused && !stopRequested) {
                try { control.wait(500); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new UserStopped(); }
            }
            if (stopRequested) throw new UserStopped();
        }
        // After a long pause, Accessibility often needs a beat before windows reappear.
        if (wasPaused) {
            SystemClock.sleep(450);
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
