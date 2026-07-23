package com.xwell.rpatt.engine;

public final class RunSnapshot {
    public enum Status { IDLE, RUNNING, PAUSED, BLOCKED, FINISHED }

    public final Status status;
    public final String phase;
    public final String step;
    public final int videosSeen;
    public final int videoLimit;
    public final int likes;
    public final int favorites;
    public final int comments;
    public final long elapsedMs;
    public final String message;

    public RunSnapshot(Status status, String phase, String step, int videosSeen,
                       int videoLimit, int likes, int favorites, int comments,
                       long elapsedMs, String message) {
        this.status = status;
        this.phase = phase;
        this.step = step;
        this.videosSeen = videosSeen;
        this.videoLimit = videoLimit;
        this.likes = likes;
        this.favorites = favorites;
        this.comments = comments;
        this.elapsedMs = elapsedMs;
        this.message = message;
    }

    public static RunSnapshot idle() {
        return new RunSnapshot(Status.IDLE, "准备", "等待开始", 0, 0, 0, 0, 0, 0, "");
    }
}
