package com.xwell.rpatt.automation;

/**
 * Pure close-state planner. Execution stays in WarmupEngine; this class keeps recovery bounded and
 * independently testable across keyboards and OEM BACK behavior.
 */
public final class CommentCloseCoordinator {
    public enum OriginStatus {
        VERIFIED,
        WINDOW_GAP,
        WAITING
    }

    public enum Action {
        HIDE_INPUT_SURFACE,
        TAP_CLOSE,
        FALLBACK_BACK,
        VERIFY_ORIGIN,
        BLOCK
    }

    public static final class Attempts {
        public final boolean inputDismissed;
        public final boolean closeTapped;
        public final boolean fallbackBack;

        public Attempts(boolean inputDismissed, boolean closeTapped, boolean fallbackBack) {
            this.inputDismissed = inputDismissed;
            this.closeTapped = closeTapped;
            this.fallbackBack = fallbackBack;
        }
    }

    public static final class Decision {
        public final Action action;
        public final String reason;
        public final CommentSurfaceContract.Result comments;

        private Decision(Action action, String reason, CommentSurfaceContract.Result comments) {
            this.action = action;
            this.reason = reason;
            this.comments = comments;
        }
    }

    private CommentCloseCoordinator() {}

    public static Decision decide(UiSnapshot snapshot, Attempts attempts) {
        CommentSurfaceContract.Result comments = CommentSurfaceContract.analyze(snapshot);
        InputSurfaceDetector.Result input = comments.inputSurface;
        if (comments.closedForOriginVerification()) {
            return new Decision(Action.VERIFY_ORIGIN, "modal_and_input_absent", comments);
        }
        if (input.inputActive() && !attempts.inputDismissed) {
            return new Decision(Action.HIDE_INPUT_SURFACE,
                    input.hasInputMethodWindow()
                            ? "input_method_window_present" : "focused_editor_present",
                    comments);
        }
        if (comments.close != null && !attempts.closeTapped) {
            return new Decision(Action.TAP_CLOSE, "comment_close_contract_present", comments);
        }
        if (!attempts.fallbackBack) {
            return new Decision(Action.FALLBACK_BACK,
                    "comment_modal_remains_after_primary_close", comments);
        }
        return new Decision(Action.BLOCK, "bounded_close_recovery_exhausted", comments);
    }

    /**
     * Keep an accessibility observation gap distinct from a failed close contract. A caller may
     * perform one bounded focus refresh, but must never infer FEED from an empty tree.
     */
    public static OriginStatus classifyOrigin(PageState firstPage, PageState secondPage,
                                              boolean commentsClosed,
                                              boolean transitionAllowed,
                                              boolean expectedContext,
                                              boolean fingerprintStable) {
        if (commentsClosed && transitionAllowed && expectedContext && fingerprintStable) {
            return OriginStatus.VERIFIED;
        }
        if (PageStability.isEmptyWindowObservation(firstPage)
                && PageStability.isEmptyWindowObservation(secondPage)) {
            return OriginStatus.WINDOW_GAP;
        }
        return OriginStatus.WAITING;
    }
}
