package com.local.farmreminder;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

/** Short, text-only monitoring feedback. All mutable state belongs to the main thread. */
public final class MonitorFeedback {
    private static final long MERGE_DELAY_MS = 150L;
    private static final long REPEAT_WINDOW_MS = 3000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private enum Event { ENTERED, EXITED, RECOGNIZED, ADDED, SAVE_FAILED }

    private static Toast activeToast;
    private static Runnable pendingRecognition;
    private static Boolean insideFarm;
    private static long lastRecognizedAt = -REPEAT_WINDOW_MS;
    private static long lastAddedAt = -REPEAT_WINDOW_MS;
    private static long lastFailedAt = -REPEAT_WINDOW_MS;

    private MonitorFeedback() { }

    public static void entered(Context context) {
        dispatch(context, Event.ENTERED);
    }

    public static void exited(Context context) {
        dispatch(context, Event.EXITED);
    }

    public static void recognized(Context context) {
        dispatch(context, Event.RECOGNIZED);
    }

    public static void added(Context context) {
        dispatch(context, Event.ADDED);
    }

    public static void saveFailed(Context context) {
        dispatch(context, Event.SAVE_FAILED);
    }

    private static void dispatch(Context context, Event event) {
        // Never retain an Activity or Service, including in queued callbacks.
        Context app = context.getApplicationContext();
        MAIN.post(() -> handle(app, event));
    }

    private static void handle(Context app, Event event) {
        long now = SystemClock.elapsedRealtime();
        switch (event) {
            case ENTERED:
            case EXITED:
                boolean entered = event == Event.ENTERED;
                if (insideFarm != null && insideFarm.booleanValue() == entered) return;
                insideFarm = entered;
                cancelRecognition();
                lastRecognizedAt = -REPEAT_WINDOW_MS;
                lastAddedAt = -REPEAT_WINDOW_MS;
                lastFailedAt = -REPEAT_WINDOW_MS;
                // Boundaries are immediate, never throttled by recognition feedback.
                show(app, entered ? "已进入农场，开始识别" : "已离开农场，暂停识别");
                break;
            case RECOGNIZED:
                if (Boolean.FALSE.equals(insideFarm)
                        || now - lastRecognizedAt < REPEAT_WINDOW_MS) return;
                lastRecognizedAt = now;
                cancelRecognition();
                pendingRecognition = () -> {
                    pendingRecognition = null;
                    show(app, "已识别成熟时间");
                };
                MAIN.postDelayed(pendingRecognition, MERGE_DELAY_MS);
                break;
            case ADDED:
                if (Boolean.FALSE.equals(insideFarm)) return;
                cancelRecognition();
                // Also merge if the two notifications arrive in the opposite order.
                lastRecognizedAt = now;
                if (now - lastAddedAt < REPEAT_WINDOW_MS) return;
                lastAddedAt = now;
                show(app, "已识别成熟时间，已添加提醒");
                break;
            case SAVE_FAILED:
                cancelRecognition();
                lastRecognizedAt = now;
                if (now - lastFailedAt < REPEAT_WINDOW_MS) return;
                lastFailedAt = now;
                show(app, "提醒保存失败，请返回应用检查");
                break;
        }
    }

    private static void cancelRecognition() {
        if (pendingRecognition != null) {
            MAIN.removeCallbacks(pendingRecognition);
            pendingRecognition = null;
        }
    }

    private static void show(Context app, String text) {
        if (activeToast != null) activeToast.cancel();
        // Standard system text Toast only: no custom view, overlay, or permission.
        activeToast = Toast.makeText(app, text, Toast.LENGTH_SHORT);
        activeToast.show();
    }
}
