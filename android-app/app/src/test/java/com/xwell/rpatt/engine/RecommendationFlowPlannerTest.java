package com.xwell.rpatt.engine;

import com.xwell.rpatt.config.WarmupConfig;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RecommendationFlowPlannerTest {
    @Test public void searchWaitsForFeedBootstrapAndEnoughInterestSignals() {
        WarmupConfig config = recommendationConfig();
        RecommendationFlowPlanner planner = new RecommendationFlowPlanner(config);

        planner.observeFeedVideo(true, Collections.singletonList("AI"));
        planner.observeFeedVideo(false, Collections.emptyList());
        assertFalse(planner.shouldSearch());

        planner.observeFeedVideo(true, Collections.singletonList("AI"));
        assertTrue(planner.shouldSearch());
        assertEquals("AI", planner.nextKeyword(new Random(1)));
    }

    @Test public void completedBurstRequiresCooldownAndDoesNotReuseKeyword() {
        WarmupConfig config = recommendationConfig();
        config.recommendationSearchCooldownVideos = 2;
        RecommendationFlowPlanner planner = new RecommendationFlowPlanner(config);
        planner.observeFeedVideo(true, Collections.singletonList("AI"));
        planner.observeFeedVideo(false, Collections.emptyList());
        planner.observeFeedVideo(true, Collections.singletonList("AI"));

        planner.markSearchAttempted("AI");
        planner.observeFeedVideo(true, Collections.singletonList("coding"));
        assertFalse(planner.shouldSearch());
        planner.observeFeedVideo(true, Collections.singletonList("coding"));

        assertTrue(planner.shouldSearch());
        assertEquals("coding", planner.nextKeyword(new Random(1)));
    }

    @Test public void recommendationModeWithoutSearchRemainsFeedOnly() {
        WarmupConfig config = recommendationConfig();
        config.searchEnabled = false;
        RecommendationFlowPlanner planner = new RecommendationFlowPlanner(config);
        for (int index = 0; index < 10; index++) {
            planner.observeFeedVideo(true, Collections.singletonList("AI"));
        }

        assertFalse(planner.shouldSearch());
        assertEquals("", planner.nextKeyword(new Random(1)));
    }

    @Test public void configuredSearchLimitCapsBursts() {
        WarmupConfig config = recommendationConfig();
        config.maxSearches = 1;
        RecommendationFlowPlanner planner = new RecommendationFlowPlanner(config);
        planner.observeFeedVideo(true, Collections.singletonList("AI"));
        planner.observeFeedVideo(true, Collections.singletonList("AI"));
        planner.observeFeedVideo(true, Collections.singletonList("AI"));
        planner.markSearchAttempted("AI");
        for (int index = 0; index < 5; index++) {
            planner.observeFeedVideo(true, Collections.singletonList("coding"));
        }

        assertFalse(planner.shouldSearch());
        assertEquals(1, planner.searchBursts());
    }

    @Test public void strongestMatchingSignalChoosesItsConfiguredKeyword() {
        WarmupConfig config = recommendationConfig();
        config.searchKeywords = Arrays.asList("AI", "coding", "robotics");
        config.recommendationSignalHits = 3;
        RecommendationFlowPlanner planner = new RecommendationFlowPlanner(config);
        planner.observeFeedVideo(true, Collections.singletonList("AI"));
        planner.observeFeedVideo(true, Collections.singletonList("coding"));
        planner.observeFeedVideo(true, Collections.singletonList("coding"));

        assertTrue(planner.shouldSearch());
        assertEquals("coding", planner.nextKeyword(new Random(1)));
    }

    private static WarmupConfig recommendationConfig() {
        WarmupConfig config = WarmupConfig.defaultsFor("custom");
        config.journeyMode = WarmupConfig.JOURNEY_RECOMMENDATION_LED;
        config.searchEnabled = true;
        config.searchKeywords = Arrays.asList("AI", "coding");
        config.maxSearches = 2;
        config.interestEnabled = true;
        config.interestKeywords = Arrays.asList("AI", "coding");
        config.recommendationBootstrapVideos = 3;
        config.recommendationSignalHits = 2;
        config.recommendationSearchCooldownVideos = 2;
        return config;
    }
}
