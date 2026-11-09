package com.local.farmreminder;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

final class SupportPromptStore {
    private static final String TAG = "SupportPrompt";
    private static final String ELIGIBLE = "eligible", SHOWN = "shown";

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences("support_prompt", Context.MODE_PRIVATE);
    }

    static synchronized void recordRecognition(Context context) { writeFlag(context, ELIGIBLE); }
    static synchronized void markPromptShown(Context context) { writeFlag(context, SHOWN); }

    private static void writeFlag(Context context, String key) {
        try {
            SharedPreferences prefs = preferences(context);
            if (prefs.getBoolean(key, false)) return;
            if (!prefs.edit().putBoolean(key, true).commit()) {
                prefs.edit().putBoolean(key, false).commit();
                Log.w(TAG, "Could not persist prompt state");
            }
        } catch (RuntimeException e) { Log.w(TAG, "Could not persist prompt state"); }
    }

    static synchronized boolean consumeAutomaticPrompt(Context context, boolean hasExistingReminder) {
        try {
            SharedPreferences prefs = preferences(context);
            if (!SupportPromptPolicy.eligible(prefs.getBoolean(ELIGIBLE, false),
                    hasExistingReminder, prefs.getBoolean(SHOWN, false))) return false;
            if (prefs.edit().putBoolean(ELIGIBLE, true).putBoolean(SHOWN, true).commit()) return true;
            // commit() also changes the in-memory map on failure; allow a later entry to retry.
            prefs.edit().putBoolean(SHOWN, false).commit();
            Log.w(TAG, "Could not persist prompt state");
        } catch (RuntimeException e) { Log.w(TAG, "Could not persist prompt state"); }
        return false;
    }

    private SupportPromptStore() { }
}
