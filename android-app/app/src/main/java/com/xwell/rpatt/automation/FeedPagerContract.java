package com.xwell.rpatt.automation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the vertical item pager without relying on TikTok's reused resource id.
 *
 * <p>The recommendation surface can expose several full-screen nodes named {@code viewpager}:
 * home-tab pagers, the vertical feed pager and a photo carousel. The feed contract therefore
 * requires semantic scroll support, full-screen geometry and direct children laid out vertically.
 * Tree depth is only a tie-breaker after those independent signals agree.</p>
 */
public final class FeedPagerContract {
    private static final double MIN_USABLE_CONFIDENCE = 0.90d;

    public static final class Result {
        public final UiNode pager;
        public final double confidence;
        public final List<String> evidence;

        Result(UiNode pager, double confidence, List<String> evidence) {
            this.pager = pager;
            this.confidence = Math.max(0d, Math.min(1d, confidence));
            this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
        }

        public boolean usable() {
            return pager != null && confidence >= MIN_USABLE_CONFIDENCE;
        }

        public JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("usable", usable())
                    .put("confidence", confidence)
                    .put("evidence", new JSONArray(evidence))
                    .put("pager", pager == null ? JSONObject.NULL : pager.toJson());
        }
    }

    private static final class Candidate {
        final UiNode node;
        final int score;
        final int directChildren;
        final int verticalSpan;
        final int horizontalSpan;

        Candidate(UiNode node, int score, int directChildren,
                  int verticalSpan, int horizontalSpan) {
            this.node = node;
            this.score = score;
            this.directChildren = directChildren;
            this.verticalSpan = verticalSpan;
            this.horizontalSpan = horizontalSpan;
        }
    }

    private FeedPagerContract() {}

    public static Result analyze(UiSnapshot snapshot) {
        Candidate best = null;
        for (UiNode node : snapshot.nodes) {
            Candidate candidate = candidate(snapshot, node);
            if (candidate == null) continue;
            if (best == null || candidate.score > best.score
                    || candidate.score == best.score
                    && depth(candidate.node.treePath) > depth(best.node.treePath)) {
                best = candidate;
            }
        }

        if (best == null || best.score < 100) {
            List<String> missing = new ArrayList<>();
            missing.add("feed_pager:no_vertical_semantic_candidate");
            return new Result(null, 0d, missing);
        }

        List<String> evidence = new ArrayList<>();
        evidence.add("feed_pager:scrollable");
        evidence.add("feed_pager:scroll_forward");
        evidence.add("feed_pager:full_screen");
        evidence.add("feed_pager:direct_children=" + best.directChildren);
        evidence.add("feed_pager:vertical_span=" + best.verticalSpan);
        evidence.add("feed_pager:horizontal_span=" + best.horizontalSpan);
        evidence.add("feed_pager:vertical_direct_child_layout");
        evidence.add("feed_pager:depth=" + depth(best.node.treePath));
        return new Result(best.node, best.score >= 130 ? 0.99d : 0.94d, evidence);
    }

    private static Candidate candidate(UiSnapshot snapshot, UiNode node) {
        if (!PageAnalyzer.TIKTOK_PACKAGE.equals(node.packageName)
                || !node.scrollable || !node.canScrollForward
                || node.treePath.isEmpty() || !pagerLike(node)
                || !fullScreen(snapshot, node)) {
            return null;
        }

        List<UiNode> directChildren = directChildren(snapshot, node);
        if (directChildren.size() < 2) return null;
        int minimumTop = Integer.MAX_VALUE;
        int maximumTop = Integer.MIN_VALUE;
        int minimumLeft = Integer.MAX_VALUE;
        int maximumLeft = Integer.MIN_VALUE;
        for (UiNode child : directChildren) {
            minimumTop = Math.min(minimumTop, child.bounds.top);
            maximumTop = Math.max(maximumTop, child.bounds.top);
            minimumLeft = Math.min(minimumLeft, child.bounds.left);
            maximumLeft = Math.max(maximumLeft, child.bounds.left);
        }
        int verticalSpan = Math.max(0, maximumTop - minimumTop);
        int horizontalSpan = Math.max(0, maximumLeft - minimumLeft);
        int height = Math.max(1, node.bounds.bottom - node.bounds.top);
        int width = Math.max(1, node.bounds.right - node.bounds.left);
        boolean verticalLayout = verticalSpan >= height * 0.55f
                && verticalSpan >= horizontalSpan * 1.5f;
        boolean horizontalLayout = horizontalSpan >= width * 0.55f
                && horizontalSpan > verticalSpan;
        if (!verticalLayout || horizontalLayout) return null;

        int score = 100;
        if (node.id.endsWith(":id/viewpager")) score += 12;
        if (node.className.toLowerCase(Locale.ROOT).contains("viewpager")) score += 12;
        score += Math.min(18, depth(node.treePath));
        if (directChildren.size() >= 3) score += 8;
        return new Candidate(node, score, directChildren.size(), verticalSpan, horizontalSpan);
    }

    private static List<UiNode> directChildren(UiSnapshot snapshot, UiNode parent) {
        List<UiNode> result = new ArrayList<>();
        for (UiNode node : snapshot.nodes) {
            if (node.windowId == parent.windowId
                    && parent.treePath.equals(parentPath(node.treePath))) {
                result.add(node);
            }
        }
        return result;
    }

    private static boolean pagerLike(UiNode node) {
        String id = node.id.toLowerCase(Locale.ROOT);
        String className = node.className.toLowerCase(Locale.ROOT);
        return id.contains("pager") || className.contains("pager");
    }

    private static boolean fullScreen(UiSnapshot snapshot, UiNode node) {
        if (snapshot.displayWidth <= 0 || snapshot.displayHeight <= 0) return false;
        int overlapWidth = Math.max(0, Math.min(node.bounds.right, snapshot.displayWidth)
                - Math.max(node.bounds.left, 0));
        int overlapHeight = Math.max(0, Math.min(node.bounds.bottom, snapshot.displayHeight)
                - Math.max(node.bounds.top, 0));
        return overlapWidth >= snapshot.displayWidth * 0.85f
                && overlapHeight >= snapshot.displayHeight * 0.72f;
    }

    private static int depth(String path) {
        if (path == null || path.isEmpty()) return 0;
        int depth = 1;
        for (int index = 0; index < path.length(); index++) {
            if (path.charAt(index) == '/') depth++;
        }
        return depth;
    }

    private static String parentPath(String path) {
        int separator = path == null ? -1 : path.lastIndexOf('/');
        return separator <= 0 ? "" : path.substring(0, separator);
    }
}
