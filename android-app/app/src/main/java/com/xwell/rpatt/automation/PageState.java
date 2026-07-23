package com.xwell.rpatt.automation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class PageState {
    public enum Page { FEED, COMMENTS, SEARCH_INPUT, SEARCH_RESULTS, SEARCH_EMPTY, INBOX, PROFILE, SHOP, SETTINGS, UNKNOWN }

    public final Page page;
    public final String contentKind;
    public final List<String> blockers;
    public final List<String> interruptions;
    public final double confidence;
    public final List<String> evidence;

    public PageState(Page page, String contentKind, List<String> blockers, List<String> interruptions) {
        this(page, contentKind, blockers, interruptions, page == Page.UNKNOWN ? 0d : 1d,
                Collections.emptyList());
    }

    public PageState(Page page, String contentKind, List<String> blockers, List<String> interruptions,
                     double confidence, List<String> evidence) {
        this.page = page;
        this.contentKind = contentKind;
        this.blockers = Collections.unmodifiableList(new ArrayList<>(blockers));
        this.interruptions = Collections.unmodifiableList(new ArrayList<>(interruptions));
        this.confidence = Math.max(0d, Math.min(1d, confidence));
        this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
    }

    public boolean safe() { return blockers.isEmpty() && page != Page.UNKNOWN; }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("page", page.name().toLowerCase(Locale.ROOT))
                .put("content_kind", contentKind)
                .put("safe", safe())
                .put("confidence", confidence)
                .put("evidence", new JSONArray(evidence))
                .put("blockers", new JSONArray(blockers))
                .put("interruptions", new JSONArray(interruptions));
    }
}
