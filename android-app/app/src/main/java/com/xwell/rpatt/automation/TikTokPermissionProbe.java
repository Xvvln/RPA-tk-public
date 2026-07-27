package com.xwell.rpatt.automation;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only probe of TikTok runtime permissions against {@link WarmupPermissionPolicy}.
 * Never modifies grants. See docs/WARMUP_PERMISSION_POLICY.md.
 */
public final class TikTokPermissionProbe {
    public static final class Item {
        public final String id;
        public final String label;
        public final boolean granted;
        public final WarmupPermissionPolicy.Desired desired;
        public final String rationale;
        public final String unpackEvidence;
        public final List<String> permissions;

        public Item(String id, String label, boolean granted, WarmupPermissionPolicy.Desired desired,
                    String rationale, String unpackEvidence, List<String> permissions) {
            this.id = id;
            this.label = label;
            this.granted = granted;
            this.desired = desired;
            this.rationale = rationale == null ? "" : rationale;
            this.unpackEvidence = unpackEvidence == null ? "" : unpackEvidence;
            this.permissions = Collections.unmodifiableList(new ArrayList<>(permissions));
        }

        /** True when actual grant conflicts with frozen warmup policy. */
        public boolean mismatchesPolicy() {
            return desired == WarmupPermissionPolicy.Desired.DENIED && granted;
        }

        public String statusLine() {
            if (desired == WarmupPermissionPolicy.Desired.DENIED) {
                return granted ? "已开 · 建议关闭" : "已关 · 符合策略";
            }
            return granted ? "已开 · 可选" : "已关 · 可选";
        }

        public JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("id", id)
                    .put("label", label)
                    .put("granted", granted)
                    .put("desired", desired.name().toLowerCase(Locale.ROOT))
                    .put("mismatch", mismatchesPolicy())
                    .put("rationale", rationale)
                    .put("unpack_evidence", unpackEvidence)
                    .put("permissions", new JSONArray(permissions));
        }
    }

    public static final class Report {
        public final boolean tiktokInstalled;
        public final String versionName;
        public final int versionCode;
        public final List<Item> items;
        public final String policyBaselineVersion;

        public Report(boolean tiktokInstalled, String versionName, int versionCode, List<Item> items) {
            this.tiktokInstalled = tiktokInstalled;
            this.versionName = versionName == null ? "" : versionName;
            this.versionCode = versionCode;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.policyBaselineVersion = WarmupPermissionPolicy.BASELINE_VERSION_NAME;
        }

        public int mismatchCount() {
            int count = 0;
            for (Item item : items) {
                if (item.mismatchesPolicy()) count++;
            }
            return count;
        }

        public boolean warmupFriendly() {
            return tiktokInstalled && mismatchCount() == 0;
        }

        public String summaryLine() {
            if (!tiktokInstalled) return "未安装 TikTok";
            int n = mismatchCount();
            if (n == 0) return "TikTok 权限符合养号策略";
            return String.format(Locale.CHINA, "TikTok 权限 · %d 项建议关闭", n);
        }

        public String detailHint() {
            if (!tiktokInstalled) return "安装国际版后可检测";
            String version = versionName.isEmpty() ? "未知版本" : "v" + versionName;
            return version + " · 策略基线 " + policyBaselineVersion + " · 点开查看详情";
        }

        public JSONObject toJson() throws Exception {
            JSONArray array = new JSONArray();
            for (Item item : items) array.put(item.toJson());
            return new JSONObject()
                    .put("tiktok_installed", tiktokInstalled)
                    .put("version_name", versionName)
                    .put("version_code", versionCode)
                    .put("policy_baseline_version", policyBaselineVersion)
                    .put("summary", summaryLine())
                    .put("mismatch_count", mismatchCount())
                    .put("warmup_friendly", warmupFriendly())
                    .put("items", array);
        }
    }

    private TikTokPermissionProbe() {}

    public static Report probe(Context context) {
        PackageManager pm = context.getPackageManager();
        String versionName = "";
        int versionCode = 0;
        boolean installed;
        try {
            PackageInfo info = pm.getPackageInfo(PageAnalyzer.TIKTOK_PACKAGE, 0);
            installed = true;
            versionName = info.versionName == null ? "" : info.versionName;
            versionCode = info.versionCode;
        } catch (PackageManager.NameNotFoundException error) {
            installed = false;
        }

        List<Item> items = new ArrayList<>();
        for (WarmupPermissionPolicy.Spec spec : WarmupPermissionPolicy.specs()) {
            boolean granted = anyGranted(pm, spec.permissions);
            items.add(new Item(spec.id, spec.label, granted, spec.desired,
                    spec.rationale, spec.unpackEvidence, spec.permissions));
        }
        return new Report(installed, versionName, versionCode, items);
    }

    /**
     * Test helper: map of permission-id → granted (contacts/microphone/...).
     * Missing keys default to denied.
     */
    public static Report fromGrantMap(boolean installed, String versionName,
                                      Map<String, Boolean> grantedById) {
        Map<String, Boolean> map = grantedById == null
                ? Collections.emptyMap() : grantedById;
        List<Item> items = new ArrayList<>();
        for (WarmupPermissionPolicy.Spec spec : WarmupPermissionPolicy.specs()) {
            boolean granted = Boolean.TRUE.equals(map.get(spec.id));
            items.add(new Item(spec.id, spec.label, granted, spec.desired,
                    spec.rationale, spec.unpackEvidence, spec.permissions));
        }
        return new Report(installed, versionName, 0, items);
    }

    /** Convenience for unit tests. */
    public static Report fromFlags(
            boolean installed,
            String versionName,
            boolean contacts,
            boolean microphone,
            boolean camera,
            boolean location,
            boolean photos,
            Boolean notifications) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        map.put("contacts", contacts);
        map.put("microphone", microphone);
        map.put("camera", camera);
        map.put("location", location);
        map.put("photos", photos);
        if (notifications != null) map.put("notifications", notifications);
        return fromGrantMap(installed, versionName, map);
    }

    private static boolean anyGranted(PackageManager pm, List<String> permissions) {
        for (String permission : permissions) {
            if (permission != null
                    && pm.checkPermission(permission, PageAnalyzer.TIKTOK_PACKAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }
}
