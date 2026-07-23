package com.xwell.rpatt.storage;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts run directories left open by process death into explicit, auditable summaries.
 *
 * <p>The newest incomplete directory is left untouched while an in-process session is active.
 * Completed summaries are immutable, so this operation is safe to repeat on every UI/service
 * startup.</p>
 */
public final class InterruptedRunReconciler {
    public static final String STOP_REASON = "process_interrupted";

    public static final class Result {
        public final File directory;
        public final JSONObject summary;

        private Result(File directory, JSONObject summary) {
            this.directory = directory;
            this.summary = summary;
        }
    }

    private InterruptedRunReconciler() {}

    public static synchronized List<Result> reconcile(
            File filesDirectory, boolean activeSession, long now) throws IOException {
        File root = new File(filesDirectory, "runs");
        File[] runs = root.listFiles(File::isDirectory);
        List<Result> results = new ArrayList<>();
        if (runs == null || runs.length == 0) return results;
        Arrays.sort(runs, Comparator.comparing(File::getName).reversed());
        boolean skippedActiveCandidate = false;
        for (File run : runs) {
            if (new File(run, "summary.json").isFile()) continue;
            if (!isInitialized(run)) continue;
            if (activeSession && !skippedActiveCandidate) {
                skippedActiveCandidate = true;
                continue;
            }
            JSONObject summary = buildSummary(run, now);
            writeAtomic(new File(run, "summary.json"), pretty(summary));
            appendRecoveryEvent(run, now, summary);
            results.add(new Result(run, summary));
        }
        return results;
    }

    private static boolean isInitialized(File run) {
        return new File(run, "config.json").isFile() && new File(run, "events.jsonl").isFile();
    }

    private static JSONObject buildSummary(File run, long now) throws IOException {
        EventStats events = readEvents(new File(run, "events.jsonl"));
        VideoStats videos = readVideos(new File(run, "videos.jsonl"));
        ActionStats actions = readActions(new File(run, "actions.jsonl"));
        boolean partialVideoActions = false;
        for (String target : actions.committedTargetHashes) {
            if (!videos.targetHashes.contains(target)) {
                partialVideoActions = true;
                break;
            }
        }
        try {
            JSONObject counts = new JSONObject();
            for (Map.Entry<String, Integer> item : actions.committedCounts.entrySet()) {
                counts.put(item.getKey(), item.getValue());
            }
            JSONObject summary = new JSONObject()
                    .put("ended_at", now)
                    .put("videos_seen", videos.count)
                    .put("elapsed_ms", Math.max(0L, events.lastAt - events.firstAt))
                    .put("likes", actions.count("like"))
                    .put("favorites", actions.count("favorite"))
                    .put("comments", actions.count("comment"))
                    .put("follows", actions.count("follow"))
                    .put("shares", actions.count("share"))
                    .put("not_interested", actions.count("not_interested"))
                    .put("action_counts", counts)
                    .put("external_actions_committed", actions.committedTotal())
                    .put("partial_video_actions", partialVideoActions)
                    .put("unfinalized_action_transactions", actions.unfinalized)
                    .put("uncertain_action_transactions", actions.uncertain)
                    .put("stop_reason", STOP_REASON)
                    .put("requires_human", true)
                    .put("recovered_from_incomplete_run", true);
            JSONObject config = readObject(new File(run, "config.json"));
            if (config != null) {
                summary.put("config_schema_version",
                        config.optInt("schema_version", config.optInt("config_schema_version", 0)));
            }
            return summary;
        } catch (JSONException error) {
            throw new IOException("无法生成异常中断摘要", error);
        }
    }

    private static EventStats readEvents(File file) throws IOException {
        EventStats stats = new EventStats();
        forEachObject(file, value -> {
            long at = value.optLong("at", 0L);
            if (at <= 0L) return;
            if (stats.firstAt == 0L || at < stats.firstAt) stats.firstAt = at;
            if (at > stats.lastAt) stats.lastAt = at;
        });
        if (stats.firstAt == 0L) stats.firstAt = stats.lastAt;
        return stats;
    }

