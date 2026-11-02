package com.local.farmreminder;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Coordinates a user-initiated monitoring session, never a background restart. */
public class MonitorForegroundService extends Service {
    public static volatile boolean isActive;
    public static volatile String lastError = "";
    private static final String ACTION_START = "com.local.farmreminder.monitor.START";
    private static final String ACTION_STOP = "com.local.farmreminder.monitor.STOP";
    private static final String CHANNEL = "monitor_session";
    private static final int NOTIFICATION_ID = 42017;
    private static MonitorForegroundService owner;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean foregroundStarted;
    private String lastContent;
    private long lastRefresh;

    public static void requestStart(Context context) throws RuntimeException {
        context.startForegroundService(new Intent(context, MonitorForegroundService.class).setAction(ACTION_START));
    }

    public static void requestStop(Context context) {
        FarmAccessibilityService.setMonitoring(context, false);
        context.stopService(new Intent(context, MonitorForegroundService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        owner = this;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        MonitorSessionPolicy.Action requested = ACTION_START.equals(action) ? MonitorSessionPolicy.Action.START
                : ACTION_STOP.equals(action) ? MonitorSessionPolicy.Action.STOP : MonitorSessionPolicy.Action.IGNORE;
        switch (MonitorSessionPolicy.decide(requested, foregroundStarted, FarmAccessibilityService.isRunning)) {
            case STOP:
                if (requested == MonitorSessionPolicy.Action.START) lastError = FarmAccessibilityService.lastStatus;
                endSession();
                break;
            case START:
                startSession();
                break;
            case KEEP:
                break;
            case IGNORE:
                // Do not terminate an already active session for an unrelated intent.
                if (!foregroundStarted) stopSelf();
                break;
        }
        return START_NOT_STICKY;
    }

    private void startSession() {
        lastError = "";
        try {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "监测会话", NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            channel.enableVibration(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) throw new IllegalStateException("通知服务不可用");
            manager.createNotificationChannel(channel);
            Notification notification = notification(content());
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION_ID, notification);
            foregroundStarted = true;
            isActive = true;
            if (Build.VERSION.SDK_INT < 30) {
                lastError = "Android 11 以下不支持无障碍截图";
                endSession();
                return;
            }
            if (!FarmAccessibilityService.isConnected) {
                lastError = "无障碍服务未连接，请先启用并等待连接";
                endSession();
                return;
            }
            FarmAccessibilityService.setMonitoring(this, true);
            if (MonitorSessionPolicy.shouldEnd(foregroundStarted, FarmAccessibilityService.isConnected, FarmAccessibilityService.isRunning)) {
                lastError = FarmAccessibilityService.lastStatus;
                endSession();
                return;
            }
            lastContent = null;
            lastRefresh = 0;
            handler.post(tick);
        } catch (RuntimeException e) {
            lastError = "前台监测启动失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "（" + e.getMessage() + "）");
            endSession();
        }
    }

    private final Runnable tick = new Runnable() {
        @SuppressLint("MissingPermission")
        @Override public void run() {
            if (owner != MonitorForegroundService.this || !foregroundStarted) return;
            if (MonitorSessionPolicy.shouldEnd(foregroundStarted, FarmAccessibilityService.isConnected, FarmAccessibilityService.isRunning)) {
                lastError = FarmAccessibilityService.lastStatus;
                endSession();
                return;
            }
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastRefresh >= 2000) {
                String content = content();
                if (!content.equals(lastContent)) {
                    NotificationManager manager = getSystemService(NotificationManager.class);
                    if (manager != null) manager.notify(NOTIFICATION_ID, notification(content));
                    lastContent = content;
                }
                lastRefresh = now;
            }
            handler.postDelayed(this, 1000);
        }
    };

    private String content() {
        OcrProgress progress = FarmAccessibilityService.getOcrProgress();
        return FarmAccessibilityService.lastStatus + " · 已识别 " + progress.processed + " · 待识别 " + progress.queued;
    }

    private Notification notification(String content) {
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), pendingFlags);
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, MonitorForegroundService.class).setAction(ACTION_STOP), pendingFlags);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("农场监测已开启").setContentText(content)
                .setStyle(new Notification.BigTextStyle().bigText(content))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "停止监测", stop).build())
                .build();
    }

    private void endSession() {
        handler.removeCallbacks(tick);
        if (owner == this) {
            FarmAccessibilityService.setMonitoring(this, false);
            isActive = false;
        }
        // Also remove a partially promoted notification if startForeground threw.
        stopForeground(STOP_FOREGROUND_REMOVE);
        foregroundStarted = false;
        stopSelf();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        if (owner == this) {
            FarmAccessibilityService.setMonitoring(this, false);
            isActive = false;
            owner = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
