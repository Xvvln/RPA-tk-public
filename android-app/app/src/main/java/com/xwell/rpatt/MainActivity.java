package com.xwell.rpatt;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.xwell.rpatt.automation.AccessibilityServiceStatus;
import com.xwell.rpatt.automation.PageAnalyzer;
import com.xwell.rpatt.automation.ResolverReplayAudit;
import com.xwell.rpatt.automation.TikTokPermissionProbe;
import com.xwell.rpatt.config.ConfigStore;
import com.xwell.rpatt.config.WarmupConfig;
import com.xwell.rpatt.service.WarmupForegroundService;
import com.xwell.rpatt.engine.RunSnapshot;
import com.xwell.rpatt.runtime.WarmupSessionController;
import com.xwell.rpatt.storage.InterruptedRunReconciler;
import com.xwell.rpatt.ui.RpaPalette;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;

public final class MainActivity extends Activity {
    public static final String EXTRA_SAFE_CONTROL_READY = "safe_control_ready";
    private StatusCell accessibilityCell;
    private StatusCell overlayCell;
    private StatusCell tiktokCell;
    private TextView planTitle;
    private TextView planMeta;
    private TextView interactionDetail;
    private TextView tiktokPermSummary;
    private TextView tiktokPermVersion;
    private ImageView historyIcon;
    private TextView history;
    private MaterialButton startTaskButton;
    private WarmupConfig currentConfig;
    private boolean interruptionDialogShown;
    private boolean appliedLightTheme;

