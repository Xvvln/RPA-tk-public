package com.xwell.rpatt.config;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

public final class ConfigStore {
    private static final String PREFS = "warmup_config";
    private static final String KEY = "config_json";

    private ConfigStore() {}

    public static WarmupConfig load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = preferences.getString(KEY, null);
        if (raw == null) return WarmupConfig.defaultsFor("cold");
        try {
            JSONObject json = new JSONObject(raw);
            WarmupConfig config = WarmupConfig.fromJson(json);
            JSONObject legacyInterest = json.optJSONObject("interest");
            if (json.optJSONObject("not_interested") == null && legacyInterest != null
                    && legacyInterest.optBoolean("apply_not_interested", false)) {
                preferences.edit().putString(KEY, config.toJson().toString()).apply();
            }
            return config;
        } catch (JSONException | IllegalArgumentException ignored) {
            return WarmupConfig.defaultsFor("cold");
        }
    }

    public static void save(Context context, WarmupConfig config) {
        config.validate();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, config.toJson().toString())
                .apply();
    }
}
