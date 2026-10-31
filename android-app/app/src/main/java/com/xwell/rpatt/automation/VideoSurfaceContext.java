package com.xwell.rpatt.automation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Identifies where a full-screen video came from without changing the page classifier.
 *
 * <p>TikTok renders both recommendation videos and videos opened from search as the same
 * full-screen player. PageState therefore remains FEED for both shapes. This orthogonal contract
 * combines navigation anchors, geometry and video controls so source routing never relies on a
 * single resource id or on the player alone.</p>
 */
public final class VideoSurfaceContext {
    public enum Kind { RECOMMENDATION_HOME, SEARCH_DETAIL, UNKNOWN }

    private static final double MIN_STABLE_CONFIDENCE = 0.80d;

    public static final class Result {
        public final Kind kind;
        public final double confidence;
        public final List<String> evidence;

        Result(Kind kind, double confidence, List<String> evidence) {
            this.kind = kind;
            this.confidence = Math.max(0d, Math.min(1d, confidence));
            this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
        }
    }

    private VideoSurfaceContext() {}

    public static Result analyze(UiSnapshot snapshot) {
        CommentSurfaceContract.Result commentSurface = CommentSurfaceContract.analyze(snapshot);
        if (commentSurface.blocksFeedClassification()) {
            List<String> evidence = new ArrayList<>(commentSurface.evidence);
            evidence.add("context:comment_modal_precedence");
            evidence.add("context:input_surface="
                    + commentSurface.inputSurface.kind.name().toLowerCase(Locale.ROOT));
            return new Result(Kind.UNKNOWN, 0d, evidence);
        }

        UiNode recommendationTab = findExact(snapshot, Region.TOP, "推荐", "for you");
        UiNode homeNavigation = findExact(snapshot, Region.BOTTOM, "首页", "home");
        boolean homeContract = sameWindow(recommendationTab, homeNavigation);

        UiNode back = findExact(snapshot, Region.TOP, "返回", "back");
        UiNode search = findExact(snapshot, Region.TOP, "搜索", "search");
        ControlResolver.Match composerMatch = ControlResolver.resolve(
                snapshot, ControlResolver.Role.COMMENT_FIELD);
        UiNode composer = composerMatch == null ? null : composerMatch.node;
        int videoActions = videoActionCount(snapshot);
        boolean detailContract = back != null && videoActions >= 2
                && ((search != null && sameWindow(back, search))
                || (composer != null && sameWindow(back, composer)));

        if (homeContract && detailContract) {
            return result(Kind.UNKNOWN, 0d,
                    "context:conflicting_contracts",
                    "home:recommendation_tab", "home:bottom_navigation",
                    "search_detail:back", "search_detail:video_actions=" + videoActions);
        }
        if (homeContract) {
            return result(Kind.RECOMMENDATION_HOME, 0.98d,
                    "home:recommendation_tab", "home:bottom_navigation",
                    "home:shared_window");
        }
        if (detailContract) {
            List<String> evidence = new ArrayList<>();
            evidence.add("search_detail:back");
            evidence.add("search_detail:video_actions=" + videoActions);
            if (search != null && sameWindow(back, search)) evidence.add("search_detail:top_search");
            if (composer != null && sameWindow(back, composer)) evidence.add("search_detail:bottom_composer");
            return new Result(Kind.SEARCH_DETAIL,
                    evidence.size() >= 4 ? 0.98d : 0.92d, evidence);
        }

        List<String> evidence = new ArrayList<>();
        evidence.add(recommendationTab == null
                ? "home:recommendation_tab_missing" : "home:recommendation_tab_present");
        evidence.add(homeNavigation == null
                ? "home:bottom_navigation_missing" : "home:bottom_navigation_present");
        evidence.add(back == null ? "search_detail:back_missing" : "search_detail:back_present");
        evidence.add("search_detail:video_actions=" + videoActions);
        return new Result(Kind.UNKNOWN, 0d, evidence);
    }

    /**
     * Require a stable origin contract across two snapshots.
     *
     * <p>Cold start and page-turn animations often blank top tabs or the action rail for a single
     * frame. Treat {@code HOME|SEARCH + UNKNOWN} as a transient observation of the known side so
     * opening recovery can wait instead of failing immediately. Conflicting home vs search
     * contracts still collapse to UNKNOWN.</p>
     */
    public static Result consecutive(Result first, Result second) {
        List<String> evidence = union(first.evidence, second.evidence);
        if (first.kind == second.kind && first.kind != Kind.UNKNOWN
                && first.confidence >= MIN_STABLE_CONFIDENCE
                && second.confidence >= MIN_STABLE_CONFIDENCE) {
            evidence.add("context:stable_2_consecutive_snapshots");
            return new Result(second.kind, Math.min(first.confidence, second.confidence), evidence);
        }
        Result tolerated = tolerateTransientUnknown(first, second, evidence);
        if (tolerated != null) return tolerated;
        evidence.add("context:unstable_" + first.kind.name() + "_to_" + second.kind.name());
        return new Result(Kind.UNKNOWN, 0d, evidence);
    }

