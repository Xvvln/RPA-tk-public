package com.xwell.rpatt.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class PageStateSafetyTest {
    @Test
    public void requiresTwoMatchingConfidentSnapshots() {
        PageState feed = state(PageState.Page.FEED, 0.86d);
        PageState comments = state(PageState.Page.COMMENTS, 0.94d);

        assertEquals(PageState.Page.FEED, PageStability.consecutive(feed, feed).page);
        assertEquals(PageState.Page.UNKNOWN, PageStability.consecutive(feed, comments).page);
        assertEquals(PageState.Page.UNKNOWN,
                PageStability.consecutive(state(PageState.Page.FEED, 0.6d), feed).page);
    }

    @Test
    public void unknownWithoutConfirmedBlockerIsTransientObservationGap() {
        PageState unknown = state(PageState.Page.UNKNOWN, 0d);
        PageState blocked = new PageState(PageState.Page.UNKNOWN, "none",
                Collections.singletonList("captcha"), Collections.emptyList(), 0d,
                Collections.singletonList("blocker:captcha"));

        assertTrue(PageStability.isTransientUnknown(unknown));
        assertFalse(PageStability.isTransientUnknown(blocked));
    }

    @Test
    public void commentsRequireExplicitOpenAction() {
        assertTrue(PageTransitionPolicy.allows(PageState.Page.FEED, PageState.Page.COMMENTS,
                PageTransitionPolicy.Action.OPEN_COMMENTS));
        assertFalse(PageTransitionPolicy.allows(PageState.Page.FEED, PageState.Page.COMMENTS,
                PageTransitionPolicy.Action.NONE));
        assertFalse(PageTransitionPolicy.allows(PageState.Page.SEARCH_RESULTS, PageState.Page.COMMENTS,
                PageTransitionPolicy.Action.OPEN_SEARCH_VIDEO));
    }

    @Test
    public void transientForegroundBlockerRequiresTwoSamples() {
        PageState transientBlock = new PageState(PageState.Page.FEED, "video",
                Collections.singletonList("tiktok_not_foreground"), Collections.emptyList(),
                0.92d, Arrays.asList("window:overlay"));
        PageState stableFeed = state(PageState.Page.FEED, 0.92d);

        PageState recovered = PageStability.consecutive(transientBlock, stableFeed);
        PageState confirmed = PageStability.consecutive(transientBlock, transientBlock);

        assertEquals(PageState.Page.FEED, recovered.page);
        assertFalse(recovered.blockers.contains("tiktok_not_foreground"));
        assertTrue(confirmed.blockers.contains("tiktok_not_foreground"));
    }

    private static PageState state(PageState.Page page, double confidence) {
        return new PageState(page, "none", Collections.emptyList(), Collections.emptyList(),
                confidence, Arrays.asList("test"));
    }
}
