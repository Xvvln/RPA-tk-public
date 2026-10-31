package com.xwell.rpatt.automation;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ToggleActionOutcomeTest {
    @Test public void selectedRequiresSameVideoAndTarget() {
        assertEquals(ToggleActionOutcome.State.SELECTED,
                ToggleActionOutcome.classify(true, true, true,
                        true, true, 0));
        assertEquals(ToggleActionOutcome.State.UNKNOWN,
                ToggleActionOutcome.classify(true, true, true,
                        false, true, 0));
        assertEquals(ToggleActionOutcome.State.UNKNOWN,
                ToggleActionOutcome.classify(true, true, true,
                        true, false, 0));
    }

    @Test public void stableUnchangedStateIsNotApplied() {
        assertEquals(ToggleActionOutcome.State.NOT_APPLIED,
                ToggleActionOutcome.classify(true, true, false,
                        true, true, 6));
    }

    @Test public void oneUnchangedSampleIsStillUnknown() {
        assertEquals(ToggleActionOutcome.State.UNKNOWN,
                ToggleActionOutcome.classify(true, true, false,
                        true, true, 1));
    }

    @Test public void unavailablePostStateIsUnknown() {
        assertEquals(ToggleActionOutcome.State.UNKNOWN,
                ToggleActionOutcome.classify(true, false, false,
                        true, true, 6));
    }

    @Test public void unknownPreStateCannotAuthorizeAWriteResult() {
        assertEquals(ToggleActionOutcome.State.UNKNOWN,
                ToggleActionOutcome.classify(false, true, true,
                        true, true, 0));
        assertEquals(ToggleActionOutcome.State.UNKNOWN,
                ToggleActionOutcome.classify(false, true, false,
                        true, true, 6));
    }
}
