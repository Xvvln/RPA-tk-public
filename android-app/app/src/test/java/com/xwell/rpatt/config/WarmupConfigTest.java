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
        assertFalse(config.unmatchedAsNotInterested);
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

    @Test public void schemaFourEnabledCommentWithCompleteContractMigratesToNonZeroRate()
            throws Exception {
        JSONObject legacy = WarmupConfig.defaultsFor("custom").toJson()
                .put("schema_version", 4);
        legacy.getJSONObject("interactions")
                .put("comment_enabled", true)
                .put("comment_rate", 0.0)
                .put("max_comments", 1)
                .put("comment_texts", new org.json.JSONArray().put("test comment"));

        WarmupConfig migrated = WarmupConfig.fromJson(legacy);

        assertTrue(migrated.commentEnabled);
        assertEquals(WarmupConfig.DEFAULT_ENABLED_COMMENT_RATE,
                migrated.commentRate, 0.0);
    }

    @Test public void schemaFourIncompleteCommentContractMigratesToDisabled() throws Exception {
        JSONObject legacy = WarmupConfig.defaultsFor("custom").toJson()
                .put("schema_version", 4);
        legacy.getJSONObject("interactions")
                .put("comment_enabled", true)
                .put("comment_rate", 0.0)
                .put("comment_texts", new org.json.JSONArray());

        WarmupConfig migrated = WarmupConfig.fromJson(legacy);

        assertFalse(migrated.commentEnabled);
        assertEquals(0.0, migrated.commentRate, 0.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void currentSchemaRejectsEnabledCommentWithZeroRate() throws Exception {
        JSONObject invalid = WarmupConfig.defaultsFor("custom").toJson();
        invalid.getJSONObject("interactions")
                .put("comment_enabled", true)
                .put("comment_rate", 0.0)
                .put("max_comments", 1)
                .put("comment_texts", new org.json.JSONArray().put("test comment"));

        WarmupConfig.fromJson(invalid);
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

    @Test public void unmatchedInterestFeedbackRoundTripsAndActivatesSharedStep() {
        WarmupConfig config = WarmupConfig.defaultsFor("custom");
        config.interestEnabled = true;
        config.interestKeywords = Arrays.asList("AI");
        config.unmatchedAsNotInterested = true;
        config.notInterestedEnabled = false;

        WarmupConfig restored = WarmupConfig.fromJson(config.toJson());

        assertTrue(restored.unmatchedAsNotInterested);
        assertTrue(restored.unmatchedInterestFeedbackEnabled());
        assertTrue(restored.hasNotInterestedTrigger());
        assertFalse(restored.notInterestedEnabled);
    }

    @Test public void unmatchedInterestFeedbackIsDormantWhenInterestModuleIsOff() {
        WarmupConfig config = WarmupConfig.defaultsFor("custom");
        config.unmatchedAsNotInterested = true;

        assertFalse(config.unmatchedInterestFeedbackEnabled());
        assertFalse(config.hasNotInterestedTrigger());
        config.validate();
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
        assertEquals(WarmupConfig.STEP_COMMENTS, normal.workflowOrder.get(0));
        cold.validate();
        normal.validate();
    }

    @Test public void builtInProfilesChangeBehaviorBeyondSessionBudget() {
        WarmupConfig cold = WarmupConfig.defaultsFor("cold");
        WarmupConfig normal = WarmupConfig.defaultsFor("normal");
        WarmupConfig active = WarmupConfig.defaultsFor("active");

        assertEquals(WarmupConfig.JOURNEY_ORDERED, cold.journeyMode);
        assertEquals(WarmupConfig.JOURNEY_RECOMMENDATION_LED, normal.journeyMode);
        assertEquals("slow", cold.swipePace);
        assertEquals("natural", normal.swipePace);
        assertTrue(cold.commentReadRate < normal.commentReadRate);
        assertTrue(normal.commentReadRate < active.commentReadRate);
        assertFalse(cold.followEnabled);
        assertTrue(normal.followEnabled);
        assertFalse(normal.shareEnabled);
        assertTrue(active.shareEnabled);
        assertTrue(cold.likeRate < normal.likeRate);
        assertTrue(normal.likeRate < active.likeRate);
        cold.validate();
        normal.validate();
        active.validate();
    }

    @Test public void presetKeepsAuthoredContentAndEnablesOnlyCompleteModules() {
        WarmupConfig authored = WarmupConfig.defaultsFor("custom");
        authored.searchKeywords = Arrays.asList("GPT");
        authored.interestKeywords = Arrays.asList("AI");
        authored.unmatchedAsNotInterested = true;
        authored.notInterestedKeywords = Arrays.asList("spam");
        authored.commentTexts = Arrays.asList("Interesting");

        WarmupConfig active = WarmupConfig.presetFor("active", authored);

        assertEquals("GPT", active.searchKeywords.get(0));
        assertEquals("AI", active.interestKeywords.get(0));
        assertEquals("spam", active.notInterestedKeywords.get(0));
        assertEquals("Interesting", active.commentTexts.get(0));
        assertTrue(active.searchEnabled);
        assertTrue(active.interestEnabled);
        assertTrue(active.unmatchedAsNotInterested);
        assertTrue(active.notInterestedEnabled);
        assertTrue(active.commentEnabled);
        active.validate();
    }

    @Test public void presetWithoutAuthoredContentNeverCreatesInvalidSwitches() {
        WarmupConfig active = WarmupConfig.presetFor(
                "active", WarmupConfig.defaultsFor("custom"));

        assertFalse(active.searchEnabled);
        assertFalse(active.interestEnabled);
        assertFalse(active.notInterestedEnabled);
        assertFalse(active.commentEnabled);
        active.validate();
    }

    @Test public void lightPresetKeepsTextButDoesNotStartSearchOrPublishing() {
        WarmupConfig authored = WarmupConfig.defaultsFor("custom");
        authored.searchKeywords = Arrays.asList("GPT");
        authored.interestKeywords = Arrays.asList("AI");
        authored.commentTexts = Arrays.asList("Interesting");

        WarmupConfig cold = WarmupConfig.presetFor("cold", authored);

        assertEquals("GPT", cold.searchKeywords.get(0));
        assertTrue(cold.interestEnabled);
        assertFalse(cold.searchEnabled);
        assertFalse(cold.commentEnabled);
        cold.validate();
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
