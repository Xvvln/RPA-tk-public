package com.xwell.rpatt;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.xwell.rpatt.automation.PageAnalyzer;
import com.xwell.rpatt.automation.TikTokPermissionProbe;
import com.xwell.rpatt.automation.WarmupPermissionPolicy;
import com.xwell.rpatt.ui.RpaPalette;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Polished TikTok permission review screen.
 * Android forbids silently changing another app's grants; CTAs open system permission UI
 * so the user can one-tap into the right page and flip switches.
 */
public final class TikTokPermissionActivity extends Activity {
    private TextView heroTitle;
    private TextView heroMeta;
    private LinearLayout listHost;
    private MaterialButton primaryAction;
    private MaterialButton secondaryAction;
    private TikTokPermissionProbe.Report report;

    @Override protected void onCreate(Bundle savedInstanceState) {
        RpaPalette.prepare(this);
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private View buildUi() {
        LinearLayout root = column();
        root.setBackgroundColor(RpaPalette.BACKGROUND);
        root.setPadding(0, 0, 0, 0);

        // Top bar
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(10), dp(16), dp(6));
        MaterialButton back = ghostButton("‹ 返回", this::finish);
        top.addView(back);
        TextView title = text("TikTok 权限", 18, RpaPalette.TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        top.addView(title, weighted());
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(dp(72), 1));
        top.addView(spacer);
        root.addView(top);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = column();
        body.setPadding(dp(18), dp(4), dp(18), dp(24));
        scroll.addView(body);

        body.addView(buildHeroCard());
        body.addView(space(14));
        body.addView(sectionLabel("按养号策略"));
        listHost = column();
        body.addView(listHost);
        body.addView(space(10));
        TextView footnote = text(
                "系统不允许第三方 App 直接改 TikTok 的权限开关。"
                        + "点下方按钮会打开系统权限页，你只需按列表关闭「建议关」项。",
                12, RpaPalette.TEXT_MUTED);
        footnote.setLineSpacing(dp(2), 1f);
        body.addView(footnote);
        body.addView(space(18));

        primaryAction = solidButton("一键打开权限设置 · 建议项", this::openTikTokPermissionManager);
        body.addView(primaryAction);
        body.addView(space(10));
        secondaryAction = outlineButton("仅打开应用详情", this::openTikTokAppDetails);
        body.addView(secondaryAction);

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollParams);
        return root;
    }

    private View buildHeroCard() {
        LinearLayout content = column();
        content.setPadding(dp(16), dp(16), dp(16), dp(16));
        heroTitle = text("检测中…", 20, RpaPalette.TEXT);
        heroTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heroMeta = text("", 13, RpaPalette.TEXT_MUTED);
        heroMeta.setPadding(0, dp(6), 0, 0);
        heroMeta.setLineSpacing(dp(2), 1f);
        content.addView(heroTitle);
        content.addView(heroMeta);
        return materialCard(content, 16, RpaPalette.BORDER);
    }

    private void refresh() {
        report = TikTokPermissionProbe.probe(this);
        int accent = !report.tiktokInstalled ? RpaPalette.RED
                : (report.warmupFriendly() ? RpaPalette.GREEN : RpaPalette.AMBER);
        heroTitle.setText(report.summaryLine());
        heroTitle.setTextColor(accent);

        if (!report.tiktokInstalled) {
            heroMeta.setText("未安装国际版 TikTok，安装后再检测。");
            primaryAction.setEnabled(false);
            secondaryAction.setEnabled(false);
        } else {
            List<String> mismatches = new ArrayList<>();
            for (TikTokPermissionProbe.Item item : report.items) {
                if (item.mismatchesPolicy()) mismatches.add(item.label);
            }
            String version = report.versionName.isEmpty() ? "未知版本" : "v" + report.versionName;
            if (mismatches.isEmpty()) {
                heroMeta.setText(String.format(Locale.CHINA,
                        "%s · 策略基线 %s\n当前无需关闭敏感权限。",
                        version, WarmupPermissionPolicy.BASELINE_VERSION_NAME));
                primaryAction.setText("打开权限设置");
            } else {
                heroMeta.setText(String.format(Locale.CHINA,
                        "%s · 策略基线 %s\n建议关闭：%s",
                        version, WarmupPermissionPolicy.BASELINE_VERSION_NAME,
                        String.join("、", mismatches)));
                primaryAction.setText("一键打开权限设置 · 去关闭建议项");
            }
            primaryAction.setEnabled(true);
            secondaryAction.setEnabled(true);
        }

        listHost.removeAllViews();
        for (TikTokPermissionProbe.Item item : report.items) {
            listHost.addView(permissionCard(item));
            listHost.addView(space(10));
        }
    }

    private View permissionCard(TikTokPermissionProbe.Item item) {
        LinearLayout content = column();
        content.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = text(item.label, 16, RpaPalette.TEXT);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        head.addView(name, weighted());
        head.addView(statusChip(item));
        content.addView(head);

        TextView policy = text(
                item.desired == WarmupPermissionPolicy.Desired.DENIED
                        ? "养号策略：建议关闭"
                        : "养号策略：开 / 关均可",
                12, RpaPalette.TEXT_MUTED);
        policy.setPadding(0, dp(8), 0, 0);
        content.addView(policy);

        TextView reason = text(item.rationale, 13, RpaPalette.TEXT);
        reason.setPadding(0, dp(4), 0, 0);
        reason.setLineSpacing(dp(1), 1f);
        content.addView(reason);

        MaterialCardView card = materialCard(content, 14, RpaPalette.BORDER);
        if (item.mismatchesPolicy()) {
            card.setStrokeColor(RpaPalette.AMBER);
            card.setStrokeWidth(dp(1));
        }
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> openTikTokPermissionManager());
        return card;
    }

    private TextView statusChip(TikTokPermissionProbe.Item item) {
        String label;
        int bg;
        if (item.mismatchesPolicy()) {
            label = "已开 · 建议关";
            bg = RpaPalette.AMBER;
        } else if (item.desired == WarmupPermissionPolicy.Desired.DENIED) {
            label = "已关 · 符合";
            bg = RpaPalette.GREEN;
        } else if (item.granted) {
            label = "已开 · 可选";
            bg = RpaPalette.SURFACE_RAISED;
        } else {
            label = "已关 · 可选";
            bg = RpaPalette.SURFACE_RAISED;
        }
        TextView chip = text(label, 11,
                bg == RpaPalette.SURFACE_RAISED ? RpaPalette.TEXT_MUTED : Color.WHITE);
        chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        chip.setBackground(round(bg, 20));
        chip.setGravity(Gravity.CENTER);
        return chip;
    }

    private void openTikTokPermissionManager() {
        if (!tiktokInstalled()) {
            toast("没有检测到国际版 TikTok");
            return;
        }
        List<String> toClose = new ArrayList<>();
        if (report != null) {
            for (TikTokPermissionProbe.Item item : report.items) {
                if (item.mismatchesPolicy()) toClose.add(item.label);
            }
        }
        if (!toClose.isEmpty()) {
            toast("请关闭：" + String.join("、", toClose));
        } else {
            toast("打开 TikTok 权限页");
        }
        // Prefer all-permissions page for the package when the OEM supports it.
        Intent manage = new Intent("android.intent.action.MANAGE_APP_PERMISSIONS");
        manage.putExtra(Intent.EXTRA_PACKAGE_NAME, PageAnalyzer.TIKTOK_PACKAGE);
        manage.putExtra("android.intent.extra.PACKAGE_NAME", PageAnalyzer.TIKTOK_PACKAGE);
        try {
            startActivity(manage);
            return;
        } catch (Exception ignored) {}
        openTikTokAppDetails();
    }

    private void openTikTokAppDetails() {
        if (!tiktokInstalled()) {
            toast("没有检测到国际版 TikTok");
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + PageAnalyzer.TIKTOK_PACKAGE));
            startActivity(intent);
        } catch (Exception error) {
            toast("无法打开 TikTok 应用详情");
        }
    }

    private boolean tiktokInstalled() {
        return getPackageManager().getLaunchIntentForPackage(PageAnalyzer.TIKTOK_PACKAGE) != null;
    }

    private TextView sectionLabel(String value) {
        TextView view = text(value, 13, RpaPalette.TEXT_MUTED);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, 0, 0, dp(8));
        view.setLetterSpacing(0.04f);
        return view;
    }

    private MaterialButton solidButton(String value, Runnable action) {
        MaterialButton button = new MaterialButton(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(ColorStateList.valueOf(RpaPalette.PINK));
        button.setCornerRadius(dp(14));
        button.setMinHeight(dp(50));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private MaterialButton outlineButton(String value, Runnable action) {
        MaterialButton button = new MaterialButton(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(RpaPalette.TEXT);
        button.setBackgroundTintList(ColorStateList.valueOf(RpaPalette.SURFACE));
        button.setStrokeColor(ColorStateList.valueOf(RpaPalette.BORDER));
        button.setStrokeWidth(dp(1));
        button.setCornerRadius(dp(14));
        button.setMinHeight(dp(46));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private MaterialButton ghostButton(String value, Runnable action) {
        MaterialButton button = new MaterialButton(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(RpaPalette.TEXT);
        button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        button.setRippleColor(ColorStateList.valueOf(RpaPalette.SURFACE_RAISED));
        button.setCornerRadius(dp(10));
        button.setMinHeight(dp(40));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setOnClickListener(v -> action.run());
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

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
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

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private View space(int height) {
        View value = new View(this);
        value.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(height)));
        return value;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
