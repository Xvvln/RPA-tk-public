package com.xwell.rpatt.engine;

import com.xwell.rpatt.config.WarmupConfig;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public final class WarmupEngineSourceOrderTest {
    @Test public void searchReservationUsesConfiguredKeywordsAndPerKeywordCount() {
        WarmupConfig config = WarmupConfig.defaultsFor("custom");
        config.videoCount = 30;
        config.searchEnabled = true;
        config.searchKeywords = Arrays.asList("AI", "coding");
        config.maxSearches = 3;
        config.videosPerKeyword = 2;

        assertEquals(4, WarmupEngine.plannedSearchVideos(config));
    }

    @Test public void searchReservationNeverExceedsWholeVideoBudget() {
        WarmupConfig config = WarmupConfig.defaultsFor("custom");
        config.videoCount = 3;
        config.searchEnabled = true;
        config.searchKeywords = Arrays.asList("AI", "coding");
        config.maxSearches = 2;
        config.videosPerKeyword = 5;

        assertEquals(3, WarmupEngine.plannedSearchVideos(config));
    }
}
