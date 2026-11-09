package com.local.farmreminder;

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

/** Best-effort restore of explicit durable intent; never grants permissions or clears fences. */
public class MonitorForegroundService extends Service {
    public static volatile boolean isActive;
    public static volatile String lastError = "";
    private static final String ACTION_START = "com.local.farmreminder.monitor.START";
    private static final String ACTION_STOP = "com.local.farmreminder.monitor.STOP";
    private static final String EXTRA_REVISION = "revision", EXTRA_ATTEMPT = "lifecycle_attempt";
    private static final String CHANNEL = "monitor_session";
    private static final int NOTIFICATION_ID = 42017;
    private static MonitorForegroundService owner;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean foregroundStarted, intentionalStop, observingShizuku, hadRunning, dependencyLost;
    private long revision, lifecycleAttempt;
    private String lastContent;

    public static MonitorCoordinator.RequestResult requestStart(Context context, long attempt) { return MonitorCoordinator.start(context, attempt); }
    public static MonitorCoordinator.RequestResult requestStop(Context context) { return MonitorCoordinator.stop(context); }
    static boolean hasOwner() { return owner != null; }
    static boolean launch(Context context, long revision, long attempt) {
        MonitorIntentState s = MonitorCoordinator.current(context);
        if (s == null || !s.mayRun(revision)) return false;
        try {
            context.startForegroundService(new Intent(context, MonitorForegroundService.class).setAction(ACTION_START)
                    .putExtra(EXTRA_REVISION, revision).putExtra(EXTRA_ATTEMPT, attempt));
            return true;
        } catch (RuntimeException e) { lastError = "前台监测启动受系统限制"; return false; }
    }
    static void stopImmediately(Context context) {
        FarmAccessibilityService.setMonitoring(context, false);
        if (owner != null) owner.endSession(true);
        else context.stopService(new Intent(context, MonitorForegroundService.class));
    }
    static void accessibilityChanged(boolean connected) {
        MonitorForegroundService current = owner;
        if (current == null || !current.foregroundStarted) return;
        MonitorCoordinator.trigger(MonitorRuntimePolicy.Trigger.READINESS);
        if (!connected) {
            current.dependencyLost = true;
            // The accessibility session stops its gate on disconnect; re-establish
            // read-only observation while waiting for the next connection.
            current.observingShizuku = false;
            MonitorCoordinator.waiting(MonitorCoordinator.Reason.ACCESSIBILITY);
            MonitorCoordinator.attemptOnReady(current, current, current.revision, false, false);
        }
        current.handler.removeCallbacks(current.tick); current.handler.post(current.tick);
    }
    @Override public void onCreate() { super.onCreate(); owner = this; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) { MonitorCoordinator.stop(this); return START_NOT_STICKY; }
        if (intent != null && !ACTION_START.equals(action)) { if (!foregroundStarted) stopSelf(); return START_NOT_STICKY; }
        if (intent == null) MonitorCoordinator.trigger(MonitorRuntimePolicy.Trigger.SYSTEM_STICKY);
        lifecycleAttempt = intent == null ? 0 : intent.getLongExtra(EXTRA_ATTEMPT, 0);
        LifecycleDiagnostics.event(this, lifecycleAttempt, LifecycleHistory.Event.FGS_ACTION, LifecycleHistory.Reason.NONE, null);
        try { promote(); }
        catch (RuntimeException e) {
            MonitorIntentState current = MonitorCoordinator.current(this);
            long failedRevision = intent == null ? (current == null ? 0 : current.revision) : intent.getLongExtra(EXTRA_REVISION, 0);
            MonitorCoordinator.suspendRevision(this, failedRevision, MonitorCoordinator.Reason.START_REJECTED);
            lastError = "前台监测启动失败"; endSession(true); return START_NOT_STICKY;
        }
        MonitorIntentState durable = MonitorCoordinator.currentAfterHistory(this);
        long requested = intent == null ? (durable == null ? 0 : durable.revision) : intent.getLongExtra(EXTRA_REVISION, 0);
        if (durable == null || !durable.mayRun(requested)) {
            // A stale queued start cannot stop a newer valid owner.
            MonitorIntentState now = MonitorCoordinator.current(this);
            if (now != null && now.mayRun(now.revision)) return START_STICKY;
            if (revision != 0 && now != null && now.mayRun(revision) && MonitorCoordinator.currentOwner(this, this, revision)) return START_STICKY;
            endSession(true); return START_NOT_STICKY;
        }
        intentionalStop = false; revision = requested; lifecycleAttempt = intent == null ? 0 : intent.getLongExtra(EXTRA_ATTEMPT, 0);
        hadRunning = FarmAccessibilityService.isRunning; dependencyLost = false;
        MonitorCoordinator.attach(this, revision);
        MonitorCoordinator.waiting(MonitorCoordinator.Reason.ACCESSIBILITY);
        handler.removeCallbacks(tick); handler.post(tick);
        return START_STICKY;
    }
    private void promote() {
        if (foregroundStarted) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "监测会话", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null); channel.enableVibration(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) throw new IllegalStateException("notification unavailable");
        manager.createNotificationChannel(channel);
        LifecycleDiagnostics.event(this, lifecycleAttempt, LifecycleHistory.Event.FGS_PROMOTE_BEGIN, LifecycleHistory.Reason.NONE, null);
        Notification notification = notification("等待监测条件");
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION_ID, notification);
        foregroundStarted = true; isActive = true;
        LifecycleDiagnostics.event(this, lifecycleAttempt, LifecycleHistory.Event.FGS_PROMOTE_OK, LifecycleHistory.Reason.NONE, null);
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
            if (owner != MonitorForegroundService.this || !foregroundStarted) return;
            if (!MonitorCoordinator.currentOwner(MonitorForegroundService.this, MonitorForegroundService.this, revision)) {
                MonitorIntentState newer = MonitorCoordinator.current(MonitorForegroundService.this);
                if (newer != null && newer.mayRun(newer.revision) && newer.revision != revision) {
                    FarmAccessibilityService.setMonitoring(MonitorForegroundService.this, false);
                    MonitorCoordinator.detach(MonitorForegroundService.this);
                    return; // Wait for the newer revision's already-submitted command.
                }
                endSession(true); return;
            }
            MonitorIntentState state = MonitorCoordinator.current(MonitorForegroundService.this);
            boolean shizuku = ShizukuTaskGate.isEnabled(MonitorForegroundService.this);
            if (!MonitorRuntimePolicy.modeMatches(state, shizuku)) {
                onMonitorFailure(revision, MonitorCoordinator.Reason.SOURCE_CHANGED); return;
            }
            // Storage or alarm errors must not turn retention into a monitoring failure.
            try {
                if (new ReminderStore(MonitorForegroundService.this).pruneExpired(System.currentTimeMillis()) > 0)
                    new AlarmScheduler(MonitorForegroundService.this).rescheduleAll();
            } catch (RuntimeException ignored) { }
            boolean ready = false;
            MonitorCoordinator.Reason wait = MonitorCoordinator.Reason.ACCESSIBILITY;
            if (shizuku) {
                ShizukuTaskGate gate = ShizukuTaskGate.get(MonitorForegroundService.this);
                if (!observingShizuku) { gate.start(); observingShizuku = true; }
                ShizukuTaskGate.Snapshot snapshot = gate.snapshot();
                if (!gate.hasPermission() || "UNKNOWN".equals(snapshot.state) || snapshot.age > 1500) wait = MonitorCoordinator.Reason.SHIZUKU;
                else if (FarmAccessibilityService.isConnected) ready = true;
            } else if (!UsageAccessGate.hasPermission(MonitorForegroundService.this)) wait = MonitorCoordinator.Reason.SOURCE_PERMISSION;
            else if (FarmAccessibilityService.isConnected) ready = true;
            if (FarmAccessibilityService.isRunning) {
                if (!FarmAccessibilityService.isConnected) { FarmAccessibilityService.setMonitoring(MonitorForegroundService.this, false); ready = false; wait = MonitorCoordinator.Reason.ACCESSIBILITY; }
                else if (ready) {
                    MonitorCoordinator.Reason paused = FarmAccessibilityService.capturePauseReason();
                    if (paused == MonitorCoordinator.Reason.NONE) MonitorCoordinator.running(); else MonitorCoordinator.waiting(paused);
                }
                else MonitorCoordinator.waiting(wait);
            }
            if (!FarmAccessibilityService.isRunning) {
                if (hadRunning && !dependencyLost) { onMonitorFailure(revision, MonitorCoordinator.Reason.UNEXPECTED_STOP); return; }
                MonitorCoordinator.waiting(wait);
                if (MonitorCoordinator.attemptOnReady(MonitorForegroundService.this, MonitorForegroundService.this, revision, ready, false)) {
                    MonitorCoordinator.Reason result = FarmAccessibilityService.startOwned(MonitorForegroundService.this, revision);
                    if (result != MonitorCoordinator.Reason.NONE) { onMonitorFailure(revision, result); return; }
                    hadRunning = true; dependencyLost = false;
                    MonitorCoordinator.running();
                }
            }
            String content = FarmAccessibilityService.isRunning ? FarmAccessibilityService.lastStatus : "等待监测条件（无需自动授权）";
            if (!content.equals(lastContent)) {
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) manager.notify(NOTIFICATION_ID, notification(content));
                lastContent = content;
            }
            handler.postDelayed(this, 1000);
            } catch (RuntimeException failure) { onMonitorFailure(revision, MonitorCoordinator.Reason.ENGINE_FATAL); }
        }
    };
    void onMonitorFailure(long callbackRevision, MonitorCoordinator.Reason reason) {
        if (owner != this || !MonitorCoordinator.currentOwner(this, this, callbackRevision) || revision != callbackRevision) return;
        MonitorCoordinator.suspendIfOwner(this, this, callbackRevision, reason);
        lastError = "监测已暂停，请手动重试";
        endSession(true);
    }
    private Notification notification(String content) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, MonitorForegroundService.class).setAction(ACTION_STOP), flags);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("农场监测").setContentText(content).setStyle(new Notification.BigTextStyle().bigText(content))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "停止监测", stop).build()).build();
    }
    private void endSession(boolean intentional) {
        intentionalStop |= intentional;
        handler.removeCallbacks(tick);
        if (owner == this) {
            FarmAccessibilityService.setMonitoring(this, false);
            if (observingShizuku) ShizukuTaskGate.get(this).stop();
            observingShizuku = false; isActive = false; MonitorCoordinator.detach(this);
        }
        stopForeground(STOP_FOREGROUND_REMOVE); foregroundStarted = false; stopSelf();
    }
    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        if (owner == this) {
            if (!intentionalStop && revision != 0) MonitorCoordinator.suspendIfOwner(this, this, revision, MonitorCoordinator.Reason.UNEXPECTED_STOP);
            FarmAccessibilityService.setMonitoring(this, false);
            if (observingShizuku) ShizukuTaskGate.get(this).stop();
            isActive = false; MonitorCoordinator.detach(this); owner = null;
        }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
