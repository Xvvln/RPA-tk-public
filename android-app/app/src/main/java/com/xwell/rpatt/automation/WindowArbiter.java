package com.xwell.rpatt.automation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Selects the underlying application window without letting overlays impersonate foreground. */
public final class WindowArbiter {
    private WindowArbiter() {}

    /**
     * Stateful gestures are stricter than read-only page recognition. An active-but-unfocused
     * target is writable only when the input method explains the focus transfer; otherwise it may
     * be one frame of an external-app transition.
     */
    public static boolean allowsTargetInteraction(ForegroundDecision decision,
                                                  List<UiWindow> windows,
                                                  String targetPackage) {
        if (decision == null || targetPackage == null
                || !targetPackage.equals(decision.packageName)) return false;
        if (decision.source == ForegroundDecision.Source.TARGET_FOCUSED
                || decision.source == ForegroundDecision.Source.TARGET_UNDER_BENIGN_OVERLAY) {
            return true;
        }
        if (decision.source != ForegroundDecision.Source.TARGET_ACTIVE) return false;
        for (UiWindow window : windows == null
                ? java.util.Collections.<UiWindow>emptyList() : windows) {
            if (window.type == UiWindow.TYPE_INPUT_METHOD
                    && window.rootAvailable && (window.focused || window.active)) return true;
        }
        return false;
    }

    public static ForegroundDecision decide(List<UiWindow> windows, String targetPackage,
                                            String assistantPackage) {
        String assistant = assistantPackage == null ? "" : assistantPackage;
        List<UiWindow> ordered = new ArrayList<>(windows == null
                ? java.util.Collections.emptyList() : windows);
        ordered.sort(Comparator.comparingInt((UiWindow value) -> value.layer).reversed()
                .thenComparingInt(value -> value.id));

        UiWindow targetFocused = best(ordered, targetPackage, true, true);
        UiWindow externalFocused = bestExternal(ordered, targetPackage, assistant, true);
        UiWindow targetActive = best(ordered, targetPackage, false, true);
        UiWindow externalActive = bestExternal(ordered, targetPackage, assistant, false);
        UiWindow targetVisible = highestApplication(ordered, targetPackage);

        if (targetFocused != null) {
            return decision(targetPackage, ForegroundDecision.Source.TARGET_FOCUSED,
                    1d, true, "window:" + targetFocused.id + ":target_focused");
        }
        if (externalFocused != null) {
            return decision(externalFocused.packageName, ForegroundDecision.Source.EXTERNAL_FOCUSED,
                    1d, targetVisible != null,
                    "window:" + externalFocused.id + ":external_focused");
        }
        if (targetActive != null) {
            return decision(targetPackage, ForegroundDecision.Source.TARGET_ACTIVE,
                    0.97d, true, "window:" + targetActive.id + ":target_active");
        }
        if (externalActive != null) {
            return decision(externalActive.packageName, ForegroundDecision.Source.EXTERNAL_ACTIVE,
                    0.94d, targetVisible != null,
                    "window:" + externalActive.id + ":external_active");
        }
        if (targetVisible != null) {
            List<String> evidence = new ArrayList<>();
            evidence.add("window:" + targetVisible.id + ":target_application_visible");
            boolean onlyBenignOverlays = true;
            for (UiWindow window : ordered) {
                if (window.layer <= targetVisible.layer || isTarget(window, targetPackage)) continue;
                if (assistant.equals(window.packageName)) {
                    evidence.add("overlay:" + window.id + ":assistant_ignored");
                } else if (window.type == UiWindow.TYPE_INPUT_METHOD) {
                    evidence.add("overlay:" + window.id + ":ime_preserved");
                } else if (window.type == UiWindow.TYPE_SYSTEM
                        || window.type == UiWindow.TYPE_ACCESSIBILITY_OVERLAY) {
                    evidence.add("overlay:" + window.id + ":system_preserved");
                } else if (window.rootAvailable) {
                    onlyBenignOverlays = false;
                    evidence.add("window:" + window.id + ":unclassified_above_target");
                }
            }
            if (onlyBenignOverlays) {
                return new ForegroundDecision(targetPackage,
                        ForegroundDecision.Source.TARGET_UNDER_BENIGN_OVERLAY,
                        0.88d, true, evidence);
            }
            evidence.add("decision:unsafe_overlay_stack");
            return ForegroundDecision.unknown(evidence);
        }
        List<String> evidence = new ArrayList<>();
        evidence.add("windows:" + ordered.size());
        evidence.add("decision:no_application_candidate");
        return ForegroundDecision.unknown(evidence);
    }

    private static UiWindow best(List<UiWindow> windows, String packageName,
                                 boolean requireFocused, boolean requireSignal) {
        for (UiWindow window : windows) {
            if (!isApplication(window) || !packageName.equals(window.packageName)) continue;
            boolean signal = requireFocused ? window.focused : window.active;
            if (!requireSignal || signal) return window;
        }
        return null;
    }

    private static UiWindow bestExternal(List<UiWindow> windows, String targetPackage,
                                         String assistantPackage, boolean focused) {
        for (UiWindow window : windows) {
            if (!isApplication(window) || targetPackage.equals(window.packageName)
                    || assistantPackage.equals(window.packageName) || window.packageName.isEmpty()) continue;
            if (focused ? window.focused : window.active) return window;
        }
        return null;
    }

    private static UiWindow highestApplication(List<UiWindow> windows, String packageName) {
        for (UiWindow window : windows) {
            if (isApplication(window) && packageName.equals(window.packageName)
                    && window.rootAvailable) return window;
        }
        return null;
    }

    private static boolean isTarget(UiWindow window, String targetPackage) {
        return targetPackage.equals(window.packageName);
    }

    private static boolean isApplication(UiWindow window) {
        return window.type == UiWindow.TYPE_APPLICATION && window.rootAvailable;
    }

    private static ForegroundDecision decision(String packageName, ForegroundDecision.Source source,
                                               double confidence, boolean targetVisible,
                                               String evidence) {
        return new ForegroundDecision(packageName, source, confidence, targetVisible,
                java.util.Collections.singletonList(evidence));
    }
}
