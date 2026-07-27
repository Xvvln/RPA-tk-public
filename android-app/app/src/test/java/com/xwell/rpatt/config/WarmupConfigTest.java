package com.xwell.rpatt.config;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class WarmupConfigTest {
    @Test public void defaultModulesKeepExistingLightInteractions() {
        WarmupConfig config = WarmupConfig.defaultsFor("cold");
        assertEquals(WarmupConfig.SCHEMA_VERSION,
                config.toJson().optInt("schema_version"));
        assertTrue(config.likeEnabled);
        assertTrue(config.favoriteEnabled);
        assertFalse(config.commentEnabled);
        assertFalse(config.followEnabled);
        assertFalse(config.shareEnabled);
        assertTrue(config.swipeRandomizationEnabled);
        assertTrue(config.commentScrollRandom);
        assertTrue(config.skipLive);
        config.validate();
        assertTrue(config.toJson().optJSONObject("browse").optBoolean("skip_live", false));
    }

    @Test public void skipLiveRoundTripsInBrowseJson() throws Exception {
        WarmupConfig config = WarmupConfig.defaultsFor("cold");
        config.skipLive = false;
        WarmupConfig restored = WarmupConfig.fromJson(config.toJson());
        assertFalse(restored.skipLive);
        restored.skipLive = true;
        assertTrue(WarmupConfig.fromJson(restored.toJson()).skipLive);
    }

    @Test(expected = IllegalArgumentException.class)
    public void enabledInterestModuleRequiresKeywords() {
        WarmupConfig config = WarmupConfig.defaultsFor("cold");
        config.interestEnabled = true;
        config.validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void enabledNotInterestedModuleRequiresNegativeKeywords() {
        WarmupConfig config = WarmupConfig.defaultsFor("cold");
        config.notInterestedEnabled = true;
        config.validate();
    }

    @Test public void legacyNotInterestedWordsMigrateToMatchOnlyNegativeList() {
        WarmupConfig config = WarmupConfig.defaultsFor("active");
        config.interestEnabled = true;
        config.interestKeywords.add("old-positive-value");

        WarmupConfig.migrateLegacyNotInterested(config, Arrays.asList("learn"));

        assertFalse(config.interestEnabled);
        assertTrue(config.interestKeywords.isEmpty());
        assertTrue(config.notInterestedEnabled);
        assertEquals("learn", config.notInterestedKeywords.get(0));
        assertEquals(1.0, config.notInterestedRate, 0.0);
        assertEquals(3, config.maxNotInterested);
    }

    @Test public void positiveAndNegativeInterestsValidateIndependently() {
        WarmupConfig config = WarmupConfig.defaultsFor("custom");
        config.interestEnabled = true;
        config.interestKeywords.add("AI");
        config.notInterestedEnabled = true;
        config.notInterestedKeywords.add("learn");
        config.notInterestedRate = 0.4;
        config.maxNotInterested = 2;

        config.validate();
        assertEquals("AI", config.interestKeywords.get(0));
        assertEquals("learn", config.notInterestedKeywords.get(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void swipePaceUsesKnownValues() {
        WarmupConfig config = WarmupConfig.defaultsFor("cold");
        config.swipePace = "constant";
        config.validate();
    }

    @Test public void profilesOwnDifferentWorkflowOrders() {
        WarmupConfig cold = WarmupConfig.defaultsFor("cold");
        WarmupConfig normal = WarmupConfig.defaultsFor("normal");
        assertEquals(WarmupConfig.STEP_COMMENTS, cold.workflowOrder.get(0));
        assertEquals(WarmupConfig.STEP_LIKE, normal.workflowOrder.get(0));
        cold.validate();
        normal.validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void workflowCannotLoseAnAction() {
        WarmupConfig config = WarmupConfig.defaultsFor("custom");
        config.workflowOrder.remove(WarmupConfig.STEP_SHARE);
        config.validate();
    }

    @Test public void customSourceOrderCanPutFeedBeforeSearch() {
        WarmupConfig config = WarmupConfig.defaultsFor("custom");
        config.sourceOrder = Arrays.asList(WarmupConfig.SOURCE_FEED, WarmupConfig.SOURCE_SEARCH);

        config.validate();

        assertEquals(WarmupConfig.SOURCE_FEED, config.sourceOrder.get(0));
    }

    @Test public void oldConfigKeepsOrderedJourneyByDefault() throws Exception {
        WarmupConfig config = WarmupConfig.fromJson(new JSONObject()
                .put("profile", "custom")
                .put("session", new JSONObject().put("max_minutes", 20).put("video_count", 12)));

        assertEquals(WarmupConfig.JOURNEY_ORDERED, config.journeyMode);
        assertEquals(1.0, config.commentReadRate, 0.0);
        assertFalse(config.commentsOnlyIfRelevant);
        assertTrue(config.swipeRandomizationEnabled);
        assertTrue(config.commentScrollRandom);
        assertEquals(0, config.interestWatchExtraMinSeconds);
        assertEquals(0, config.interestWatchExtraMaxSeconds);
    }

    @Test public void recommendationJourneyRoundTrips() {
        WarmupConfig config = WarmupConfig.defaultsFor("custom");
        config.journeyMode = WarmupConfig.JOURNEY_RECOMMENDATION_LED;
        config.recommendationBootstrapVideos = 4;
        config.recommendationSignalHits = 2;
        config.recommendationSearchCooldownVideos = 6;
        config.searchEnabled = true;
        config.searchKeywords = Arrays.asList("AI");
        config.interestEnabled = true;
        config.interestKeywords = Arrays.asList("AI");
        config.interestWatchExtraMinSeconds = 2;
        config.interestWatchExtraMaxSeconds = 7;
        config.commentReadRate = 0.6;
        config.commentsOnlyIfRelevant = true;
        config.swipeRandomizationEnabled = false;
        config.commentScrollRandom = false;

        WarmupConfig restored = WarmupConfig.fromJson(config.toJson());

        assertEquals(WarmupConfig.JOURNEY_RECOMMENDATION_LED, restored.journeyMode);
        assertEquals(4, restored.recommendationBootstrapVideos);
        assertEquals(2, restored.recommendationSignalHits);
        assertEquals(6, restored.recommendationSearchCooldownVideos);
        assertEquals(2, restored.interestWatchExtraMinSeconds);
        assertEquals(7, restored.interestWatchExtraMaxSeconds);
        assertEquals(0.6, restored.commentReadRate, 0.0);
        assertTrue(restored.commentsOnlyIfRelevant);
        assertFalse(restored.swipeRandomizationEnabled);
        assertFalse(restored.commentScrollRandom);
    }

    @Test(expected = IllegalArgumentException.class)
    public void recommendationSearchNeedsInterestSignals() {
        WarmupConfig config = WarmupConfig.defaultsFor("custom");
        config.journeyMode = WarmupConfig.JOURNEY_RECOMMENDATION_LED;
        config.searchEnabled = true;
        config.searchKeywords = Arrays.asList("AI");
        config.validate();
    }
}
