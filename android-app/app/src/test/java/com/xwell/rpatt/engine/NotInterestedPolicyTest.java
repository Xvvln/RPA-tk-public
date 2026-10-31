package com.xwell.rpatt.engine;

import com.xwell.rpatt.config.WarmupConfig;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NotInterestedPolicyTest {
    @Test public void unmatchedPositiveInterestAlwaysPlansFeedback() {
        WarmupConfig config = interestFallbackConfig();
        config.notInterestedRate = 0.0;

        NotInterestedPolicy.Decision decision = NotInterestedPolicy.decide(
                config, Collections.emptyList(), false);

        assertTrue(decision.shouldPlan);
        assertEquals(NotInterestedPolicy.REASON_INTEREST_MISS, decision.reason);
        assertEquals(1.0, decision.rate, 0.0);
        assertTrue(decision.matchedKeywords.isEmpty());
    }

    @Test public void positiveInterestMatchSuppressesFallback() {
        WarmupConfig config = interestFallbackConfig();

        NotInterestedPolicy.Decision decision = NotInterestedPolicy.decide(
                config, Collections.emptyList(), true);

        assertFalse(decision.shouldPlan);
        assertEquals(NotInterestedPolicy.REASON_NONE, decision.reason);
    }

    @Test public void explicitNegativeKeywordStillUsesConfiguredRate() {
        WarmupConfig config = WarmupConfig.defaultsFor("custom");
        config.notInterestedEnabled = true;
        config.notInterestedKeywords = Arrays.asList("广告");
        config.notInterestedRate = 0.4;

        NotInterestedPolicy.Decision decision = NotInterestedPolicy.decide(
                config, Arrays.asList("广告"), true);

        assertTrue(decision.shouldPlan);
        assertEquals(NotInterestedPolicy.REASON_NEGATIVE_KEYWORD, decision.reason);
        assertEquals(0.4, decision.rate, 0.0);
        assertEquals(Arrays.asList("广告"), decision.matchedKeywords);
    }

    @Test public void interestMissWinsWhenBothTriggersMatch() {
        WarmupConfig config = interestFallbackConfig();
        config.notInterestedEnabled = true;
        config.notInterestedKeywords = Arrays.asList("广告");
        config.notInterestedRate = 0.2;

        NotInterestedPolicy.Decision decision = NotInterestedPolicy.decide(
                config, Arrays.asList("广告"), false);

        assertEquals(NotInterestedPolicy.REASON_INTEREST_MISS, decision.reason);
        assertEquals(1.0, decision.rate, 0.0);
    }

    private static WarmupConfig interestFallbackConfig() {
        WarmupConfig config = WarmupConfig.defaultsFor("custom");
        config.interestEnabled = true;
        config.interestKeywords = Arrays.asList("AI");
        config.unmatchedAsNotInterested = true;
        return config;
    }
}
