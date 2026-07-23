package com.xwell.rpatt.automation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Result of deterministic multi-window arbitration; never depends on traversal order. */
public final class ForegroundDecision {
    public enum Source {
        TARGET_FOCUSED,
        TARGET_ACTIVE,
        TARGET_UNDER_BENIGN_OVERLAY,
        EXTERNAL_FOCUSED,
        EXTERNAL_ACTIVE,
        LEGACY_SNAPSHOT,
        UNKNOWN
    }

    public final String packageName;
    public final Source source;
    public final double confidence;
    public final boolean targetVisible;
    public final List<String> evidence;

    public ForegroundDecision(String packageName, Source source, double confidence,
                              boolean targetVisible, List<String> evidence) {
        this.packageName = packageName == null ? "" : packageName.trim();
        this.source = source == null ? Source.UNKNOWN : source;
        this.confidence = Math.max(0d, Math.min(1d, confidence));
        this.targetVisible = targetVisible;
        this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
    }

    public boolean confirmsTarget(String targetPackage) {
        return targetPackage != null && targetPackage.equals(packageName)
                && source != Source.UNKNOWN && confidence >= 0.80d;
    }

    public boolean confirmsOtherApp(String targetPackage) {
        return !packageName.isEmpty() && !packageName.equals(targetPackage)
                && (source == Source.EXTERNAL_FOCUSED || source == Source.EXTERNAL_ACTIVE)
                && confidence >= 0.90d;
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("package", packageName)
                .put("source", source.name().toLowerCase(Locale.ROOT))
                .put("confidence", confidence)
                .put("target_visible", targetVisible)
                .put("evidence", new JSONArray(evidence));
    }

    public static ForegroundDecision fromJson(JSONObject value) {
        if (value == null) return unknown(Collections.singletonList("decision:missing"));
        List<String> evidence = new ArrayList<>();
        JSONArray values = value.optJSONArray("evidence");
        if (values != null) {
            for (int index = 0; index < values.length(); index++) {
                String item = values.optString(index);
                if (!item.isEmpty()) evidence.add(item);
            }
        }
        Source source;
        try { source = Source.valueOf(value.optString("source").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { source = Source.UNKNOWN; }
        return new ForegroundDecision(value.optString("package"), source,
                value.optDouble("confidence"), value.optBoolean("target_visible"), evidence);
    }

    public static ForegroundDecision legacy(String packageName, String targetPackage) {
        String value = packageName == null ? "" : packageName.trim();
        if (value.isEmpty()) return unknown(Collections.singletonList("legacy:empty_foreground"));
        return new ForegroundDecision(value, Source.LEGACY_SNAPSHOT,
                value.equals(targetPackage) ? 0.85d : 0.75d,
                value.equals(targetPackage), Collections.singletonList("legacy:foreground_package"));
    }

    public static ForegroundDecision unknown(List<String> evidence) {
        return new ForegroundDecision("", Source.UNKNOWN, 0d, false, evidence);
    }
}
