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
    private static long captureAttempt;
    private static long captureRevision;
    private static MonitorForegroundService captureOwner;
    private static final AtomicLong NEXT_INSTANCE = new AtomicLong();
    private static final String CAPTURE_BLOCKED = "截图任务未结束，暂不可启动；等待完成或重启本提醒应用进程";
    private final long instanceSequence = NEXT_INSTANCE.incrementAndGet();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = handler::post;
    private final SamplingPolicy policy = new SamplingPolicy();
    private final ContainerCapturePolicy containerPolicy = new ContainerCapturePolicy();
    private final CaptureAnnouncementPolicy announcementPolicy = new CaptureAnnouncementPolicy();
    private final Runnable sample = this::sample;
    private String foregroundKey, candidateClass;
    private UsageForegroundPolicy.State usageState = UsageForegroundPolicy.State.UNKNOWN;
    private final java.util.Set<Long> acceptedSequences = new java.util.HashSet<>();
    private boolean foreground, destroyed, receiverRegistered, shizukuMode;
    private ShizukuTaskGate.Snapshot shizukuSnapshot;
    private boolean usagePermission, screenInteractive, screenLocked = true, userUnlocked;
    private String captureSession = UUID.randomUUID().toString(), reason = "等待启动";
    private long captureSequence, runEpoch, nextCaptureAllowed, screenshots, errors;
    private long monitorRevision;
    private MonitorForegroundService monitorOwner;
    private long sceneEnters, sceneExits, recognizedEvents, added, saveFailures, frameFailures;
    private volatile String dumpSnapshot = "snapshot not yet available";
    private final LiveDiagnostics liveDiagnostics = new LiveDiagnostics();
    private final EntryFeedbackDiagnostics entryFeedback = new EntryFeedbackDiagnostics();
    private final java.util.Map<Long, android.widget.Toast> activeToasts = new java.util.HashMap<>();
    private boolean entryDebug;
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
            boolean pending = containerPolicy.pending(frame.sessionId, epoch, frame.captureSequence);
            if (pending) {
                boolean trusted = updateForeground() && captureSession.equals(frame.sessionId);
                FarmIdentityRecognition.Outcome classification = outcome.committed && !outcome.replay && trusted
                        ? FarmIdentityRecognition.classify(lines, frame.width, frame.height) : null;
                boolean identity = classification != null && classification != FarmIdentityRecognition.Outcome.NO_MATCH;
                boolean accepted = containerPolicy.result(frame.sessionId, epoch, frame.captureSequence, outcome.committed, outcome.replay, trusted, identity);
                EntryFeedbackDiagnostics.Reason why = accepted ? EntryFeedbackDiagnostics.Reason.NONE
                        : !outcome.committed || outcome.replay ? EntryFeedbackDiagnostics.Reason.COMMIT_OR_REPLAY
                        : !trusted ? EntryFeedbackDiagnostics.Reason.UNTRUSTED_FOREGROUND : EntryFeedbackDiagnostics.Reason.NO_IDENTITY;
                if (accepted || containerPolicy.pending(frame.sessionId, epoch, frame.captureSequence)
                        || containerPolicy.matches(frame.sessionId, epoch) && containerPolicy.state() == ContainerCapturePolicy.State.FAILED)
                    entryFeedback.result(accepted, why, classification == null ? "NOT_RUN" : classification.name(), containerPolicy.state().name(), SystemClock.elapsedRealtime());
                if (accepted) announceIfAllowed();
            }
            if (captureSession.equals(frame.sessionId)) acceptedSequences.remove(frame.captureSequence);
            if (outcome.committed && !outcome.replay) {
                if (outcome.confirmed) recognizedEvents++;
                if (outcome.added) added++;
            }
            reason = !foreground ? (shizukuMode ? "等待 Shizuku 前台任务，停止截图，旧图继续识别" : "等待微信容器，停止截图，旧图继续识别")
                    : containerPolicy.state() == ContainerCapturePolicy.State.FAILED ? "USAGE_VERIFY_REJECTED：首图验证未通过，停止截图"
                    : !shizukuMode && containerPolicy.state() == ContainerCapturePolicy.State.VERIFIED ? "USAGE_VERIFY_READY：身份验证通过，正在记录"
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
            long revision = monitorRevision; MonitorForegroundService owner = monitorOwner;
            stopSession(false); reason = status; lastStatus = status; publish();
            if (owner != null) owner.onMonitorFailure(revision, MonitorCoordinator.Reason.ENGINE_FATAL);
        }
        @Override public void onFrameFailure(String anonymousCode, long epoch) {
            if (!alive() || !isRunning || engine == null || epoch != runEpoch || epoch != engine.epoch()) return;
            frameFailures++;
            if ("BUSINESS_FAILED".equals(anonymousCode)) {
                saveFailures++;
            }
            reason = anonymousCode;
            lastStatus = reason; publish();
        }
        @Override public void onFrameFailure(DiskFrameStore.Frame frame, String anonymousCode, long epoch) {
            if (!alive() || !isRunning || engine == null || epoch != runEpoch || epoch != engine.epoch()) return;
            if (frame != null && containerPolicy.pending(frame.sessionId, epoch, frame.captureSequence)) {
                containerPolicy.fail(frame.sessionId, epoch, frame.captureSequence);
                entryFeedback.failure("WRITE_FAILED".equals(anonymousCode) || "STORAGE_FAILED".equals(anonymousCode) || "BUSINESS_FAILED".equals(anonymousCode)
                        ? EntryFeedbackDiagnostics.Reason.WRITE_FAILURE : "OCR_FAILED".equals(anonymousCode)
                        ? EntryFeedbackDiagnostics.Reason.OCR_FAILURE : EntryFeedbackDiagnostics.Reason.OTHER_FAILURE,
                        containerPolicy.state().name(), SystemClock.elapsedRealtime());
                onFrameFailure(anonymousCode, epoch);
                reason = "USAGE_VERIFY_FAILED：首图处理失败，停止截图"; lastStatus = reason; publish(); schedule();
            } else onFrameFailure(anonymousCode, epoch);
        }
    };

    private void announceIfAllowed() {
        if (announcementPolicy.onAcceptedFrame()) announce();
        else entryFeedback.reset(EntryFeedbackDiagnostics.Event.SUPPRESSED_BY_POLICY, SystemClock.elapsedRealtime());
    }
    private void announce() {
        long request = entryFeedback.request(Build.VERSION.SDK_INT >= 30, SystemClock.elapsedRealtime());
        long requestRun = entryFeedback.run(), requestStay = entryFeedback.stay();
        try {
            android.widget.Toast toast = android.widget.Toast.makeText(getApplicationContext(), "检测到已进入QQ经典农场，开始记录", android.widget.Toast.LENGTH_SHORT);
            if (entryDebug && Build.VERSION.SDK_INT >= 30) {
                toast.addCallback(new android.widget.Toast.Callback() {
                    @Override public void onToastShown() {
                        entryFeedback.callback(request, requestRun, requestStay, true, SystemClock.elapsedRealtime());
                    }
                    @Override public void onToastHidden() {
                        entryFeedback.callback(request, requestRun, requestStay, false, SystemClock.elapsedRealtime());
                        activeToasts.remove(request);
                    }
                });
                activeToasts.put(request, toast);
            }
            toast.show();
        } catch (RuntimeException e) {
            activeToasts.remove(request);
            entryFeedback.exception(request, e, SystemClock.elapsedRealtime());
        }
    }
    private boolean alive() { return InstanceOwner.alive(instance, this, destroyed); }
    private void lifecycle(LifecycleHistory.Event event, LifecycleHistory.Reason reason) {
        LifecycleDiagnostics.event(this, LifecycleDiagnostics.current(this), event, reason, null);
    }
    private static DurableFrameEngine engine(Context context) {
        DurableFrameEngine e = DurableFrameEngine.get(context.getApplicationContext());
        engine = e; return e;
    }
    public static OcrProgress getOcrProgress() {
        DurableFrameEngine e = engine;
        return e == null ? new OcrProgress(0, 0, 0, 0, false, 0, 0, 0, false, "STOPPED") : e.progress();
    }
    /** Read-only projection of the current foreground stay's blocking native capture failure. */
    static ContainerCapturePolicy.CaptureIssue currentCaptureIssue() {
        FarmAccessibilityService service = instance;
        if (service == null || !service.alive() || !isRunning || !service.foreground
                || engine == null || engine.epoch() != service.runEpoch)
            return ContainerCapturePolicy.CaptureIssue.NONE;
        return service.containerPolicy.captureIssue(service.captureSession, service.runEpoch);
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
        // Starts require an explicit current owner/revision, including at execution time.
        if (enabled) return;
        long expected = service.monitorRevision;
        MonitorForegroundService expectedOwner = service.monitorOwner;
        if (Looper.myLooper() == Looper.getMainLooper()) service.setRunning(false);
        else service.handler.post(() -> { if (service.alive() && service.monitorRevision == expected && service.monitorOwner == expectedOwner) service.setRunning(false); });
    }
    static MonitorCoordinator.Reason startOwned(MonitorForegroundService owner, long revision) {
        FarmAccessibilityService service = instance;
        if (service == null || !isConnected || !MonitorCoordinator.currentOwner(owner, owner, revision)) return MonitorCoordinator.Reason.ACCESSIBILITY;
        if (isRunning) return service.monitorOwner == owner && service.monitorRevision == revision ? MonitorCoordinator.Reason.NONE : MonitorCoordinator.Reason.BUSY;
        if (!MonitorRuntimePolicy.captureAvailable(capture.captureBusy())) return MonitorCoordinator.Reason.BUSY;
        service.monitorOwner = owner; service.monitorRevision = revision;
        service.setRunning(true);
        return isRunning ? MonitorCoordinator.Reason.NONE : MonitorCoordinator.Reason.BUSY;
    }
    static MonitorCoordinator.Reason capturePauseReason() {
        FarmAccessibilityService service = instance;
        if (service == null || !service.alive()) return MonitorCoordinator.Reason.ACCESSIBILITY;
        if (!service.userUnlocked || !service.screenInteractive || service.screenLocked) return MonitorCoordinator.Reason.LOCKED;
        return service.foreground ? MonitorCoordinator.Reason.NONE : MonitorCoordinator.Reason.NOT_TARGET;
    }
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if (destroyed) return;
        FarmAccessibilityService previous = instance;
        if (previous != null) { MonitorForegroundService.accessibilityChanged(false); previous.retire(); }
        instance = this; isConnected = true; isRunning = false;
        lifecycle(LifecycleHistory.Event.ACCESS_CONNECT, LifecycleHistory.Reason.NONE);
        entryDebug = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        entryFeedback.enable(entryDebug);
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
        MonitorForegroundService.accessibilityChanged(true);
    }
    private void setRunning(boolean start) {
        if (!alive()) return;
        if (!start) { stopSession(true); return; }
        if (isRunning) return;
        if (capture.captureBusy()) { lifecycle(LifecycleHistory.Event.ACCESS_START_REJECT, LifecycleHistory.Reason.CAPTURE_BUSY); lastStatus = CAPTURE_BLOCKED; reason = lastStatus; publish(); return; }
        DurableFrameEngine e = engine(this);
        if (!e.start(listener)) { lifecycle(LifecycleHistory.Event.ACCESS_START_REJECT, LifecycleHistory.Reason.ENGINE_BUSY); lastStatus = "识别任务未结束，请等待完成后重试"; reason = lastStatus; publish(); return; }
        runEpoch = e.epoch(); shizukuMode = ShizukuTaskGate.isEnabled(this); isRunning = true;
        lifecycle(LifecycleHistory.Event.ACCESS_START_OK, LifecycleHistory.Reason.NONE);
        entryFeedback.start(shizukuMode ? "SHIZUKU" : "USAGE", SystemClock.elapsedRealtime());
        if (shizukuMode) ShizukuTaskGate.get(this).start();
        announcementPolicy.reset();
        entryFeedback.reset(EntryFeedbackDiagnostics.Event.ANNOUNCEMENT_RESET, SystemClock.elapsedRealtime());
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("monitoringEnabled", true).apply();
        newCaptureSession(); foreground = false; policy.invalidate();
        reason = shizukuMode ? "等待 Shizuku 前台任务" : "等待微信前台推断"; lastStatus = reason; updateForeground(); publish(); schedule();
    }
    private void stopSession(boolean stopEngine) {
        if (!alive()) return;
        if (isRunning) lifecycle(LifecycleHistory.Event.ACCESS_STOP, LifecycleHistory.Reason.NONE);
        if (stopEngine && engine != null && isRunning && engine.epoch() == runEpoch) engine.stop();
        if (shizukuMode) ShizukuTaskGate.get(this).stop();
        isRunning = false; handler.removeCallbacks(sample); containerPolicy.clear(); foregroundKey = null; candidateClass = null; policy.invalidate();
        leaveScene(); foreground = false; newCaptureSession();
        announcementPolicy.reset();
        entryFeedback.reset(EntryFeedbackDiagnostics.Event.ANNOUNCEMENT_RESET, SystemClock.elapsedRealtime());
        entryFeedback.stop(SystemClock.elapsedRealtime());
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("monitoringEnabled", false).apply();
        reason = "已停止"; lastStatus = reason; publish();
    }
    private void retire() {
        if (alive()) { stopSession(true); detachListener(); }
        handler.removeCallbacks(sample);
        if (receiverRegistered) { try { unregisterReceiver(screenReceiver); } catch (IllegalArgumentException ignored) { } receiverRegistered = false; }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() {
        if (!alive()) return;
        lifecycle(LifecycleHistory.Event.ACCESS_INTERRUPT, LifecycleHistory.Reason.INTERRUPT);
        if (!MonitorRuntimePolicy.interruptRequiresSuspension(isRunning)) return;
        long revision = monitorRevision; MonitorForegroundService owner = monitorOwner;
        stopSession(true);
        reason = "服务中断，请手动重试监测"; lastStatus = reason; publish();
        if (owner != null) owner.onMonitorFailure(revision, MonitorCoordinator.Reason.UNEXPECTED_STOP);
    }
    @Override public boolean onUnbind(Intent intent) {
        if (alive()) lifecycle(LifecycleHistory.Event.ACCESS_UNBIND, LifecycleHistory.Reason.UNBIND);
        if (alive()) { stopSession(true); detachListener(); isConnected = false; instance = null; lastStatus = "服务断开，请重新启动监测"; MonitorForegroundService.accessibilityChanged(false); }
        return super.onUnbind(intent);
    }
    private void newCaptureSession() { captureSession = UUID.randomUUID().toString(); captureSequence = 0; acceptedSequences.clear(); }
    private void readDeviceState() {
        liveDiagnostics.deviceRead(SystemClock.elapsedRealtime());
        usagePermission = false; screenInteractive = false; screenLocked = true; userUnlocked = false;
        try {
            UserManager user = (UserManager) getSystemService(USER_SERVICE);
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            userUnlocked = user != null && user.isUserUnlocked();
            screenInteractive = power != null && power.isInteractive();
            screenLocked = keyguard == null || keyguard.isKeyguardLocked();
        } catch (RuntimeException ignored) { }
    }
    private UsageForegroundPolicy.Candidate inferForeground() {
        readDeviceState();
        usageState = UsageForegroundPolicy.State.UNKNOWN;
        try {
            usagePermission = UsageAccessGate.hasPermission(this);
            if (!usagePermission || !userUnlocked || !screenInteractive || screenLocked) return null;
            UsageStatsManager manager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            if (manager == null) return null;
            long end = System.currentTimeMillis();
            UsageEvents events = manager.queryEvents(end - 24L * 60 * 60 * 1000, end);
            if (events == null) return null;
            UsageForegroundPolicy inference = new UsageForegroundPolicy();
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                if (!events.getNextEvent(event)) return null;
                int type = event.getEventType();
                int action;
                if (type == UsageEvents.Event.DEVICE_SHUTDOWN || type == UsageEvents.Event.DEVICE_STARTUP) action = UsageForegroundPolicy.RESET;
                else if (type == UsageEvents.Event.ACTIVITY_RESUMED) action = UsageForegroundPolicy.RESUMED;
                else if (type == UsageEvents.Event.ACTIVITY_PAUSED) action = UsageForegroundPolicy.PAUSED;
                else if (type == UsageEvents.Event.ACTIVITY_STOPPED) action = UsageForegroundPolicy.STOPPED;
                else continue;
                inference.event(event.getTimeStamp(), event.getPackageName(), event.getClassName(), action);
            }
            usageState = inference.state(usagePermission, userUnlocked, screenInteractive, screenLocked);
            return inference.candidate(usagePermission, userUnlocked, screenInteractive, screenLocked);
        } catch (RuntimeException e) { return null; }
    }
    private boolean updateForeground() {
        if (!alive()) return false;
        if (isRunning && shizukuMode != ShizukuTaskGate.isEnabled(this)) {
            lifecycle(LifecycleHistory.Event.ACCESS_STOP, LifecycleHistory.Reason.SOURCE_CHANGED);
            long revision = monitorRevision; MonitorForegroundService owner = monitorOwner;
            stopSession(true); reason = "前台来源已更改，监测已停止，请重新启动"; lastStatus = reason; publish();
            if (owner != null) owner.onMonitorFailure(revision, MonitorCoordinator.Reason.SOURCE_CHANGED);
            return false;
        }
        UsageForegroundPolicy.Candidate candidate = null;
        String key = null, className = null;
        if (isRunning && shizukuMode) {
            readDeviceState();
            ShizukuTaskGate gate = ShizukuTaskGate.get(this);
            shizukuSnapshot = gate.snapshot();
            if (gate.hasPermission() && shizukuSnapshot.age <= 1500
                    && "NON_MATCH".equals(shizukuSnapshot.state)) {
                announcementPolicy.confirmedDeparture();
                entryFeedback.budgetReset();
                if (foreground) entryFeedback.reset(EntryFeedbackDiagnostics.Event.DEPARTURE_RESET, SystemClock.elapsedRealtime());
            }
            key = ForegroundSourcePolicy.key(true, null, shizukuSnapshot.match,
                    shizukuSnapshot.age <= 1500, gate.hasPermission(), userUnlocked, screenInteractive,
                    screenLocked, shizukuSnapshot.key, shizukuSnapshot.component);
            if (key != null) className = shizukuSnapshot.component;
        } else if (isRunning) {
            shizukuSnapshot = null;
            candidate = inferForeground();
            key = ForegroundSourcePolicy.key(false, candidate == null ? null : candidate.key(), false,
                    false, usagePermission, userUnlocked, screenInteractive, screenLocked, null, null);
            if (key != null) className = candidate.className;
        }
        boolean now = key != null;
        boolean changed;
        if (shizukuMode) {
            changed = !java.util.Objects.equals(foregroundKey, key);
        } else {
            boolean departed = usageState == UsageForegroundPolicy.State.CONFIRMED_NON_CANDIDATE;
            changed = containerPolicy.observe(key, departed);
            if (departed) {
                announcementPolicy.confirmedDeparture();
                entryFeedback.budgetReset();
                if (foreground) entryFeedback.reset(EntryFeedbackDiagnostics.Event.DEPARTURE_RESET, SystemClock.elapsedRealtime());
                if (foreground) leaveScene();
                foreground = false; foregroundKey = null; candidateClass = null;
                policy.invalidate();
            }
        }
        if (changed) {
            if (foreground) leaveScene();
            if (shizukuMode) containerPolicy.clear();
            if (!shizukuMode) { announcementPolicy.confirmedDeparture(); entryFeedback.reset(EntryFeedbackDiagnostics.Event.CHANGE_RESET, SystemClock.elapsedRealtime()); }
            newCaptureSession(); policy.invalidate();
            if (now) {
                sceneEnters++;
                containerPolicy.enter(captureSession, runEpoch, shizukuMode);
                entryFeedback.enter(containerPolicy.state().name(), SystemClock.elapsedRealtime());
            }
        }
        boolean wasForeground = foreground;
        foreground = now; foregroundKey = key; candidateClass = className;
        entryFeedback.foreground(now, containerPolicy.state().name());
        if (changed || wasForeground != now) {
            reason = now ? (shizukuMode ? "微信小程序容器观察中" : containerPolicy.state() == ContainerCapturePolicy.State.FAILED
                    ? "USAGE_VERIFY_REJECTED：首图验证未通过，停止截图" : containerPolicy.state() == ContainerCapturePolicy.State.VERIFIED
                    ? "USAGE_VERIFY_READY：身份验证通过，正在记录" : "USAGE_VERIFY_PENDING：等待首图身份核验") : shizukuMode
                    ? "等待 Shizuku 前台任务，停止截图，旧图继续识别"
                    : "等待微信容器或使用情况权限，停止截图，旧图继续识别";
            lastStatus = reason; publish();
        }
        return now;
    }
    private void schedule() {
        if (!alive()) return;
        handler.removeCallbacks(sample);
        if (!isRunning) return;
        if (capture.captureBusy()) { handler.postDelayed(sample, 1000); return; }
        if (!foreground || !containerPolicy.canRequest() || engine == null || !engine.canCapture()) { handler.postDelayed(sample, 1000); return; }
        long now = SystemClock.elapsedRealtime();
        handler.postDelayed(sample, Math.max(0, Math.max(policy.next(now), nextCaptureAllowed) - now));
    }
    private void sample() {
        liveDiagnostics.sample(SystemClock.elapsedRealtime());
        if (!alive() || !isRunning) return;
        if (!updateForeground()) { schedule(); return; }
        if (!containerPolicy.canRequest() || capture.captureBusy() || engine == null || !engine.canCapture()) { schedule(); return; }
        long now = SystemClock.elapsedRealtime();
        long next = Math.max(policy.next(now), nextCaptureAllowed);
        if (next > now) { handler.postDelayed(sample, next - now); return; }
        long uptime = SystemClock.uptimeMillis(), wall = System.currentTimeMillis();
        long request = capture.beginCapture(uptime);
        if (request == 0) return;
        captureAttempt = LifecycleDiagnostics.current(this);
        captureRevision = monitorRevision; captureOwner = monitorOwner;
        String session = captureSession; long sequence = ++captureSequence;
        long epoch = runEpoch;
        if (!containerPolicy.begin(session, epoch, sequence)) { capture.endCapture(request); schedule(); return; }
        if (!currentCapture(session, epoch, sequence, request)) { failProbe(session, epoch, sequence, EntryFeedbackDiagnostics.Reason.STALE_RESULT); capture.endCapture(request); schedule(); return; }
        if (containerPolicy.pending(session, epoch, sequence)) entryFeedback.probe(containerPolicy.state().name(), SystemClock.elapsedRealtime());
        policy.captured(now); armWatchdog(request, uptime);
        try {
            if (Build.VERSION.SDK_INT >= 30) takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback(session, epoch, sequence, request, uptime, wall, captureAttempt));
        } catch (RuntimeException e) {
            LifecycleDiagnostics.event(this, LifecycleDiagnostics.current(this), LifecycleHistory.Event.CAPTURE_FAILURE, LifecycleHistory.Reason.EXCEPTION, e);
            containerPolicy.screenshotFailure(session, epoch, sequence, ContainerCapturePolicy.CaptureIssue.SCREENSHOT_FAILED);
            failProbe(session, epoch, sequence, EntryFeedbackDiagnostics.Reason.SCREENSHOT_FAILURE); capture.endCapture(request); errors++; reason = "USAGE_VERIFY_FAILED：截图请求失败"; lastStatus = reason; publish(); schedule();
        }
    }
    @android.annotation.TargetApi(30)
    private TakeScreenshotCallback callback(String session, long epoch, long sequence, long request, long requestUptime, long requestWall, long attempt) {
        return new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer(); Bitmap hardware = null, copy = null;
                boolean current = currentCapture(session, epoch, sequence, request);
                try {
                    if (!current) { failProbe(session, epoch, sequence, EntryFeedbackDiagnostics.Reason.STALE_RESULT); return; }
                    long now = SystemClock.uptimeMillis(), wall = System.currentTimeMillis();
                    hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    if (hardware == null) throw new IllegalStateException("empty screenshot");
                    copy = hardware.copy(Bitmap.Config.ARGB_8888, false);
                    if (copy == null) throw new IllegalStateException("copy failed");
                    DiskFrameStore.Frame frame = new DiskFrameStore.Frame();
                    frame.sessionId = session; frame.captureSequence = sequence; frame.windowId = -1;
                    frame.captureMethod = shizukuMode ? "accessibility_display_shizuku"
                            : containerPolicy.pending(session, epoch, sequence) ? FrameBusinessAdmission.USAGE_PROBE
                            : "accessibility_display_usage";
                    frame.contextCertain = true; // Foreground inference is not exclusive visibility.
                    frame.captureUptimeMs = FramePipeline.captureUptime(result.getTimestamp(), requestUptime, now);
                    frame.captureWallMs = FramePipeline.captureWall(result.getTimestamp(), requestUptime, requestWall, now, wall);
                    frame.width = copy.getWidth(); frame.height = copy.getHeight();
                    hardware.recycle(); hardware = null; buffer.close(); buffer = null;
                    if (!currentCapture(session, epoch, sequence, request)) { failProbe(session, epoch, sequence, EntryFeedbackDiagnostics.Reason.STALE_RESULT); return; }
                    engine(FarmAccessibilityService.this).accept(copy, frame); acceptedSequences.add(sequence); copy = null; screenshots++;
                    if (shizukuMode) announceIfAllowed();
                } catch (RuntimeException e) {
                    LifecycleDiagnostics.event(FarmAccessibilityService.this, attempt, LifecycleHistory.Event.CAPTURE_FAILURE, LifecycleHistory.Reason.EXCEPTION, e);
                    if (current) containerPolicy.screenshotFailure(session, epoch, sequence, ContainerCapturePolicy.CaptureIssue.SCREENSHOT_FAILED);
                    failProbe(session, epoch, sequence, EntryFeedbackDiagnostics.Reason.SCREENSHOT_FAILURE); errors++;
                    if (current && alive()) { reason = "USAGE_VERIFY_FAILED：截图保存交接失败"; lastStatus = reason; }
                } finally {
                    boolean timedOut = capture.captureTimedOut() && capture.captureId() == request;
                    boolean released = capture.endCapture(request);
                    LifecycleDiagnostics.captureReleased(FarmAccessibilityService.this, attempt, timedOut, released);
                    if (hardware != null) hardware.recycle(); if (buffer != null) buffer.close(); if (copy != null) copy.recycle();
                    if (alive()) { publish(); if (isRunning) schedule(); }
                }
            }
            @Override public void onFailure(int code) {
                boolean current = currentCapture(session, epoch, sequence, request);
                boolean timedOut = capture.captureTimedOut() && capture.captureId() == request;
                boolean retry = current && code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                        && containerPolicy.retryRateLimit(session, epoch, sequence, SystemClock.elapsedRealtime());
                if (current && !retry) containerPolicy.screenshotFailure(session, epoch, sequence,
                        code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ? ContainerCapturePolicy.CaptureIssue.SCREENSHOT_RATE_LIMIT
                                : ContainerCapturePolicy.CaptureIssue.SCREENSHOT_FAILED);
                if (!retry) failProbe(session, epoch, sequence, EntryFeedbackDiagnostics.Reason.SCREENSHOT_FAILURE);
                boolean released = capture.endCapture(request);
                LifecycleDiagnostics.event(FarmAccessibilityService.this, attempt, LifecycleHistory.Event.CAPTURE_FAILURE, LifecycleHistory.Reason.NONE, null);
                LifecycleDiagnostics.captureReleased(FarmAccessibilityService.this, attempt, timedOut, released);
                if (current && alive()) {
                    errors++;
                    if (code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) nextCaptureAllowed = Math.max(nextCaptureAllowed,
                            retry ? containerPolicy.retryNotBefore() : SystemClock.elapsedRealtime() + 2000);
                    reason = (retry ? "USAGE_VERIFY_PENDING：截图过于频繁，将重试 " : shizukuMode ? "截图错误代码 " : "USAGE_VERIFY_FAILED：截图错误代码 ") + code; lastStatus = reason; publish(); schedule();
                } else if (alive() && isRunning) { publish(); schedule(); }
            }
        };
    }
    private void failProbe(String session, long epoch, long sequence, EntryFeedbackDiagnostics.Reason reason) {
        boolean pending = containerPolicy.pending(session, epoch, sequence);
        containerPolicy.fail(session, epoch, sequence);
        if (pending) entryFeedback.failure(reason, containerPolicy.state().name(), SystemClock.elapsedRealtime());
    }
    private boolean currentCapture(String session, long epoch, long sequence, long request) {
        if (!CaptureAdmissionPolicy.allowed(capture.captureUsable(request), alive(), isRunning,
                foreground, session, captureSession) || epoch != runEpoch) return false;
        boolean inferred = updateForeground();
        return CaptureAdmissionPolicy.allowed(capture.captureUsable(request), alive(), isRunning,
                inferred && epoch == runEpoch && containerPolicy.permitsHandoff(session, epoch, sequence), session, captureSession);
    }
    private void detachListener() {
        // detach compares the listener registration epoch, not the fence's post-stop epoch.
        if (alive() && engine != null && runEpoch != 0) engine.detach(listener, runEpoch);
    }
    private void armWatchdog(long request, long started) {
        long attempt = captureAttempt;
        long revision = captureRevision; MonitorForegroundService owner = captureOwner;
        handler.postDelayed(() -> {
            if (!alive() || !capture.timeoutCapture(request, SystemClock.uptimeMillis())) return;
            LifecycleDiagnostics.event(this, attempt, LifecycleHistory.Event.CAPTURE_WATCHDOG, LifecycleHistory.Reason.WATCHDOG, null);
            if (isRunning && monitorRevision == revision && monitorOwner == owner) stopSession(true);
            reason = CAPTURE_BLOCKED; lastStatus = reason; publish();
            if (owner != null) owner.onMonitorFailure(revision, MonitorCoordinator.Reason.WATCHDOG);
        }, Math.max(0, FramePipeline.REQUEST_TIMEOUT_MS - (SystemClock.uptimeMillis() - started)));
    }
    private void leaveScene() {
        if (foreground) sceneExits++;
    }
    private void publish() {
        if (!alive()) return;
        OcrProgress p = getOcrProgress();
        diagnostics = "截图 " + screenshots + " · 磁盘待识别 " + p.queued + " · 失败 " + p.failedCount
                + " · 待清理 " + p.cleanupPendingCount + " · 保留 " + p.retainedBytes + "B · 写入 " + p.writing
                + " · 暂停原因 " + p.pauseReason + " · OCR " + p.recognizing + " · 截图错误 " + errors
                + "\n来源 " + (shizukuMode ? "Shizuku" : "Usage")
                + (shizukuMode ? " · Shizuku " + ShizukuTaskGate.get(this).status()
                    + " · 快照年龄ms " + (shizukuSnapshot == null ? "无" : shizukuSnapshot.age)
                    + " · 快照原因 " + (shizukuSnapshot == null ? "无" : shizukuSnapshot.reason) : "")
                + " · 使用情况权限 " + usagePermission + " · 用户解锁 " + userUnlocked
                + " · 屏幕开启 " + screenInteractive + " · 锁屏 " + screenLocked
                + " · 推断微信前台 " + foreground + " · 进入/退出 " + sceneEnters + "/" + sceneExits
                + " · 容器 " + (candidateClass == null ? "无" : candidateClass)
                + " · 首图验证 " + (shizukuMode ? "不适用" : containerPolicy.state())
                + " · 识别反馈 " + recognizedEvents + " · 新增 " + added + " · 处理失败 " + frameFailures + " · 保存失败 " + saveFailures + "\n" + reason;
        dumpSnapshot = "pid=" + Process.myPid() + " instance=" + instanceSequence + " owner=" + alive()
                + " running=" + isRunning + " foreground=" + foreground + " captureInFlight=" + capture.captureBusy()
                + " captureTimeout=" + capture.captureTimedOut() + " diskQueued=" + p.queued + " failed=" + p.failedCount
                + " cleanup=" + p.cleanupPendingCount + " retained=" + p.retainedBytes + " writing=" + p.writing
                + " pauseReason=" + p.pauseReason + " recognizing=" + p.recognizing + " errors=" + errors
                 + " source=" + (shizukuMode ? "shizuku" : "usage")
                 + (shizukuMode ? " shizukuStatus=" + ShizukuTaskGate.get(this).status()
                     + " snapshotAgeAtPublish=" + (shizukuSnapshot == null ? "none" : shizukuSnapshot.age) : "")
                + " usagePermission=" + usagePermission + " screenInteractive=" + screenInteractive
                + " screenLocked=" + screenLocked + " userUnlocked=" + userUnlocked;
        liveDiagnostics.published(SystemClock.elapsedRealtime());
    }
    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            writer.println("local " + liveDiagnostics.serviceSnapshot(SystemClock.elapsedRealtime()));
            writer.println("runtime running=" + isRunning + " connected=" + isConnected
                    + " captureInFlight=" + capture.captureBusy() + " captureTimeout=" + capture.captureTimedOut()
                    + " foregroundServiceErrorPresent=" + !MonitorForegroundService.lastError.isEmpty());
            MonitorCoordinator.Snapshot restore = MonitorCoordinator.getSnapshot(this);
            writer.println("restore revision=" + restore.revision + " enrolled=" + restore.enrolled + " wanted=" + restore.wanted
                    + " state=" + restore.state + " reason=" + restore.reason + " fgs=" + restore.foregroundActive + " running=" + restore.monitoringRunning
                    + " " + MonitorCoordinator.diagnostics());
            writer.println(entryFeedback.snapshot(SystemClock.elapsedRealtime()));
            writer.println("lifecycle generation|attempt|elapsedMs|wallMs|event|reason|bits|exceptionClass; bits: ENGINE_REJECT=running,frameActive,nativeActive,transaction; other events=connected,running,fgs,reserved\n" + LifecycleDiagnostics.snapshot(this));
            writer.println("cachedPublished " + dumpSnapshot);
            ShizukuTaskGate gate = ShizukuTaskGate.peek();
            writer.println(gate == null ? "gate=not_initialized" : gate.debugSnapshot());
        }
    }
    @Override public void onDestroy() {
        if (alive()) lifecycle(LifecycleHistory.Event.ACCESS_DESTROY, LifecycleHistory.Reason.DESTROY);
        if (alive()) { stopSession(true); detachListener(); isConnected = false; instance = null; lastStatus = "服务断开，请重新启动监测"; MonitorForegroundService.accessibilityChanged(false); }
        destroyed = true; handler.removeCallbacks(sample);
        if (receiverRegistered) { try { unregisterReceiver(screenReceiver); } catch (IllegalArgumentException ignored) { } receiverRegistered = false; }
        super.onDestroy();
    }
}
