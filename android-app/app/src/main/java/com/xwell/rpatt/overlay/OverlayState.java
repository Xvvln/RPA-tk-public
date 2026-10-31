package com.xwell.rpatt.overlay;

/** Immutable UI model consumed by the overlay; it has no dependency on the task engine. */
public final class OverlayState {
    public enum Status {
        IDLE, RUNNING, PAUSING, PAUSED, STOPPING, BLOCKED, FAILED, FINISHED
    }

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

    public OverlayState(Status status, String phase, String step, int videosSeen, int videoLimit,
                        int likes, int favorites, int comments, long elapsedMs, String message) {
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

    public boolean active() {
        return status == Status.RUNNING || status == Status.PAUSING
                || status == Status.PAUSED || status == Status.STOPPING
                || status == Status.BLOCKED;
    }
    public boolean paused() { return status == Status.PAUSED; }
    public boolean pausing() { return status == Status.PAUSING; }
    public boolean stopping() { return status == Status.STOPPING; }
    public boolean blocked() { return status == Status.BLOCKED; }
    public boolean canTogglePause() { return status == Status.RUNNING || status == Status.PAUSED; }
    public boolean canContinue() { return status == Status.PAUSED || status == Status.BLOCKED; }
    public boolean canStop() {
        return status == Status.RUNNING || status == Status.PAUSING
                || status == Status.PAUSED || status == Status.BLOCKED;
    }
    /** A blocked or finished run is over and may be started again from the overlay. */
    public boolean canStart() { return !active(); }
}
