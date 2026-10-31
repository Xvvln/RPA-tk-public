package com.xwell.rpatt.engine;

import com.xwell.rpatt.config.WarmupConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Resolves all supported signals into one deduplicated "not interested" action decision. */
final class NotInterestedPolicy {
    static final String REASON_NONE = "none";
    static final String REASON_INTEREST_MISS = "interest_keywords_unmatched";
    static final String REASON_NEGATIVE_KEYWORD = "negative_keyword_matched";

    private NotInterestedPolicy() {}

    static Decision decide(WarmupConfig config, List<String> negativeMatches, boolean relevant) {
        if (config.unmatchedInterestFeedbackEnabled() && !relevant) {
            // "All unmatched" is deterministic. The shared per-session cap still applies in
            // WarmupEngine, but the negative-keyword probability must not discard this trigger.
            return new Decision(true, REASON_INTEREST_MISS, 1.0,
                    Collections.emptyList());
        }
        if (config.notInterestedEnabled
                && negativeMatches != null && !negativeMatches.isEmpty()) {
            return new Decision(true, REASON_NEGATIVE_KEYWORD, config.notInterestedRate,
                    negativeMatches);
        }
        return new Decision(false, REASON_NONE, 0.0, Collections.emptyList());
    }

    static final class Decision {
        final boolean shouldPlan;
        final String reason;
        final double rate;
        final List<String> matchedKeywords;

        Decision(boolean shouldPlan, String reason, double rate, List<String> matchedKeywords) {
            this.shouldPlan = shouldPlan;
            this.reason = reason;
            this.rate = rate;
            this.matchedKeywords = Collections.unmodifiableList(
                    new ArrayList<>(matchedKeywords));
        }
    }
}
