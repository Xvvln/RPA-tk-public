package com.xwell.rpatt.automation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Requires two independent observations before a page is safe for stateful actions. */
public final class PageStability {
    private static final double MIN_CONFIDENCE = 0.72d;

    private PageStability() {}

    /**
     * UNKNOWN without a confirmed blocker is an observation gap, not proof that navigation left
     * the expected page. Animations and OEM accessibility-window handoffs commonly produce this
     * state for a few frames, so callers should wait for a bounded contract deadline.
     */
    public static boolean isTransientUnknown(PageState state) {
        return state.page == PageState.Page.UNKNOWN && state.blockers.isEmpty();
    }

    public static PageState consecutive(PageState first, PageState second) {
        List<String> blockers = stableBlockers(first.blockers, second.blockers);
        List<String> interruptions = union(first.interruptions, second.interruptions);
        if (first.page == second.page && first.page != PageState.Page.UNKNOWN
                && first.confidence >= MIN_CONFIDENCE && second.confidence >= MIN_CONFIDENCE) {
            List<String> evidence = union(first.evidence, second.evidence);
            evidence.add("stable:2_consecutive_snapshots");
            return new PageState(second.page, second.contentKind, blockers, interruptions,
                    Math.min(first.confidence, second.confidence), evidence);
        }
        List<String> evidence = union(first.evidence, second.evidence);
        if (containsOnlyOne(first.blockers, second.blockers, "tiktok_not_foreground")) {
            evidence.add("transient:tiktok_not_foreground");
        }
        evidence.add("unstable:" + first.page.name() + "->" + second.page.name());
        return new PageState(PageState.Page.UNKNOWN, "none", blockers, interruptions, 0d, evidence);
    }

    private static List<String> stableBlockers(List<String> first, List<String> second) {
        List<String> values = union(first, second);
        // Foreground focus changes are inherently transient in a multi-window accessibility
        // environment. Verification/login/risk-control evidence remains fail-fast, while this
        // one blocker requires confirmation in both samples.
        if (containsOnlyOne(first, second, "tiktok_not_foreground")) {
            values.remove("tiktok_not_foreground");
        }
        return values;
    }

    private static boolean containsOnlyOne(List<String> first, List<String> second, String value) {
        return first.contains(value) != second.contains(value);
    }

    private static List<String> union(List<String> first, List<String> second) {
        LinkedHashSet<String> values = new LinkedHashSet<>(first);
        values.addAll(second);
        return new ArrayList<>(values);
    }
}
