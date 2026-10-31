package com.xwell.rpatt.automation;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * Separates service registration, system authorization and live binding.
 *
 * <p>Those are different Android states. Treating an authorized-but-unbound service as ready
 * makes the task fail later with a misleading automation error.</p>
 */
public final class AccessibilityServiceStatus {
    public enum State {
        NOT_REGISTERED,
        NOT_AUTHORIZED,
        AUTHORIZED_NOT_CONNECTED,
        CONNECTED
    }

    private AccessibilityServiceStatus() {}

    public static State query(Context context) {
        // A live service instance is stronger evidence than AccessibilityManager's installed /
        // enabled lists, which some OEMs refresh late after an in-place APK update.
        if (WarmupAccessibilityService.isConnected()) return State.CONNECTED;
        AccessibilityManager manager =
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        ComponentName expected = new ComponentName(context, WarmupAccessibilityService.class);
        boolean registered = contains(
                manager.getInstalledAccessibilityServiceList(), expected);
        boolean authorized = contains(manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK), expected);
        return classify(registered, authorized, false);
    }

    static State classify(boolean registered, boolean authorized, boolean connected) {
        if (connected) return State.CONNECTED;
        if (!registered) return State.NOT_REGISTERED;
        if (!authorized) return State.NOT_AUTHORIZED;
        return State.AUTHORIZED_NOT_CONNECTED;
    }

    public static boolean isAuthorized(State state) {
        return state == State.AUTHORIZED_NOT_CONNECTED || state == State.CONNECTED;
    }

    public static boolean isReady(State state) {
        return state == State.CONNECTED;
    }

    public static String shortLabel(State state) {
        switch (state) {
            case NOT_REGISTERED:
                return "服务未注册";
            case NOT_AUTHORIZED:
                return "未授权";
            case AUTHORIZED_NOT_CONNECTED:
                return "已授权，等待连接";
            case CONNECTED:
            default:
                return "已连接";
        }
    }

    private static boolean contains(List<AccessibilityServiceInfo> services,
                                    ComponentName expected) {
        for (AccessibilityServiceInfo info : services) {
            ComponentName actual = ComponentName.unflattenFromString(info.getId());
            if (expected.equals(actual)) return true;
        }
        return false;
    }
}
