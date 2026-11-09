package com.local.farmreminder;

import android.content.Context;

/** Persistent, opt-in screenshot retention for newly admitted captures. */
final class ScreenshotRetentionPolicy {
    private static final String KEY = "debugSaveScreenshots";

    private ScreenshotRetentionPolicy() {}

    static boolean enabled(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(KEY, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putBoolean(KEY, enabled).apply();
    }
}
