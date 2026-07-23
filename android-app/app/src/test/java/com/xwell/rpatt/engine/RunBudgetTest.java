package com.xwell.rpatt.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RunBudgetTest {
    @Test public void pausedTimeDoesNotConsumeBudget() {
        RunBudget budget = new RunBudget(1_000, 10_000, 3);
        budget.pause(3_000);
        assertEquals(2_000, budget.elapsedMs(9_000));
        budget.resume(9_000);
        assertEquals(3_000, budget.elapsedMs(10_000));
    }

    @Test public void firstReachedLimitDeterminesStop() {
        RunBudget budget = new RunBudget(0, 10_000, 2);
        assertFalse(budget.exhausted(2_000));
        budget.recordVideo();
        budget.recordVideo();
        assertTrue(budget.exhausted(2_000));
        assertEquals("video_budget", budget.exhaustionReason(2_000));
    }

    @Test public void timeLimitIsReported() {
        RunBudget budget = new RunBudget(1_000, 5_000, 20);
        assertTrue(budget.exhausted(6_000));
        assertEquals("time_budget", budget.exhaustionReason(6_000));
    }
}
