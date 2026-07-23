package com.xwell.rpatt.engine;

import java.util.Random;

/** Pure bounded sampling helpers shared by browsing actions. */
final class BrowseRandomizer {
    private BrowseRandomizer() {}

    static int commentScrollCount(int maximum, boolean randomize, Random random) {
        int boundedMaximum = Math.max(0, maximum);
        if (!randomize || boundedMaximum == 0) return boundedMaximum;
        return random.nextInt(boundedMaximum + 1);
    }
}