    @Override protected void onCreate(Bundle savedInstanceState) {
        appliedLightTheme = RpaPalette.prepare(this);
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        if (getIntent().getBooleanExtra("resolver_audit", false)) {
            new Thread(() -> {
                try { ResolverReplayAudit.run(this); } catch (Exception ignored) {}
            }, "resolver-replay-audit").start();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (appliedLightTheme != RpaPalette.isLightMode(this)) {
            recreate();
            return;
        }
        reconcileInterruptedRuns();
        currentConfig = ConfigStore.load(this);
        if (!currentConfig.interactionsApply) {
            currentConfig.interactionsApply = true;
            ConfigStore.save(this, currentConfig);
        }
        updateDashboard(currentConfig);
        refreshStatus();
        refreshTikTokPermissions();
        refreshHistory();
        RunSnapshot session = WarmupSessionController.currentSession();
        boolean active = WarmupSessionController.hasActiveSession();
        startTaskButton.setText(active ? "返回 TikTok 并继续" : "开始任务");
        boolean safeControlReady = getIntent().getBooleanExtra(
                EXTRA_SAFE_CONTROL_READY, false);
        getIntent().removeExtra(EXTRA_SAFE_CONTROL_READY);
        if ((session.status == RunSnapshot.Status.RUNNING
                || session.status == RunSnapshot.Status.PAUSING)
                && !safeControlReady) {
            Intent handoff = new Intent(this, WarmupForegroundService.class)
                    .setAction(WarmupForegroundService.ACTION_OPEN_CONTROL);
            startForegroundService(handoff);
            moveTaskToBack(true);
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(RpaPalette.BACKGROUND);

        LinearLayout root = column();
        root.setPadding(dp(18), dp(12), dp(18), dp(24));
        scroll.addView(root);

        root.addView(buildHeader());
        root.addView(space(14));
        root.addView(buildReadinessCard());
        root.addView(sectionLabel("TikTok 权限"));
        root.addView(buildTikTokPermissionCard());
        root.addView(sectionLabel("任务方案"));
        root.addView(buildPlanCard());
        root.addView(sectionLabel("互动策略"));
        root.addView(buildInteractionCard());
        root.addView(space(12));
        startTaskButton = primaryButton("开始任务", this::startTask);
        root.addView(startTaskButton);
        root.addView(space(10));
        root.addView(secondaryButton("调整方案", this::openSettings));
        root.addView(sectionLabel("最近运行"));
        root.addView(buildHistoryCard());
        return scroll;
    }

    private View buildHeader() {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout copy = column();
        TextView title = text("TK养号助手", 24, RpaPalette.TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(title);
        copy.addView(text("本机养号任务", 14, RpaPalette.TEXT_MUTED));
        row.addView(copy, weighted());

        LinearLayout avatar = column();
        avatar.setGravity(Gravity.CENTER);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.tk_logo_mark);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        logo.setPadding(dp(4), dp(4), dp(4), dp(4));
        logo.setBackground(round(RpaPalette.SURFACE, 48));
        avatar.addView(logo, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView settings = text("设置", 10, RpaPalette.TEXT_MUTED);
        settings.setGravity(Gravity.CENTER);
        avatar.addView(settings, new LinearLayout.LayoutParams(dp(50), dp(18)));
        avatar.setContentDescription("打开应用设置");
        avatar.setClickable(true);
        avatar.setFocusable(true);
        avatar.setOnClickListener(view -> startActivity(new Intent(this, ApplicationSettingsActivity.class)));
        row.addView(avatar, new LinearLayout.LayoutParams(dp(56), dp(64)));
        return row;
    }

    private View buildReadinessCard() {
        LinearLayout content = row();
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(6), dp(9), dp(6), dp(9));

        accessibilityCell = new StatusCell("无障碍", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        overlayCell = new StatusCell("悬浮窗", () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        tiktokCell = new StatusCell("TikTok", this::openTikTok);
        content.addView(accessibilityCell.view, weighted());
        content.addView(divider(true));
        content.addView(overlayCell.view, weighted());
        content.addView(divider(true));
        content.addView(tiktokCell.view, weighted());
        return materialCard(content, 14, RpaPalette.BORDER);
    }

    /** Compact summary; detail lives in {@link TikTokPermissionActivity}. */
    private View buildTikTokPermissionCard() {
        LinearLayout content = column();
        content.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = column();
        tiktokPermSummary = text("检测中…", 15, RpaPalette.TEXT);
        tiktokPermSummary.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tiktokPermVersion = text("点开查看并处理", 12, RpaPalette.TEXT_MUTED);
        tiktokPermVersion.setPadding(0, dp(3), 0, 0);
        copy.addView(tiktokPermSummary);
        copy.addView(tiktokPermVersion);
        header.addView(copy, weighted());
        header.addView(trailingIcon());
        content.addView(header);

        MaterialCardView card = materialCard(content, 12, RpaPalette.BORDER);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view ->
                startActivity(new Intent(this, TikTokPermissionActivity.class)));
        return card;
    }

    private void refreshTikTokPermissions() {
        if (tiktokPermSummary == null) return;
        TikTokPermissionProbe.Report report = TikTokPermissionProbe.probe(this);
        boolean friendly = report.warmupFriendly();
        int accent = !report.tiktokInstalled ? RpaPalette.RED
                : (friendly ? RpaPalette.GREEN : RpaPalette.AMBER);
        tiktokPermSummary.setText(report.summaryLine());
        tiktokPermSummary.setTextColor(accent);
        tiktokPermVersion.setText(report.detailHint());
    }

    private View buildPlanCard() {
        LinearLayout card = column();
        planTitle = text("常规养号", 24, RpaPalette.TEXT);
        planTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(planTitle);
        planMeta = text("30 分钟 · 20 条视频", 15, RpaPalette.TEXT_MUTED);
        planMeta.setPadding(0, dp(3), 0, 0);
        card.addView(planMeta);
        return card;
    }

    private View buildInteractionCard() {
        LinearLayout content = row();
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout copy = column();
        TextView title = text("按方案实际执行", 15, RpaPalette.PINK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        interactionDetail = text("点赞 20% · 收藏 8% · 评论关闭", 12, RpaPalette.TEXT_MUTED);
        copy.addView(title);
        copy.addView(interactionDetail);
        content.addView(copy, weighted());
        content.addView(trailingIcon());
        MaterialCardView card = materialCard(content, 12, RpaPalette.PINK_DARK);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> openSettings());
        return card;
    }

    private View buildHistoryCard() {
        LinearLayout content = row();
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(12), dp(10), dp(12), dp(10));
        historyIcon = new ImageView(this);
        historyIcon.setImageResource(R.drawable.ic_clock);
        historyIcon.setColorFilter(RpaPalette.TEXT_MUTED);
        content.addView(historyIcon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        history = text("暂无任务", 14, RpaPalette.TEXT_MUTED);
        history.setPadding(dp(12), 0, dp(8), 0);
        content.addView(history, weighted());
        content.addView(trailingIcon());
        MaterialCardView card = materialCard(content, 12, RpaPalette.BORDER);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> startActivity(new Intent(this, RunHistoryActivity.class)));
        return card;
    }

    private void updateDashboard(WarmupConfig config) {
        planTitle.setText(profileName(config.profile));
        planMeta.setText(String.format(Locale.CHINA, "%s · %d 分钟 · %d 条视频",
                WarmupConfig.JOURNEY_RECOMMENDATION_LED.equals(config.journeyMode)
                        ? "推荐驱动" : "顺序流程",
                config.maxMinutes, config.videoCount));
        int enabled = (config.likeEnabled ? 1 : 0) + (config.favoriteEnabled ? 1 : 0)
                + (config.commentEnabled ? 1 : 0) + (config.followEnabled ? 1 : 0)
                + (config.shareEnabled ? 1 : 0) + (config.openComments ? 1 : 0)
                + (config.searchEnabled ? 1 : 0) + (config.interestEnabled ? 1 : 0)
                + (config.hasNotInterestedTrigger() ? 1 : 0);
        interactionDetail.setText(String.format(Locale.CHINA, "已启用 %d 项 · 点赞 %s · 收藏 %s",
                enabled,
                config.likeEnabled ? percent(config.likeRate) + "%" : "关闭",
                config.favoriteEnabled ? percent(config.favoriteRate) + "%" : "关闭"));
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void startTask() {
        if (WarmupSessionController.hasActiveSession()) {
            Intent intent = new Intent(this, WarmupForegroundService.class)
                    .setAction(WarmupForegroundService.ACTION_RETURN_TO_TASK);
            startForegroundService(intent);
            toast("正在返回 TikTok 并继续任务");
            return;
        }
        if (!prepareForService()) return;
        Intent intent = new Intent(this, WarmupForegroundService.class).setAction(WarmupForegroundService.ACTION_START);
        startForegroundService(intent);
        toast("任务已开始，正在打开 TikTok");
    }

    private boolean prepareForService() {
        try {
            currentConfig.validate();
            ConfigStore.save(this, currentConfig);
        } catch (RuntimeException error) {
            new MaterialAlertDialogBuilder(this).setTitle("配置有误").setMessage(error.getMessage())
                    .setPositiveButton("去调整", (dialog, which) -> openSettings()).show();
            return false;
        }
        AccessibilityServiceStatus.State accessibility =
                AccessibilityServiceStatus.query(this);
        if (!AccessibilityServiceStatus.isReady(accessibility)) {
            String message;
            if (accessibility == AccessibilityServiceStatus.State.NOT_REGISTERED) {
                message = "系统没有发现服务组件，请重新安装当前版本后再检查。";
            } else if (accessibility
                    == AccessibilityServiceStatus.State.AUTHORIZED_NOT_CONNECTED) {
                message = "系统已保留授权，但服务尚未连接。请在无障碍列表关闭后重新开启一次。";
            } else {
                message = "系统当前没有授权。请在列表中找到“TK养号助手 · 页面操作辅助”并开启。";
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle("无障碍尚未就绪")
                    .setMessage(message)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("去系统设置", (dialog, which) ->
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .show();
            return false;
        }
        if (!tiktokInstalled()) {
            toast("没有检测到国际版 TikTok");
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 33);
            toast("请允许通知后再次操作");
            return false;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            return false;
        }
        return true;
    }

    private void refreshStatus() {
        AccessibilityServiceStatus.State accessibility =
                AccessibilityServiceStatus.query(this);
        boolean overlay = Settings.canDrawOverlays(this);
        boolean tiktok = tiktokInstalled();
        accessibilityCell.render(AccessibilityServiceStatus.isReady(accessibility),
                AccessibilityServiceStatus.shortLabel(accessibility));
        overlayCell.render(overlay, overlay ? "已允许" : "需授权");
        tiktokCell.render(tiktok, tiktok ? "已连接" : "未安装");
    }

    private void refreshHistory() {
        File root = new File(getFilesDir(), "runs");
        File[] runs = root.listFiles(File::isDirectory);
        if (runs == null || runs.length == 0) {
            historyIcon.setColorFilter(RpaPalette.TEXT_MUTED);
            history.setText("暂无任务");
            return;
        }
        Arrays.sort(runs, Comparator.comparing(File::getName).reversed());
        File latest = runs[0];
        File summary = new File(latest, "summary.json");
        if (!summary.isFile()) {
            historyIcon.setColorFilter(RpaPalette.AMBER);
            RunSnapshot active = WarmupSessionController.currentSession();
            if (WarmupSessionController.hasActiveSession()) {
                String activeLabel = active.status == RunSnapshot.Status.PAUSED ? "已暂停"
                        : (active.status == RunSnapshot.Status.PAUSING ? "暂停中"
                        : (active.status == RunSnapshot.Status.STOPPING ? "停止中"
                        : (active.status == RunSnapshot.Status.BLOCKED
                        ? "已中断，可继续" : "正在运行")));
                history.setText(String.format(Locale.CHINA, "%s · %s\n%d/%d 条 · %s",
                        profileName(currentConfig.profile),
                        activeLabel,
                        active.videosSeen, active.videoLimit, active.step));
            } else {
                history.setText(String.format(Locale.CHINA, "%s · 未正常收尾",
                        profileName(currentConfig.profile)));
            }
            return;
        }
        try {
            JSONObject value = new JSONObject(new String(Files.readAllBytes(summary.toPath()), StandardCharsets.UTF_8));
            String reason = value.optString("stop_reason", "finished");
            boolean completed = "video_limit".equals(reason) || "video_budget".equals(reason)
                    || "max_minutes".equals(reason) || "time_budget".equals(reason)
                    || "completed".equals(reason) || "finished".equals(reason);
            boolean userStopped = "user".equals(reason) || "user_stop".equals(reason);
            historyIcon.setColorFilter(completed ? RpaPalette.GREEN
                    : (userStopped ? RpaPalette.AMBER : RpaPalette.RED));
            history.setText(String.format(Locale.CHINA, "%s · %d 条视频\n%s",
                    profileName(currentConfig.profile), value.optInt("videos_seen"), stopReasonLabel(reason)));
        } catch (Exception ignored) {
            history.setText(latest.getName());
        }
    }

    private void reconcileInterruptedRuns() {
        try {
            List<InterruptedRunReconciler.Result> recovered =
                    InterruptedRunReconciler.reconcile(
                            getFilesDir(), WarmupSessionController.hasActiveSession(),
                            System.currentTimeMillis());
            if (!recovered.isEmpty() && !interruptionDialogShown) {
                interruptionDialogShown = true;
                InterruptedRunReconciler.Result latest = recovered.get(0);
                new MaterialAlertDialogBuilder(this)
                        .setTitle("上次任务异常中断")
                        .setMessage(String.format(Locale.CHINA,
                                "已归档 %d 条视频和 %d 次已确认操作。系统不会重复这些操作，请先查看运行记录。",
                                latest.summary.optInt("videos_seen"),
                                latest.summary.optInt("external_actions_committed")))
                        .setNegativeButton("稍后查看", null)
                        .setPositiveButton("查看记录", (dialog, which) ->
                                startActivity(new Intent(this, RunHistoryActivity.class)))
                        .show();
            }
        } catch (Exception ignored) {}
    }

    private boolean tiktokInstalled() {
        return getPackageManager().getLaunchIntentForPackage(PageAnalyzer.TIKTOK_PACKAGE) != null;
    }

    private void openTikTok() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(PageAnalyzer.TIKTOK_PACKAGE);
        if (intent == null) toast("没有检测到国际版 TikTok");
        else startActivity(intent);
    }

    private LinearLayout column() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        return value;
    }

    private LinearLayout row() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.HORIZONTAL);
        return value;
    }

