package com.xwell.rpatt.service;

import com.xwell.rpatt.engine.RunSnapshot;
import com.xwell.rpatt.overlay.OverlayState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RunUiStateMapperTest {
    @Test public void pausedRuntimeStateBecomesPausedOverlayState() {
        RunSnapshot runtime = new RunSnapshot(RunSnapshot.Status.PAUSED, "推荐流", "等待",
                3, 12, 1, 2, 0, 45_000, "已暂停");

        OverlayState overlay = RunUiStateMapper.toOverlayState(runtime);

        assertEquals(OverlayState.Status.PAUSED, overlay.status);
        assertEquals("推荐流", overlay.phase);
        assertEquals(3, overlay.videosSeen);
        assertEquals(2, overlay.favorites);
        assertTrue(overlay.active());
        assertTrue(overlay.paused());
    }

    @Test public void blockedRunCanBeStartedAgainFromOverlay() {
        RunSnapshot runtime = new RunSnapshot(RunSnapshot.Status.BLOCKED, "收尾", "需人工处理",
                0, 30, 0, 0, 0, 10_000, "上次任务已停止");

        OverlayState overlay = RunUiStateMapper.toOverlayState(runtime);

        assertEquals(OverlayState.Status.BLOCKED, overlay.status);
        assertFalse(overlay.active());
        assertTrue(overlay.canStart());
    }
}
