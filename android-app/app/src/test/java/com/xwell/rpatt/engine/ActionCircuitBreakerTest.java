package com.xwell.rpatt.engine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ActionCircuitBreakerTest {
    @Test public void oneNoOpDoesNotSuppressModule() {
        ActionCircuitBreaker breaker = new ActionCircuitBreaker(2);

        ActionCircuitBreaker.Snapshot state = breaker.recordNotApplied("favorite");

        assertFalse(state.suppressed);
        assertTrue(breaker.allows("favorite"));
    }

    @Test public void consecutiveNoOpsSuppressOnlyAffectedModule() {
        ActionCircuitBreaker breaker = new ActionCircuitBreaker(2);
        breaker.recordNotApplied("favorite");

        ActionCircuitBreaker.Snapshot state = breaker.recordNotApplied("favorite");

        assertTrue(state.suppressed);
        assertFalse(breaker.allows("favorite"));
        assertTrue(breaker.allows("like"));
    }

    @Test public void appliedActionClosesCircuitAgain() {
        ActionCircuitBreaker breaker = new ActionCircuitBreaker(2);
        breaker.recordNotApplied("favorite");
        breaker.recordNotApplied("favorite");

        breaker.recordApplied("favorite");

        assertTrue(breaker.allows("favorite"));
        assertFalse(breaker.snapshot("favorite").suppressed);
    }
}
