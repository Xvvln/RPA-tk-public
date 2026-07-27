package com.xwell.rpatt;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.RangeSlider;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.xwell.rpatt.config.ConfigStore;
import com.xwell.rpatt.config.WarmupConfig;
import com.xwell.rpatt.ui.RpaPalette;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Material plan editor. Each automation action owns its switch and parameter panel. */
public final class SettingsActivity extends Activity {
    private boolean uiReady;
    private final List<ModuleCard> modules = new ArrayList<>();
    private ScrollView settingsScroll;
    private WarmupConfig workingConfig;
    private String selectedProfile = "cold";

    private MaterialButton coldProfile;
    private MaterialButton normalProfile;
    private MaterialButton activeProfile;
    private MaterialButton customProfile;
    private EditText maxMinutes;
    private EditText videoCount;
    private MaterialButtonToggleGroup journeyMode;
    private EditText recommendationBootstrapVideos;
    private EditText recommendationSignalHits;
    private EditText recommendationSearchCooldownVideos;
    private TextView workflowMode;
    private LinearLayout workflowRows;
    private List<String> sourceOrder = new ArrayList<>(WarmupConfig.SOURCE_STEPS);
    private List<String> workflowOrder = new ArrayList<>(WarmupConfig.WORKFLOW_STEPS);

    private ModuleCard browseModule;
    private RangeSlider watchRange;
    private MaterialButtonToggleGroup swipePace;
    private MaterialSwitch swipeRandomization;
    private MaterialSwitch skipLive;
    private EditText pauseMin;
    private EditText pauseMax;

    private ModuleCard searchModule;
    private EditText searchKeywords;
    private EditText maxSearches;
    private EditText videosPerKeyword;
    private MaterialButtonToggleGroup searchOrder;

    private ModuleCard commentReadModule;
    private Slider commentReadRate;
    private MaterialCheckBox commentsOnlyIfRelevant;
    private RangeSlider commentDwellRange;
    private EditText commentScrolls;
    private MaterialSwitch commentScrollRandom;
    private EditText replyThreads;

    private ModuleCard likeModule;
    private Slider likeRate;
    private EditText maxLikes;
    private MaterialCheckBox likeRelevant;

    private ModuleCard favoriteModule;
    private Slider favoriteRate;
    private EditText maxFavorites;
    private MaterialCheckBox favoriteRelevant;

    private ModuleCard commentModule;
    private Slider commentRate;
    private EditText maxComments;
    private MaterialCheckBox commentRelevant;
    private MaterialButtonToggleGroup commentOrder;
    private EditText commentTexts;

    private ModuleCard followModule;
    private Slider followRate;
    private EditText maxFollows;
    private MaterialCheckBox followRelevant;

    private ModuleCard shareModule;
    private Slider shareRate;
    private EditText maxShares;
    private MaterialCheckBox shareRelevant;

    private ModuleCard interestModule;
    private EditText interestKeywords;
    private RangeSlider interestWatchExtraRange;
    private ModuleCard notInterestedModule;
    private EditText notInterestedKeywords;
    private Slider notInterestedRate;
    private EditText maxNotInterested;

    private ModuleCard recoveryModule;
    private EditText maxRecoveryActions;
    private MaterialCheckBox stopOnBlockers;
    private MaterialCheckBox requireNetwork;

    @Override protected void onCreate(Bundle savedInstanceState) {
        RpaPalette.prepare(this);
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        uiReady = true;
        workingConfig = ConfigStore.load(this);
        loadConfig(workingConfig);
    }

    private View buildUi() {
        LinearLayout screen = column();
        screen.setBackgroundColor(RpaPalette.BACKGROUND);

        settingsScroll = new ScrollView(this);
        settingsScroll.setFillViewport(true);
        LinearLayout root = column();
        root.setPadding(dp(18), dp(10), dp(18), dp(24));
        settingsScroll.addView(root);
        screen.addView(settingsScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildHeader());
        root.addView(sectionLabel("方案与预算"));
        root.addView(buildBudgetCard());
        root.addView(sectionLabel("会话流程"));
        root.addView(buildJourneyCard());

        browseModule = module("浏览与滑动", android.R.drawable.ic_menu_view, false, buildBrowseDetails());
        searchModule = module("兴趣搜索", android.R.drawable.ic_menu_search, true, buildSearchDetails());
        commentReadModule = module("评论区浏览", android.R.drawable.sym_action_chat, true, buildCommentReadDetails());
        likeModule = module("点赞", android.R.drawable.btn_star_big_on, true, buildLikeDetails());
        favoriteModule = module("收藏", android.R.drawable.btn_star_big_off, true, buildFavoriteDetails());
        commentModule = module("评论", R.drawable.ic_keyboard, true, buildCommentDetails());
        followModule = module("关注", android.R.drawable.ic_input_add, true, buildFollowDetails());
        shareModule = module("Repost（公开转发）", android.R.drawable.ic_menu_send, true, buildShareDetails());
        interestModule = module("感兴趣判定", android.R.drawable.btn_star_big_on, true, buildInterestDetails());
        notInterestedModule = module("不感兴趣", android.R.drawable.ic_menu_close_clear_cancel, true,
                buildNotInterestedDetails());
        recoveryModule = module("安全与恢复", android.R.drawable.ic_lock_lock, true, buildRecoveryDetails());

        root.addView(sectionLabel("执行顺序"));
        root.addView(buildWorkflowCard());
        root.addView(sectionLabel("执行模块"));

        browseModule.toggle.setChecked(true);
        browseModule.toggle.setEnabled(false);
        browseModule.toggle.setContentDescription("核心浏览始终启用");
        for (ModuleCard module : modules) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(8);
            root.addView(module.root, params);
        }