    private TextView sectionLabel(String value) {
        TextView view = text(value, 16, RpaPalette.TEXT_MUTED);
        view.setPadding(0, dp(17), 0, dp(7));
        return view;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private ImageView trailingIcon() {
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_chevron_right);
        icon.setColorFilter(RpaPalette.TEXT_MUTED);
        icon.setPadding(dp(4), dp(4), dp(4), dp(4));
        icon.setContentDescription("打开详情");
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
        return icon;
    }

    private MaterialButton primaryButton(String value, Runnable action) {
        MaterialButton button = baseButton(value, action);
        button.setTextSize(18);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(ColorStateList.valueOf(RpaPalette.PINK));
        button.setCornerRadius(dp(14));
        button.setMinHeight(dp(52));
        return button;
    }

    private MaterialButton secondaryButton(String value, Runnable action) {
        MaterialButton button = baseButton(value, action);
        button.setTextSize(17);
        button.setTextColor(RpaPalette.TEXT);
        button.setBackgroundTintList(ColorStateList.valueOf(RpaPalette.SURFACE_RAISED));
        button.setStrokeColor(ColorStateList.valueOf(RpaPalette.BORDER));
        button.setStrokeWidth(dp(1));
        button.setCornerRadius(dp(14));
        button.setMinHeight(dp(50));
        return button;
    }

