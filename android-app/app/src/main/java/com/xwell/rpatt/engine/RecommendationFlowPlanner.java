package com.xwell.rpatt.engine;

import com.xwell.rpatt.config.WarmupConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Pure session-level state machine for recommendation-led browsing.
 *
 * <p>This class only decides when a bounded search burst is eligible and which configured
 * keyword should be used. It does not know about Android UI and cannot dispatch an action.</p>
 */
final class RecommendationFlowPlanner {
    private final WarmupConfig config;
    private final Map<String, Integer> signalCounts = new LinkedHashMap<>();
    private final Set<String> usedKeywords = new HashSet<>();
    private int feedVideosSeen;
    private int feedVideosSinceSearch;
    private int relevantSignals;
    private int searchBursts;

    RecommendationFlowPlanner(WarmupConfig config) {
        this.config = config;
    }

    void observeFeedVideo(boolean relevant, List<String> matchedKeywords) {
        feedVideosSeen++;
        feedVideosSinceSearch++;
        if (!relevant || matchedKeywords == null || matchedKeywords.isEmpty()) return;
        relevantSignals++;
        for (String keyword : matchedKeywords) {
            String normalized = normalize(keyword);
            if (normalized.isEmpty()) continue;
            signalCounts.put(normalized, signalCounts.getOrDefault(normalized, 0) + 1);
        }
    }

    boolean shouldSearch() {
        if (!config.searchEnabled || !config.interestEnabled) return false;
        if (availableKeywords().isEmpty()) return false;
        if (searchBursts >= Math.min(config.maxSearches, clean(config.searchKeywords).size())) {
            return false;
        }
        int requiredFeedVideos = searchBursts == 0
                ? config.recommendationBootstrapVideos
                : config.recommendationSearchCooldownVideos;
        return feedVideosSeen >= config.recommendationBootstrapVideos
                && feedVideosSinceSearch >= requiredFeedVideos
                && relevantSignals >= config.recommendationSignalHits;
    }

    String nextKeyword(Random random) {
        if (!shouldSearch()) return "";
        List<String> available = availableKeywords();
        if (available.isEmpty()) return "";

        String strongest = "";
        int strongestCount = 0;
        for (String candidate : available) {
            int count = signalCounts.getOrDefault(normalize(candidate), 0);
            if (count > strongestCount) {
                strongest = candidate;
                strongestCount = count;
            }
        }
        if (!strongest.isEmpty()) return strongest;

        if ("random".equals(config.searchPick)) {
            return available.get(random.nextInt(available.size()));
        }
        return available.get(0);
    }

    void markSearchAttempted(String keyword) {
        String normalized = normalize(keyword);
        if (!normalized.isEmpty()) usedKeywords.add(normalized);
        searchBursts++;
        feedVideosSinceSearch = 0;
        relevantSignals = 0;
        signalCounts.clear();
    }

    int feedVideosSeen() {
        return feedVideosSeen;
    }

    int feedVideosSinceSearch() {
        return feedVideosSinceSearch;
    }

    int relevantSignals() {
        return relevantSignals;
    }

    int searchBursts() {
        return searchBursts;
    }

    private List<String> availableKeywords() {
        List<String> result = new ArrayList<>();
        for (String keyword : clean(config.searchKeywords)) {
            if (!usedKeywords.contains(normalize(keyword))) result.add(keyword);
        }
        return result;
    }

    private static List<String> clean(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values == null) return result;
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            String trimmed = value == null ? "" : value.trim();
            String normalized = normalize(trimmed);
            if (!trimmed.isEmpty() && seen.add(normalized)) result.add(trimmed);
        }
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