        LinearLayout actions = row();
        actions.setPadding(dp(18), dp(10), dp(18), dp(14));
        actions.setBackgroundColor(RpaPalette.BACKGROUND);
        MaterialButton discard = secondaryButton("放弃", this::finish);
        actions.addView(discard, new LinearLayout.LayoutParams(0, dp(52), 0.34f));
        actions.addView(horizontalSpace(10));
        MaterialButton save = primaryButton("保存方案", this::saveAndFinish);
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(52), 0.66f));
        screen.addView(actions);
        return screen;
    }

    private View buildHeader() {
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton back = iconButton(
                R.drawable.ic_arrow_back,
                "返回", this::finish);
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout copy = column();
        copy.setPadding(dp(10), 0, 0, 0);
        TextView title = text("编辑方案", 24, RpaPalette.TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(title);
        copy.addView(text("选择操作并设置各自参数", 13, RpaPalette.TEXT_MUTED));
        header.addView(copy, weighted());
        return header;
    }

    private View buildBudgetCard() {
        LinearLayout content = column();
        content.setPadding(dp(14), dp(12), dp(14), dp(12));
        content.addView(text("方案档位", 13, RpaPalette.TEXT_MUTED));
        LinearLayout profiles = row();
        profiles.setPadding(dp(3), dp(3), dp(3), dp(3));
        profiles.setBackground(outlined(RpaPalette.SURFACE_RAISED, RpaPalette.BORDER, 10));
        coldProfile = profileButton("轻量", "cold");
        normalProfile = profileButton("常规", "normal");
        activeProfile = profileButton("积极", "active");
        customProfile = profileButton("自定义", "custom");
        profiles.addView(coldProfile, weighted());
        profiles.addView(normalProfile, weighted());
        profiles.addView(activeProfile, weighted());
        profiles.addView(customProfile, weighted());
        LinearLayout.LayoutParams profileParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        profileParams.topMargin = dp(6);
        content.addView(profiles, profileParams);

        LinearLayout budget = row();
        maxMinutes = number("20", false);
        videoCount = number("12", false);
        budget.addView(field("最大分钟", maxMinutes), weighted());
        budget.addView(horizontalSpace(8));
        budget.addView(field("视频条数", videoCount), weighted());
        content.addView(budget);
        return materialCard(content, 14, RpaPalette.BORDER);
    }

    private View buildWorkflowCard() {
        LinearLayout content = column();
        content.setPadding(dp(14), dp(12), dp(14), dp(10));
        workflowMode = text("轻量养号 · 固定顺序", 14, RpaPalette.TEXT);
        workflowMode.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(workflowMode);
        TextView note = text("开场检查和保存记录属于固定步骤；自定义方案可调整中间动作。", 12,
                RpaPalette.TEXT_MUTED);
        note.setPadding(0, dp(3), 0, dp(7));
        content.addView(note);
        workflowRows = column();
        content.addView(workflowRows);
        return materialCard(content, 14, RpaPalette.BORDER);
    }

    private View buildJourneyCard() {
        LinearLayout content = column();
        content.setPadding(dp(14), dp(12), dp(14), dp(12));
        content.addView(fieldLabel("编排方式"));
        journeyMode = choices(new String[]{"顺序流程", "推荐驱动"});
        journeyMode.addOnButtonCheckedListener((group, checkedId, checked) -> {
            if (checked && uiReady) refreshWorkflowRows();
        });
        content.addView(journeyMode);

        LinearLayout trigger = row();
        recommendationBootstrapVideos = number("3", false);
        recommendationSignalHits = number("2", false);
        trigger.addView(field("起步推荐条数", recommendationBootstrapVideos), weighted());
        trigger.addView(horizontalSpace(8));
        trigger.addView(field("触发搜索需命中", recommendationSignalHits), weighted());
        content.addView(trigger);
        recommendationSearchCooldownVideos = number("5", false);
        content.addView(field("两次搜索间至少浏览的推荐视频", recommendationSearchCooldownVideos));
        refreshWorkflowOnText(recommendationBootstrapVideos);
        refreshWorkflowOnText(recommendationSignalHits);
        refreshWorkflowOnText(recommendationSearchCooldownVideos);

        TextView note = text(
                "推荐驱动会先刷 For You；正向兴趣累计到阈值后，只执行一小段已配置关键词搜索，再返回推荐流。动作模块和安全规则与顺序流程共用。",
                12, RpaPalette.TEXT_MUTED);
        note.setPadding(0, dp(6), 0, dp(2));
        content.addView(note);
        return materialCard(content, 14, RpaPalette.BORDER);
    }

    private void refreshWorkflowOnText(EditText value) {
        value.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                refreshWorkflowRows();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
    }

    private View buildBrowseDetails() {
        LinearLayout details = details();
        details.addView(fieldLabel("纯观看时长（不含交互）"));
        watchRange = range(1, 120, 1, 8, 20);
        details.addView(watchRange);
        details.addView(text("只控制滑走前「干看」几秒。点赞/开评论/写评论/搜索/恢复弹窗的时间不在这里，"
                        + "所以单条视频墙钟时间会明显长于该区间。",
                12, RpaPalette.TEXT_MUTED));
        details.addView(fieldLabel("滑动速度"));
        swipePace = choices(new String[]{"慢速", "自然", "快速"});
        details.addView(swipePace);
        swipeRandomization = optionSwitch("随机调整每次滑动轨迹与速度", true);
        details.addView(swipeRandomization);
        details.addView(text("在所选速度档位内加入小幅、受限的起止点与时长偏差",
                12, RpaPalette.TEXT_MUTED));
        details.addView(fieldLabel("视频间随机停顿（滑走后、下一条前，秒）"));
        // Number fields instead of RangeSlider: thumbs at 0/0 or equal values are hard to drag
        // on Material RangeSlider (min separation + nested ScrollView).
        LinearLayout pauseRow = row();
        pauseMin = number("0", false);
        pauseMax = number("1", false);
        pauseRow.addView(field("最短", pauseMin), weighted());
        pauseRow.addView(horizontalSpace(8));
        pauseRow.addView(field("最长", pauseMax), weighted());
        details.addView(pauseRow);
        LinearLayout pausePresets = row();
        pausePresets.setPadding(0, dp(6), 0, dp(2));
        pausePresets.addView(presetChip("无停顿", 0, 0), weighted());
        pausePresets.addView(horizontalSpace(6));
        pausePresets.addView(presetChip("0–1秒", 0, 1), weighted());
        pausePresets.addView(horizontalSpace(6));
        pausePresets.addView(presetChip("1–4秒", 1, 4), weighted());
        details.addView(pausePresets);
        details.addView(text("可填 0–30。最短≤最长；无停顿填 0 和 0。与纯观看时长叠加。",
                12, RpaPalette.TEXT_MUTED));
        refreshOnText(pauseMin);
        refreshOnText(pauseMax);
        skipLive = optionSwitch("直播内容直接跳过（不点赞/评论/关注）", true);
        details.addView(skipLive);
        details.addView(text("推荐流出现「直播中」时观看后滑走，不执行交互。关闭后按普通视频尝试工作流。",
                12, RpaPalette.TEXT_MUTED));
        skipLive.setOnCheckedChangeListener((button, checked) -> refreshSummaries());
        return details;
    }

    private View buildSearchDetails() {
        LinearLayout details = details();
        searchKeywords = input("GPT, AI, programming");
        details.addView(field("关键词（逗号分隔）", searchKeywords));
        LinearLayout limits = row();
        maxSearches = number("3", false);
        videosPerKeyword = number("2", false);
        limits.addView(field("最多搜索", maxSearches), weighted());
        limits.addView(horizontalSpace(8));
        limits.addView(field("每词视频", videosPerKeyword), weighted());
        details.addView(limits);
        details.addView(fieldLabel("关键词顺序"));
        searchOrder = choices(new String[]{"顺序", "随机"});
        details.addView(searchOrder);
        return details;
    }

    private View buildCommentReadDetails() {
        LinearLayout details = details();
        commentReadRate = rateSlider();
        details.addView(sliderBlock("进入评论区概率", commentReadRate));
        commentsOnlyIfRelevant = checkbox("仅对符合兴趣的视频进入评论区");
        commentsOnlyIfRelevant.setOnCheckedChangeListener((button, checked) -> refreshSummaries());
        details.addView(commentsOnlyIfRelevant);
        details.addView(fieldLabel("评论区停留秒数"));
        commentDwellRange = range(0, 60, 1, 2, 6);
        details.addView(commentDwellRange);
        commentScrollRandom = optionSwitch("随机评论区滚动次数", true);
        details.addView(commentScrollRandom);
        LinearLayout limits = row();
        commentScrolls = number("1", false);
        replyThreads = number("1", false);
        limits.addView(field("滚动次数上限", commentScrolls), weighted());
        limits.addView(horizontalSpace(8));
        limits.addView(field("展开二级评论", replyThreads), weighted());
        details.addView(limits);
        return details;
    }

    private View buildLikeDetails() {
        LinearLayout details = details();
        likeRate = rateSlider();
        details.addView(sliderBlock("执行概率", likeRate));
        maxLikes = number("3", false);
        details.addView(field("单次任务上限", maxLikes));
        likeRelevant = checkbox("仅对符合兴趣的视频执行");
        details.addView(likeRelevant);
        return details;
    }

    private View buildFavoriteDetails() {
        LinearLayout details = details();
        favoriteRate = rateSlider();
        details.addView(sliderBlock("执行概率", favoriteRate));
        maxFavorites = number("2", false);
        details.addView(field("单次任务上限", maxFavorites));
        favoriteRelevant = checkbox("仅对符合兴趣的视频执行");
        details.addView(favoriteRelevant);
        return details;
    }

    private View buildCommentDetails() {
        LinearLayout details = details();
        commentRate = rateSlider();
        details.addView(sliderBlock("执行概率", commentRate));
        maxComments = number("1", false);
        details.addView(field("单次任务上限", maxComments));
        commentRelevant = checkbox("仅对符合兴趣的视频执行");
        details.addView(commentRelevant);
        details.addView(fieldLabel("候选文案顺序"));
        commentOrder = choices(new String[]{"随机", "顺序"});
        details.addView(commentOrder);
        commentTexts = input("每行一条候选评论");
        commentTexts.setMinLines(3);
        details.addView(field("评论候选池", commentTexts));
        return details;
    }

    private View buildFollowDetails() {
        LinearLayout details = details();
        followRate = rateSlider();
        details.addView(sliderBlock("执行概率", followRate));
        maxFollows = number("1", false);
        details.addView(field("单次任务上限", maxFollows));
        followRelevant = checkbox("仅对符合兴趣的视频执行");
        details.addView(followRelevant);
        return details;
    }

    private View buildShareDetails() {
        LinearLayout details = details();
        TextView mode = text("执行方式：点击 TikTok Repost，不选择联系人，也不会发送私信。",
                13, RpaPalette.TEXT_MUTED);
        mode.setPadding(0, dp(4), 0, dp(8));
        details.addView(mode);
        shareRate = rateSlider();
        details.addView(sliderBlock("执行概率", shareRate));
        maxShares = number("1", false);
        details.addView(field("单次任务上限", maxShares));
        shareRelevant = checkbox("仅对符合兴趣的视频执行");
        details.addView(shareRelevant);
        return details;
    }

    private View buildInterestDetails() {
        LinearLayout details = details();
        interestKeywords = input("AI, technology, programming");
        details.addView(field("正向兴趣词（逗号分隔）", interestKeywords));
        details.addView(fieldLabel("命中后额外观看秒数"));
        interestWatchExtraRange = range(0, 30, 1, 0, 0);
        details.addView(interestWatchExtraRange);
        TextView note = text("命中后标记为感兴趣，供点赞、收藏、评论和关注的“仅对感兴趣内容”条件使用。",
                12, RpaPalette.TEXT_MUTED);
        note.setPadding(0, dp(6), 0, dp(4));
        details.addView(note);
        return details;
    }

    private View buildNotInterestedDetails() {
        LinearLayout details = details();
        notInterestedKeywords = input("广告, 搬运, learn");
        details.addView(field("负向关键词（命中才执行）", notInterestedKeywords));
        notInterestedRate = rateSlider();
        details.addView(sliderBlock("命中后的执行概率", notInterestedRate));
        maxNotInterested = number("3", false);
        details.addView(field("单次任务上限", maxNotInterested));
        TextView note = text("只对标题、简介或作者中命中负向关键词的视频执行；未命中时不会操作。",
                12, RpaPalette.TEXT_MUTED);
        note.setPadding(0, dp(6), 0, dp(4));
        details.addView(note);
        return details;
    }

    private View buildRecoveryDetails() {
        LinearLayout details = details();
        maxRecoveryActions = number("3", false);
        details.addView(field("最多自动恢复动作", maxRecoveryActions));
        stopOnBlockers = checkbox("验证码、登录或限制出现时始终停止（必需）");
        stopOnBlockers.setChecked(true);
        stopOnBlockers.setEnabled(false);
        requireNetwork = checkbox("开始前要求 Android 已验证网络");
        details.addView(stopOnBlockers);
        details.addView(requireNetwork);
        return details;
    }

    private ModuleCard module(String title, int icon, boolean switchable, View details) {
        ModuleCard module = new ModuleCard(title, icon, switchable, details);
        modules.add(module);
        return module;
    }

    private void expand(ModuleCard target) {
        int[] before = new int[2];
        target.root.getLocationOnScreen(before);
        boolean expanded = target.details.getVisibility() != View.VISIBLE;

        // Preserve the tapped card's screen position before Android draws the resized ScrollView.
        // This avoids showing one incorrect frame and does not collapse unrelated modules.
        ViewTreeObserver observer = settingsScroll.getViewTreeObserver();
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            private boolean corrected;

            @Override public boolean onPreDraw() {
                if (!settingsScroll.getViewTreeObserver().isAlive()) return true;
                settingsScroll.getViewTreeObserver().removeOnPreDrawListener(this);
                if (corrected || isFinishing()) return true;
                corrected = true;
                int[] after = new int[2];
                target.root.getLocationOnScreen(after);
                int offset = after[1] - before[1];
                if (offset != 0) {
                    settingsScroll.scrollBy(0, offset);
                    settingsScroll.postInvalidateOnAnimation();
                    return false;
                }
                return true;
            }
        });
        target.details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        target.arrow.setContentDescription(
                (expanded ? "收起" : "展开") + target.title + "参数");
        float rotation = expanded ? 180f : 0f;
        if (target.arrow.getRotation() != rotation) {
            target.arrow.animate().rotation(rotation).setDuration(160).start();
        }
    }

    private void loadConfig(WarmupConfig config) {
        selectedProfile = config.profile;
        sourceOrder = new ArrayList<>(config.sourceOrder);
        workflowOrder = new ArrayList<>(config.workflowOrder);
        refreshProfileButtons();
        maxMinutes.setText(String.valueOf(config.maxMinutes));
        videoCount.setText(String.valueOf(config.videoCount));
        checkChoice(journeyMode, WarmupConfig.JOURNEY_RECOMMENDATION_LED.equals(
                config.journeyMode) ? 1 : 0);
        recommendationBootstrapVideos.setText(String.valueOf(config.recommendationBootstrapVideos));
        recommendationSignalHits.setText(String.valueOf(config.recommendationSignalHits));
        recommendationSearchCooldownVideos.setText(String.valueOf(
                config.recommendationSearchCooldownVideos));
        watchRange.setValues((float) config.watchMinSeconds, (float) config.watchMaxSeconds);
        checkChoice(swipePace, paceIndex(config.swipePace));
        swipeRandomization.setChecked(config.swipeRandomizationEnabled);
        pauseMin.setText(String.valueOf(Math.max(0, config.pauseMinSeconds)));
        pauseMax.setText(String.valueOf(Math.max(config.pauseMinSeconds, config.pauseMaxSeconds)));
        skipLive.setChecked(config.skipLive);

        searchModule.toggle.setChecked(config.searchEnabled);
        searchKeywords.setText(String.join(", ", config.searchKeywords));
        maxSearches.setText(String.valueOf(config.maxSearches));
        videosPerKeyword.setText(String.valueOf(config.videosPerKeyword));
        checkChoice(searchOrder, "random".equals(config.searchPick) ? 1 : 0);

        commentReadModule.toggle.setChecked(config.openComments);
        commentReadRate.setValue((float) (config.commentReadRate * 100));
        commentsOnlyIfRelevant.setChecked(config.commentsOnlyIfRelevant);
        commentDwellRange.setValues((float) config.commentMinSeconds, (float) config.commentMaxSeconds);
        commentScrolls.setText(String.valueOf(config.commentScrolls));
        commentScrollRandom.setChecked(config.commentScrollRandom);
        replyThreads.setText(String.valueOf(config.replyThreads));

        likeModule.toggle.setChecked(config.likeEnabled);
        likeRate.setValue((float) (config.likeRate * 100));
        maxLikes.setText(String.valueOf(config.maxLikes));
        likeRelevant.setChecked(config.likeOnlyIfRelevant);
        favoriteModule.toggle.setChecked(config.favoriteEnabled);
        favoriteRate.setValue((float) (config.favoriteRate * 100));
        maxFavorites.setText(String.valueOf(config.maxFavorites));
        favoriteRelevant.setChecked(config.favoriteOnlyIfRelevant);
        commentModule.toggle.setChecked(config.commentEnabled);
        commentRate.setValue((float) (config.commentRate * 100));
        maxComments.setText(String.valueOf(config.maxComments));
        commentRelevant.setChecked(config.commentOnlyIfRelevant);
        checkChoice(commentOrder, "sequential".equals(config.commentOrder) ? 1 : 0);
        commentTexts.setText(String.join("\n", config.commentTexts));

        followModule.toggle.setChecked(config.followEnabled);
        followRate.setValue((float) (config.followRate * 100));
        maxFollows.setText(String.valueOf(config.maxFollows));
        followRelevant.setChecked(config.followOnlyIfRelevant);
        shareModule.toggle.setChecked(config.shareEnabled);
        shareRate.setValue((float) (config.shareRate * 100));
        maxShares.setText(String.valueOf(config.maxShares));
        shareRelevant.setChecked(config.shareOnlyIfRelevant);

        interestModule.toggle.setChecked(config.interestEnabled);
        interestKeywords.setText(String.join(", ", config.interestKeywords));
        interestWatchExtraRange.setValues((float) config.interestWatchExtraMinSeconds,
                (float) config.interestWatchExtraMaxSeconds);
        notInterestedModule.toggle.setChecked(config.notInterestedEnabled);
        notInterestedKeywords.setText(String.join(", ", config.notInterestedKeywords));
        notInterestedRate.setValue((float) (config.notInterestedRate * 100));
        maxNotInterested.setText(String.valueOf(config.maxNotInterested));
        recoveryModule.toggle.setChecked(config.autoRecover);
        maxRecoveryActions.setText(String.valueOf(config.maxRecoveryActions));
        stopOnBlockers.setChecked(config.stopOnBlockers);
        requireNetwork.setChecked(config.requireNetwork);
        refreshSummaries();
        refreshWorkflowRows();
    }

    private WarmupConfig readConfig() {
        WarmupConfig config = WarmupConfig.fromJson(workingConfig.toJson());
        config.profile = selectedProfile;
        config.maxMinutes = integer(maxMinutes);
        config.videoCount = integer(videoCount);
        config.journeyMode = checkedIndex(journeyMode) == 1
                ? WarmupConfig.JOURNEY_RECOMMENDATION_LED : WarmupConfig.JOURNEY_ORDERED;
        config.recommendationBootstrapVideos = integer(recommendationBootstrapVideos);
        config.recommendationSignalHits = integer(recommendationSignalHits);
        config.recommendationSearchCooldownVideos = integer(recommendationSearchCooldownVideos);
        config.watchMinSeconds = Math.round(watchRange.getValues().get(0));
        config.watchMaxSeconds = Math.round(watchRange.getValues().get(1));
        config.swipePace = paceValue(checkedIndex(swipePace));
        config.swipeRandomizationEnabled = swipeRandomization.isChecked();
        int pauseLo = Math.max(0, integerOr(pauseMin, 0));
        int pauseHi = Math.max(0, integerOr(pauseMax, pauseLo));
        if (pauseHi < pauseLo) {
            int swap = pauseLo;
            pauseLo = pauseHi;
            pauseHi = swap;
        }
        config.pauseMinSeconds = Math.min(pauseLo, 30);
        config.pauseMaxSeconds = Math.min(Math.max(pauseHi, config.pauseMinSeconds), 60);
        config.skipLive = skipLive.isChecked();

        config.searchEnabled = searchModule.toggle.isChecked();
        config.searchKeywords = csv(searchKeywords.getText().toString());
        config.maxSearches = integer(maxSearches);
        config.videosPerKeyword = integer(videosPerKeyword);
        config.searchPick = checkedIndex(searchOrder) == 1 ? "random" : "sequential";

        config.openComments = commentReadModule.toggle.isChecked();
        config.commentReadRate = commentReadRate.getValue() / 100.0;
        config.commentsOnlyIfRelevant = commentsOnlyIfRelevant.isChecked();
        config.commentMinSeconds = Math.round(commentDwellRange.getValues().get(0));
        config.commentMaxSeconds = Math.round(commentDwellRange.getValues().get(1));
        config.commentScrolls = integer(commentScrolls);
        config.commentScrollRandom = commentScrollRandom.isChecked();
        config.replyThreads = integer(replyThreads);

        config.likeEnabled = likeModule.toggle.isChecked();
        config.likeRate = likeRate.getValue() / 100.0;
        config.maxLikes = integer(maxLikes);
        config.likeOnlyIfRelevant = likeRelevant.isChecked();
        config.favoriteEnabled = favoriteModule.toggle.isChecked();
        config.favoriteRate = favoriteRate.getValue() / 100.0;
        config.maxFavorites = integer(maxFavorites);
        config.favoriteOnlyIfRelevant = favoriteRelevant.isChecked();
        config.commentEnabled = commentModule.toggle.isChecked();
        config.commentRate = commentRate.getValue() / 100.0;
        config.maxComments = integer(maxComments);
        config.commentOnlyIfRelevant = commentRelevant.isChecked();
        config.commentOrder = checkedIndex(commentOrder) == 1 ? "sequential" : "random";
        config.commentTexts = lines(commentTexts.getText().toString());

        config.followEnabled = followModule.toggle.isChecked();
        config.followApply = true;
        config.followRate = followRate.getValue() / 100.0;
        config.maxFollows = integer(maxFollows);
        config.followOnlyIfRelevant = followRelevant.isChecked();
        config.shareEnabled = shareModule.toggle.isChecked();
        config.shareRate = shareRate.getValue() / 100.0;
        config.maxShares = integer(maxShares);
        config.shareOnlyIfRelevant = shareRelevant.isChecked();

        config.interestEnabled = interestModule.toggle.isChecked();
        config.interestKeywords = csv(interestKeywords.getText().toString());
        config.interestWatchExtraMinSeconds = Math.round(
                interestWatchExtraRange.getValues().get(0));
        config.interestWatchExtraMaxSeconds = Math.round(
                interestWatchExtraRange.getValues().get(1));
        config.notInterestedEnabled = notInterestedModule.toggle.isChecked();
        config.notInterestedKeywords = csv(notInterestedKeywords.getText().toString());
        config.notInterestedRate = notInterestedRate.getValue() / 100.0;
        config.maxNotInterested = integer(maxNotInterested);
        config.autoRecover = recoveryModule.toggle.isChecked();
        config.maxRecoveryActions = integer(maxRecoveryActions);
        config.stopOnBlockers = true;
        config.requireNetwork = requireNetwork.isChecked();
        config.interactionsApply = true;
        config.sourceOrder = new ArrayList<>(sourceOrder);
        config.workflowOrder = new ArrayList<>(workflowOrder);
        return config;
    }

    private void selectProfile(String profile) {
        if ("custom".equals(profile)) {
            try { workingConfig = readConfig(); } catch (RuntimeException ignored) {}
            workingConfig.profile = "custom";
            selectedProfile = "custom";
            refreshProfileButtons();
            refreshWorkflowRows();
            return;
        }
        workingConfig = WarmupConfig.defaultsFor(profile);
        loadConfig(workingConfig);
    }

    private void refreshProfileButtons() {
        styleProfileButton(coldProfile, "cold".equals(selectedProfile));
        styleProfileButton(normalProfile, "normal".equals(selectedProfile));
        styleProfileButton(activeProfile, "active".equals(selectedProfile));
        styleProfileButton(customProfile, "custom".equals(selectedProfile));
    }

    private void refreshWorkflowRows() {
        if (!uiReady || workflowRows == null || browseModule == null) return;
        workflowRows.removeAllViews();
        boolean custom = "custom".equals(selectedProfile);
        boolean recommendationLed = checkedIndex(journeyMode) == 1;
        workflowMode.setText(String.format(Locale.CHINA, "%s · %s%s",
                MainActivity.profileName(selectedProfile),
                recommendationLed ? "推荐驱动" : "顺序流程",
                custom ? " · 动作可排序" : ""));

        List<String> visible = activeWorkflowSteps();
        int number = 1;
        if (recommendationLed) {
            addWorkflowRow(number++, "推荐流起步",
                    "先浏览 " + safe(recommendationBootstrapVideos) + " 条 For You 视频",
                    false, null, visible, false);
            addWorkflowRow(number++, "累计兴趣信号",
                    "正向兴趣累计 " + safe(recommendationSignalHits) + " 次后满足条件",
                    false, null, visible, false);
            addWorkflowRow(number++, "条件触发兴趣搜索",
                    searchModule.toggle.isChecked()
                            ? "每次只搜索 1 个已配置关键词"
                            : "搜索未启用，将持续浏览推荐流",
                    false, null, visible, false);
            addWorkflowRow(number++, "返回推荐流",
                    "至少再浏览 " + safe(recommendationSearchCooldownVideos) + " 条后才可再次搜索",
                    false, null, visible, false);
        } else {
            List<String> sources = activeSourceSteps();
            for (String source : sources) {
                addWorkflowRow(number++, sourceLabel(source), sourceSubtitle(source),
                        custom && sources.size() > 1, source, sources, true);
            }
        }
        for (String step : visible) {
            addWorkflowRow(number++, workflowLabel(step), "按模块概率和上限执行",
                    custom, step, visible, false);
        }
        addWorkflowRow(number, "保存记录并翻页", "固定收尾", false, null, visible, false);
    }

    private void addWorkflowRow(int number, String title, String subtitle, boolean movable,
                                String step, List<String> visible, boolean sourceStep) {
        if (workflowRows.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(RpaPalette.BORDER);
            workflowRows.addView(divider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        }
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView index = text(String.valueOf(number), 12, Color.WHITE);
        index.setGravity(Gravity.CENTER);
        index.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        index.setBackground(round(movable ? RpaPalette.PINK_DARK : RpaPalette.SURFACE_RAISED, 14));
        row.addView(index, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout copy = column();
        copy.setPadding(dp(10), 0, dp(6), 0);
        copy.addView(text(title, 14, RpaPalette.TEXT));
        copy.addView(text(subtitle, 11, RpaPalette.TEXT_MUTED));
        row.addView(copy, weighted());
        if (movable && step != null) {
            int position = visible.indexOf(step);
            MaterialButton up = iconButton(
                    R.drawable.ic_expand_less,
                    "上移" + title, () -> moveOrderedStep(step, -1, visible, sourceStep));
            up.setEnabled(position > 0);
            up.setAlpha(position > 0 ? 1f : 0.28f);
            row.addView(up, new LinearLayout.LayoutParams(dp(40), dp(40)));
            MaterialButton down = iconButton(
                    R.drawable.ic_expand_more,
                    "下移" + title, () -> moveOrderedStep(step, 1, visible, sourceStep));
            down.setEnabled(position >= 0 && position < visible.size() - 1);
            down.setAlpha(position >= 0 && position < visible.size() - 1 ? 1f : 0.28f);
            row.addView(down, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        workflowRows.addView(row);
    }

    private void moveOrderedStep(String step, int direction, List<String> visible,
                                 boolean sourceStep) {
        int visibleIndex = visible.indexOf(step);
        int targetVisible = visibleIndex + direction;
        if (visibleIndex < 0 || targetVisible < 0 || targetVisible >= visible.size()) return;
        String other = visible.get(targetVisible);
        List<String> order = sourceStep ? sourceOrder : workflowOrder;
        int first = order.indexOf(step);
        int second = order.indexOf(other);
        if (first < 0 || second < 0) return;
        order.set(first, other);
        order.set(second, step);
        refreshWorkflowRows();
    }

    private List<String> activeSourceSteps() {
        List<String> result = new ArrayList<>();
        for (String source : sourceOrder) {
            if (WarmupConfig.SOURCE_FEED.equals(source)
                    || (WarmupConfig.SOURCE_SEARCH.equals(source) && searchModule.toggle.isChecked())) {
                result.add(source);
            }
        }
        return result;
    }

    private String sourceLabel(String source) {
        return WarmupConfig.SOURCE_SEARCH.equals(source) ? "兴趣搜索" : "推荐流浏览";
    }

    private String sourceSubtitle(String source) {
        return WarmupConfig.SOURCE_SEARCH.equals(source)
                ? "按关键词浏览搜索结果" : "浏览 For You 推荐流";
    }

    private List<String> activeWorkflowSteps() {
        List<String> result = new ArrayList<>();
        for (String step : workflowOrder) {
            if (workflowStepEnabled(step)) result.add(step);
        }
        return result;
    }

    private boolean workflowStepEnabled(String step) {
        switch (step) {
            case WarmupConfig.STEP_COMMENTS: return commentReadModule.toggle.isChecked();
            case WarmupConfig.STEP_LIKE: return likeModule.toggle.isChecked();
            case WarmupConfig.STEP_FAVORITE: return favoriteModule.toggle.isChecked();
            case WarmupConfig.STEP_COMMENT: return commentModule.toggle.isChecked();
            case WarmupConfig.STEP_FOLLOW: return followModule.toggle.isChecked();
            case WarmupConfig.STEP_SHARE: return shareModule.toggle.isChecked();
            case WarmupConfig.STEP_NOT_INTERESTED: return notInterestedModule.toggle.isChecked();
            default: return false;
        }
    }

    private String workflowLabel(String step) {
        switch (step) {
            case WarmupConfig.STEP_COMMENTS: return "评论区浏览";
            case WarmupConfig.STEP_LIKE: return "点赞";
            case WarmupConfig.STEP_FAVORITE: return "收藏";
            case WarmupConfig.STEP_COMMENT: return "评论";
            case WarmupConfig.STEP_FOLLOW: return "关注";
            case WarmupConfig.STEP_SHARE: return "Repost（公开转发）";
            case WarmupConfig.STEP_NOT_INTERESTED: return "不感兴趣";
            default: return step;
        }
    }

    private void refreshSummaries() {
        if (!uiReady) return;
        int pMin = Math.max(0, integerOr(pauseMin, 0));
        int pMax = Math.max(pMin, integerOr(pauseMax, pMin));
        browseModule.summary.setText(String.format(Locale.CHINA,
                "必需 · 纯看 %d–%d秒 · 间停 %d–%d秒 · %s · %s",
                Math.round(watchRange.getValues().get(0)), Math.round(watchRange.getValues().get(1)),
                pMin, pMax,
                paceLabel(checkedIndex(swipePace)),
                skipLive.isChecked() ? "跳过直播" : "直播可交互"));
        searchModule.summary.setText(searchModule.toggle.isChecked()
                ? Math.max(1, csv(searchKeywords.getText().toString()).size()) + " 个关键词 · 最多 " + safe(maxSearches) + " 次"
                : "未启用");
        commentReadModule.summary.setText(commentReadModule.toggle.isChecked()
                ? Math.round(commentReadRate.getValue()) + "% · "
                + rangeSummary(commentDwellRange) + "秒 · "
                + (commentScrollRandom.isChecked()
                ? "滚动 0–" + safe(commentScrolls) + " 次"
                : "滚动 " + safe(commentScrolls) + " 次") + " · "
                + (commentsOnlyIfRelevant.isChecked() ? "仅感兴趣" : "全部内容")
                : "未启用");
        likeModule.summary.setText(actionSummary(likeModule, likeRate, maxLikes));
        favoriteModule.summary.setText(actionSummary(favoriteModule, favoriteRate, maxFavorites));
        commentModule.summary.setText(actionSummary(commentModule, commentRate, maxComments));
        followModule.summary.setText(actionSummary(followModule, followRate, maxFollows));
        shareModule.summary.setText(actionSummary(shareModule, shareRate, maxShares));
        interestModule.summary.setText(interestModule.toggle.isChecked()
                ? Math.max(1, csv(interestKeywords.getText().toString()).size())
                + " 个正向词 · 额外 " + rangeSummary(interestWatchExtraRange) + " 秒"
                : "未启用");
        notInterestedModule.summary.setText(notInterestedModule.toggle.isChecked()
                ? Math.max(1, csv(notInterestedKeywords.getText().toString()).size()) + " 个负向词 · "
                + Math.round(notInterestedRate.getValue()) + "% · 最多 " + safe(maxNotInterested) + " 次"
                : "未启用");
        recoveryModule.summary.setText(recoveryModule.toggle.isChecked()
                ? "已启用 · 最多 " + safe(maxRecoveryActions) + " 次" : "关闭自动恢复");
    }

    private String actionSummary(ModuleCard module, Slider rate, EditText limit) {
        return module.toggle.isChecked()
                ? Math.round(rate.getValue()) + "% · 最多 " + safe(limit) + " 次" : "未启用";
    }

    private void saveAndFinish() {
        try {
            WarmupConfig config = readConfig();
            config.validate();
            ConfigStore.save(this, config);
            workingConfig = config;
            toast("方案已保存");
            finish();
        } catch (RuntimeException error) {
            new MaterialAlertDialogBuilder(this).setTitle("配置有误").setMessage(error.getMessage())
                    .setPositiveButton("知道了", null).show();
        }
    }

    private MaterialCardView materialCard(View content, int radius, int stroke) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(RpaPalette.SURFACE);
        card.setRadius(dp(radius));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(stroke);
        card.setCardElevation(0);
        card.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private LinearLayout details() {
        LinearLayout details = column();
        details.setPadding(dp(14), dp(4), dp(14), dp(14));
        return details;
    }

    private RangeSlider range(float from, float to, float step, float first, float second) {
        RangeSlider value = new RangeSlider(this);
        value.setValueFrom(from);
        value.setValueTo(to);
        value.setStepSize(step);
        // Allow both thumbs on the same value (e.g. 0–0 / 3–3). Default separation blocks that.
        try {
            value.setMinSeparationValue(0f);
        } catch (RuntimeException ignored) {
            // Older Material may not expose separation APIs; number fields cover critical cases.
        }
        float lo = Math.max(from, Math.min(first, second));
        float hi = Math.min(to, Math.max(first, second));
        value.setValues(lo, hi);
        value.setTickVisible(false);
        value.setTrackHeight(dp(4));
        value.setThumbWidth(dp(18));
        value.setThumbHeight(dp(18));
        value.setTrackActiveTintList(ColorStateList.valueOf(RpaPalette.PINK));
        value.setThumbTintList(ColorStateList.valueOf(RpaPalette.PINK));
        value.addOnChangeListener((slider, changed, user) -> refreshSummaries());
        return value;
    }

    private MaterialButton presetChip(String label, int minSeconds, int maxSeconds) {
        MaterialButton button = baseButton(label, () -> {
            pauseMin.setText(String.valueOf(minSeconds));
            pauseMax.setText(String.valueOf(maxSeconds));
            refreshSummaries();
        });
        button.setTextSize(12);
        button.setTextColor(RpaPalette.TEXT);
        button.setBackgroundTintList(ColorStateList.valueOf(RpaPalette.SURFACE_RAISED));
        button.setStrokeColor(ColorStateList.valueOf(RpaPalette.BORDER));
        button.setStrokeWidth(dp(1));
        button.setCornerRadius(dp(10));
        button.setMinHeight(dp(40));
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    private void refreshOnText(EditText value) {
        value.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                refreshSummaries();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
    }

    private static int integerOr(EditText value, int fallback) {
        try {
            String text = value.getText() == null ? "" : value.getText().toString().trim();
            if (text.isEmpty()) return fallback;
            return Integer.parseInt(text);
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private Slider rateSlider() {
        Slider value = new Slider(this);
        value.setValueFrom(0);
        value.setValueTo(100);
        value.setStepSize(1);
        value.setTickVisible(false);
        value.setTrackHeight(dp(4));
        value.setThumbWidth(dp(18));
        value.setThumbHeight(dp(18));
        value.setTrackActiveTintList(ColorStateList.valueOf(RpaPalette.PINK));
        value.setThumbTintList(ColorStateList.valueOf(RpaPalette.PINK));
        value.addOnChangeListener((slider, changed, user) -> refreshSummaries());
        return value;
    }

    private View sliderBlock(String label, Slider slider) {
        LinearLayout block = column();
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(fieldLabel(label), weighted());
        TextView value = text("0%", 14, RpaPalette.PINK);
        top.addView(value);
        slider.addOnChangeListener((control, changed, fromUser) ->
                value.setText(String.format(Locale.CHINA, "%d%%", Math.round(changed))));
        block.addView(top);
        block.addView(slider);
        return block;
    }

    private MaterialButtonToggleGroup choices(String[] labels) {
        MaterialButtonToggleGroup group = new MaterialButtonToggleGroup(this);
        group.setSingleSelection(true);
        group.setSelectionRequired(true);
        for (String label : labels) {
            MaterialButton button = baseButton(label, () -> {});
            button.setId(View.generateViewId());
            button.setCheckable(true);
            button.setTextSize(13);
            button.setTextColor(RpaPalette.TEXT);
            button.setBackgroundTintList(ColorStateList.valueOf(RpaPalette.SURFACE_RAISED));
            button.setStrokeColor(ColorStateList.valueOf(RpaPalette.BORDER));
            button.setStrokeWidth(dp(1));
            group.addView(button, new LinearLayout.LayoutParams(0, dp(44), 1f));
        }
        group.addOnButtonCheckedListener((g, id, checked) -> {
            if (checked) {
                for (int index = 0; index < g.getChildCount(); index++) {
                    MaterialButton button = (MaterialButton) g.getChildAt(index);
                    boolean selected = button.getId() == id;
                    button.setBackgroundTintList(ColorStateList.valueOf(
                            selected ? RpaPalette.PINK_DARK : RpaPalette.SURFACE_RAISED));
                    button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
                }
                refreshSummaries();
            }
        });
        return group;
    }

    private View field(String label, EditText editText) {
        TextInputLayout field = new TextInputLayout(
                this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        field.setHint(label);
        field.setDefaultHintTextColor(ColorStateList.valueOf(RpaPalette.TEXT_MUTED));
        field.setHintTextColor(new ColorStateList(
                new int[][] { new int[] { android.R.attr.state_focused }, new int[] {} },
                new int[] { RpaPalette.PINK, RpaPalette.TEXT_MUTED }));
        CharSequence placeholder = editText.getHint();
        if (placeholder != null && placeholder.length() > 0) {
            field.setPlaceholderText(placeholder);
            field.setPlaceholderTextColor(ColorStateList.valueOf(RpaPalette.TEXT_MUTED));
            editText.setHint(null);
        }
        field.addView(editText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        field.setBoxBackgroundColor(RpaPalette.SURFACE_RAISED);
        field.setBoxStrokeColorStateList(new ColorStateList(
                new int[][] { new int[] { android.R.attr.state_focused }, new int[] {} },
                new int[] { RpaPalette.PINK, RpaPalette.BORDER }));
        field.setBoxStrokeWidth(dp(2));
        field.setBoxStrokeWidthFocused(dp(2));
        field.setBoxCornerRadii(dp(10), dp(10), dp(10), dp(10));
        field.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
        field.setEndIconDrawable(R.drawable.ic_keyboard);
        field.setEndIconTintList(ColorStateList.valueOf(RpaPalette.TEXT_MUTED));
        field.setEndIconContentDescription("点击填写" + label);
        LinearLayout wrapper = column();
        wrapper.setPadding(0, dp(6), 0, dp(4));
        wrapper.addView(field);
        return wrapper;
    }

    private EditText input(String hint) {
        TextInputEditText value = new TextInputEditText(this);
        value.setHint(hint);
        value.setTextColor(RpaPalette.TEXT);
        value.setHintTextColor(RpaPalette.TEXT_MUTED);
        value.setTextSize(14);
        value.setPadding(dp(12), dp(10), dp(12), dp(10));
        value.setBackgroundColor(Color.TRANSPARENT);
        value.setSingleLine(false);
        value.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { refreshSummaries(); }
            @Override public void afterTextChanged(Editable value) {}
        });
        return value;
    }

    private EditText number(String value, boolean decimal) {
        EditText input = input("");
        input.setText(value);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        return input;
    }

    private MaterialCheckBox checkbox(String label) {
        MaterialCheckBox value = new MaterialCheckBox(this);
        value.setText(label);
        value.setTextSize(14);
        value.setTextColor(RpaPalette.TEXT);
        value.setButtonTintList(ColorStateList.valueOf(RpaPalette.PINK));
        value.setMinHeight(dp(44));
        return value;
    }

    private MaterialSwitch optionSwitch(String label, boolean checked) {
        MaterialSwitch value = new MaterialSwitch(this);
        value.setText(label);
        value.setTextSize(14);
        value.setTextColor(RpaPalette.TEXT);
        value.setChecked(checked);
        value.setMinHeight(dp(48));
        value.setThumbTintList(new ColorStateList(
                new int[][] {
                        new int[] { android.R.attr.state_checked },
                        new int[] {}
                },
                new int[] { Color.WHITE, RpaPalette.TEXT_MUTED }));
        value.setTrackTintList(new ColorStateList(
                new int[][] {
                        new int[] { android.R.attr.state_checked },
                        new int[] {}
                },
                new int[] { RpaPalette.PINK, RpaPalette.SURFACE_RAISED }));
        value.setOnCheckedChangeListener((button, isChecked) -> refreshSummaries());
        return value;
    }

    private MaterialButton profileButton(String label, String profile) {
        MaterialButton button = baseButton(label, () -> selectProfile(profile));
        button.setTextSize(13);
        button.setMaxLines(1);
        button.setMinWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setCornerRadius(dp(8));
        return button;
    }

    private void styleProfileButton(MaterialButton button, boolean selected) {
        button.setTextColor(selected ? Color.WHITE : RpaPalette.TEXT_MUTED);
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        button.setBackgroundTintList(ColorStateList.valueOf(
                selected ? RpaPalette.PINK_DARK : Color.TRANSPARENT));
        button.setStrokeWidth(0);
    }

    private MaterialButton primaryButton(String label, Runnable action) {
        MaterialButton button = baseButton(label, action);
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(ColorStateList.valueOf(RpaPalette.PINK));
        button.setCornerRadius(dp(14));
        return button;
    }

    private MaterialButton secondaryButton(String label, Runnable action) {
        MaterialButton button = baseButton(label, action);
        button.setTextSize(15);
        button.setTextColor(RpaPalette.TEXT);
        button.setBackgroundTintList(ColorStateList.valueOf(RpaPalette.SURFACE_RAISED));
        button.setStrokeColor(ColorStateList.valueOf(RpaPalette.BORDER));
        button.setStrokeWidth(dp(1));
        button.setCornerRadius(dp(14));
        return button;
    }

    private MaterialButton baseButton(String label, Runnable action) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private MaterialButton iconButton(int iconResource, String description, Runnable action) {
        MaterialButton button = baseButton("", action);
        button.setIconResource(iconResource);
        button.setIconTint(ColorStateList.valueOf(RpaPalette.TEXT));
        button.setIconPadding(0);
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setContentDescription(description);
        button.setMinWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        return button;
    }

    private TextView sectionLabel(String label) {
        TextView view = text(label, 16, RpaPalette.TEXT_MUTED);
        view.setPadding(0, dp(18), 0, dp(8));
        return view;
    }

    private TextView fieldLabel(String label) {
        TextView view = text(label, 13, RpaPalette.TEXT_MUTED);
        view.setPadding(0, dp(8), 0, dp(2));
        return view;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout row() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.HORIZONTAL);
        return value;
    }

    private LinearLayout column() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        return value;
    }

    private View horizontalSpace(int width) {
        View value = new View(this);
        value.setLayoutParams(new LinearLayout.LayoutParams(dp(width), 1));
        return value;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable value = new GradientDrawable();
        value.setColor(color);
        value.setCornerRadius(dp(radius));
        return value;
    }

    private GradientDrawable outlined(int fill, int stroke, int radius) {
        GradientDrawable value = round(fill, radius);
        value.setStroke(dp(1), stroke);
        return value;
    }

    private void checkChoice(MaterialButtonToggleGroup group, int index) {
        if (group.getChildCount() == 0) return;
        int safe = Math.max(0, Math.min(index, group.getChildCount() - 1));
        group.check(group.getChildAt(safe).getId());
    }

    private int checkedIndex(MaterialButtonToggleGroup group) {
        int id = group.getCheckedButtonId();
        for (int index = 0; index < group.getChildCount(); index++) {
            if (group.getChildAt(index).getId() == id) return index;
        }
        return 0;
    }

    private static int paceIndex(String pace) {
        if ("slow".equals(pace)) return 0;
        if ("fast".equals(pace)) return 2;
        return 1;
    }

    private static String paceValue(int index) { return index == 0 ? "slow" : index == 2 ? "fast" : "natural"; }
    private static String paceLabel(int index) { return index == 0 ? "慢速" : index == 2 ? "快速" : "自然"; }
    private static String rangeSummary(RangeSlider range) {
        return Math.round(range.getValues().get(0)) + "–" + Math.round(range.getValues().get(1));
    }
    private static int integer(EditText value) { return Integer.parseInt(value.getText().toString().trim()); }
    private static String safe(EditText value) {
        String text = value.getText().toString().trim();
        return text.isEmpty() ? "0" : text;
    }
    private static List<String> csv(String value) { return split(value, "[,，\\n]"); }
    private static List<String> lines(String value) { return split(value, "\\n"); }
    private static List<String> split(String value, String pattern) {
        List<String> result = new ArrayList<>();
        for (String item : value.split(pattern)) {
            String normalized = item.trim();
            if (!normalized.isEmpty() && !result.contains(normalized)) result.add(normalized);
        }
        return result;
    }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class ModuleCard {
        final String title;
        final MaterialCardView root;
        final MaterialSwitch toggle;
        final TextView summary;
        final ImageView arrow;
        final View details;

        ModuleCard(String title, int iconResource, boolean switchable, View details) {
            this.title = title;
            this.details = details;
            LinearLayout content = column();
            LinearLayout header = row();
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(12), dp(10), dp(8), dp(10));

            MaterialCardView iconSurface = new MaterialCardView(SettingsActivity.this);
            iconSurface.setRadius(dp(18));
            int accent = moduleColor(title);
            iconSurface.setCardBackgroundColor(Color.argb(52, Color.red(accent), Color.green(accent), Color.blue(accent)));
            iconSurface.setCardElevation(0);
            ImageView icon = new ImageView(SettingsActivity.this);
            icon.setImageResource(iconResource);
            icon.setColorFilter(accent);
            icon.setPadding(dp(8), dp(8), dp(8), dp(8));
            iconSurface.addView(icon, new FrameLayout.LayoutParams(dp(36), dp(36)));
            header.addView(iconSurface, new LinearLayout.LayoutParams(dp(36), dp(36)));

            LinearLayout copy = column();
            copy.setPadding(dp(10), 0, dp(6), 0);
            TextView label = text(title, 16, RpaPalette.TEXT);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            summary = text("未启用", 12, RpaPalette.TEXT_MUTED);
            copy.addView(label);
            copy.addView(summary);
            header.addView(copy, weighted());

            toggle = new MaterialSwitch(SettingsActivity.this);
            toggle.setThumbTintList(new ColorStateList(
                    new int[][] {
                            new int[] { android.R.attr.state_checked },
                            new int[] {}
                    },
                    new int[] { Color.WHITE, RpaPalette.TEXT_MUTED }));
            toggle.setTrackTintList(new ColorStateList(
                    new int[][] {
                            new int[] { android.R.attr.state_checked },
                            new int[] {}
                    },
                    new int[] { RpaPalette.PINK, RpaPalette.SURFACE_RAISED }));
            toggle.setMinWidth(dp(48));
            toggle.setContentDescription("启用" + title);
            header.addView(toggle, new LinearLayout.LayoutParams(dp(52), dp(48)));

            arrow = new ImageView(SettingsActivity.this);
            arrow.setImageResource(R.drawable.ic_expand_more);
            arrow.setColorFilter(RpaPalette.TEXT_MUTED);
            arrow.setPadding(dp(7), dp(7), dp(7), dp(7));
            arrow.setContentDescription("展开" + title + "参数");
            header.addView(arrow, new LinearLayout.LayoutParams(dp(36), dp(48)));

            content.addView(header);
            View divider = new View(SettingsActivity.this);
            divider.setBackgroundColor(RpaPalette.BORDER);
            content.addView(divider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            details.setVisibility(View.GONE);
            content.addView(details);
            root = materialCard(content, 14, RpaPalette.BORDER);
            header.setOnClickListener(view -> expand(this));
            arrow.setOnClickListener(view -> expand(this));
            toggle.setOnCheckedChangeListener((button, checked) -> {
                if (!switchable && !checked) {
                    toggle.setChecked(true);
                    return;
                }
                refreshSummaries();
                refreshWorkflowRows();
            });
        }
    }

    private int moduleColor(String title) {
        switch (title) {
            case "评论区浏览": return Color.rgb(166, 112, 236);
            case "点赞": return Color.rgb(255, 100, 110);
            case "收藏": return Color.rgb(255, 164, 55);
            case "评论": return Color.rgb(96, 139, 225);
            case "关注": return Color.rgb(66, 201, 143);
            case "Repost（公开转发）": return Color.rgb(44, 188, 207);
            case "感兴趣判定": return Color.rgb(77, 196, 118);
            case "不感兴趣": return Color.rgb(235, 90, 101);
            case "安全与恢复": return Color.rgb(131, 112, 232);
            default: return RpaPalette.PINK;
        }
    }
}
