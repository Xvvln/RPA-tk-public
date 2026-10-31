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

    @Test public void blockedRunKeepsContinuationAndStopControls() {
        RunSnapshot runtime = new RunSnapshot(RunSnapshot.Status.BLOCKED, "收尾", "需人工处理",
                0, 30, 0, 0, 0, 10_000, "上次任务已停止");

        OverlayState overlay = RunUiStateMapper.toOverlayState(runtime);

        assertEquals(OverlayState.Status.BLOCKED, overlay.status);
        assertTrue(overlay.active());
        assertTrue(overlay.canContinue());
        assertTrue(overlay.canStop());
        assertFalse(overlay.canStart());
    }

    @Test public void failedRunCanOnlyStartANewTask() {
        RunSnapshot runtime = new RunSnapshot(RunSnapshot.Status.FAILED,
                "收尾", "不可恢复错误", 2, 30, 0, 0, 0, 10_000, "内部错误");

        OverlayState overlay = RunUiStateMapper.toOverlayState(runtime);

        assertEquals(OverlayState.Status.FAILED, overlay.status);
        assertFalse(overlay.active());
        assertFalse(overlay.canContinue());
        assertFalse(overlay.canStop());
        assertTrue(overlay.canStart());
    }

    @Test public void pausingAcknowledgesCommandWithoutAllowingDuplicateToggle() {
        RunSnapshot runtime = new RunSnapshot(RunSnapshot.Status.PAUSING,
                "推荐流", "正在暂停", 4, 12, 1, 0, 0, 50_000,
                "控制命令已收到");

        OverlayState overlay = RunUiStateMapper.toOverlayState(runtime);

        assertEquals(OverlayState.Status.PAUSING, overlay.status);
        assertTrue(overlay.active());
        assertFalse(overlay.paused());
        assertFalse(overlay.canTogglePause());
        assertTrue(overlay.canStop());
        assertFalse(overlay.canStart());
    }

    @Test public void stoppingDisablesAllRepeatedControlCommands() {
        RunSnapshot runtime = new RunSnapshot(RunSnapshot.Status.STOPPING,
                "推荐流", "正在停止", 4, 12, 1, 0, 0, 50_000,
                "停止命令已收到");

        OverlayState overlay = RunUiStateMapper.toOverlayState(runtime);

        assertEquals(OverlayState.Status.STOPPING, overlay.status);
        assertTrue(overlay.active());
        assertFalse(overlay.canTogglePause());
        assertFalse(overlay.canStop());
        assertFalse(overlay.canStart());
    }
}
