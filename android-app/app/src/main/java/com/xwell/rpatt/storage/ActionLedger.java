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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Durable write-ahead action journal and cross-run idempotency index. */
public final class ActionLedger {
    public enum State { PREPARED, ATTEMPTED, VERIFIED, COMMITTED, UNKNOWN }
    public enum BeginDisposition { ALLOWED, DUPLICATE_COMMITTED, DUPLICATE_UNCERTAIN }

    public static final class BeginResult {
        public final BeginDisposition disposition;
        public final Transaction transaction;
        public final State priorState;

        private BeginResult(BeginDisposition disposition, Transaction transaction, State priorState) {
            this.disposition = disposition;
            this.transaction = transaction;
            this.priorState = priorState;
        }

        public boolean allowed() { return disposition == BeginDisposition.ALLOWED; }
    }

    public final class Transaction {
        public final String id;
        public final String action;
        public final String idempotencyKey;
        private final String targetHash;
        private State state;

        private Transaction(String id, String action, String idempotencyKey, String targetHash) {
            this.id = id;
            this.action = action;
            this.idempotencyKey = idempotencyKey;
            this.targetHash = targetHash;
            this.state = State.PREPARED;
        }

        public synchronized void attempted(JSONObject evidence) throws IOException {
            transition(State.ATTEMPTED, evidence);
        }

        public synchronized void verified(JSONObject evidence) throws IOException {
            transition(State.VERIFIED, evidence);
        }

        public synchronized void committed(JSONObject evidence) throws IOException {
            transition(State.COMMITTED, evidence);
        }

        public synchronized void unknown(String reason, JSONObject evidence) throws IOException {
            JSONObject detail = copy(evidence);
            try { detail.put("reason", reason); }
            catch (JSONException error) { throw new IOException("无法记录动作未知状态", error); }
            transition(State.UNKNOWN, detail);
        }

        public State state() { return state; }

        private void transition(State next, JSONObject detail) throws IOException {
            boolean allowed = (state == State.PREPARED && next == State.ATTEMPTED)
                    || (state == State.ATTEMPTED && (next == State.VERIFIED || next == State.UNKNOWN))
                    || (state == State.VERIFIED && next == State.COMMITTED);
            if (!allowed) {
                throw new IOException("非法动作事务迁移：" + state + " -> " + next);
            }
            append(this, next, detail);
            state = next;
            if (next == State.COMMITTED) {
                currentCommitted.put(action, currentCommitted.getOrDefault(action, 0) + 1);
                currentActionTargets.add(targetHash);
            }
        }
    }

    private final File runFile;
    private final File globalFile;
    private final String runId;
    private final Map<String, State> latestByKey = new LinkedHashMap<>();
    private final Map<String, Integer> currentCommitted = new LinkedHashMap<>();
    private final Set<String> currentActionTargets = new LinkedHashSet<>();
    private final Set<String> committedVideoTargets = new LinkedHashSet<>();

    public ActionLedger(File runFile, File globalFile, String runId) throws IOException {
        this.runFile = runFile;
        this.globalFile = globalFile;
        this.runId = runId;
        loadGlobalIndex();
    }

    public synchronized BeginResult begin(String action, String targetFingerprint,
                                          String payload, JSONObject evidence) throws IOException {
        String normalizedAction = clean(action).toLowerCase(Locale.ROOT);
        if (normalizedAction.isEmpty() || clean(targetFingerprint).isEmpty()) {
            throw new IOException("动作类型和目标指纹不能为空");
        }
        String targetHash = sha256(targetFingerprint);
        String payloadHash = sha256(payload == null ? "" : payload);
        String key = sha256(normalizedAction + "\n" + targetHash + "\n" + payloadHash);
        State prior = latestByKey.get(key);
        if (prior != null) {
            return new BeginResult(prior == State.COMMITTED
                    ? BeginDisposition.DUPLICATE_COMMITTED
                    : BeginDisposition.DUPLICATE_UNCERTAIN, null, prior);
        }
        Transaction transaction = new Transaction(
                UUID.randomUUID().toString(), normalizedAction, key, targetHash);
        JSONObject detail = copy(evidence);
        try {
            detail.put("target_hash", targetHash).put("payload_hash", payloadHash);
        } catch (JSONException error) {
            throw new IOException("无法记录动作准备状态", error);
        }
        append(transaction, State.PREPARED, detail);
        return new BeginResult(BeginDisposition.ALLOWED, transaction, null);
    }

    public synchronized int committedCount(String action) {
        return currentCommitted.getOrDefault(clean(action).toLowerCase(Locale.ROOT), 0);
    }

    public synchronized int committedTotal() {
        int total = 0;
        for (int count : currentCommitted.values()) total += count;
        return total;
    }

    public synchronized JSONObject currentCountsJson() throws IOException {
        JSONObject value = new JSONObject();
        try {
            for (Map.Entry<String, Integer> item : currentCommitted.entrySet()) {
                value.put(item.getKey(), item.getValue());
            }
        } catch (JSONException error) {
            throw new IOException("无法序列化动作统计", error);
        }
        return value;
    }

    /** Mark a video record durable only after videos.jsonl has been synchronously written. */
    public synchronized void markVideoCommitted(String targetFingerprint) throws IOException {
        if (clean(targetFingerprint).isEmpty()) return;
        committedVideoTargets.add(sha256(targetFingerprint));
    }

    public synchronized boolean hasPartialVideoActions() {
        for (String targetHash : currentActionTargets) {
            if (!committedVideoTargets.contains(targetHash)) return true;
        }
        return false;
    }

    private synchronized void append(Transaction transaction, State state,
                                     JSONObject detail) throws IOException {
        JSONObject base = new JSONObject();
        try {
            base.put("at", System.currentTimeMillis())
                    .put("run_id", runId)
                    .put("transaction_id", transaction.id)
                    .put("action", transaction.action)
                    .put("idempotency_key", transaction.idempotencyKey)
                    .put("target_hash", transaction.targetHash)
                    .put("state", state.name().toLowerCase(Locale.ROOT));
            JSONObject runRecord = new JSONObject(base.toString()).put("detail", copy(detail));
            // The global index intentionally excludes raw node details and user-visible text.
            // It is synced first: a crash may conservatively suppress a retry, but can never
            // authorize a second external write merely because diagnostics were interrupted.
            write(globalFile, base.toString() + "\n");
            latestByKey.put(transaction.idempotencyKey, state);
            write(runFile, runRecord.toString() + "\n");
        } catch (JSONException error) {
            throw new IOException("无法序列化动作事务", error);
        }
    }

    private void loadGlobalIndex() throws IOException {
        if (!globalFile.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(globalFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    JSONObject value = new JSONObject(line);
                    String key = value.optString("idempotency_key");
                    State state = parseState(value.optString("state"));
                    if (!key.isEmpty() && state != null) latestByKey.put(key, state);
                } catch (JSONException ignored) {
                    // A torn final line cannot authorize a retry. It is ignored here and remains
                    // visible in the file for diagnostics; prior complete states still apply.
                }
            }
        }
    }

    private static State parseState(String value) {
        try { return State.valueOf(clean(value).toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static void write(File file, String value) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建动作账本目录");
        }
        try (FileOutputStream stream = new FileOutputStream(file, true)) {
            stream.write(value.getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
    }

    private static JSONObject copy(JSONObject value) throws IOException {
        if (value == null) return new JSONObject();
        try { return new JSONObject(value.toString()); }
        catch (JSONException error) { throw new IOException("无法复制动作证据", error); }
    }

    private static String sha256(String value) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) output.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return output.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("系统缺少 SHA-256", error);
        }
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