    private MaterialButton baseButton(String value, Runnable action) {
        MaterialButton button = new MaterialButton(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private MaterialCardView materialCard(View content, int radiusDp, int strokeColor) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(RpaPalette.SURFACE);
        card.setRadius(dp(radiusDp));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(strokeColor);
        card.setCardElevation(0);
        card.setUseCompatPadding(false);
        card.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private View divider(boolean vertical) {
        View divider = new View(this);
        divider.setBackgroundColor(RpaPalette.BORDER);
        divider.setLayoutParams(vertical
                ? new LinearLayout.LayoutParams(dp(1), dp(40))
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        return divider;
    }

    private View space(int height) {
        View value = new View(this);
        value.setLayoutParams(new LinearLayout.LayoutParams(height == 8 ? dp(8) : 1, dp(height)));
        return value;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams match(int height, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
        params.topMargin = topMargin;
        return params;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable outlined(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = round(fill, radiusDp);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private static int percent(double value) { return (int) Math.round(value * 100); }

    static String profileName(String profile) {
        switch (profile) {
            case "normal": return "常规养号";
            case "active": return "积极养号";
            case "custom": return "自定义方案";
            default: return "轻量养号";
        }
    }

    static String stopReasonLabel(String reason) {
        switch (reason) {
            case "video_limit":
            case "video_budget": return "已完成 · 达到视频上限";
            case "max_minutes":
            case "time_budget": return "已完成 · 达到时长上限";
            case "user":
            case "user_stop": return "已停止 · 用户结束";
            case "comments_not_opened": return "需人工处理 · 评论区未打开";
            case "search_submit_failed": return "需人工处理 · 搜索提交失败";
            case "search_entry_missing": return "需人工处理 · 未找到搜索入口";
            case "search_result_not_opened": return "需人工处理 · 未打开搜索结果";
            case "next_video_unverified": return "需人工处理 · 未能确认切换到下一条";
            case "unexpected_page": return "需人工处理 · 页面状态异常";
            case "accessibility_window_gap": return "需人工处理 · 无障碍短暂读不到窗口，请回 TikTok 重试";
            case "tiktok_not_foreground": return "需人工处理 · TikTok 不在前台";
            case "accessibility_disconnected": return "需人工处理 · 无障碍未连接";
            case "process_interrupted": return "需人工处理 · 任务进程异常中断";
            default: return "需人工处理 · " + reason.replace('_', ' ');
        }
    }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class StatusCell {
        final LinearLayout view = column();
        final TextView icon = text("✓", 11, Color.WHITE);
        final TextView labelView;
        final TextView detail = text("", 11, RpaPalette.TEXT_MUTED);

        StatusCell(String label, Runnable action) {
            view.setGravity(Gravity.CENTER);
            view.setPadding(dp(4), dp(2), dp(4), dp(2));
            icon.setGravity(Gravity.CENTER);
            icon.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            icon.setBackground(round(RpaPalette.GREEN, 20));
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(20), dp(20));
            iconParams.gravity = Gravity.CENTER_HORIZONTAL;
            view.addView(icon, iconParams);
            labelView = text(label, 12, RpaPalette.TEXT);
            labelView.setGravity(Gravity.CENTER);
            labelView.setPadding(0, dp(4), 0, 0);
            detail.setGravity(Gravity.CENTER);
            view.addView(labelView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            view.addView(detail, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            view.setOnClickListener(v -> action.run());
        }

        void render(boolean ready, String message) {
            int color = ready ? RpaPalette.GREEN : RpaPalette.RED;
            icon.setText(ready ? "✓" : "!");
            icon.setBackground(round(color, 20));
            detail.setText(message);
            detail.setTextColor(color);
        }
    }
}
