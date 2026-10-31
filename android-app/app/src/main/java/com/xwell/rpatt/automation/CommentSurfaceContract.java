package com.xwell.rpatt.automation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Contract for TikTok's comments UI, including the editor-focused variant produced by any IME.
 */
public final class CommentSurfaceContract {
    public enum State {
        CLOSED,
        INLINE_COMPOSER,
        OPENING,
        PANEL_BROWSING,
        PANEL_EDITOR_FOCUSED,
        UNKNOWN
    }

    public static final class Result {
        public final State state;
        public final double confidence;
        public final UiNode header;
        public final UiNode list;
        public final UiNode composer;
        public final UiNode close;
        public final InputSurfaceDetector.Result inputSurface;
        public final List<String> evidence;

        private Result(State state, double confidence, UiNode header, UiNode list,
                       UiNode composer, UiNode close, InputSurfaceDetector.Result inputSurface,
                       List<String> evidence) {
            this.state = state;
            this.confidence = Math.max(0d, Math.min(1d, confidence));
            this.header = header;
            this.list = list;
            this.composer = composer;
            this.close = close;
            this.inputSurface = inputSurface;
            this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
        }

        /** Strong or transitional modal evidence must suppress FEED classification. */
        public boolean blocksFeedClassification() {
            return state == State.PANEL_BROWSING
                    || state == State.PANEL_EDITOR_FOCUSED
                    || state == State.OPENING
                    || state == State.UNKNOWN;
        }

        /** Inline feed composers are allowed; a focused editor or any comment modal is not. */
        public boolean closedForOriginVerification() {
            return !blocksFeedClassification() && !inputSurface.inputActive();
        }

        public boolean panelOpen() {
            return state == State.PANEL_BROWSING
                    || state == State.PANEL_EDITOR_FOCUSED
                    || state == State.OPENING;
        }

