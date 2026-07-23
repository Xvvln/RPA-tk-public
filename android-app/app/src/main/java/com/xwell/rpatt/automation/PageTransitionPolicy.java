package com.xwell.rpatt.automation;

/** Explicit action intent constrains which page transitions are considered legitimate. */
public final class PageTransitionPolicy {
    public enum Action {
        NONE, OPEN_COMMENTS, CLOSE_COMMENTS, OPEN_SEARCH, SUBMIT_SEARCH,
        OPEN_SEARCH_VIDEO, RETURN_HOME, SWIPE_VIDEO
    }

    private PageTransitionPolicy() {}

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
                return from == PageState.Page.FEED && to == PageState.Page.SEARCH_INPUT;
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
}
