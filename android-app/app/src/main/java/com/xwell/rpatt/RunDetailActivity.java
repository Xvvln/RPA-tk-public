package com.xwell.rpatt;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.xwell.rpatt.ui.RpaPalette;
import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Read-only detail page for a single local run. */
public final class RunDetailActivity extends Activity {
    public static final String EXTRA_RUN_ID = "run_id";
    private static final String RUN_ID_PATTERN = "[0-9]{8}-[0-9]{6}-[0-9]{3}";

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

        String runId = getIntent().getStringExtra(EXTRA_RUN_ID);
        if (runId == null || !runId.matches(RUN_ID_PATTERN)) {
            root.addView(header("运行详情"));
            root.addView(text("记录编号无效", 15, RpaPalette.RED));
            return scroll;
        }
        File run = new File(new File(getFilesDir(), "runs"), runId);
        root.addView(header(formatRunId(runId)));
        root.addView(section("任务摘要"));
        root.addView(summaryCard(run));
        root.addView(section("事件日志 · 最新在前"));
        addEvents(root, new File(run, "events.jsonl"));
        return scroll;
    }

    private View header(String titleText) {
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton back = iconButton(
                R.drawable.ic_arrow_back,
                "返回", this::finish);
        back.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout copy = column();
        copy.setPadding(dp(10), 0, 0, 0);
        TextView title = text(titleText, 21, RpaPalette.TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(title);
        copy.addView(text("本机只读日志", 13, RpaPalette.TEXT_MUTED));
        header.addView(copy, weighted());
        return header;
    }

    private View summaryCard(File run) {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(outlined(RpaPalette.SURFACE, RpaPalette.BORDER, 12));
        JSONObject summary = readJson(new File(run, "summary.json"));
        if (summary == null) {
            card.addView(text("暂无 summary.json，任务可能仍在运行或异常退出。", 14, RpaPalette.AMBER));
            return card;
        }
        card.addView(metric("浏览", summary.optInt("videos_seen") + " 条"));
        card.addView(metric("互动", String.format(Locale.CHINA, "%d 赞 · %d 藏 · %d 评",
                summary.optInt("likes"), summary.optInt("favorites"), summary.optInt("comments"))));
        card.addView(metric("其他操作", String.format(Locale.CHINA, "%d 关注 · %d 转发 · %d 不感兴趣",
                summary.optInt("follows"), summary.optInt("shares"),
                summary.optInt("not_interested"))));
        card.addView(metric("已提交操作", summary.optInt("external_actions_committed",
                summary.optInt("likes") + summary.optInt("favorites")
                        + summary.optInt("comments") + summary.optInt("follows")
                        + summary.optInt("shares") + summary.optInt("not_interested")) + " 次"));
        if (summary.optBoolean("partial_video_actions", false)) {
            card.addView(text("有外部操作已提交，但对应视频记录未完成归档。",
                    13, RpaPalette.AMBER));
        }
        card.addView(metric("耗时", formatElapsed(summary.optLong("elapsed_ms"))));
        card.addView(metric("结果", MainActivity.stopReasonLabel(summary.optString("stop_reason", "finished"))));
        card.addView(metric("人工处理", summary.optBoolean("requires_human", false) ? "需要" : "不需要"));
        return card;
    }

    private View metric(String label, String value) {
        LinearLayout row = row();
        row.setPadding(0, dp(3), 0, dp(3));
        row.addView(text(label, 13, RpaPalette.TEXT_MUTED), weighted());
        row.addView(text(value, 14, RpaPalette.TEXT));
        return row;
    }

    private void addEvents(LinearLayout root, File eventsFile) {
        if (!eventsFile.isFile()) {
            root.addView(text("暂无 events.jsonl", 14, RpaPalette.TEXT_MUTED));
            return;
        }
        List<JSONObject> events = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(eventsFile.toPath(), StandardCharsets.UTF_8)) {
                String clean = line.trim();
                if (!clean.isEmpty()) events.add(new JSONObject(clean));
            }
        } catch (Exception error) {
            root.addView(text("日志读取失败：" + error.getMessage(), 14, RpaPalette.RED));
            return;
        }
        Collections.reverse(events);
        if (events.isEmpty()) {
            root.addView(text("事件日志为空", 14, RpaPalette.TEXT_MUTED));
            return;
        }
        for (int index = 0; index < Math.min(200, events.size()); index++) {
            root.addView(eventCard(events.get(index)));
            root.addView(space(8));
        }
    }

    private View eventCard(JSONObject event) {
        LinearLayout card = column();
        card.setPadding(dp(12), dp(9), dp(12), dp(9));
        card.setBackground(outlined(RpaPalette.SURFACE, RpaPalette.BORDER, 10));
        LinearLayout top = row();
        String type = event.optString("type", "event");
        TextView title = text(eventLabel(type), 14, eventColor(type));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.addView(title, weighted());
        top.addView(text(formatTime(event.optLong("at")), 12, RpaPalette.TEXT_MUTED));
        card.addView(top);
        JSONObject detail = event.optJSONObject("detail");
        if (detail != null && detail.length() > 0) {
            TextView raw = text(detailText(detail), 12, RpaPalette.TEXT_MUTED);
            raw.setTextIsSelectable(true);
            raw.setPadding(0, dp(4), 0, 0);
            card.addView(raw);
        }
        return card;
    }

    private String eventLabel(String type) {
        switch (type) {
            case "opening_check_started": return "开始检查";
            case "preflight_started": return "环境预检";
            case "preflight_passed": return "预检通过";
            case "preflight_failed": return "预检失败";
            case "preflight_check": return "预检项";
            case "recovery": return "自动恢复";
            case "opening_recovery": return "开场恢复";
            case "run_started": return "任务开始";
            case "ready_to_work": return "准备完成";
            case "search_results": return "搜索结果";
            case "search_result_not_opened": return "未打开搜索结果";
            case "comments_read": return "读取评论";
            case "next_video": return "切换视频";
            case "like": return "已点赞";
            case "favorite": return "已收藏";
            case "comment": return "已评论";
            case "blocked": return "任务阻断";
            case "finished": return "任务结束";
            default: return type.replace('_', ' ');
        }
    }

    private int eventColor(String type) {
        if ("blocked".equals(type) || "preflight_failed".equals(type)) return RpaPalette.RED;
        if ("preflight_passed".equals(type) || "ready_to_work".equals(type)) return RpaPalette.TEXT;
        if ("like".equals(type) || "favorite".equals(type) || "comment".equals(type)) return RpaPalette.PINK;
        return RpaPalette.TEXT;
    }

    private String detailText(JSONObject detail) {
        String message = detail.optString("message", "");
        String code = detail.optString("code", "");
        String interruption = detail.optString("interruption_code", "");
        String action = detail.optString("action", "");
        if (!interruption.isEmpty() || (!action.isEmpty() && detail.has("attempt"))) {
            StringBuilder line = new StringBuilder();
            if (!interruption.isEmpty()) line.append(interruption);
            if (!action.isEmpty()) {
                if (line.length() > 0) line.append(" · ");
                line.append(action);
            }
            int attempt = detail.optInt("attempt", 0);
            if (attempt > 0) line.append(" · #").append(attempt);
            if (!message.isEmpty()) line.append(" · ").append(message);
            return line.toString();
        }
        if (!message.isEmpty()) return code.isEmpty() ? message : code + " · " + message;
        if (detail.has("status") && detail.has("checks")) {
            return detail.optString("status", "") + " · checks=" + detail.optJSONArray("checks").length();
        }
        return detail.toString();
    }

    private JSONObject readJson(File file) {
        if (!file.isFile()) return null;
        try { return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }
        catch (Exception ignored) { return null; }
    }

    private String formatRunId(String value) {
        try {
            Date date = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).parse(value);
            return new SimpleDateFormat("MM月dd日 HH:mm:ss", Locale.CHINA).format(date);
        } catch (Exception ignored) { return value; }
    }

    private String formatTime(long timestamp) {
        return timestamp <= 0 ? "--:--:--" : new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date(timestamp));
    }

    private String formatElapsed(long elapsedMs) {
        long seconds = elapsedMs / 1000;
        return String.format(Locale.CHINA, "%d分%02d秒", seconds / 60, seconds % 60);
    }

    private TextView section(String value) { TextView view = text(value, 15, RpaPalette.TEXT_MUTED); view.setPadding(0, dp(18), 0, dp(8)); return view; }
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
