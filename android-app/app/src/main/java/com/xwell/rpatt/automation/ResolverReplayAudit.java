package com.xwell.rpatt.automation;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only replay of saved blocker snapshots against the current page/control contracts. */
public final class ResolverReplayAudit {
    private ResolverReplayAudit() {}

    public static File run(Context context) throws Exception {
        File runs = new File(context.getFilesDir(), "runs");
        JSONArray results = new JSONArray();
        int checked = 0;
        int failed = 0;
        File[] directories = runs.listFiles(File::isDirectory);
        if (directories != null) {
            for (File directory : directories) {
                File blocker = new File(directory, "blocker-nodes.json");
                if (!blocker.isFile()) continue;
                UiSnapshot snapshot = UiSnapshot.fromJson(new JSONObject(read(blocker)));
                if (snapshot.nodes.isEmpty()) continue;
                PageState state = PageAnalyzer.analyze(snapshot, true);
                List<String> missing = requiredControls(directory, snapshot, state.page);
                String historicalStopReason = historicalStopReason(directory);
                boolean historicalStopReproduced = state.blockers.contains(historicalStopReason);
                boolean knownFalseForegroundFixed = "tiktok_not_foreground".equals(historicalStopReason)
                        && snapshot.foregroundDecision.confirmsTarget(PageAnalyzer.TIKTOK_PACKAGE)
                        && !historicalStopReproduced;
                checked++;
                if (!missing.isEmpty()) failed++;
                results.put(new JSONObject()
                        .put("run_id", directory.getName())
                        .put("page", state.page.name())
                        .put("confidence", state.confidence)
                        .put("foreground_decision", snapshot.foregroundDecision.toJson())
                        .put("blockers", new JSONArray(state.blockers))
                        .put("interruptions", new JSONArray(state.interruptions))
                        .put("historical_stop_reason", historicalStopReason)
                        .put("historical_stop_reproduced", historicalStopReproduced)
                        .put("known_false_foreground_fixed", knownFalseForegroundFixed)
                        .put("missing", new JSONArray(missing)));
            }
        }
        JSONObject report = new JSONObject()
                .put("checked", checked).put("failed", failed).put("results", results);
        File output = new File(context.getFilesDir(), "resolver-replay-audit.json");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write((report.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
        return output;
    }

    private static String historicalStopReason(File directory) throws Exception {
        File summary = new File(directory, "summary.json");
        if (!summary.isFile()) return "";
        return new JSONObject(read(summary)).optString("stop_reason", "");
    }

    private static List<String> requiredControls(File directory, UiSnapshot snapshot,
                                                 PageState.Page page) throws Exception {
        List<String> missing = new ArrayList<>();
        if (page == PageState.Page.SEARCH_INPUT) {
            ControlResolver.Match field = ControlResolver.resolve(
                    snapshot, ControlResolver.Role.SEARCH_FIELD);
            require(missing, "SEARCH_FIELD", field);
            require(missing, "SEARCH_SUBMIT", ControlResolver.resolve(snapshot,
                    ControlResolver.Role.SEARCH_SUBMIT, field == null ? null : field.node));
        } else if (page == PageState.Page.COMMENTS) {
            ControlResolver.Match field = ControlResolver.resolve(
                    snapshot, ControlResolver.Role.COMMENT_FIELD);
            require(missing, "COMMENT_FIELD", field);
            require(missing, "COMMENT_CLOSE",
                    ControlResolver.resolve(snapshot, ControlResolver.Role.COMMENT_CLOSE));
            if (field != null && !field.node.text.isEmpty()
                    && !field.node.text.contains("添加评论")
                    && !field.node.text.toLowerCase(Locale.ROOT).contains("add comment")) {
                require(missing, "COMMENT_SEND", ControlResolver.resolve(
                        snapshot, ControlResolver.Role.COMMENT_SEND, field.node));
            }
        } else if (page == PageState.Page.FEED) {
            requireWhenSemanticPresent(snapshot, missing, "VIDEO_LIKE",
                    ControlResolver.Role.VIDEO_LIKE,
                    "点赞视频", "点赞的视频", "like video", "liked video");
            requireWhenSemanticPresent(snapshot, missing, "VIDEO_COMMENTS",
                    ControlResolver.Role.VIDEO_COMMENTS,
                    "阅读或添加评论", "read or add comments");
            requireWhenSemanticPresent(snapshot, missing, "VIDEO_FAVORITE",
                    ControlResolver.Role.VIDEO_FAVORITE,
                    "添加到或移出收藏", "移出收藏", "favorite");
            requireWhenSemanticPresent(snapshot, missing, "VIDEO_SHARE",
                    ControlResolver.Role.VIDEO_SHARE,
                    "分享视频", "share video");
            requireWhenSemanticPresent(snapshot, missing, "VIDEO_FOLLOW",
                    ControlResolver.Role.VIDEO_FOLLOW,
                    "关注 ", "follow ");
        }
        requireCommittedCommentDraft(directory, snapshot, missing);
        return missing;
    }

    /** A prior input failure is recoverable only when the intended draft exists in the snapshot. */
    private static void requireCommittedCommentDraft(File directory, UiSnapshot snapshot,
                                                      List<String> missing) throws Exception {
        File summaryFile = new File(directory, "summary.json");
        File configFile = new File(directory, "config.json");
        File eventsFile = new File(directory, "events.jsonl");
        if (!summaryFile.isFile() || !configFile.isFile() || !eventsFile.isFile()) return;
        JSONObject summary = new JSONObject(read(summaryFile));
        if (!"comment_input_failed".equals(summary.optString("stop_reason"))) return;
        // Older builds wrote directly through the feed's inline composer and used the same stop
        // code. That snapshot is not an execution trace of the current full-comments transaction.
        String events = read(eventsFile);
        if (!events.contains("评论框聚焦或文字复核失败，未发送评论")) return;
        JSONObject interactions = new JSONObject(read(configFile)).optJSONObject("interactions");
        JSONArray texts = interactions == null ? null : interactions.optJSONArray("comment_texts");
        boolean committed = false;
        if (texts != null) {
            for (int index = 0; index < texts.length(); index++) {
                if (snapshot.hasExactInputText(texts.optString(index))) {
                    committed = true;
                    break;
                }
            }
        }
        if (!committed) missing.add("COMMENT_INPUT_COMMIT");
    }

    private static void requireWhenSemanticPresent(UiSnapshot snapshot, List<String> missing,
                                                    String name, ControlResolver.Role role,
                                                    String... markers) {
        if (!hasVisibleSemantic(snapshot, markers)) return;
        require(missing, name, ControlResolver.resolve(snapshot, role));
    }

    private static boolean hasVisibleSemantic(UiSnapshot snapshot, String... markers) {
        for (UiNode node : snapshot.nodes) {
            if (!snapshot.isVisible(node)) continue;
            String semantic = (node.text + " " + node.description).toLowerCase(Locale.ROOT);
            for (String marker : markers) {
                if (semantic.contains(marker.toLowerCase(Locale.ROOT))) return true;
            }
        }
        return false;
    }

    private static void require(List<String> missing, String role, ControlResolver.Match match) {
        if (match == null) missing.add(role);
    }

    private static String read(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream stream = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = stream.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
