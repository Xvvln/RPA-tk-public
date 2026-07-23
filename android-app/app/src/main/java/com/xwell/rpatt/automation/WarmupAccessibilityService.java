package com.xwell.rpatt.automation;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.atomic.AtomicReference;

public final class WarmupAccessibilityService extends AccessibilityService {
    private static final AtomicReference<WarmupAccessibilityService> ACTIVE = new AtomicReference<>();

    public static WarmupAccessibilityService active() { return ACTIVE.get(); }
    public static boolean isConnected() { return ACTIVE.get() != null; }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        ACTIVE.set(this);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // The engine intentionally pulls a fresh snapshot before every action.
    }

    @Override public void onInterrupt() {
        // Engine polling turns a disconnect into a blocker and summary.
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        ACTIVE.compareAndSet(this, null);
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        ACTIVE.compareAndSet(this, null);
        super.onDestroy();
    }
}
