package com.xwell.rpatt.automation;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class AccessibilityGateway {
    private final WarmupAccessibilityService service;

    private AccessibilityGateway(WarmupAccessibilityService service) { this.service = service; }

    public static AccessibilityGateway require() {
        WarmupAccessibilityService service = WarmupAccessibilityService.active();
        if (service == null) throw new IllegalStateException("无障碍服务未开启");
        return new AccessibilityGateway(service);
    }

    public boolean overlayAllowed() { return Settings.canDrawOverlays(service); }

    public boolean screenInteractive() {
        PowerManager manager = (PowerManager) service.getSystemService(Context.POWER_SERVICE);
        return manager.isInteractive();
    }

    public boolean deviceLocked() {
        KeyguardManager manager = (KeyguardManager) service.getSystemService(Context.KEYGUARD_SERVICE);
        return manager.isKeyguardLocked();
    }

    public boolean networkAvailable() {
        ConnectivityManager manager = (ConnectivityManager) service.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = manager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    public boolean launchTikTok() {
        Intent intent = service.getPackageManager().getLaunchIntentForPackage(PageAnalyzer.TIKTOK_PACKAGE);
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        service.startActivity(intent);
        return true;
    }

    public UiSnapshot snapshot() {
        // OEM accessibility bridges sometimes return an empty window list for one or two frames
        // after pause / notification shade / activity resume. Retry before treating as unknown.
        UiSnapshot last = captureSnapshotOnce();
        for (int attempt = 0; attempt < 2 && isEmptyObservation(last); attempt++) {
            SystemClock.sleep(120 + attempt * 80L);
            last = captureSnapshotOnce();
        }
        return last;
    }

    private UiSnapshot captureSnapshotOnce() {
        List<UiNode> nodes = new ArrayList<>();
        List<UiWindow> windows = new ArrayList<>();
        List<AccessibilityWindowInfo> availableWindows = service.getWindows();
        if (availableWindows != null) {
            for (AccessibilityWindowInfo window : availableWindows) {
                AccessibilityNodeInfo root = window.getRoot();
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                String packageName = root == null ? "" : value(root.getPackageName());
                windows.add(new UiWindow(window.getId(), packageName, window.getType(),
                        window.getLayer(), window.isActive(), window.isFocused(),
                        window.isAccessibilityFocused(), root != null, bounds));
                if (root != null) {
                    traverse(root, nodes, window.getId(), "0");
                    root.recycle();
                }
            }
        }
        if (nodes.isEmpty()) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null) {
                String packageName = value(root.getPackageName());
                traverse(root, nodes, -1, "0");
                Rect bounds = new Rect(0, 0, displayWidth(), displayHeight());
                windows.add(new UiWindow(-1, packageName, UiWindow.TYPE_APPLICATION,
                        0, true, true, true, true, bounds));
                root.recycle();
            }
        }
        ForegroundDecision decision = WindowArbiter.decide(windows,
                PageAnalyzer.TIKTOK_PACKAGE, service.getPackageName());
        return new UiSnapshot(System.currentTimeMillis(), decision.packageName,
                displayWidth(), displayHeight(), nodes, windows, decision);
    }

    private static boolean isEmptyObservation(UiSnapshot snapshot) {
        return snapshot == null
                || snapshot.nodes == null
                || snapshot.nodes.isEmpty()
                || snapshot.windows == null
                || snapshot.windows.isEmpty();
    }

    /** Write only to a control that has already passed a multi-signal role contract. */
    public boolean setTextOnResolvedInput(UiNode resolved, String text) {
        if (!targetInteractionAllowed()
                || resolved == null
                || !"android.widget.EditText".equals(resolved.className)) return false;
        UiNode current = visibleEditTextNear(resolved);
        if (current == null || !tapCenter(current.bounds)) return false;
        SystemClock.sleep(180);
        boolean acted = false;
        if (!current.id.isEmpty()) {
            performDirectOnIdNear(current.id, current,
                    AccessibilityNodeInfo.ACTION_FOCUS, null);
            Bundle arguments = new Bundle();
            arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            acted = performDirectOnIdNear(current.id, current,
                    AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        }
        if (!acted) {
            AccessibilityNodeInfo root = findTikTokRoot();
            if (root != null) {
                Bundle arguments = new Bundle();
                arguments.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                try {
                    acted = setTextOnVisibleEditText(root, arguments);
                } finally {
                    root.recycle();
                }
            }
        }
        if (!acted) return false;

        // ACTION_SET_TEXT is performed at most once. During input-method animation, verify the
        // original node identity first; a newly visible blank clone is not evidence of failure.
        for (int sample = 0; sample < 8; sample++) {
            SystemClock.sleep(sample == 0 ? 220 : 120);
            UiSnapshot readback = snapshot();
            UiNode exact = readback.inputByIdentity(current);
            if (inputTextMatches(exact, text)) return true;
            UiNode visible = visibleEditTextNear(current);
            if (inputTextMatches(visible, text)) return true;
        }
        return false;
    }

    /** Hide only an actual input-method window; never use Back blindly on the comments panel. */
    public boolean dismissInputMethodIfPresent() {
        boolean inputMethodPresent = false;
        for (AccessibilityWindowInfo window : service.getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                inputMethodPresent = true;
                break;
            }
        }
        if (!inputMethodPresent) return true;
        boolean dismissed = pressBack();
        if (dismissed) SystemClock.sleep(450);
        return dismissed;
    }

    public boolean clickText(String... labels) {
        if (!targetInteractionAllowed()) return false;
        AccessibilityNodeInfo root = findTikTokRoot();
        if (root == null) return false;
        try {
            return clickMatching(root, labels, false);
        } finally {
            root.recycle();
        }
    }

    /** Tap an exact visible text label with a gesture, avoiding false ACTION_CLICK success. */
    public boolean tapText(String... labels) {
        UiSnapshot snapshot = snapshot();
        for (UiNode node : snapshot.nodes) {
            if (!PageAnalyzer.TIKTOK_PACKAGE.equals(node.packageName)
                    || !snapshot.isVisible(node)) continue;
            for (String label : labels) {
                if (label.equalsIgnoreCase(node.text) && tapCenter(node.bounds)) return true;
            }
        }
        return false;
    }

    /**
     * Deny a system GrantPermissionsActivity (or equivalent) without requiring TikTok focus.
     * Only taps deny-family labels; never Allow/允许.
     */
    public boolean denySystemPermissionDialog(String... denyLabels) {
        String[] labels = InterruptPolicy.filterSoftLabels(
                denyLabels == null || denyLabels.length == 0
                        ? InterruptPolicy.SYSTEM_PERMISSION_DENY_LABELS
                        : denyLabels);
        if (labels.length == 0) return false;
        if (clickTextAnyWindow(labels)) return true;
        if (tapDenyLabelAnyPackage(labels)) return true;
        // Last resort: BACK often dismisses as deny on some OEMs; never taps Allow.
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
    }

    /** ACTION_CLICK across every window root (permission controller is not TikTok). */
    private boolean clickTextAnyWindow(String... labels) {
        List<AccessibilityWindowInfo> windows = service.getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    if (clickMatching(root, labels, false)) return true;
                } finally {
                    root.recycle();
                }
            }
        }
        AccessibilityNodeInfo active = service.getRootInActiveWindow();
        if (active == null) return false;
        try {
            return clickMatching(active, labels, false);
        } finally {
            active.recycle();
        }
    }

    /**
     * Gesture-tap deny labels in any package. Uses a bypass path because system permission
     * sheets steal focus from TikTok and {@link #targetInteractionAllowed()} would block.
     */
    private boolean tapDenyLabelAnyPackage(String... labels) {
        UiSnapshot snapshot = snapshot();
        for (UiNode node : snapshot.nodes) {
            if (!snapshot.isVisible(node)) continue;
            String text = node.text == null ? "" : node.text.trim();
            String desc = node.description == null ? "" : node.description.trim();
            for (String label : labels) {
                if (label == null) continue;
                if (label.equalsIgnoreCase(text) || label.equalsIgnoreCase(desc)) {
                    if (InterruptPolicy.isPrimaryAcceptLabel(text)
                            || InterruptPolicy.isPrimaryAcceptLabel(desc)) {
                        continue;
                    }
                    if (gestureBypassTargetGate(node.bounds, 100)) return true;
                }
            }
        }
        return false;
    }

    /** dispatchGesture without requiring TikTok foreground (system dialogs only). */
    private boolean gestureBypassTargetGate(android.graphics.Rect bounds, long durationMs) {
        if (!centerInsideDisplay(bounds)) return false;
        Path path = new Path();
        path.moveTo(bounds.exactCenterX(), bounds.exactCenterY());
        GestureDescription description = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(80, durationMs)))
                .build();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);
        boolean dispatched = service.dispatchGesture(description,
                new AccessibilityService.GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription gestureDescription) {
                        completed.set(true);
                        latch.countDown();
                    }

                    @Override public void onCancelled(GestureDescription gestureDescription) {
                        latch.countDown();
                    }
                }, null);
        if (!dispatched) return false;
        try {
            latch.await(durationMs + 3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        return completed.get();
    }

    /** Returns a visible action control in TikTok's right-side action rail. */
    public UiNode visibleRightAction(String... descriptionMarkers) {
        return visibleRightActionNear(null, descriptionMarkers);
    }

    /** Return the semantic right-rail action nearest to the control captured before a write. */
    public UiNode visibleRightActionNear(UiNode anchor, String... descriptionMarkers) {
        int width = displayWidth();
        int height = displayHeight();
        UiNode nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (UiNode node : snapshot().nodes) {
            if (!node.visibleToUser || !centerInsideDisplay(node.bounds)
                    || node.bounds.centerX() < width * 0.72f
                    || node.bounds.centerY() < height * 0.25f
                    || node.bounds.centerY() > height * 0.92f) continue;
            String description = node.description.toLowerCase(Locale.ROOT);
            for (String marker : descriptionMarkers) {
                if (!description.contains(marker.toLowerCase(Locale.ROOT))) continue;
                if (anchor == null) return node;
                long deltaX = (long) node.bounds.centerX() - anchor.bounds.centerX();
                long deltaY = (long) node.bounds.centerY() - anchor.bounds.centerY();
                long distance = deltaX * deltaX + deltaY * deltaY;
                if (distance < nearestDistance) {
                    nearest = node;
                    nearestDistance = distance;
                }
                break;
            }
        }
        if (anchor == null || nearest == null) return nearest;
        int tolerance = Math.max(240, Math.max(anchor.bounds.width(), anchor.bounds.height()) * 2);
        return nearestDistance <= (long) tolerance * tolerance ? nearest : null;
    }

    /** Execute a resolved control by exact accessibility identity, then use visible geometry. */
    public boolean tapNode(UiNode node) {
        if (!targetInteractionAllowed() || node == null) return false;
        if (performOnIdentity(node, AccessibilityNodeInfo.ACTION_CLICK, null)) return true;
        return tapVisibleIntersection(node.bounds);
    }

    /**
     * TikTok custom controls can acknowledge ACTION_CLICK without changing UI state. Re-resolve
     * the semantic role, bind it to the captured on-screen target, and dispatch one real gesture.
     */
    public boolean tapResolvedGesture(ControlResolver.Role role, UiNode captured) {
        return tapResolvedGesture(role, captured, null, null);
    }

    /** Rebind both an anchored control and its anchor from the same fresh snapshot. */
    public boolean tapResolvedGesture(ControlResolver.Role role, UiNode captured,
                                      ControlResolver.Role anchorRole, UiNode capturedAnchor) {
        if (role == null || captured == null) return false;
        UiSnapshot current = snapshot();
        UiNode currentAnchor = null;
        if (anchorRole != null || capturedAnchor != null) {
            if (anchorRole == null || capturedAnchor == null) return false;
            ControlResolver.Match anchorMatch = ControlResolver.resolve(current, anchorRole);
            currentAnchor = anchorMatch == null ? null : anchorMatch.node;
            if (!GestureTargetContract.sameVisibleTarget(
                    current, capturedAnchor, currentAnchor)) return false;
        }
        ControlResolver.Match match = ControlResolver.resolve(current, role, currentAnchor);
        UiNode target = match == null ? null : match.node;
        if (!GestureTargetContract.sameVisibleTarget(current, captured, target)) return false;
        return tapVisibleIntersection(target.bounds);
    }

    public UiNode visibleCommentSend(UiNode input) {
        return snapshot().visibleCommentSendNear(input);
    }

    public boolean hasTextOrDescription(String... markers) {
        UiSnapshot current = snapshot();
        for (UiNode node : current.nodes) {
            if (!PageAnalyzer.TIKTOK_PACKAGE.equals(node.packageName)
                    || !current.isVisible(node)) continue;
            String candidate = (node.text + " " + node.description).toLowerCase(Locale.ROOT);
            for (String marker : markers) {
                if (candidate.contains(marker.toLowerCase(Locale.ROOT))) return true;
            }
        }
        return false;
    }

    public boolean hasVisibleExactText(String... labels) {
        UiSnapshot current = snapshot();
        for (UiNode node : current.nodes) {
            if (!PageAnalyzer.TIKTOK_PACKAGE.equals(node.packageName)
                    || !current.isVisible(node)) continue;
            for (String label : labels) {
                if (label.equalsIgnoreCase(node.text)) return true;
            }
        }
        return false;
    }

    public boolean clickDescriptionContains(String... labels) {
        if (!targetInteractionAllowed()) return false;
        AccessibilityNodeInfo root = findTikTokRoot();
        if (root == null) return false;
        try {
            return clickMatching(root, labels, true);
        } finally {
            root.recycle();
        }
    }

    public boolean pressBack() {
        return targetInteractionAllowed()
                && service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
    }

    /**
     * System BACK without requiring TikTok foreground. Used for anomaly recovery when the
     * focused window is a sheet, soft-ask, or unclassified page.
     */
    public boolean pressBackAlways() {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
    }

    public boolean openFirstSearchVideo() {
        // TikTok has used several short IDs for search-result cards. Accept only a visible,
        // half-width grid card so a retained off-screen node or toolbar action cannot be tapped.
        UiSnapshot current = snapshot();
        UiNode best = null;
        for (UiNode node : current.nodes) {
            if (!PageAnalyzer.TIKTOK_PACKAGE.equals(node.packageName)
                    || !node.clickable || !current.isVisible(node)
                    || !isKnownSearchCardId(node.id)
                    || !isVisibleSearchCard(node.bounds)) continue;
            // Prefer the semantic card exposed by current builds over an empty retained container.
            if (best == null || (!node.description.isEmpty() && best.description.isEmpty())
                    || (node.description.isEmpty() == best.description.isEmpty()
                    && (node.bounds.top < best.bounds.top
                    || (node.bounds.top == best.bounds.top && node.bounds.left < best.bounds.left)))) {
                best = node;
            }
        }
        if (best != null && tapSearchCard(best.bounds)) return true;

        AccessibilityNodeInfo root = findTikTokRoot();
        if (root == null) return false;
        try {
            for (String id : PageAnalyzer.SEARCH_RESULT_CARD_IDS) {
                List<AccessibilityNodeInfo> candidates = root.findAccessibilityNodeInfosByViewId(id);
                for (AccessibilityNodeInfo candidate : candidates) {
                    Rect bounds = new Rect();
                    candidate.getBoundsInScreen(bounds);
                    // TikTok currently reports ACTION_CLICK success for these cards without
                    // navigating. A real accessibility gesture at the verified card center is
                    // required, and the engine verifies the resulting page before continuing.
                    if (isVisibleSearchCard(candidate, bounds) && tapSearchCard(bounds)) {
                        recycleAll(candidates);
                        return true;
                    }
                }
                recycleAll(candidates);
            }
            List<AccessibilityNodeInfo> grids = root.findAccessibilityNodeInfosByViewId(
                    PageAnalyzer.TIKTOK_PACKAGE + ":id/mui");
            for (AccessibilityNodeInfo grid : grids) {
                if (clickFirstVisibleSearchCard(grid)) {
                    recycleAll(grids);
                    return true;
                }
            }
            recycleAll(grids);
            return false;
        } finally {
            root.recycle();
        }
    }

    private boolean isVisibleSearchCard(AccessibilityNodeInfo node, Rect bounds) {
        return node.isVisibleToUser() && node.isClickable() && isVisibleSearchCard(bounds);
    }

    private boolean isVisibleSearchCard(Rect bounds) {
        int width = displayWidth();
        int height = displayHeight();
        return centerInsideDisplay(bounds)
                && bounds.top > height * 0.15f
                && bounds.width() > width * 0.30f
                && bounds.width() < width * 0.62f
                && bounds.height() > height * 0.12f;
    }

    private boolean isKnownSearchCardId(String id) {
        for (String candidate : PageAnalyzer.SEARCH_RESULT_CARD_IDS) {
            if (candidate.equals(id)) return true;
        }
        return false;
    }

    private boolean clickFirstVisibleSearchCard(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (isVisibleSearchCard(node, bounds)) {
            return tapSearchCard(bounds);
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try {
                if (clickFirstVisibleSearchCard(child)) return true;
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    public boolean expandOneReplyThread() {
        AccessibilityNodeInfo root = findTikTokRoot();
        if (root == null) return false;
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(PageAnalyzer.TIKTOK_PACKAGE + ":id/tv_reply_comment_title");
            for (AccessibilityNodeInfo node : nodes) {
                String text = value(node.getText()).toLowerCase(Locale.ROOT);
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (node.isVisibleToUser() && centerInsideDisplay(bounds)
                        && (text.startsWith("查看") || text.startsWith("view")
                        || text.startsWith("voir") || text.startsWith("ver"))
                        && clickNode(node)) {
                    recycleAll(nodes);
                    return true;
                }
            }
            recycleAll(nodes);
            return false;
        } finally {
            root.recycle();
        }
    }

    public boolean swipeUp(long durationMs, float horizontalOffset) {
        return swipeUp(durationMs,
                0.50f + horizontalOffset, 0.48f - horizontalOffset / 2f,
                0.79f, 0.23f);
    }

    public boolean swipeUp(long durationMs, float startXFraction, float endXFraction,
                           float startYFraction, float endYFraction) {
        int width = displayWidth();
        int height = displayHeight();
        float x1 = width * startXFraction;
        float x2 = width * endXFraction;
        float y1 = height * startYFraction;
        float y2 = height * endYFraction;
        Path path = new Path();
        path.moveTo(x1, y1);
        path.quadTo((x1 + x2) / 2f, (y1 + y2) / 2f, x2, y2);
        return gesture(path, durationMs);
    }

    public boolean longPressCenter(long durationMs) {
        int width = displayWidth();
        int height = displayHeight();
        Path path = new Path();
        path.moveTo(width * 0.50f, height * 0.48f);
        return gesture(path, durationMs);
    }

    public boolean saveScreenshot(File target) {
        Bitmap bitmap = screenshotBitmap();
        if (bitmap == null) return false;
        try (FileOutputStream stream = new FileOutputStream(target)) {
            return bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        } catch (IOException ignored) {
            return false;
        } finally {
            bitmap.recycle();
        }
    }

    public FavoriteVisualEvidence.Profile favoriteVisualState(UiNode node) {
        Bitmap bitmap = screenshotBitmap();
        if (bitmap == null) return FavoriteVisualEvidence.unavailable();
        try {
            int centerX = Math.max(0, Math.min(bitmap.getWidth() - 1, node.bounds.centerX()));
            int centerY = Math.max(0, Math.min(bitmap.getHeight() - 1, node.bounds.centerY()));
            int radius = Math.max(8, Math.min(node.bounds.width(), node.bounds.height()) / 8);
            int left = Math.max(0, centerX - radius);
            int top = Math.max(0, centerY - radius);
            int right = Math.min(bitmap.getWidth() - 1, centerX + radius);
            int bottom = Math.min(bitmap.getHeight() - 1, centerY + radius);
            int[] pixels = new int[(right - left + 1) * (bottom - top + 1)];
            int index = 0;
            for (int x = Math.max(0, centerX - radius); x <= Math.min(bitmap.getWidth() - 1, centerX + radius); x++) {
                for (int y = Math.max(0, centerY - radius); y <= Math.min(bitmap.getHeight() - 1, centerY + radius); y++) {
                    pixels[index++] = bitmap.getPixel(x, y);
                }
            }
            return FavoriteVisualEvidence.analyze(pixels);
        } finally {
            bitmap.recycle();
        }
    }

    public boolean isFavoriteSelected(UiNode node) {
        return favoriteVisualState(node).selected();
    }

    private Bitmap screenshotBitmap() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bitmap> captured = new AtomicReference<>();
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
            @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                if (hardware != null) {
                    captured.set(hardware.copy(Bitmap.Config.ARGB_8888, false));
                    hardware.recycle();
                }
                buffer.close();
                latch.countDown();
            }

            @Override public void onFailure(int errorCode) { latch.countDown(); }
        });
        try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        return captured.get();
    }

    private boolean gesture(Path path, long durationMs) {
        if (!targetInteractionAllowed()) return false;
        GestureDescription description = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(80, durationMs)))
                .build();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);
        boolean dispatched = service.dispatchGesture(description, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                completed.set(true);
                latch.countDown();
            }

            @Override public void onCancelled(GestureDescription gestureDescription) { latch.countDown(); }
        }, null);
        if (!dispatched) return false;
        try { latch.await(durationMs + 3000, TimeUnit.MILLISECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        return completed.get();
    }

    private boolean performDirectOnIdNear(String id, UiNode anchor, int action, Bundle arguments) {
        if (!targetInteractionAllowed()) return false;
        if (performOnIdentity(anchor, action, arguments)) return true;
        AccessibilityNodeInfo root = findTikTokRoot();
        if (root == null) return false;
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            AccessibilityNodeInfo best = null;
            long bestDistance = Long.MAX_VALUE;
            for (AccessibilityNodeInfo node : nodes) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (!node.isVisibleToUser() || !centerInsideDisplay(bounds)) continue;
                long dx = (long) centerX(bounds) - centerX(anchor.bounds);
                long dy = (long) centerY(bounds) - centerY(anchor.bounds);
                long distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    best = node;
                    bestDistance = distance;
                }
            }
            boolean performed = best != null && best.performAction(action, arguments);
            recycleAll(nodes);
            return performed;
        } finally {
            root.recycle();
        }
    }

    private UiNode visibleEditTextNear(UiNode anchor) {
        UiSnapshot current = snapshot();
        UiNode best = null;
        long bestDistance = Long.MAX_VALUE;
        for (UiNode node : current.nodes) {
            if (!PageAnalyzer.TIKTOK_PACKAGE.equals(node.packageName)
                    || !"android.widget.EditText".equals(node.className)
                    || !current.isVisible(node)) continue;
            long dx = (long) centerX(node.bounds) - centerX(anchor.bounds);
            long dy = (long) centerY(node.bounds) - centerY(anchor.bounds);
            long distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                best = node;
                bestDistance = distance;
            }
        }
        int tolerance = Math.max(360, Math.max(
                anchor.bounds.right - anchor.bounds.left,
                anchor.bounds.bottom - anchor.bounds.top));
        return best != null && bestDistance <= (long) tolerance * tolerance ? best : null;
    }

    private static boolean inputTextMatches(UiNode input, String expected) {
        if (input == null) return false;
        if (expected.equals(input.text)) return true;
        return expected.isEmpty() && (input.text.isEmpty()
                || input.text.contains("添加评论")
                || input.text.toLowerCase(Locale.ROOT).contains("add comment"));
    }

    private boolean setTextOnVisibleEditText(AccessibilityNodeInfo node, Bundle arguments) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (node.isVisibleToUser() && centerInsideDisplay(bounds)
                && "android.widget.EditText".contentEquals(node.getClassName())
                ) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return true;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try {
                if (setTextOnVisibleEditText(child, arguments)) return true;
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    private boolean tapCenter(Rect bounds) {
        if (!centerInsideDisplay(bounds)) return false;
        Path path = new Path();
        path.moveTo(bounds.exactCenterX(), bounds.exactCenterY());
        return gesture(path, 100);
    }

    /** Tap the centre of the actually visible portion of a partially clipped control. */
    private boolean tapVisibleIntersection(Rect bounds) {
        if (bounds == null || bounds.isEmpty()) return false;
        int left = Math.max(0, bounds.left);
        int top = Math.max(0, bounds.top);
        int right = Math.min(displayWidth(), bounds.right);
        int bottom = Math.min(displayHeight(), bounds.bottom);
        if (left >= right || top >= bottom) return false;
        Path path = new Path();
        path.moveTo(left + (right - left) / 2f, top + (bottom - top) / 2f);
        return gesture(path, 100);
    }

    private boolean performOnIdentity(UiNode target, int action, Bundle arguments) {
        if (!targetInteractionAllowed() || target == null || target.treePath.isEmpty()) return false;
        for (AccessibilityWindowInfo window : service.getWindows()) {
            if (target.windowId >= 0 && window.getId() != target.windowId) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            AccessibilityNodeInfo exact = null;
            try {
                exact = descendantAtPath(root, target.treePath);
                if (exact == null || !sameIdentity(exact, target)) continue;
                return performNode(exact, action, arguments);
            } finally {
                if (exact != null) exact.recycle();
                root.recycle();
            }
        }
        return false;
    }

    private static AccessibilityNodeInfo descendantAtPath(AccessibilityNodeInfo root, String path) {
        String[] parts = path.split("/");
        if (parts.length == 0 || !"0".equals(parts[0])) return null;
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(root);
        for (int index = 1; index < parts.length; index++) {
            int childIndex;
            try {
                childIndex = Integer.parseInt(parts[index]);
            } catch (NumberFormatException error) {
                current.recycle();
                return null;
            }
            AccessibilityNodeInfo child = childIndex >= 0 && childIndex < current.getChildCount()
                    ? current.getChild(childIndex) : null;
            current.recycle();
            if (child == null) return null;
            current = child;
        }
        return current;
    }

    private static boolean sameIdentity(AccessibilityNodeInfo live, UiNode captured) {
        if (!captured.packageName.equals(value(live.getPackageName()))
                || !captured.className.equals(value(live.getClassName()))) return false;
        String liveId = value(live.getViewIdResourceName());
        return captured.id.isEmpty() || captured.id.equals(liveId);
    }

    /** Tap inside the thumbnail, above captions and per-card controls that may consume the tap. */
    private boolean tapSearchCard(Rect bounds) {
        if (!centerInsideDisplay(bounds)) return false;
        float x = bounds.exactCenterX();
        float y = bounds.top + Math.min(bounds.height() * 0.32f, bounds.width() * 0.65f);
        Path path = new Path();
        path.moveTo(x, y);
        return gesture(path, 120);
    }

    private boolean centerInsideDisplay(Rect bounds) {
        if (bounds == null || bounds.isEmpty()) return false;
        int centerX = bounds.centerX();
        int centerY = bounds.centerY();
        return centerX >= 0 && centerX < displayWidth() && centerY >= 0 && centerY < displayHeight();
    }

    private static int centerX(Rect bounds) {
        return bounds.left + (bounds.right - bounds.left) / 2;
    }

    private static int centerY(Rect bounds) {
        return bounds.top + (bounds.bottom - bounds.top) / 2;
    }

    /**
     * Accessibility node bounds and dispatched gestures use physical screen coordinates. Android
     * resource metrics can describe only the app content area on gesture-navigation devices, so
     * derive the coordinate extent from all accessibility windows and use metrics as a fallback.
     */
    private int displayWidth() {
        return physicalExtent(true,
                service.getResources().getDisplayMetrics().widthPixels);
    }

    private int displayHeight() {
        return physicalExtent(false,
                service.getResources().getDisplayMetrics().heightPixels);
    }

    private int physicalExtent(boolean horizontal, int fallback) {
        int extent = fallback;
        try {
            for (AccessibilityWindowInfo window : service.getWindows()) {
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                extent = Math.max(extent, horizontal ? bounds.right : bounds.bottom);
            }
        } catch (RuntimeException ignored) {
            // A window may disappear while the list is sampled. The resource metric remains a
            // safe fallback and the semantic target is re-resolved before every write gesture.
        }
        return extent;
    }

    public boolean targetInteractionAllowed() {
        UiSnapshot current = snapshot();
        return WindowArbiter.allowsTargetInteraction(current.foregroundDecision,
                current.windows, PageAnalyzer.TIKTOK_PACKAGE);
    }

    private static boolean performNode(AccessibilityNodeInfo node, int action, Bundle arguments) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        try {
            for (int depth = 0; depth < 5 && current != null; depth++) {
                if (current.performAction(action, arguments)) return true;
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            }
            return false;
        } finally {
            if (current != null) current.recycle();
        }
    }

    private static boolean clickNode(AccessibilityNodeInfo node) {
        return performNode(node, AccessibilityNodeInfo.ACTION_CLICK, null);
    }

    private boolean clickMatching(AccessibilityNodeInfo node, String[] labels, boolean description) {
        String candidate = value(description ? node.getContentDescription() : node.getText()).toLowerCase(Locale.ROOT);
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (node.isVisibleToUser() && visibleIntersection(bounds)) {
            for (String label : labels) {
                String normalized = label.toLowerCase(Locale.ROOT);
                if ((!description && candidate.equals(normalized)) || (description && candidate.contains(normalized))) {
                    if (clickNode(node)) return true;
                }
            }
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try {
                if (clickMatching(child, labels, description)) return true;
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    private boolean visibleIntersection(Rect bounds) {
        return bounds != null && !bounds.isEmpty()
                && bounds.right > 0 && bounds.bottom > 0
                && bounds.left < displayWidth() && bounds.top < displayHeight();
    }

    private AccessibilityNodeInfo findTikTokRoot() {
        List<AccessibilityWindowInfo> windows = service.getWindows();
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null && PageAnalyzer.TIKTOK_PACKAGE.equals(value(root.getPackageName()))) return root;
            if (root != null) root.recycle();
        }
        AccessibilityNodeInfo active = service.getRootInActiveWindow();
        if (active != null && PageAnalyzer.TIKTOK_PACKAGE.equals(value(active.getPackageName()))) return active;
        if (active != null) active.recycle();
        return null;
    }

    private static void traverse(AccessibilityNodeInfo node, List<UiNode> target,
                                 int windowId, String treePath) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        target.add(new UiNode(node.getViewIdResourceName(), value(node.getText()), value(node.getContentDescription()),
                value(node.getClassName()), value(node.getPackageName()), node.isSelected(), node.isClickable(),
                node.isVisibleToUser(), bounds, windowId, treePath));
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            traverse(child, target, windowId, treePath + "/" + index);
            child.recycle();
        }
    }

    private static void recycleAll(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo node : nodes) node.recycle();
    }

    private static String value(CharSequence value) { return value == null ? "" : value.toString().trim(); }
}
