package com.xwell.rpatt.engine;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Run-local protection for platform actions that are accepted by Android but repeatedly ignored
 * by the target app. It never retries an individual write and only suppresses the affected module.
 */
public final class ActionCircuitBreaker {
    public static final class Snapshot {
        public final int consecutiveNotApplied;
        public final boolean suppressed;

        private Snapshot(int consecutiveNotApplied, boolean suppressed) {
            this.consecutiveNotApplied = consecutiveNotApplied;
            this.suppressed = suppressed;
        }
    }

    private final int threshold;
    private final Map<String, Integer> consecutiveNotApplied = new LinkedHashMap<>();

    public ActionCircuitBreaker(int threshold) {
        if (threshold < 1) throw new IllegalArgumentException("threshold must be positive");
        this.threshold = threshold;
    }

    public boolean allows(String action) {
        return consecutive(action) < threshold;
    }

    public Snapshot recordNotApplied(String action) {
        String key = normalize(action);
        int next = consecutive(key) + 1;
        consecutiveNotApplied.put(key, next);
        return new Snapshot(next, next >= threshold);
    }

    public Snapshot recordApplied(String action) {
        String key = normalize(action);
        consecutiveNotApplied.remove(key);
        return new Snapshot(0, false);
    }

    public Snapshot snapshot(String action) {
        int count = consecutive(action);
        return new Snapshot(count, count >= threshold);
    }

    private int consecutive(String action) {
        return consecutiveNotApplied.getOrDefault(normalize(action), 0);
    }

    private static String normalize(String action) {
        return action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
    }
}