    private static VideoStats readVideos(File file) throws IOException {
        VideoStats stats = new VideoStats();
        forEachObject(file, value -> {
            stats.count++;
            String author = value.optString("author").trim();
            String description = value.optString("description").trim();
            String music = value.optString("music").trim();
            if (!author.isEmpty() && !description.isEmpty()) {
                stats.targetHashes.add(sha256(author + "\n" + description + "\n" + music));
            }
        });
        return stats;
    }

    private static ActionStats readActions(File file) throws IOException {
        Map<String, ActionRecord> latest = new LinkedHashMap<>();
        forEachObject(file, value -> {
            String transaction = value.optString("transaction_id");
            String action = value.optString("action").trim().toLowerCase(Locale.ROOT);
            String state = value.optString("state").trim().toLowerCase(Locale.ROOT);
            if (transaction.isEmpty() || action.isEmpty() || state.isEmpty()) return;
            latest.put(transaction, new ActionRecord(
                    action, state, value.optString("target_hash")));
        });
        ActionStats stats = new ActionStats();
        for (ActionRecord record : latest.values()) {
            if ("committed".equals(record.state)) {
                stats.committedCounts.put(record.action,
                        stats.committedCounts.getOrDefault(record.action, 0) + 1);
                if (!record.targetHash.isEmpty()) {
                    stats.committedTargetHashes.add(record.targetHash);
                }
            } else {
                stats.unfinalized++;
                if ("attempted".equals(record.state) || "verified".equals(record.state)
                        || "unknown".equals(record.state)) {
                    stats.uncertain++;
                }
            }
        }
        return stats;
    }

    private static void appendRecoveryEvent(File run, long now, JSONObject summary)
            throws IOException {
        try {
            JSONObject event = new JSONObject()
                    .put("at", now)
                    .put("type", "process_interruption_recovered")
                    .put("detail", new JSONObject()
                            .put("videos_seen", summary.optInt("videos_seen"))
                            .put("external_actions_committed",
                                    summary.optInt("external_actions_committed"))
                            .put("uncertain_action_transactions",
                                    summary.optInt("uncertain_action_transactions")));
            append(new File(run, "events.jsonl"), event.toString() + "\n");
        } catch (JSONException error) {
            throw new IOException("无法记录异常中断恢复事件", error);
        }
    }

    private interface ObjectConsumer {
        void accept(JSONObject value) throws IOException;
    }

    private static void forEachObject(File file, ObjectConsumer consumer) throws IOException {
        if (!file.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    consumer.accept(new JSONObject(line));
                } catch (JSONException ignored) {
                    // A torn final JSONL line is retained for diagnostics but cannot affect counts.
                }
            }
        }
    }

    private static JSONObject readObject(File file) throws IOException {
        if (!file.isFile()) return null;
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
        }
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException ignored) {
            return null;
        }
    }

    private static void writeAtomic(File target, String value) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary, false)) {
            stream.write(value.getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
        if (target.isFile() || !temporary.renameTo(target)) {
            if (temporary.isFile() && !temporary.delete()) temporary.deleteOnExit();
            if (!target.isFile()) throw new IOException("无法原子写入异常中断摘要");
        }
    }

    private static String pretty(JSONObject value) throws IOException {
        try {
            return value.toString(2) + "\n";
        } catch (JSONException error) {
            throw new IOException("无法序列化异常中断摘要", error);
        }
    }

    private static void append(File target, String value) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(target, true)) {
            stream.write(value.getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
    }

    static String sha256(String value) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                output.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("系统缺少 SHA-256", error);
        }
    }

    private static final class EventStats {
        long firstAt;
        long lastAt;
    }

    private static final class VideoStats {
        int count;
        final Set<String> targetHashes = new LinkedHashSet<>();
    }

    private static final class ActionRecord {
        final String action;
        final String state;
        final String targetHash;

        ActionRecord(String action, String state, String targetHash) {
            this.action = action;
            this.state = state;
            this.targetHash = targetHash == null ? "" : targetHash;
        }
    }

    private static final class ActionStats {
        final Map<String, Integer> committedCounts = new LinkedHashMap<>();
        final Set<String> committedTargetHashes = new LinkedHashSet<>();
        int unfinalized;
        int uncertain;

        int count(String action) {
            return committedCounts.getOrDefault(action, 0);
        }

        int committedTotal() {
            int total = 0;
            for (int count : committedCounts.values()) total += count;
            return total;
        }
    }
}
