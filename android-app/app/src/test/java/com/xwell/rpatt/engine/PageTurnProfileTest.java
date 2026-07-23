package com.xwell.rpatt.engine;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class PageTurnProfileTest {
    @Test public void retriesEscalateDistanceAndUseDistinctProfiles() {
        Random random = new Random(7);
        PageTurnProfile standard = PageTurnProfile.forAttempt(1, "natural", random);
        PageTurnProfile extended = PageTurnProfile.forAttempt(2, "natural", random);
        PageTurnProfile maximum = PageTurnProfile.forAttempt(3, "natural", random);

        assertEquals("standard", standard.name);
        assertEquals("extended_flick", extended.name);
        assertEquals("maximum_flick", maximum.name);
        assertTrue(extended.startY - extended.endY > standard.startY - standard.endY);
        assertTrue(maximum.startY - maximum.endY > extended.startY - extended.endY);
    }

    @Test public void fallbackFlicksStayFastEvenWhenConfiguredPaceIsSlow() {
        Random random = new Random(9);
        PageTurnProfile standard = PageTurnProfile.forAttempt(1, "slow", random);
        PageTurnProfile extended = PageTurnProfile.forAttempt(2, "slow", random);
        PageTurnProfile maximum = PageTurnProfile.forAttempt(3, "slow", random);

        assertTrue(standard.durationMs >= 650);
        assertTrue(extended.durationMs <= 300);
        assertTrue(maximum.durationMs <= 340);
    }

    @Test public void randomProfilesAreDeterministicForSeedAndStayBounded() {
        PageTurnProfile first = PageTurnProfile.forAttempt(
                1, "natural", true, new Random(42));
        PageTurnProfile repeated = PageTurnProfile.forAttempt(
                1, "natural", true, new Random(42));
        PageTurnProfile different = PageTurnProfile.forAttempt(
                1, "natural", true, new Random(43));

        assertEquals(first.durationMs, repeated.durationMs);
        assertEquals(first.startX, repeated.startX, 0f);
        assertEquals(first.endX, repeated.endX, 0f);
        assertEquals(first.startY, repeated.startY, 0f);
        assertEquals(first.endY, repeated.endY, 0f);
        assertTrue(first.randomized);
        assertTrue(first.startX >= 0.40f && first.startX <= 0.72f);
        assertTrue(first.endX >= 0.38f && first.endX <= 0.70f);
        assertTrue(first.startY >= 0.70f && first.startY <= 0.94f);
        assertTrue(first.endY >= 0.06f && first.endY <= 0.30f);
        assertTrue(first.durationMs != different.durationMs
                || first.startX != different.startX
                || first.endX != different.endX
                || first.startY != different.startY
                || first.endY != different.endY);
    }

    @Test public void disabledRandomizationUsesStableProfile() {
        PageTurnProfile first = PageTurnProfile.forAttempt(
                1, "natural", false, new Random(1));
        PageTurnProfile repeated = PageTurnProfile.forAttempt(
                1, "natural", false, new Random(999));

        assertEquals(550, first.durationMs);
        assertEquals(first.durationMs, repeated.durationMs);
        assertEquals(first.startX, repeated.startX, 0f);
        assertEquals(first.endX, repeated.endX, 0f);
        assertEquals(first.startY, repeated.startY, 0f);
        assertEquals(first.endY, repeated.endY, 0f);
        assertTrue(!first.randomized);
    }
}