        public JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("state", state.name())
                    .put("confidence", confidence)
                    .put("header_present", header != null)
                    .put("list_present", list != null)
                    .put("composer_present", composer != null)
                    .put("close_present", close != null)
                    .put("input_surface", inputSurface.toJson())
                    .put("evidence", new JSONArray(evidence));
        }
    }

    private CommentSurfaceContract() {}

    public static Result analyze(UiSnapshot snapshot) {
        return analyze(snapshot, false);
    }

    public static Result analyze(UiSnapshot snapshot, boolean searchResultsVisible) {
        InputSurfaceDetector.Result input = InputSurfaceDetector.analyze(snapshot);
        ControlResolver.Match composerMatch = ControlResolver.resolve(
                snapshot, ControlResolver.Role.COMMENT_FIELD);
        UiNode composer = composerMatch == null ? null : composerMatch.node;
        UiNode header = findHeader(snapshot);
        UiNode list = findList(snapshot, header, composer);
        ControlResolver.Match closeMatch = ControlResolver.resolve(
                snapshot, ControlResolver.Role.COMMENT_CLOSE);
        UiNode close = closeMatch == null ? null : closeMatch.node;
        boolean empty = hasEmptyPanelText(snapshot, header, composer,
                "抢沙发", "喜欢这个作品", "还没有评论", "暂无评论", "成为第一个评论", "抢首评",
                "be the first to comment", "no comments yet", "first to comment");
        boolean headerComposerPanel = sharesPanelAncestor(header, composer);
        boolean closeInPanel = close == null || header == null
                || sharesPanelAncestor(header, close);
        boolean panelCore = !searchResultsVisible && header != null && composer != null
                && headerComposerPanel && closeInPanel
                && (list != null || close != null || empty);

        List<String> evidence = new ArrayList<>();
        if (header != null) evidence.add("comments:header");
        if (list != null) evidence.add("comments:list");
        if (composer != null) evidence.add("comments:composer");
        if (close != null) evidence.add("comments:close");
        if (empty) evidence.add("comments:empty_panel");
        if (headerComposerPanel) evidence.add("comments:shared_panel");
        evidence.addAll(input.evidence);

        if (searchResultsVisible && (header != null || composer != null || close != null)) {
            evidence.add("comments:rejected_by_search_results");
            return new Result(State.CLOSED, 0.92d, header, list, composer, close, input, evidence);
        }

        if (panelCore) {
            boolean editorFocused = input.isFocusedEditor(composer)
                    || input.hasInputMethodWindow();
            if (editorFocused) {
                evidence.add("comments:editor_focused");
                return new Result(State.PANEL_EDITOR_FOCUSED, 0.99d,
                        header, list, composer, close, input, evidence);
            }
            return new Result(State.PANEL_BROWSING, close != null ? 0.98d : 0.94d,
                    header, list, composer, close, input, evidence);
        }

        if (!searchResultsVisible && header != null && close != null
                && sharesPanelAncestor(header, close)) {
            evidence.add("comments:opening_or_reflow");
            return new Result(State.OPENING, 0.86d,
                    header, list, composer, close, input, evidence);
        }

        if (composer != null && header == null && close == null) {
            evidence.add("comments:inline_composer");
            return new Result(State.INLINE_COMPOSER, 0.82d,
                    null, null, composer, null, input, evidence);
        }

        if (header != null || close != null) {
            evidence.add("comments:ambiguous_modal_evidence");
            return new Result(State.UNKNOWN, 0d,
                    header, list, composer, close, input, evidence);
        }

        evidence.add("comments:modal_absent");
        return new Result(State.CLOSED, 1d,
                null, null, composer, null, input, evidence);
    }

    /**
     * Strong postcondition for a comment write: the draft is not enough. The exact text must be a
     * visible, non-editable row inside the open comments panel.
     */
    public static boolean postedCommentVisible(UiSnapshot snapshot, String expectedText) {
        String expected = normalizeText(expectedText);
        if (snapshot == null || expected.isEmpty()) return false;
        Result surface = analyze(snapshot);
        if (!surface.panelOpen() || surface.header == null || surface.composer == null) return false;
        for (UiNode node : snapshot.nodes) {
            if (!snapshot.isVisible(node)
                    || !sameWindow(node, surface.composer)
                    || node.editable
                    || "android.widget.EditText".equals(node.className)
                    || centerY(node) <= surface.header.bounds.bottom
                    || centerY(node) >= surface.composer.bounds.top) continue;
            if (expected.equals(normalizeText(node.text))) return true;
        }
        return false;
    }

    private static UiNode findHeader(UiSnapshot snapshot) {
        for (UiNode node : snapshot.nodes) {
            if (!snapshot.isVisible(node) || snapshot.displayHeight <= 0) continue;
            int centerY = centerY(node);
            boolean headerRegion = centerY > snapshot.displayHeight * 0.20f
                    && centerY < snapshot.displayHeight * 0.55f;
            boolean semantic = contains(node.searchableText(), "评论", "comment");
            boolean textShape = node.className.contains("Text");
            if (headerRegion && semantic && textShape) return node;
        }
        return null;
    }

    private static UiNode findList(UiSnapshot snapshot, UiNode header, UiNode composer) {
        if (header == null || composer == null || !sameWindow(header, composer)) return null;
        for (UiNode node : snapshot.nodes) {
            if (!snapshot.isVisible(node) || !sameWindow(node, composer)
                    || node.text.trim().length() < 2
                    || centerY(node) <= header.bounds.bottom
                    || centerY(node) >= composer.bounds.top) continue;
            boolean knownId = node.id.endsWith(":id/eu1") || node.id.endsWith(":id/euj");
            boolean textShape = (node.className.contains("Text") || node.className.startsWith("X."))
                    && node.bounds.right - node.bounds.left > snapshot.displayWidth * 0.25f;
            if (knownId || textShape) return node;
        }
        return null;
    }

    /** Nodes must belong to one accessibility window and share a non-root tree container. */
    private static boolean sharesPanelAncestor(UiNode... nodes) {
        if (nodes.length == 0 || nodes[0] == null || nodes[0].treePath.isEmpty()) return false;
        int windowId = nodes[0].windowId;
        String[] common = nodes[0].treePath.split("/");
        int commonLength = common.length;
        for (int index = 1; index < nodes.length; index++) {
            UiNode node = nodes[index];
            if (node == null || node.windowId != windowId || node.treePath.isEmpty()) return false;
            String[] path = node.treePath.split("/");
            commonLength = Math.min(commonLength, path.length);
            int part = 0;
            while (part < commonLength && common[part].equals(path[part])) part++;
            commonLength = part;
        }
        return commonLength >= 2;
    }

    private static boolean hasEmptyPanelText(UiSnapshot snapshot, UiNode header, UiNode composer,
                                             String... markers) {
        if (header == null || composer == null || !sameWindow(header, composer)) return false;
        for (UiNode node : snapshot.nodes) {
            if (!snapshot.isVisible(node) || !sameWindow(node, composer)
                    || centerY(node) <= header.bounds.bottom
                    || centerY(node) >= composer.bounds.top) continue;
            if (contains(node.searchableText(), markers)) return true;
        }
        return false;
    }

    private static boolean sameWindow(UiNode first, UiNode second) {
        return first != null && second != null
                && (first.windowId < 0 || second.windowId < 0 || first.windowId == second.windowId);
    }

    private static int centerY(UiNode node) {
        return node.bounds.top + (node.bounds.bottom - node.bounds.top) / 2;
    }

    private static boolean contains(String text, String... markers) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String marker : markers) {
            if (normalized.contains(marker.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }
}
