package com.xwell.rpatt.automation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Resolves semantic control roles from multiple independent UI signals. IDs are bonus evidence. */
public final class ControlResolver {
    public enum Role {
        SEARCH_FIELD, SEARCH_SUBMIT, COMMENT_FIELD, COMMENT_SEND,
        VIDEO_LIKE, VIDEO_COMMENTS, VIDEO_FAVORITE, VIDEO_SHARE, VIDEO_FOLLOW,
        COMMENT_CLOSE, NOT_INTERESTED, REPOST, REPOST_REMOVE
    }

    public static final class Match {
        public final UiNode node;
        public final double confidence;
        public final List<String> evidence;

        private Match(UiNode node, double confidence, List<String> evidence) {
            this.node = node;
            this.confidence = confidence;
            this.evidence = Collections.unmodifiableList(evidence);
        }
    }

    private ControlResolver() {}

    public static Match resolve(UiSnapshot snapshot, Role role) {
        return resolve(snapshot, role, null);
    }

    public static Match resolve(UiSnapshot snapshot, Role role, UiNode anchor) {
        if (role == Role.COMMENT_SEND) {
            UiNode send = snapshot.visibleCommentSendNear(anchor);
            return send == null ? null : match(send, 0.94d,
                    "visible", "clickable_button", "same_parent_as_composer", "right_of_composer");
        }
        Match best = null;
        Match second = null;
        for (UiNode node : snapshot.nodes) {
            Match candidate = score(snapshot, role, node, anchor);
            if (candidate == null) continue;
            // A semantic wrapper and one of its semantic descendants may resolve to the same
            // actionable node. That is corroborating evidence, not two ambiguous controls.
            if (best != null && candidate.node == best.node) {
                if (candidate.confidence > best.confidence) best = candidate;
                continue;
            }
            if (second != null && candidate.node == second.node) {
                if (candidate.confidence > second.confidence) second = candidate;
                continue;
            }
            if (best == null || candidate.confidence > best.confidence) {
                second = best;
                best = candidate;
            } else if (second == null || candidate.confidence > second.confidence) {
                second = candidate;
            }
        }
        if (best == null || best.confidence < 0.72d) return null;
        if (second != null && best.confidence - second.confidence < 0.08d) return null;
        return best;
    }

    /**
     * A browse action has no useful work when the right-rail entry explicitly says that the video
     * has no comments. Inspect the actionable wrapper and its semantic descendants because TikTok
     * may move the label away from the clickable node between releases.
     */
    public static boolean isDefinitelyEmptyCommentEntry(UiSnapshot snapshot, UiNode entry) {
        if (snapshot == null || entry == null) return false;
        for (UiNode node : snapshot.nodes) {
            if (!PageAnalyzer.TIKTOK_PACKAGE.equals(node.packageName)
                    || !snapshot.isVisible(node)
                    || node.windowId != entry.windowId) continue;
            boolean sameNode = node == entry;
            boolean descendant = !entry.treePath.isEmpty()
                    && node.treePath.startsWith(entry.treePath + "/");
            boolean insideEntry = containsPoint(entry, centerX(node), centerY(node));
            if ((sameNode || descendant || insideEntry)
                    && hasEmptyCommentSemantics(node.searchableText())) return true;
        }
        return hasEmptyCommentSemantics(entry.searchableText());
    }

    private static boolean hasEmptyCommentSemantics(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").trim();
        return contains(normalized, "抢首评", "抢沙发", "暂无评论", "还没有评论",
                "成为第一个评论", "be the first to comment", "no comments yet",
                "first to comment")
                || normalized.matches(".*(?:^|\\D)0\\s*条?\\s*评论(?:\\D|$).*")
                || normalized.matches(".*\\b0\\s+comments?\\b.*");
    }

