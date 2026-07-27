package com.xwell.rpatt.automation;

import android.Manifest;
import android.os.Build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Frozen warmup permission policy for TikTok 46.1.3, derived from APK uses-permission
 * dump and docs/POPUP_CATALOG soft-deny rules. See docs/WARMUP_PERMISSION_POLICY.md.
 */
public final class WarmupPermissionPolicy {
    public enum Desired {
        /** Should stay denied for low-risk browse warmup. */
        DENIED,
        /** Either state is acceptable; shown for transparency only. */
        OPTIONAL
    }

    public static final class Spec {
        public final String id;
        public final String label;
        public final Desired desired;
        public final String rationale;
        public final String unpackEvidence;
        public final List<String> permissions;

        public Spec(String id, String label, Desired desired, String rationale,
                    String unpackEvidence, String... permissions) {
            this.id = id;
            this.label = label;
            this.desired = desired;
            this.rationale = rationale;
            this.unpackEvidence = unpackEvidence;
            this.permissions = Collections.unmodifiableList(Arrays.asList(permissions));
        }

        public String desiredLabel() {
            return desired == Desired.DENIED ? "建议关闭" : "开/关均可";
        }
    }

    /** Catalog / APK baseline this policy was frozen against. */
    public static final String BASELINE_PACKAGE = PageAnalyzer.TIKTOK_PACKAGE;
    public static final String BASELINE_VERSION_NAME = "46.1.3";
    public static final int BASELINE_VERSION_CODE = 2024601030;

    private static final List<Spec> SPECS = buildSpecs();

    private WarmupPermissionPolicy() {}

    public static List<Spec> specs() {
        return SPECS;
    }

    public static Spec byId(String id) {
        for (Spec spec : SPECS) {
            if (spec.id.equals(id)) return spec;
        }
        return null;
    }

    private static List<Spec> buildSpecs() {
        List<Spec> specs = new ArrayList<>();
        specs.add(new Spec(
                "contacts",
                "通讯录",
                Desired.DENIED,
                "养号不扫通讯录；减少找好友/同步弹窗",
                "APK uses-permission READ_CONTACTS；catalog contacts_sync_prompt / follow_friends soft-deny",
                Manifest.permission.READ_CONTACTS));
        specs.add(new Spec(
                "microphone",
                "麦克风",
                Desired.DENIED,
                "浏览养号不录音",
                "APK RECORD_AUDIO + FOREGROUND_SERVICE_MICROPHONE",
                Manifest.permission.RECORD_AUDIO));
        specs.add(new Spec(
                "camera",
                "相机",
                Desired.DENIED,
                "浏览养号不拍摄",
                "APK CAMERA + FOREGROUND_SERVICE_CAMERA；解包文案「想要访问你的相机」",
                Manifest.permission.CAMERA));
        specs.add(new Spec(
                "location",
                "位置",
                Desired.DENIED,
                "浏览养号不需要定位",
                "APK ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION",
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION));
        // Photos: API-dependent aliases; any grant counts as granted.
        if (Build.VERSION.SDK_INT >= 33) {
            specs.add(new Spec(
                    "photos",
                    "相册",
                    Desired.DENIED,
                    "养号不发帖、不选图",
                    "APK READ_MEDIA_IMAGES / READ_MEDIA_VIDEO",
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO));
        } else {
            specs.add(new Spec(
                    "photos",
                    "相册",
                    Desired.DENIED,
                    "养号不发帖、不选图",
                    "APK READ_EXTERNAL_STORAGE (maxSdk 32) / WRITE_EXTERNAL_STORAGE",
                    Manifest.permission.READ_EXTERNAL_STORAGE));
        }
        if (Build.VERSION.SDK_INT >= 33) {
            specs.add(new Spec(
                    "notifications",
                    "通知",
                    Desired.OPTIONAL,
                    "系统通知开/关均可；App 内开启通知询问仍 soft-deny",
                    "APK POST_NOTIFICATIONS；catalog notification_prompt soft-deny",
                    Manifest.permission.POST_NOTIFICATIONS));
        }
        return Collections.unmodifiableList(specs);
    }
}