    private static Result tolerateTransientUnknown(Result first, Result second,
                                                   List<String> evidence) {
        Result known;
        Result unknown;
        if (first.kind != Kind.UNKNOWN && second.kind == Kind.UNKNOWN
                && first.confidence >= MIN_STABLE_CONFIDENCE) {
            known = first;
            unknown = second;
        } else if (second.kind != Kind.UNKNOWN && first.kind == Kind.UNKNOWN
                && second.confidence >= MIN_STABLE_CONFIDENCE) {
            known = second;
            unknown = first;
        } else {
            return null;
        }
        // A blank paint of the same surface is fine. A peer that still shows the search-detail
        // back affordance (or home chrome while the known side is search) is a real conflict.
        if (known.kind == Kind.RECOMMENDATION_HOME
                && (hasEvidence(unknown, "search_detail:back_present")
                || hasEvidence(unknown, "search_detail:top_search"))) {
            return null;
        }
        if (known.kind == Kind.SEARCH_DETAIL
                && hasEvidence(unknown, "home:recommendation_tab_present")
                && hasEvidence(unknown, "home:bottom_navigation_present")) {
            return null;
        }
        evidence.add("context:tolerate_transient_unknown_peer");
        evidence.add("context:kept_" + known.kind.name().toLowerCase(Locale.ROOT));
        return new Result(known.kind, Math.max(0.80d, known.confidence * 0.92d), evidence);
    }

    private static boolean hasEvidence(Result result, String marker) {
        return result.evidence.contains(marker);
    }

    private enum Region { TOP, BOTTOM }

    private static UiNode findExact(UiSnapshot snapshot, Region region, String... labels) {
        if (snapshot.displayHeight <= 0) return null;
        for (UiNode node : snapshot.nodes) {
            // Some OEMs report the usable display height (excluding the navigation inset) while
            // TikTok reports its bottom navigation in physical-screen coordinates. Accept a
            // meaningfully clipped bottom anchor, but never an entirely off-screen retained node.
            if (region == Region.TOP ? !snapshot.isVisible(node)
                    : !intersectsDisplay(snapshot, node, 0.15f)) continue;
            int centerY = node.bounds.top + (node.bounds.bottom - node.bounds.top) / 2;
            boolean inRegion = region == Region.TOP
                    ? centerY <= snapshot.displayHeight * 0.20f
                    : centerY >= snapshot.displayHeight * 0.80f;
            if (!inRegion) continue;
            String text = normalize(node.text);
            String description = normalize(node.description);
            for (String label : labels) {
                String expected = normalize(label);
                if (expected.equals(text) || expected.equals(description)) return node;
            }
        }
        return null;
    }

    private static boolean intersectsDisplay(UiSnapshot snapshot, UiNode node,
                                             float minimumVisibleFraction) {
        if (!node.visibleToUser || snapshot.displayWidth <= 0 || snapshot.displayHeight <= 0) {
            return false;
        }
        int width = node.bounds.right - node.bounds.left;
        int height = node.bounds.bottom - node.bounds.top;
        if (width <= 0 || height <= 0) return false;
        int overlapWidth = Math.max(0, Math.min(node.bounds.right, snapshot.displayWidth)
                - Math.max(node.bounds.left, 0));
        int overlapHeight = Math.max(0, Math.min(node.bounds.bottom, snapshot.displayHeight)
                - Math.max(node.bounds.top, 0));
        long visibleArea = (long) overlapWidth * overlapHeight;
        long totalArea = (long) width * height;
        return visibleArea >= totalArea * minimumVisibleFraction;
    }

    private static int videoActionCount(UiSnapshot snapshot) {
        int count = 0;
        if (ControlResolver.resolve(snapshot, ControlResolver.Role.VIDEO_LIKE) != null) count++;
        if (ControlResolver.resolve(snapshot, ControlResolver.Role.VIDEO_COMMENTS) != null) count++;
        if (ControlResolver.resolve(snapshot, ControlResolver.Role.VIDEO_FAVORITE) != null) count++;
        if (ControlResolver.resolve(snapshot, ControlResolver.Role.VIDEO_SHARE) != null) count++;
        return count;
    }

    private static boolean sameWindow(UiNode first, UiNode second) {
        if (first == null || second == null) return false;
        return first.windowId < 0 || second.windowId < 0 || first.windowId == second.windowId;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private static Result result(Kind kind, double confidence, String... evidence) {
        List<String> values = new ArrayList<>();
        Collections.addAll(values, evidence);
        return new Result(kind, confidence, values);
    }

    private static List<String> union(List<String> first, List<String> second) {
        LinkedHashSet<String> values = new LinkedHashSet<>(first);
        values.addAll(second);
        return new ArrayList<>(values);
    }
}
