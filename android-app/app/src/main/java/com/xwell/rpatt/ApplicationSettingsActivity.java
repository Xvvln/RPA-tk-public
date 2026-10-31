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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.xwell.rpatt.automation.AccessibilityServiceStatus;
import com.xwell.rpatt.automation.PageAnalyzer;
import com.xwell.rpatt.config.ConfigStore;
import com.xwell.rpatt.config.WarmupConfig;
import com.xwell.rpatt.service.WarmupForegroundService;
import com.xwell.rpatt.ui.RpaPalette;

/** App-level permissions and overlay preferences, intentionally separate from a plan. */
public final class ApplicationSettingsActivity extends Activity {
    private MaterialSwitch lightMode;
    private MaterialSwitch startCollapsed;
    private MaterialSwitch vibrateOnBlock;
    private LinearLayout permissionRows;
    private WarmupConfig config;

    @Override protected void onCreate(Bundle savedInstanceState) {
        RpaPalette.prepare(this);
        super.onCreate(savedInstanceState);
        config = ConfigStore.load(this);
        setContentView(buildUi());
    }

    @Override protected void onResume() {
        super.onResume();
        if (startCollapsed != null) {
            config = ConfigStore.load(this);
            lightMode.setChecked(RpaPalette.isLightMode(this));
            startCollapsed.setChecked(config.startCollapsed);
            vibrateOnBlock.setChecked(config.vibrateOnBlock);
        }
        if (permissionRows != null) refreshPermissionRows();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(RpaPalette.BACKGROUND);
        LinearLayout root = column();
        root.setPadding(dp(18), dp(10), dp(18), dp(28));
        scroll.addView(root);

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton back = baseButton("", this::finish);
        back.setIconResource(R.drawable.ic_arrow_back);
        back.setIconTint(ColorStateList.valueOf(RpaPalette.TEXT));
        back.setIconPadding(0);
        back.setMinWidth(0);
        back.setContentDescription("返回");
        back.setPadding(0, 0, 0, 0);
        back.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout copy = column();
        copy.setPadding(dp(8), 0, 0, 0);
        TextView title = text("应用设置", 24, RpaPalette.TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(title);
        copy.addView(text("权限、悬浮窗与提醒", 13, RpaPalette.TEXT_MUTED));
        header.addView(copy, weighted());
        root.addView(header);

        root.addView(section("外观"));
        LinearLayout appearance = column();
        appearance.setPadding(dp(14), dp(6), dp(14), dp(8));
        lightMode = settingSwitch("浅色模式", RpaPalette.isLightMode(this));
        appearance.addView(lightMode);
        TextView appearanceNote = text("默认使用黑色模式；切换后返回首页立即生效。",
                12, RpaPalette.TEXT_MUTED);
        appearanceNote.setPadding(0, 0, 0, dp(6));
        appearance.addView(appearanceNote);
        root.addView(materialCard(appearance));

        root.addView(section("系统权限"));
        permissionRows = column();
        permissionRows.setPadding(dp(14), dp(6), dp(14), dp(10));
        refreshPermissionRows();
        root.addView(materialCard(permissionRows));

        root.addView(section("悬浮控制"));
        LinearLayout overlay = column();
        overlay.setPadding(dp(14), dp(8), dp(14), dp(12));
        startCollapsed = settingSwitch("启动时收起悬浮窗", config.startCollapsed);
        vibrateOnBlock = settingSwitch("需要人工处理时振动提醒", config.vibrateOnBlock);
        overlay.addView(startCollapsed);
        overlay.addView(vibrateOnBlock);
        MaterialButton show = secondaryButton("仅显示悬浮窗", this::showOverlay);
        LinearLayout.LayoutParams showParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        showParams.topMargin = dp(8);
        overlay.addView(show, showParams);
        root.addView(materialCard(overlay));

        root.addView(space(18));
        root.addView(primaryButton("保存设置", this::saveAndFinish), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        return scroll;
    }

    private void refreshPermissionRows() {
        permissionRows.removeAllViews();
        AccessibilityServiceStatus.State accessibility =
                AccessibilityServiceStatus.query(this);
        permissionRows.addView(permissionRow(
                "无障碍服务",
                AccessibilityServiceStatus.shortLabel(accessibility),
                AccessibilityServiceStatus.isReady(accessibility),
                () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        TextView accessibilityNote = text(
                "系统列表名称：TK养号助手 · 页面操作辅助。授权由系统保存；若系统清空，普通 App 不能静默恢复。",
                11, RpaPalette.TEXT_MUTED);
        accessibilityNote.setPadding(dp(34), 0, dp(4), dp(8));
        permissionRows.addView(accessibilityNote);
        permissionRows.addView(divider());

        boolean overlay = Settings.canDrawOverlays(this);
        permissionRows.addView(permissionRow(
                "悬浮窗权限", overlay ? "已允许" : "需授权", overlay,
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())))));
        permissionRows.addView(divider());

        boolean notified = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        permissionRows.addView(permissionRow(
                "通知权限", notified ? "已允许" : "需授权", notified,
                this::requestNotifications));
    }

    private View permissionRow(String label, String status, boolean ready, Runnable action) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView marker = text(ready ? "✓" : "!", 12, Color.WHITE);
        marker.setGravity(Gravity.CENTER);
        marker.setBackground(roundBackground(ready ? RpaPalette.GREEN : RpaPalette.RED, 12));
        row.addView(marker, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout copy = column();
        copy.setPadding(dp(10), 0, 0, 0);
        copy.addView(text(label, 15, RpaPalette.TEXT));
        copy.addView(text(status, 12, ready ? RpaPalette.GREEN : RpaPalette.RED));
        row.addView(copy, weighted());
        MaterialButton open = baseButton("去设置", action);
        open.setTextSize(12);
        open.setMaxLines(1);
        open.setTextColor(RpaPalette.TEXT);
        open.setBackgroundTintList(ColorStateList.valueOf(RpaPalette.SURFACE_RAISED));
        row.addView(open, new LinearLayout.LayoutParams(dp(88), dp(42)));
        return row;
    }

    private MaterialSwitch settingSwitch(String label, boolean checked) {
        MaterialSwitch value = new MaterialSwitch(this);
        value.setText(label);
        value.setTextSize(14);
        value.setTextColor(RpaPalette.TEXT);
        value.setChecked(checked);
        value.setGravity(Gravity.CENTER_VERTICAL);
        value.setMinHeight(dp(52));
        return value;
    }

    private void saveAndFinish() {
        config.startCollapsed = startCollapsed.isChecked();
        config.vibrateOnBlock = vibrateOnBlock.isChecked();
        ConfigStore.save(this, config);
        RpaPalette.setLightMode(this, lightMode.isChecked());
        toast("应用设置已保存");
        finish();
    }

    private void showOverlay() {
        config.startCollapsed = startCollapsed.isChecked();
        config.vibrateOnBlock = vibrateOnBlock.isChecked();
        ConfigStore.save(this, config);
        AccessibilityServiceStatus.State accessibility =
                AccessibilityServiceStatus.query(this);
        if (!AccessibilityServiceStatus.isReady(accessibility)) {
            toast(accessibility == AccessibilityServiceStatus.State.NOT_AUTHORIZED
                    ? "请开启“TK养号助手 · 页面操作辅助”"
                    : "无障碍服务尚未连接，请关闭后重新开启");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (getPackageManager().getLaunchIntentForPackage(PageAnalyzer.TIKTOK_PACKAGE) == null) {
            toast("没有检测到国际版 TikTok");
            return;
        }
        Intent intent = new Intent(this, WarmupForegroundService.class)
                .setAction(WarmupForegroundService.ACTION_SHOW);
        startForegroundService(intent);
        toast("悬浮窗已显示");
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 33);
        } else toast("当前系统无需单独授权通知");
    }

