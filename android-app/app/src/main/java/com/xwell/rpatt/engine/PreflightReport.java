package com.xwell.rpatt.engine;

import com.xwell.rpatt.automation.TikTokPermissionProbe;
import com.xwell.rpatt.automation.WarmupPermissionPolicy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structured L0 environment preflight result.
 * See docs/INTERRUPT_AND_PREFLIGHT.md §3.
 */
public final class PreflightReport {
    public enum Severity { HARD, SOFT, INFO }

    public enum Status {
        PASS,
        FAIL,
        PASS_WITH_WARNINGS
    }

    public static final class Check {
        public final String id;
        public final Severity severity;
        public final boolean ok;
        public final String code;
        public final String message;

        public Check(String id, Severity severity, boolean ok, String code, String message) {
            this.id = id;
            this.severity = severity;
            this.ok = ok;
            this.code = code == null ? "" : code;
            this.message = message == null ? "" : message;
        }

        public JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("id", id)
                    .put("severity", severity.name().toLowerCase())
                    .put("ok", ok)
                    .put("code", code)
                    .put("message", message);
        }
    }

    public final Status status;
    public final List<Check> checks;

    public PreflightReport(List<Check> checks) {
        this.checks = Collections.unmodifiableList(new ArrayList<>(checks));
        boolean hardFail = false;
        boolean softFail = false;
        for (Check check : this.checks) {
            if (check.ok) continue;
            if (check.severity == Severity.HARD) hardFail = true;
            else if (check.severity == Severity.SOFT || check.severity == Severity.INFO) softFail = true;
        }
        if (hardFail) this.status = Status.FAIL;
        else if (softFail) this.status = Status.PASS_WITH_WARNINGS;
        else this.status = Status.PASS;
    }

    public boolean passed() {
        return status != Status.FAIL;
    }

    public Check firstHardFailure() {
        for (Check check : checks) {
            if (!check.ok && check.severity == Severity.HARD) return check;
        }
        return null;
    }

    public JSONObject toJson() throws Exception {
        JSONArray array = new JSONArray();
        for (Check check : checks) array.put(check.toJson());
        JSONArray hardFailures = new JSONArray();
        JSONArray warnings = new JSONArray();
        for (Check check : checks) {
            if (check.ok) continue;
            if (check.severity == Severity.HARD) hardFailures.put(check.toJson());
            else warnings.put(check.toJson());
        }
        return new JSONObject()
                .put("status", status.name().toLowerCase())
                .put("checks", array)
                .put("hard_failures", hardFailures)
                .put("warnings", warnings);
    }

    public static final class Builder {
        private final List<Check> checks = new ArrayList<>();

        public Builder add(String id, Severity severity, boolean ok, String code, String message) {
            checks.add(new Check(id, severity, ok, code, message));
            return this;
        }

        public PreflightReport build() {
            return new PreflightReport(checks);
        }
    }

    public static PreflightReport evaluateEnvironment(
            boolean accessibilityConnected,
            boolean screenInteractive,
            boolean deviceLocked,
            boolean requireNetwork,
            boolean networkAvailable,
            boolean tiktokLaunchOk,
            boolean overlayAllowed,
            boolean notificationsOk) {
        return evaluateEnvironment(
                accessibilityConnected, screenInteractive, deviceLocked,
                requireNetwork, networkAvailable, tiktokLaunchOk,
                overlayAllowed, notificationsOk, null);
    }

    public static PreflightReport evaluateEnvironment(
            boolean accessibilityConnected,
            boolean screenInteractive,
            boolean deviceLocked,
            boolean requireNetwork,
            boolean networkAvailable,
            boolean tiktokLaunchOk,
            boolean overlayAllowed,
            boolean notificationsOk,
            TikTokPermissionProbe.Report tiktokPermissions) {
        Builder builder = new Builder();
        builder.add("P01", Severity.HARD, accessibilityConnected,
                accessibilityConnected ? "" : "accessibility_disabled",
                accessibilityConnected ? "无障碍服务已开启" : "无障碍服务未开启");
        builder.add("P02", Severity.HARD, screenInteractive,
                screenInteractive ? "" : "screen_off",
                screenInteractive ? "屏幕已点亮" : "屏幕未点亮");
        builder.add("P03", Severity.HARD, !deviceLocked,
                deviceLocked ? "device_locked" : "",
                deviceLocked ? "手机仍处于锁屏状态" : "设备已解锁");
        boolean networkOk = !requireNetwork || networkAvailable;
        builder.add("P04", Severity.HARD, networkOk,
                networkOk ? "" : "network_unavailable",
                networkOk ? (requireNetwork ? "网络可用" : "未要求网络检查") : "Android 未检测到可用网络");
        builder.add("P05", Severity.HARD, tiktokLaunchOk,
                tiktokLaunchOk ? "" : "tiktok_not_installed",
                tiktokLaunchOk ? "TikTok 可启动" : "未找到 TikTok");
        builder.add("P07", Severity.SOFT, overlayAllowed,
                overlayAllowed ? "" : "overlay_unavailable",
                overlayAllowed ? "悬浮窗可用" : "悬浮窗权限未授予，操控可能变差");
        builder.add("P08", Severity.SOFT, notificationsOk,
                notificationsOk ? "" : "notifications_unavailable",
                notificationsOk ? "通知权限可用" : "通知权限未授予，前台提示可能受限");
        appendTikTokPermissionChecks(builder, tiktokPermissions);
        return builder.build();
    }

    /** P12: TikTok target-app permissions against frozen warmup policy (info only). */
    static void appendTikTokPermissionChecks(Builder builder, TikTokPermissionProbe.Report report) {
        if (report == null) return;
        if (!report.tiktokInstalled) {
            builder.add("P12", Severity.INFO, false, "tiktok_not_installed",
                    "无法检测 TikTok 权限（未安装）");
            return;
        }
        String versionNote = report.versionName.isEmpty() ? "" : " v" + report.versionName;
        builder.add("P12", Severity.INFO, report.warmupFriendly(),
                report.warmupFriendly() ? "" : "tiktok_permission_policy_mismatch",
                "TikTok" + versionNote + " · " + report.summaryLine());
        for (TikTokPermissionProbe.Item item : report.items) {
            if (item.desired == WarmupPermissionPolicy.Desired.OPTIONAL) {
                builder.add("P12_" + item.id, Severity.INFO, true, "",
                        "TikTok " + item.label + (item.granted ? " 已授权（可选）" : " 未授权（可选）"));
                continue;
            }
            boolean ok = !item.mismatchesPolicy();
            builder.add("P12_" + item.id, Severity.INFO, ok,
                    ok ? "" : "tiktok_permission_granted_" + item.id,
                    ok
                            ? "TikTok " + item.label + " 已关（符合策略）"
                            : "TikTok " + item.label + " 已开（策略建议关闭）");
        }
    }
}
