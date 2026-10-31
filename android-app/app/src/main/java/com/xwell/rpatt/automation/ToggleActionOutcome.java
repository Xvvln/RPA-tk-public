package com.xwell.rpatt.automation;

/**
 * Classifies a toggle write from independent page, target, and rendered-state evidence.
 * A stable unchanged state is not UNKNOWN: the platform deterministically did not apply it.
 */
public final class ToggleActionOutcome {
    public enum State { SELECTED, NOT_APPLIED, UNKNOWN }

    private ToggleActionOutcome() {}

    public static State classify(boolean beforeKnownUnselected,
                                 boolean afterStateAvailable,
                                 boolean afterSelected,
                                 boolean sameVideo,
                                 boolean sameTarget,
                                 int consecutiveStableUnselectedSamples) {
        if (!sameVideo || !sameTarget || !afterStateAvailable) return State.UNKNOWN;
        if (beforeKnownUnselected && afterSelected) return State.SELECTED;
        if (beforeKnownUnselected && consecutiveStableUnselectedSamples >= 2) {
            return State.NOT_APPLIED;
        }
        return State.UNKNOWN;
    }
}
