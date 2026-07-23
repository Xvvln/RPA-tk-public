package com.xwell.rpatt.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.view.Window;

import com.xwell.rpatt.R;

/** Shared visual tokens for the app dashboard and the independent overlay UI. */
public final class RpaPalette {
    private static final String PREFS = "ui_preferences";
    private static final String LIGHT_MODE = "light_mode";

    public static int BACKGROUND = Color.rgb(16, 17, 20);
    public static int SURFACE = Color.rgb(27, 29, 34);
    public static int SURFACE_RAISED = Color.rgb(38, 40, 46);
    public static int BORDER = Color.rgb(51, 54, 62);
    public static int TEXT = Color.rgb(247, 247, 249);
    public static int TEXT_MUTED = Color.rgb(174, 177, 186);
    public static final int PINK = Color.rgb(255, 44, 85);
    public static int PINK_DARK = Color.rgb(151, 37, 61);
    public static final int GREEN = Color.rgb(45, 191, 101);
    public static final int AMBER = Color.rgb(240, 164, 43);
    public static final int RED = Color.rgb(226, 61, 79);

    private RpaPalette() {}

    /** Apply the persisted palette before an Activity builds any Material components. */
    public static boolean prepare(Activity activity) {
        boolean light = isLightMode(activity);
        applyColors(light);
        activity.setTheme(light ? R.style.AppTheme_Light : R.style.AppTheme);
        applySystemBars(activity, light);
        return light;
    }

    /** Service/overlay entry point where no Activity theme lifecycle is available. */
    public static boolean apply(Context context) {
        boolean light = isLightMode(context);
        applyColors(light);
        return light;
    }

    public static int themeResource(Context context) {
        return isLightMode(context) ? R.style.AppTheme_Light : R.style.AppTheme;
    }

    public static boolean isLightMode(Context context) {
        return preferences(context).getBoolean(LIGHT_MODE, false);
    }

    public static void setLightMode(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(LIGHT_MODE, enabled).apply();
        applyColors(enabled);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void applyColors(boolean light) {
        if (light) {
            BACKGROUND = Color.rgb(246, 247, 249);
            SURFACE = Color.WHITE;
            SURFACE_RAISED = Color.rgb(236, 238, 242);
            BORDER = Color.rgb(215, 218, 224);
            TEXT = Color.rgb(24, 25, 29);
            TEXT_MUTED = Color.rgb(108, 112, 122);
            PINK_DARK = Color.rgb(184, 31, 67);
        } else {
            BACKGROUND = Color.rgb(16, 17, 20);
            SURFACE = Color.rgb(27, 29, 34);
            SURFACE_RAISED = Color.rgb(38, 40, 46);
            BORDER = Color.rgb(51, 54, 62);
            TEXT = Color.rgb(247, 247, 249);
            TEXT_MUTED = Color.rgb(174, 177, 186);
            PINK_DARK = Color.rgb(151, 37, 61);
        }
    }

    private static void applySystemBars(Activity activity, boolean light) {
        Window window = activity.getWindow();
        window.setStatusBarColor(BACKGROUND);
        window.setNavigationBarColor(BACKGROUND);
        int flags = window.getDecorView().getSystemUiVisibility();
        int lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(
                light ? flags | lightFlags : flags & ~lightFlags);
    }
}
