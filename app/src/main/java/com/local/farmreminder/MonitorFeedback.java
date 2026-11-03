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
    private static Boolean insideMiniProgram;
    private static boolean processingActive;
    // Written only on the main thread; dispatch may read it from a worker.
    private static volatile long processingEpoch;
    private static long lastRecognizedAt = -REPEAT_WINDOW_MS;
    private static long lastAddedAt = -REPEAT_WINDOW_MS;
    private static long lastFailedAt = -REPEAT_WINDOW_MS;

    private MonitorFeedback() { }

    public static void setSessionActive(Context context, boolean active) {
        Runnable update = () -> {
            processingEpoch++;
            processingActive = active;
            cancelRecognition();
            insideMiniProgram = null;
            lastRecognizedAt = -REPEAT_WINDOW_MS;
            lastAddedAt = -REPEAT_WINDOW_MS;
            lastFailedAt = -REPEAT_WINDOW_MS;
            if (!active && activeToast != null) {
                activeToast.cancel();
                activeToast = null;
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) update.run();
        else MAIN.post(update);
    }

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
        long epoch = processingEpoch;
        if (Looper.myLooper() == Looper.getMainLooper()) handle(app, event, epoch);
        else MAIN.post(() -> handle(app, event, epoch));
    }

    private static void handle(Context app, Event event, long epoch) {
        if (!processingActive || epoch != processingEpoch) return;
        long now = SystemClock.elapsedRealtime();
        switch (event) {
            case ENTERED:
            case EXITED:
                boolean entered = event == Event.ENTERED;
                if (insideMiniProgram != null && insideMiniProgram.booleanValue() == entered) return;
                insideMiniProgram = entered;
                cancelRecognition();
                lastRecognizedAt = -REPEAT_WINDOW_MS;
                lastAddedAt = -REPEAT_WINDOW_MS;
                lastFailedAt = -REPEAT_WINDOW_MS;
                // Boundaries are immediate, never throttled by recognition feedback.
                show(app, entered ? "小程序窗口已进入，开始识别" : "已离开小程序，停止截图；队列继续识别");
                break;
            case RECOGNIZED:
                if (now - lastRecognizedAt < REPEAT_WINDOW_MS) return;
                lastRecognizedAt = now;
                cancelRecognition();
                pendingRecognition = () -> {
                    pendingRecognition = null;
                    if (!processingActive || epoch != processingEpoch) return;
                    show(app, "已识别成熟时间");
                };
                MAIN.postDelayed(pendingRecognition, MERGE_DELAY_MS);
                break;
            case ADDED:
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