    private static Match score(UiSnapshot snapshot, Role role, UiNode node, UiNode anchor) {
        if (!PageAnalyzer.TIKTOK_PACKAGE.equals(node.packageName) || !snapshot.isVisible(node)) return null;
        int width = snapshot.displayWidth;
        int height = snapshot.displayHeight;
        if (width <= 0 || height <= 0) return null;
        int centerX = centerX(node);
        int centerY = centerY(node);
        String semantic = (node.text + " " + node.description).toLowerCase(Locale.ROOT);
        List<String> evidence = new ArrayList<>();
        evidence.add("visible");

        switch (role) {
            case SEARCH_FIELD:
                if (!"android.widget.EditText".equals(node.className)
                        || centerY >= height * 0.22f
                        || node.bounds.right - node.bounds.left < width * 0.35f) return null;
                evidence.add("edit_text");
                evidence.add("upper_screen");
                return match(node, 0.80d + (known(node.id, PageAnalyzer.SEARCH_FIELD_IDS) ? 0.12d : 0d),
                        evidence, known(node.id, PageAnalyzer.SEARCH_FIELD_IDS) ? "known_id_bonus" : "geometry_only");
            case COMMENT_FIELD:
                if (!InputSurfaceDetector.isEditable(node)
                        || node.bounds.right - node.bounds.left < width * 0.30f) return null;
                InputSurfaceDetector.Result inputSurface = InputSurfaceDetector.analyze(snapshot);
                float relativeCenterY = inputSurface.normalizedCenterY(node);
                if (relativeCenterY < 0.68f) return null;
                evidence.add("edit_text");
                evidence.add("lower_usable_viewport");
                if (inputSurface.hasInputMethodWindow()) evidence.add("input_method_aware");
                return match(node, 0.82d + (known(node.id, PageAnalyzer.COMMENT_FIELD_IDS) ? 0.12d : 0d),
                        evidence, known(node.id, PageAnalyzer.COMMENT_FIELD_IDS) ? "known_id_bonus" : "geometry_only");
            case SEARCH_SUBMIT:
                if (anchor == null || !node.clickable
                        || node.bounds.left < anchor.bounds.right - 8
                        || verticalOverlap(anchor, node) < Math.min(nodeHeight(anchor), nodeHeight(node)) * 0.50f
                        || !sharesLocalContainer(anchor, node)) return null;
                boolean searchSemantic = contains(semantic, "搜索", "search");
                boolean searchId = PageAnalyzer.SEARCH_SUBMIT_ID.equals(node.id);
                if (!searchSemantic && !searchId) return null;
                evidence.add("clickable");
                evidence.add("outside_right_edge_of_search_field");
                evidence.add("vertical_toolbar_overlap");
                evidence.add("shared_local_toolbar_container");
                if (searchSemantic) evidence.add("search_semantic");
                if (searchId) evidence.add("known_id_bonus");
                return match(node, 0.78d + (searchSemantic ? 0.14d : 0d) + (searchId ? 0.06d : 0d), evidence);
            case VIDEO_LIKE:
                return videoAction(snapshot, node, semantic, evidence,
                        new String[] {"点赞视频", "点赞的视频", "like video", "liked video"});
            case VIDEO_COMMENTS:
                return videoAction(snapshot, node, semantic, evidence,
                        new String[] {"阅读或添加评论", "read or add comments"});
            case VIDEO_FAVORITE:
                return videoAction(snapshot, node, semantic, evidence,
                        new String[] {"添加到或移出收藏", "移出收藏", "favorite"});
            case VIDEO_SHARE:
                return videoAction(snapshot, node, semantic, evidence,
                        new String[] {"分享视频", "share video"});
            case VIDEO_FOLLOW:
                return videoAction(snapshot, node, semantic, evidence,
                        new String[] {"关注 ", "follow "});
            case COMMENT_CLOSE:
                if (!node.clickable || !contains(semantic, "关闭", "close")
                        || centerY < height * 0.18f || centerY > height * 0.70f) return null;
                evidence.add("close_semantic");
                evidence.add("comment_header_region");
                return match(node, 0.91d, evidence);
            case NOT_INTERESTED:
                return sheetAction(snapshot, node, semantic, evidence,
                        new String[] {"不感兴趣", "not interested"});
            case REPOST:
                if (contains(semantic, "取消转发", "删除转发", "撤销转发",
                        "remove repost", "undo repost")) return null;
                return sheetAction(snapshot, node, semantic, evidence,
                        new String[] {"转发", "repost"});
            case REPOST_REMOVE:
                return sheetAction(snapshot, node, semantic, evidence,
                        new String[] {"取消转发", "删除转发", "撤销转发",
                                "remove repost", "undo repost"});
            default:
                return null;
        }
    }

    private static Match videoAction(UiSnapshot snapshot, UiNode node, String semantic,
                                     List<String> evidence, String[] markers) {
        if (centerX(node) < snapshot.displayWidth * 0.72f
                || centerY(node) < snapshot.displayHeight * 0.25f
                || centerY(node) > snapshot.displayHeight * 0.92f
                || !contains(semantic, markers)) return null;
        UiNode actionable = nearestClickableAncestor(snapshot, node);
        if (actionable == null) return null;
        evidence.add("right_action_rail");
        evidence.add("semantic_role");
        if (actionable == node) {
            evidence.add("semantic_actionable_node");
        } else {
            evidence.add("semantic_descendant");
            evidence.add("clickable_ancestor");
        }
        return match(actionable, 0.94d, evidence);
    }

