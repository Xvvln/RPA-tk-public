package com.xwell.rpatt.engine;

import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class BrowseRandomizerTest {
    @Test public void disabledRandomizationAlwaysUsesMaximum() {
        assertEquals(4, BrowseRandomizer.commentScrollCount(4, false, new Random(1)));
        assertEquals(4, BrowseRandomizer.commentScrollCount(4, false, new Random(999)));
    }

    @Test public void randomScrollCountVariesWithinInclusiveUpperBound() {
        Random random = new Random(17);
        Set<Integer> observed = new HashSet<>();
        for (int index = 0; index < 80; index++) {
            int sampled = BrowseRandomizer.commentScrollCount(4, true, random);
            assertTrue(sampled >= 0);
            assertTrue(sampled <= 4);
            observed.add(sampled);
        }
        assertTrue(observed.size() > 1);
    }

    @Test public void zeroOrNegativeMaximumCannotProduceScrolls() {
        assertEquals(0, BrowseRandomizer.commentScrollCount(0, true, new Random(2)));
        assertEquals(0, BrowseRandomizer.commentScrollCount(-3, true, new Random(2)));
    }
}