    private MaterialCardView materialCard(View content) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(RpaPalette.SURFACE);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(RpaPalette.BORDER);
        card.setRadius(dp(14));
        card.setCardElevation(0);
        card.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        return card;
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
        button.setTextColor(RpaPalette.TEXT);
        button.setBackgroundTintList(ColorStateList.valueOf(RpaPalette.SURFACE_RAISED));
        button.setStrokeColor(ColorStateList.valueOf(RpaPalette.BORDER));
        button.setStrokeWidth(dp(1));
        button.setCornerRadius(dp(12));
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

    private TextView section(String label) {
        TextView view = text(label, 16, RpaPalette.TEXT_MUTED);
        view.setPadding(0, dp(18), 0, dp(8));
        return view;
    }

    private View divider() {
        View value = new View(this);
        value.setBackgroundColor(RpaPalette.BORDER);
        value.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        return value;
    }

    private TextView text(String label, int size, int color) {
        TextView value = new TextView(this);
        value.setText(label);
        value.setTextSize(size);
        value.setTextColor(color);
        return value;
    }

    private GradientDrawable roundBackground(int color, int radiusDp) {
        GradientDrawable value = new GradientDrawable();
        value.setColor(color);
        value.setCornerRadius(dp(radiusDp));
        return value;
    }

    private LinearLayout row() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.HORIZONTAL); return value; }
    private LinearLayout column() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); }
    private View space(int height) { View value = new View(this); value.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height))); return value; }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
