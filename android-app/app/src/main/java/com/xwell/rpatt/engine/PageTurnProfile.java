package com.xwell.rpatt.engine;

import java.util.Random;

/** Progressive, bounded gesture profiles for changing the active TikTok item. */
final class PageTurnProfile {
    final String name;
    final long durationMs;
    final float startX;
    final float endX;
    final float startY;
    final float endY;
    final boolean randomized;

    private PageTurnProfile(String name, long durationMs, float startX, float endX,
                            float startY, float endY, boolean randomized) {
        this.name = name;
        this.durationMs = durationMs;
        this.startX = startX;
        this.endX = endX;
        this.startY = startY;
        this.endY = endY;
        this.randomized = randomized;
    }

    static PageTurnProfile forAttempt(int attempt, String configuredPace, Random random) {
        return forAttempt(attempt, configuredPace, true, random);
    }

    static PageTurnProfile forAttempt(int attempt, String configuredPace,
                                      boolean randomize, Random random) {
        if (attempt <= 1) {
            return profile("standard",
                    randomize ? configuredDuration(random, configuredPace)
                            : fixedConfiguredDuration(configuredPace),
                    0.52f, 0.48f, 0.79f, 0.23f, randomize, random);
        }
        if (attempt == 2) {
            return profile("extended_flick", randomize ? sample(random, 180, 300) : 240,
                    0.60f, 0.58f, 0.88f, 0.14f, randomize, random);
        }
        return profile("maximum_flick", randomize ? sample(random, 200, 340) : 270,
                0.64f, 0.61f, 0.91f, 0.08f, randomize, random);
    }

    private static PageTurnProfile profile(String name, long durationMs,
                                           float startX, float endX, float startY, float endY,
                                           boolean randomize, Random random) {
        if (!randomize) {
            return new PageTurnProfile(
                    name, durationMs, startX, endX, startY, endY, false);
        }
        return new PageTurnProfile(name, durationMs,
                boundedJitter(random, startX, 0.055f, 0.40f, 0.72f),
                boundedJitter(random, endX, 0.055f, 0.38f, 0.70f),
                boundedJitter(random, startY, 0.04f, 0.70f, 0.94f),
                boundedJitter(random, endY, 0.04f, 0.06f, 0.30f),
                true);
    }

    private static long configuredDuration(Random random, String pace) {
        if ("slow".equals(pace)) return sample(random, 650, 1050);
        if ("fast".equals(pace)) return sample(random, 180, 360);
        return sample(random, 300, 850);
    }

    private static long fixedConfiguredDuration(String pace) {
        if ("slow".equals(pace)) return 850;
        if ("fast".equals(pace)) return 270;
        return 550;
    }

    private static float boundedJitter(Random random, float center, float radius,
                                       float minimum, float maximum) {
        float sampled = center + ((random.nextFloat() * 2f) - 1f) * radius;
        return Math.max(minimum, Math.min(maximum, sampled));
    }

    private static int sample(Random random, int minimum, int maximum) {
        if (maximum <= minimum) return minimum;
        return minimum + random.nextInt(maximum - minimum + 1);
    }
}
