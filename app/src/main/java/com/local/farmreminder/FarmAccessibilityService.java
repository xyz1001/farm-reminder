package com.local.farmreminder;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserManager;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/** Capture adapter. Screenshot and lifecycle state are confined to the main thread. */
public class FarmAccessibilityService extends AccessibilityService {
    public static volatile boolean isConnected, isRunning;
    public static volatile String lastStatus = "无障碍服务未连接", diagnostics = "本次会话尚无数据";
    private static volatile FarmAccessibilityService instance;
    private static volatile DurableFrameEngine engine;
    private static final FramePipeline<Bitmap> capture = new FramePipeline<>(Bitmap::recycle);
    private static final AtomicLong NEXT_INSTANCE = new AtomicLong();
    private static final String CAPTURE_BLOCKED = "截图任务未结束，暂不可启动；等待完成或重启本提醒应用进程";
    private final long instanceSequence = NEXT_INSTANCE.incrementAndGet();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = handler::post;
    private final SamplingPolicy policy = new SamplingPolicy();
    private final Runnable sample = this::sample;
    private boolean foreground, destroyed, receiverRegistered;
    private boolean usagePermission, screenInteractive, screenLocked = true, userUnlocked;
    private String captureSession = UUID.randomUUID().toString(), reason = "等待启动";
    private long captureSequence, runEpoch, nextCaptureAllowed, screenshots, errors;
    private long sceneEnters, sceneExits, recognizedEvents, added, saveFailures, frameFailures;
    private volatile String dumpSnapshot = "snapshot not yet available";
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (alive() && isRunning) { updateForeground(); schedule(); }
        }
    };
    private final DurableFrameEngine.Listener listener = new DurableFrameEngine.Listener() {
        @Override public void onProgress(OcrProgress progress) {
            if (!alive()) return;
            publish();
            if (isRunning) schedule();
        }
        @Override public void onFrameResult(DiskFrameStore.Frame frame, List<OcrLine> lines,
                                            ReminderStore.FrameOutcome outcome, long epoch) {
            if (!alive() || !isRunning || engine == null || epoch != runEpoch || epoch != engine.epoch()) return;
            if (outcome.committed && !outcome.replay) {
                if (outcome.confirmed) { recognizedEvents++; MonitorFeedback.recognized(FarmAccessibilityService.this); }
                if (outcome.added) { added++; MonitorFeedback.added(FarmAccessibilityService.this); }
            }
            reason = !foreground ? "等待微信前台，停止截图，旧图继续识别"
                    : outcome.code == null ? "处理完成" : "处理完成: " + outcome.code;
            lastStatus = reason; publish(); schedule();
        }
        @Override public void onStatus(String status) {
            // This callback carries no epoch and may have been posted by a previous run.
            // Progress and epoch-tagged failures provide the safe status surface instead.
        }
        @Override public void onFatal(String status) {
            if (!alive() || !isRunning || engine == null) return;
            // Both engine fatal paths stop exactly the run that registered this listener.
            if (engine.isRunning() || engine.epoch() != runEpoch + 1) return;
            stopSession(false); reason = status; lastStatus = status; publish();
        }
        @Override public void onFrameFailure(String anonymousCode, long epoch) {
            if (!alive() || !isRunning || engine == null || epoch != runEpoch || epoch != engine.epoch()) return;
            frameFailures++;
            if ("BUSINESS_FAILED".equals(anonymousCode)) {
                saveFailures++; MonitorFeedback.saveFailed(FarmAccessibilityService.this);
            }
            reason = anonymousCode; lastStatus = reason; publish();
        }
    };

    private boolean alive() { return InstanceOwner.alive(instance, this, destroyed); }
    private static DurableFrameEngine engine(Context context) {
        DurableFrameEngine e = DurableFrameEngine.get(context.getApplicationContext());
        engine = e; return e;
    }
    public static OcrProgress getOcrProgress() {
        DurableFrameEngine e = engine;
        return e == null ? new OcrProgress(0, 0, 0, 0, false, 0, 0, 0, false, "STOPPED") : e.progress();
    }
    public static void refreshDiskProgress(Context context) { engine(context).refresh(); }
    public static void retryFailedFrames(Context context) { engine(context).retryFailed(); }
    public static void exportDiagnostics(Context context, android.net.Uri destination, DiagnosticsExportCallback callback) {
        engine(context).exportDiagnostics(destination, callback);
    }
    public static boolean isEnabled(Context context) {
        AccessibilityManager manager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        ComponentName wanted = new ComponentName(context, FarmAccessibilityService.class);
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null) {
                android.content.pm.ServiceInfo service = info.getResolveInfo().serviceInfo;
                if (wanted.equals(new ComponentName(service.packageName, service.name))) return true;
            }
        }
        return false;
    }
    public static void setMonitoring(Context context, boolean enabled) {
        FarmAccessibilityService service = instance;
        if (service == null || !isConnected || Build.VERSION.SDK_INT < 30) {
            isRunning = false;
            lastStatus = enabled ? "服务未连接或系统版本不支持截图" : "已停止";
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) service.setRunning(enabled);
        else service.handler.post(() -> service.setRunning(enabled));
    }
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if (destroyed) return;
        FarmAccessibilityService previous = instance;
        if (previous != null) previous.retire();
        instance = this; isConnected = true; isRunning = false;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = 0;
            info.flags &= ~AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            setServiceInfo(info);
        }
        engine(this).refresh(); newCaptureSession();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_USER_PRESENT);
        if (!receiverRegistered) {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenReceiver, filter);
            receiverRegistered = true;
        }
        if (capture.captureBusy()) armWatchdog(capture.captureId(), capture.captureStarted());
        lastStatus = capture.captureBusy() ? CAPTURE_BLOCKED : "服务已连接，请手动启动监测";
        reason = lastStatus; publish();
    }
    private void setRunning(boolean start) {
        if (!alive()) return;
        if (!start) { stopSession(true); return; }
        if (isRunning) return;
        if (capture.captureBusy()) { lastStatus = CAPTURE_BLOCKED; reason = lastStatus; publish(); return; }
        DurableFrameEngine e = engine(this);
        if (!e.start(listener)) { lastStatus = "识别任务未结束，请等待完成后重试"; reason = lastStatus; publish(); return; }
        runEpoch = e.epoch(); isRunning = true;
        MonitorFeedback.setSessionActive(this, true);
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("monitoringEnabled", true).apply();
        newCaptureSession(); foreground = false; policy.invalidate();
        reason = "等待微信前台推断"; lastStatus = reason; updateForeground(); publish(); schedule();
    }
    private void stopSession(boolean stopEngine) {
        if (!alive()) return;
        if (stopEngine && engine != null && isRunning && engine.epoch() == runEpoch) engine.stop();
        isRunning = false; handler.removeCallbacks(sample); policy.invalidate();
        leaveScene(); foreground = false; newCaptureSession();
        MonitorFeedback.setSessionActive(this, false);
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("monitoringEnabled", false).apply();
        reason = "已停止"; lastStatus = reason; publish();
    }
    private void retire() {
        if (alive()) { stopSession(true); detachListener(); }
        handler.removeCallbacks(sample);
        if (receiverRegistered) { try { unregisterReceiver(screenReceiver); } catch (IllegalArgumentException ignored) { } receiverRegistered = false; }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { if (alive()) { stopSession(true); lastStatus = "服务中断，请重新启动"; } }
    @Override public boolean onUnbind(Intent intent) {
        if (alive()) { stopSession(true); detachListener(); isConnected = false; instance = null; lastStatus = "服务断开，请重新启动监测"; }
        return super.onUnbind(intent);
    }
    private void newCaptureSession() { captureSession = UUID.randomUUID().toString(); captureSequence = 0; }
    private boolean inferForeground() {
        usagePermission = false; screenInteractive = false; screenLocked = true; userUnlocked = false;
        try {
            usagePermission = UsageAccessGate.hasPermission(this);
            UserManager user = (UserManager) getSystemService(USER_SERVICE);
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            userUnlocked = user != null && user.isUserUnlocked();
            screenInteractive = power != null && power.isInteractive();
            screenLocked = keyguard == null || keyguard.isKeyguardLocked();
            if (!usagePermission || !userUnlocked || !screenInteractive || screenLocked) return false;
            UsageStatsManager manager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            if (manager == null) return false;
            long end = System.currentTimeMillis();
            UsageEvents events = manager.queryEvents(end - 24L * 60 * 60 * 1000, end);
            if (events == null) return false;
            UsageForegroundPolicy inference = new UsageForegroundPolicy();
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                if (!events.getNextEvent(event)) return false;
                int type = event.getEventType();
                int action;
                if (type == UsageEvents.Event.DEVICE_SHUTDOWN || type == UsageEvents.Event.DEVICE_STARTUP) action = UsageForegroundPolicy.RESET;
                else if (type == UsageEvents.Event.ACTIVITY_RESUMED) action = UsageForegroundPolicy.RESUMED;
                else if (type == UsageEvents.Event.ACTIVITY_PAUSED) action = UsageForegroundPolicy.PAUSED;
                else if (type == UsageEvents.Event.ACTIVITY_STOPPED) action = UsageForegroundPolicy.STOPPED;
                else continue;
                inference.event(event.getTimeStamp(), event.getPackageName(), event.getClassName(), action);
            }
            return inference.canCapture(usagePermission, userUnlocked, screenInteractive, screenLocked);
        } catch (RuntimeException e) { return false; }
    }
    private boolean updateForeground() {
        if (!alive()) return false;
        boolean now = isRunning && inferForeground();
        if (foreground != now) {
            if (foreground) leaveScene();
            else { sceneEnters++; MonitorFeedback.entered(this); }
            newCaptureSession(); policy.invalidate();
            foreground = now;
            reason = now ? "推断微信处于前台，正在采集" : "等待微信前台或使用情况权限，停止截图，旧图继续识别";
            lastStatus = reason; publish();
        }
        return now;
    }
    private void schedule() {
        if (!alive()) return;
        handler.removeCallbacks(sample);
        if (!isRunning) return;
        if (capture.captureBusy()) { handler.postDelayed(sample, 1000); return; }
        if (!foreground || engine == null || !engine.canCapture()) { handler.postDelayed(sample, 1000); return; }
        long now = SystemClock.elapsedRealtime();
        handler.postDelayed(sample, Math.max(0, Math.max(policy.next(now), nextCaptureAllowed) - now));
    }
    private void sample() {
        if (!alive() || !isRunning) return;
        if (!updateForeground() || capture.captureBusy() || engine == null || !engine.canCapture()) { schedule(); return; }
        long now = SystemClock.elapsedRealtime();
        long next = Math.max(policy.next(now), nextCaptureAllowed);
        if (next > now) { handler.postDelayed(sample, next - now); return; }
        long uptime = SystemClock.uptimeMillis(), wall = System.currentTimeMillis();
        long request = capture.beginCapture(uptime);
        if (request == 0) return;
        String session = captureSession; long sequence = ++captureSequence;
        policy.captured(now); armWatchdog(request, uptime);
        try {
            if (Build.VERSION.SDK_INT >= 30) takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback(session, sequence, request, uptime, wall));
        } catch (RuntimeException e) {
            capture.endCapture(request); newCaptureSession(); errors++; reason = "截图请求失败"; lastStatus = reason; publish(); schedule();
        }
    }
    @android.annotation.TargetApi(30)
    private TakeScreenshotCallback callback(String session, long sequence, long request, long requestUptime, long requestWall) {
        return new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer(); Bitmap hardware = null, copy = null;
                boolean current = currentCapture(session, request);
                try {
                    if (!current) return;
                    long now = SystemClock.uptimeMillis(), wall = System.currentTimeMillis();
                    hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    if (hardware == null) throw new IllegalStateException("empty screenshot");
                    copy = hardware.copy(Bitmap.Config.ARGB_8888, false);
                    if (copy == null) throw new IllegalStateException("copy failed");
                    DiskFrameStore.Frame frame = new DiskFrameStore.Frame();
                    frame.sessionId = session; frame.captureSequence = sequence; frame.windowId = -1; frame.captureMethod = "accessibility_display_usage";
                    frame.contextCertain = true; // Usage-history inference only, not exclusive visibility.
                    frame.captureUptimeMs = FramePipeline.captureUptime(result.getTimestamp(), requestUptime, now);
                    frame.captureWallMs = FramePipeline.captureWall(result.getTimestamp(), requestUptime, requestWall, now, wall);
                    frame.width = copy.getWidth(); frame.height = copy.getHeight();
                    hardware.recycle(); hardware = null; buffer.close(); buffer = null;
                    if (!currentCapture(session, request)) return;
                    engine(FarmAccessibilityService.this).accept(copy, frame); copy = null; screenshots++;
                } catch (RuntimeException e) {
                    errors++;
                    if (current && alive()) { newCaptureSession(); reason = "截图保存交接失败"; lastStatus = reason; }
                } finally {
                    capture.endCapture(request);
                    if (hardware != null) hardware.recycle(); if (buffer != null) buffer.close(); if (copy != null) copy.recycle();
                    if (alive()) { publish(); if (isRunning) schedule(); }
                }
            }
            @Override public void onFailure(int code) {
                boolean current = currentCapture(session, request);
                capture.endCapture(request);
                if (current && alive()) {
                    newCaptureSession(); errors++;
                    if (code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) nextCaptureAllowed = SystemClock.elapsedRealtime() + 2000;
                    reason = "截图错误代码 " + code; lastStatus = reason; publish(); schedule();
                } else if (alive() && isRunning) { publish(); schedule(); }
            }
        };
    }
    private boolean currentCapture(String session, long request) {
        if (!CaptureAdmissionPolicy.allowed(capture.captureUsable(request), alive(), isRunning,
                foreground, session, captureSession)) return false;
        boolean inferred = updateForeground();
        return CaptureAdmissionPolicy.allowed(capture.captureUsable(request), alive(), isRunning,
                inferred, session, captureSession);
    }
    private void detachListener() {
        // detach compares the listener registration epoch, not the fence's post-stop epoch.
        if (alive() && engine != null && runEpoch != 0) engine.detach(listener, runEpoch);
    }
    private void armWatchdog(long request, long started) {
        handler.postDelayed(() -> {
            if (!alive() || !capture.timeoutCapture(request, SystemClock.uptimeMillis())) return;
            if (isRunning) stopSession(true);
            reason = CAPTURE_BLOCKED; lastStatus = reason; publish();
        }, Math.max(0, FramePipeline.REQUEST_TIMEOUT_MS - (SystemClock.uptimeMillis() - started)));
    }
    private void leaveScene() {
        if (foreground) { sceneExits++; MonitorFeedback.exited(this); }
    }
    private void publish() {
        if (!alive()) return;
        OcrProgress p = getOcrProgress();
        diagnostics = "截图 " + screenshots + " · 磁盘待识别 " + p.queued + " · 失败 " + p.failedCount
                + " · 待清理 " + p.cleanupPendingCount + " · 保留 " + p.retainedBytes + "B · 写入 " + p.writing
                + " · 暂停原因 " + p.pauseReason + " · OCR " + p.recognizing + " · 截图错误 " + errors
                + "\n使用情况权限 " + usagePermission + " · 用户解锁 " + userUnlocked
                + " · 屏幕开启 " + screenInteractive + " · 锁屏 " + screenLocked
                + " · 推断微信前台 " + foreground + " · 进入/退出 " + sceneEnters + "/" + sceneExits
                + " · 识别反馈 " + recognizedEvents + " · 新增 " + added + " · 处理失败 " + frameFailures + " · 保存失败 " + saveFailures + "\n" + reason;
        dumpSnapshot = "pid=" + Process.myPid() + " instance=" + instanceSequence + " owner=" + alive()
                + " running=" + isRunning + " foreground=" + foreground + " captureInFlight=" + capture.captureBusy()
                + " captureTimeout=" + capture.captureTimedOut() + " diskQueued=" + p.queued + " failed=" + p.failedCount
                + " cleanup=" + p.cleanupPendingCount + " retained=" + p.retainedBytes + " writing=" + p.writing
                + " pauseReason=" + p.pauseReason + " recognizing=" + p.recognizing + " errors=" + errors
                + " usagePermission=" + usagePermission + " screenInteractive=" + screenInteractive
                + " screenLocked=" + screenLocked + " userUnlocked=" + userUnlocked;
    }
    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) writer.println(dumpSnapshot);
    }
    @Override public void onDestroy() {
        if (alive()) { stopSession(true); detachListener(); isConnected = false; instance = null; lastStatus = "服务断开，请重新启动监测"; }
        destroyed = true; handler.removeCallbacks(sample);
        if (receiverRegistered) { try { unregisterReceiver(screenReceiver); } catch (IllegalArgumentException ignored) { } receiverRegistered = false; }
        super.onDestroy();
    }
}
