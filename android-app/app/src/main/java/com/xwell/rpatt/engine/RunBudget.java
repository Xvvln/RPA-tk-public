package com.xwell.rpatt.engine;

/** Monotonic session budget. Paused time is excluded by default. */
public final class RunBudget {
    private final long startedAtMs;
    private final long maximumMs;
    private final int maximumVideos;
    private long pausedAtMs = -1;
    private long pausedTotalMs = 0;
    private int videosSeen = 0;

    public RunBudget(long nowMs, long maximumMs, int maximumVideos) {
        if (maximumMs <= 0 || maximumVideos <= 0) throw new IllegalArgumentException("预算必须为正数");
        this.startedAtMs = nowMs;
        this.maximumMs = maximumMs;
        this.maximumVideos = maximumVideos;
    }

    public synchronized void pause(long nowMs) {
        if (pausedAtMs < 0) pausedAtMs = nowMs;
    }

    public synchronized void resume(long nowMs) {
        if (pausedAtMs >= 0) {
            pausedTotalMs += Math.max(0, nowMs - pausedAtMs);
            pausedAtMs = -1;
        }
    }

    public synchronized long elapsedMs(long nowMs) {
        long currentPause = pausedAtMs >= 0 ? Math.max(0, nowMs - pausedAtMs) : 0;
        return Math.max(0, nowMs - startedAtMs - pausedTotalMs - currentPause);
    }

    public synchronized boolean exhausted(long nowMs) {
        return elapsedMs(nowMs) >= maximumMs || videosSeen >= maximumVideos;
    }

    public synchronized String exhaustionReason(long nowMs) {
        if (elapsedMs(nowMs) >= maximumMs) return "time_budget";
        if (videosSeen >= maximumVideos) return "video_budget";
        return "";
    }

    public synchronized int recordVideo() {
        videosSeen += 1;
        return videosSeen;
    }

    public synchronized int videosSeen() { return videosSeen; }
    public int maximumVideos() { return maximumVideos; }
}