    private static Match sheetAction(UiSnapshot snapshot, UiNode node, String semantic,
                                     List<String> evidence, String[] markers) {
        if (centerY(node) < snapshot.displayHeight * 0.35f
                || centerY(node) > snapshot.displayHeight * 0.96f
                || !contains(semantic, markers)) return null;
        UiNode actionable = nearestClickableAncestor(snapshot, node);
        if (actionable == null) return null;
        if (actionable.bounds.right - actionable.bounds.left > snapshot.displayWidth * 0.55f
                && actionable.bounds.bottom - actionable.bounds.top
                > snapshot.displayHeight * 0.20f) return null;
        evidence.add("action_sheet_region");
        evidence.add("semantic_role");
        evidence.add(actionable == node ? "semantic_actionable_node" : "clickable_ancestor");
        return match(actionable, 0.92d, evidence);
    }

    /**
     * Accessibility trees often put the role description on an icon/button leaf while click
     * handling belongs to a surrounding layout. Keep those responsibilities separate: the leaf
     * proves what the control means, and the nearest visible containing ancestor is the action
     * target. Geometry and tree ancestry prevent an unrelated clickable node from being lifted.
     */
    private static UiNode nearestClickableAncestor(UiSnapshot snapshot, UiNode semanticNode) {
        if (semanticNode.clickable) return semanticNode;
        if (semanticNode.treePath.isEmpty()) return null;
        UiNode nearest = null;
        int nearestDepth = -1;
        for (UiNode candidate : snapshot.nodes) {
            if (!candidate.clickable || !snapshot.isVisible(candidate)
                    || candidate.windowId != semanticNode.windowId
                    || !semanticNode.packageName.equals(candidate.packageName)
                    || candidate.treePath.isEmpty()
                    || !semanticNode.treePath.startsWith(candidate.treePath + "/")
                    || !containsPoint(candidate, centerX(semanticNode), centerY(semanticNode))) {
                continue;
            }
            int depth = pathDepth(candidate.treePath);
            if (depth > nearestDepth) {
                nearest = candidate;
                nearestDepth = depth;
            }
        }
        return nearest;
    }

    private static Match match(UiNode node, double score, String... values) {
        List<String> evidence = new ArrayList<>();
        Collections.addAll(evidence, values);
        return new Match(node, Math.min(1d, score), evidence);
    }

    private static Match match(UiNode node, double score, List<String> base, String... values) {
        List<String> evidence = new ArrayList<>(base);
        Collections.addAll(evidence, values);
        return new Match(node, Math.min(1d, score), evidence);
    }

    private static boolean known(String id, String... ids) {
        for (String value : ids) if (value.equals(id)) return true;
        return false;
    }

    private static boolean contains(String value, String... markers) {
        for (String marker : markers) if (value.contains(marker.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static int centerX(UiNode node) {
        return node.bounds.left + (node.bounds.right - node.bounds.left) / 2;
    }

    private static int centerY(UiNode node) {
        return node.bounds.top + (node.bounds.bottom - node.bounds.top) / 2;
    }

    private static int nodeHeight(UiNode node) {
        return node.bounds.bottom - node.bounds.top;
    }

    private static boolean containsPoint(UiNode node, int x, int y) {
        return x >= node.bounds.left && x < node.bounds.right
                && y >= node.bounds.top && y < node.bounds.bottom;
    }

    private static int pathDepth(String path) {
        if (path.isEmpty()) return 0;
        int depth = 1;
        for (int index = 0; index < path.length(); index++) {
            if (path.charAt(index) == '/') depth++;
        }
        return depth;
    }

    private static int verticalOverlap(UiNode first, UiNode second) {
        return Math.max(0, Math.min(first.bounds.bottom, second.bounds.bottom)
                - Math.max(first.bounds.top, second.bounds.top));
    }

    /** Controls in one toolbar may differ by at most their final wrapper and leaf segments. */
    private static boolean sharesLocalContainer(UiNode first, UiNode second) {
        if (first.windowId != second.windowId || first.treePath.isEmpty() || second.treePath.isEmpty()) {
            return false;
        }
        String[] firstParts = first.treePath.split("/");
        String[] secondParts = second.treePath.split("/");
        int limit = Math.min(firstParts.length, secondParts.length);
        int common = 0;
        while (common < limit && firstParts[common].equals(secondParts[common])) common++;
        return common >= limit - 2;
    }
}
