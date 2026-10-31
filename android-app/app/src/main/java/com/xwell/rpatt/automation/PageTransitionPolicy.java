package com.xwell.rpatt.automation;

/** Explicit action intent constrains which page transitions are considered legitimate. */
public final class PageTransitionPolicy {
    public enum Action {
        NONE, OPEN_COMMENTS, CLOSE_COMMENTS, OPEN_SEARCH, SUBMIT_SEARCH,
        OPEN_SEARCH_VIDEO, RETURN_HOME, SWIPE_VIDEO
    }

    private PageTransitionPolicy() {}

    /**
     * Page-only compatibility check. Stateful engine code should prefer the evidence-aware
     * overload so a residual modal cannot impersonate the destination page.
     */
    public static boolean allows(PageState.Page from, PageState.Page to, Action action) {
        if (from == PageState.Page.UNKNOWN || to == PageState.Page.UNKNOWN) return false;
        switch (action) {
            case NONE:
                return from == to;
            case OPEN_COMMENTS:
                return from == PageState.Page.FEED && to == PageState.Page.COMMENTS;
            case CLOSE_COMMENTS:
                return from == PageState.Page.COMMENTS && to == PageState.Page.FEED;
            case OPEN_SEARCH:
                // TikTok may restore the last search results instead of showing a blank editor.
                // Both are legitimate search landings; the engine must still overwrite and
                // verify the field before it is allowed to submit the configured query.
                return from == PageState.Page.FEED
                        && (to == PageState.Page.SEARCH_INPUT
                        || to == PageState.Page.SEARCH_RESULTS);
            case SUBMIT_SEARCH:
                return from == PageState.Page.SEARCH_INPUT
                        && (to == PageState.Page.SEARCH_RESULTS || to == PageState.Page.SEARCH_EMPTY);
            case OPEN_SEARCH_VIDEO:
                return from == PageState.Page.SEARCH_RESULTS && to == PageState.Page.FEED;
            case RETURN_HOME:
                return to == PageState.Page.FEED;
            case SWIPE_VIDEO:
                return from == PageState.Page.FEED && to == PageState.Page.FEED;
            default:
                return false;
        }
    }

    public static boolean allows(PageState.Page from, PageState observed, Action action) {
        if (observed == null || !allows(from, observed.page, action)) return false;
        if (action != Action.CLOSE_COMMENTS) return true;
        return !observed.interruptions.contains("comments_open")
                && observed.evidence.contains("comments:modal_absent")
                && observed.evidence.contains("input_surface:none");
    }
}
