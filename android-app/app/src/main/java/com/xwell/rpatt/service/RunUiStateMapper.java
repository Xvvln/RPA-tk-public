package com.xwell.rpatt.service;

import com.xwell.rpatt.engine.RunSnapshot;
import com.xwell.rpatt.overlay.OverlayState;

/** Explicit adapter between the runtime model and the independent overlay UI model. */
public final class RunUiStateMapper {
    private RunUiStateMapper() {}

    public static OverlayState toOverlayState(RunSnapshot snapshot) {
        OverlayState.Status status;
        switch (snapshot.status) {
            case RUNNING: status = OverlayState.Status.RUNNING; break;
            case PAUSED: status = OverlayState.Status.PAUSED; break;
            case BLOCKED: status = OverlayState.Status.BLOCKED; break;
            case FINISHED: status = OverlayState.Status.FINISHED; break;
            default: status = OverlayState.Status.IDLE;
        }
        return new OverlayState(status, snapshot.phase, snapshot.step,
                snapshot.videosSeen, snapshot.videoLimit, snapshot.likes, snapshot.favorites,
                snapshot.comments, snapshot.elapsedMs, snapshot.message);
    }
}
