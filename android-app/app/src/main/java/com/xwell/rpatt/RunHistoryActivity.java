package com.xwell.rpatt;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.xwell.rpatt.ui.RpaPalette;
import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/** Browse local run artifacts and open one run's detailed event log. */
public final class RunHistoryActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        RpaPalette.prepare(this);
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(RpaPalette.BACKGROUND);
        LinearLayout root = column();
        root.setPadding(dp(18), dp(10), dp(18), dp(32));
        scroll.addView(root);
        root.addView(header());

        File runsRoot = new File(getFilesDir(), "runs");
        File[] runs = runsRoot.listFiles(File::isDirectory);
        if (runs == null || runs.length == 0) {
            TextView empty = text("还没有运行记录", 15, RpaPalette.TEXT_MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(80), 0, 0);
            root.addView(empty);
            return scroll;
        }
        Arrays.sort(runs, Comparator.comparing(File::getName).reversed());
        TextView count = text("本机记录 · " + runs.length + " 次", 14, RpaPalette.TEXT_MUTED);
        count.setPadding(0, dp(20), 0, dp(8));
        root.addView(count);
        for (int index = 0; index < Math.min(30, runs.length); index++) {
            root.addView(runCard(runs[index]));
            root.addView(space(10));
        }
        return scroll;
    }

    private View header() {
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton back = iconButton(
                R.drawable.ic_arrow_back,
                "返回", this::finish);
        back.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout copy = column();
        copy.setPadding(dp(10), 0, 0, 0);
        TextView title = text("运行记录", 24, RpaPalette.TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(title);
        copy.addView(text("点击记录查看摘要与事件日志", 13, RpaPalette.TEXT_MUTED));
        header.addView(copy, weighted());
        return header;
    }

    private View runCard(File run) {
        LinearLayout card = row();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(12), dp(12));
        card.setBackground(outlined(RpaPalette.SURFACE, RpaPalette.BORDER, 12));

        JSONObject summary = readJson(new File(run, "summary.json"));
        JSONObject config = readJson(new File(run, "config.json"));
        String reason = summary == null ? "running" : summary.optString("stop_reason", "finished");
        int color = statusColor(reason, summary != null && summary.optBoolean("requires_human", false));
        View indicator = new View(this);
        indicator.setBackground(round(color, 20));
        card.addView(indicator, new LinearLayout.LayoutParams(dp(10), dp(10)));

        LinearLayout copy = column();
        copy.setPadding(dp(12), 0, dp(8), 0);
        String profile = config == null ? "" : config.optString("profile", "");
        TextView title = text(formatRunId(run.getName()) + (profile.isEmpty() ? "" : " · " + MainActivity.profileName(profile)),
                15, RpaPalette.TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(title);
        if (summary == null) {
            copy.addView(text("运行中或未正常收尾", 13, RpaPalette.AMBER));
        } else {
            String compact = String.format(Locale.CHINA, "%d 条 · %d 赞 · %d 藏 · %d 评",
                    summary.optInt("videos_seen"), summary.optInt("likes"),
                    summary.optInt("favorites"), summary.optInt("comments"));
            if (summary.optBoolean("partial_video_actions", false)) compact += " · 有操作未归档";
            copy.addView(text(compact, 13, summary.optBoolean("partial_video_actions", false)
                    ? RpaPalette.AMBER : RpaPalette.TEXT_MUTED));
            copy.addView(text(MainActivity.stopReasonLabel(reason), 12, color));
        }
        card.addView(copy, weighted());
        ImageView open = new ImageView(this);
        open.setImageResource(R.drawable.ic_chevron_right);
        open.setColorFilter(RpaPalette.TEXT_MUTED);
        open.setPadding(dp(4), dp(4), dp(4), dp(4));
        card.addView(open, new LinearLayout.LayoutParams(dp(28), dp(28)));
        card.setOnClickListener(view -> startActivity(new Intent(this, RunDetailActivity.class)
                .putExtra(RunDetailActivity.EXTRA_RUN_ID, run.getName())));
        return card;
    }

    private JSONObject readJson(File file) {
        if (!file.isFile()) return null;
        try { return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }
        catch (Exception ignored) { return null; }
    }

    private int statusColor(String reason, boolean requiresHuman) {
        if (requiresHuman) return RpaPalette.RED;
        if ("user".equals(reason) || "user_stop".equals(reason) || "running".equals(reason)) return RpaPalette.AMBER;
        return RpaPalette.GREEN;
    }

    private String formatRunId(String value) {
        try {
            Date date = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).parse(value);
            return new SimpleDateFormat("MM月dd日 HH:mm:ss", Locale.CHINA).format(date);
        } catch (Exception ignored) { return value; }
    }

    private LinearLayout column() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private LinearLayout row() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.HORIZONTAL); return value; }
    private TextView text(String value, int size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view; }
    private MaterialButton button(String value, Runnable action) { MaterialButton button = new MaterialButton(this); button.setText(value); button.setAllCaps(false); button.setTextColor(RpaPalette.TEXT); button.setTextSize(15); button.setInsetTop(0); button.setInsetBottom(0); button.setOnClickListener(view -> action.run()); return button; }
    private MaterialButton iconButton(int icon, String description, Runnable action) { MaterialButton button = button("", action); button.setIconResource(icon); button.setIconTint(ColorStateList.valueOf(RpaPalette.TEXT)); button.setIconPadding(0); button.setContentDescription(description); button.setMinWidth(0); button.setPadding(0, 0, 0, 0); return button; }
    private View space(int height) { View view = new View(this); view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height))); return view; }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); }
    private GradientDrawable round(int color, int radiusDp) { GradientDrawable value = new GradientDrawable(); value.setColor(color); value.setCornerRadius(dp(radiusDp)); return value; }
    private GradientDrawable outlined(int fill, int stroke, int radiusDp) { GradientDrawable value = round(fill, radiusDp); value.setStroke(dp(1), stroke); return value; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
