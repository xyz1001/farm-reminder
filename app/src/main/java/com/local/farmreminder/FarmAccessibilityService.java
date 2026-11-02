package com.local.farmreminder;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/** User-controlled, local-only screenshots; all mutable instance state is on the main thread. */
public class FarmAccessibilityService extends AccessibilityService {
    public static volatile boolean isConnected, isRunning;
    public static volatile String lastStatus = "无障碍服务未连接", diagnostics = "本次会话尚无数据";
    private static volatile FarmAccessibilityService instance;
    private static final AtomicLong NEXT_INSTANCE = new AtomicLong();
    // Lifecycle callbacks and these counters are confined to the main thread.
    private static long connectionCount, unbindCount, destroyCount, lifecycleSequence;
    private final long instanceSequence = NEXT_INSTANCE.incrementAndGet();
    private volatile String dumpSnapshot = "snapshot not yet available";
    private long connectedSequence, unbindSequence, destroySequence;
    private long connectedAt = -1, unbindAt = -1, destroyAt = -1, lastSummaryLog = -5000;
    private long ocrFailures;
    private String foregroundPackage = "null", lastError = "none";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = handler::post;
    private final SamplingPolicy policy = new SamplingPolicy();
    private final RecognitionLogic logic = new RecognitionLogic();
    private final ScenePresence scene = new ScenePresence();
    private final FeedbackEventPolicy feedback = new FeedbackEventPolicy();
    private long sceneEnters, sceneExits, recognizedEvents, saveFailures;
    private TextRecognizer recognizer;
    private static final FramePipeline<Bitmap> pipeline = new FramePipeline<>(Bitmap::recycle);
    private boolean foreground, farm, destroyed, receiverRegistered, closeRecognizerWhenIdle;
    private long nextCaptureAllowed, lastCaptureUptime = -1, captureInterval, lastProcessedRequest;
    private static long ocrStartedUptime;
    private static FarmAccessibilityService ocrOwner;
    private static final String OCR_BLOCKED = "识别任务未结束，暂不可启动；等待完成或重启本提醒应用进程";
    private static final String CAPTURE_BLOCKED = "截图任务未结束，暂不可启动；等待完成或重启本提醒应用进程";
    private int windowId = -1;
    private long farmSeen, screenshots, clicks, contents, ocr, ocrMs, paused, rejectedFriend,
            rejectedTime, candidates, added, duplicate, errors;
    private String reason = "等待启动";
    private final Runnable sample = this::sample;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { if (alive()) { updateForeground(); schedule(); } }
    };

    private boolean alive() { return InstanceOwner.alive(instance, this, destroyed); }

    private boolean debugEnabled() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    // Only immutable, anonymous data captured on the main thread is read by dump's binder thread.
    private void snapshot() {
        if (!debugEnabled()) return;
        dumpSnapshot = "pid=" + Process.myPid() + " instance=" + instanceSequence
                + " generation=" + policy.generation() + " owner=" + (instance == this)
                + " destroyed=" + destroyed + " connected=" + (instance == this && isConnected)
                + " running=" + (instance == this && isRunning) + " foreground=" + foreground
                + " farm=" + farm + " windowId=" + windowId + " package=" + foregroundPackage
                + " captureInFlight=" + pipeline.captureBusy() + " ocrInFlight=" + pipeline.ocrBusy()
                + " captureTimeout=" + pipeline.captureTimedOut() + " ocrTimeout=" + pipeline.ocrTimedOut()
                + " pendingCount=" + pipeline.pendingCount() + " pendingBytes=" + pipeline.pendingBytes()
                + " maxQueueObserved=" + pipeline.maxQueueObserved + " dropFull=" + pipeline.dropFull
                + " dropExpired=" + pipeline.dropExpired + " dropInvalidated=" + pipeline.dropInvalidated
                + " captureInterval=" + captureInterval + " lastQueueWaitMs=" + pipeline.lastQueueWaitMs
                + " screenshots=" + screenshots + " ocr=" + ocr
                + " ocrMs=" + ocrMs + " ocrFailures=" + ocrFailures + " errors=" + errors
                + " lastError=" + lastError + " rejectedFriend=" + rejectedFriend
                + " rejectedTime=" + rejectedTime + " candidates=" + candidates
                + " added=" + added + " duplicate=" + duplicate + " reason=" + reason
                + " scenePresent=" + scene.present() + " sceneEnters=" + sceneEnters + " sceneExits=" + sceneExits
                + " recognizedEvents=" + recognizedEvents + " saveFailures=" + saveFailures
                + " connectedSeq=" + connectedSequence + " connectedElapsedMs=" + connectedAt
                + " unbindSeq=" + unbindSequence + " unbindElapsedMs=" + unbindAt
                + " destroySeq=" + destroySequence + " destroyElapsedMs=" + destroyAt
                + " processConnections=" + connectionCount + " processUnbinds=" + unbindCount
                + " processDestroys=" + destroyCount;
    }

    private void diagLog(String event) {
        if (debugEnabled()) Log.d("FarmDiag", "instance=" + instanceSequence + " " + event);
    }

    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (!debugEnabled()) return;
        writer.println(dumpSnapshot);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        int result = super.onStartCommand(intent, flags, startId);
        snapshot();
        return result;
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
        boolean start = enabled && Build.VERSION.SDK_INT >= 30 && service != null && isConnected;
        if (service == null && !start) {
            context.getApplicationContext().getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean("monitoringEnabled", false).apply();
            isRunning = false;
            lastStatus = enabled ? "服务未连接或系统版本不支持截图" : "已停止";
        }
        if (service != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) service.setRunning(start);
            else service.handler.post(() -> service.setRunning(start));
        }
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if (destroyed) return;
        connectedSequence = ++lifecycleSequence; connectionCount++;
        connectedAt = SystemClock.elapsedRealtime();
        FarmAccessibilityService previous = instance;
        if (previous == this) leaveScene();
        handler.removeCallbacks(sample); policy.invalidate(); pipeline.clear(); logic.reset(); farm = false; foreground = false; windowId = -1;
        instance = this; isConnected = true; isRunning = false;
        if (previous != null && previous != this) { previous.retire(); previous.snapshot(); }
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("monitoringEnabled", false).apply();
        closeRecognizerWhenIdle = false;
        if (recognizer == null && !pipeline.ocrBusy()) recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_USER_PRESENT);
        if (!receiverRegistered) {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenReceiver, filter);
            receiverRegistered = true;
        }
        pipeline.updateTimeouts(SystemClock.uptimeMillis());
        if (pipeline.ocrBusy()) armWatchdog(false, pipeline.ocrId(), pipeline.ocrStarted());
        if (pipeline.captureBusy()) armWatchdog(true, pipeline.captureId(), pipeline.captureStarted());
        lastStatus = pipeline.startAllowed() ? "服务已连接，请手动启动监测" : blockedStatus();
        reason = lastStatus;
        snapshot(); diagLog("connected");
    }

    private void setRunning(boolean running) {
        if (!alive()) return;
        leaveScene();
        pipeline.updateTimeouts(SystemClock.uptimeMillis());
        running = running && pipeline.startAllowed();
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("monitoringEnabled", running).apply();
        handler.removeCallbacks(sample); policy.invalidate(); pipeline.clear(); logic.reset(); farm = false; foreground = false; windowId = -1; foregroundPackage = "null";
        isRunning = running;
        lastStatus = !pipeline.startAllowed() ? blockedStatus() : (running ? "等待微信前台" : "已停止");
        reason = lastStatus; publish();
        diagLog(running ? "start" : "stop");
        if (running) { updateForeground(); schedule(); }
    }

    private static String blockedStatus() { return pipeline.ocrTimedOut() ? OCR_BLOCKED : CAPTURE_BLOCKED; }

    private static void checkTimeouts() {
        if (!pipeline.updateTimeouts(SystemClock.uptimeMillis())) return;
        FarmAccessibilityService owner = instance;
        if (owner != null && owner.alive()) owner.setRunning(false);
    }

    private static void recoveredAfterTimeout(boolean timedOut) {
        FarmAccessibilityService owner = instance;
        if (timedOut && pipeline.startAllowed() && owner != null && owner.alive() && !isRunning) {
            lastStatus = "任务已结束，请手动启动监测"; owner.reason = lastStatus; owner.publish();
        }
    }

    private void retire() {
        leaveScene();
        handler.removeCallbacks(sample); policy.invalidate(); pipeline.clear(); logic.reset(); foreground = false; farm = false;
        if (receiverRegistered) { try { unregisterReceiver(screenReceiver); } catch (IllegalArgumentException ignored) { } receiverRegistered = false; }
        if (recognizer != null) { if (ocrOwner == this) closeRecognizerWhenIdle = true; else { recognizer.close(); recognizer = null; } }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!alive() || !isRunning || event == null) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) clicks++;
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) contents++;
        updateForeground();
        if (foreground && farm && event.getPackageName() != null && "com.tencent.mm".contentEquals(event.getPackageName())
                && (type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
            policy.event(SystemClock.elapsedRealtime(), farm);
        }
        publish(); schedule();
    }

    @Override public void onInterrupt() {
        if (!alive()) return;
        setMonitoring(this, false); lastStatus = pipeline.startAllowed() ? "服务中断，请重新启动" : blockedStatus();
    }

    @Override public boolean onUnbind(Intent intent) {
        unbindSequence = ++lifecycleSequence; unbindCount++;
        unbindAt = SystemClock.elapsedRealtime();
        if (alive()) {
            setRunning(false);
            isConnected = false; instance = null;
            lastStatus = pipeline.startAllowed() ? "服务断开，请重新启动监测" : blockedStatus();
        }
        snapshot(); diagLog("unbind");
        return super.onUnbind(intent);
    }

    private int foregroundWindow() {
        foregroundPackage = "null";
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (power == null || !power.isInteractive() || keyguard == null || keyguard.isKeyguardLocked()) return -1;
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active == null) return -1;
        try {
            CharSequence pkg = active.getPackageName();
            foregroundPackage = pkg == null ? "null" : ("com.tencent.mm".contentEquals(pkg) ? "com.tencent.mm" : "other");
            if (!"com.tencent.mm".contentEquals(active.getPackageName())) return -1;
            int activeId = active.getWindowId();
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window.getId() == activeId && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                        && window.isActive() && window.isFocused()) return activeId;
            }
            return -1;
        } finally { active.recycle(); }
    }

    private boolean updateForeground() {
        if (!alive()) return false;
        int current;
        try { current = isRunning ? foregroundWindow() : -1; }
        catch (RuntimeException e) { current = -1; }
        boolean now = current != -1;
        if (foreground != now || (now && current != windowId)) {
            if (!now) leaveScene();
            else scene.breakStreak();
            policy.invalidate(); pipeline.clear(); logic.reset(); farm = false;
            if (!now && foreground) paused++;
            foreground = now; windowId = current;
            reason = now ? "微信前台，低频探测场景" : "非微信前台或屏幕锁定，暂停";
            lastStatus = reason; publish();
        }
        snapshot();
        return now;
    }

    private void schedule() {
        if (!alive()) return;
        handler.removeCallbacks(sample);
        snapshot();
        if (!isRunning) return;
        if (!foreground) { handler.postDelayed(sample, 2000); return; }
        long now = SystemClock.elapsedRealtime();
        if (farm && now - farmSeen > 10000) { farm = false; policy.clearBurst(); logic.reset(); }
        snapshot();
        if (pipeline.captureBusy()) return; // callback schedules the next capture
        handler.postDelayed(sample, Math.max(0, Math.max(policy.next(now, farm), nextCaptureAllowed) - now));
    }

    private void sample() {
        checkTimeouts();
        if (!alive() || !isRunning) return;
        if (!updateForeground()) { schedule(); return; }
        if (pipeline.captureBusy()) return;
        long now = SystemClock.elapsedRealtime();
        long next = Math.max(policy.next(now, farm), nextCaptureAllowed);
        if (next > now) { handler.postDelayed(sample, next - now); return; }
        int token = policy.generation(), target = windowId;
        long uptime = SystemClock.uptimeMillis(), wall = System.currentTimeMillis();
        long request = pipeline.beginCapture(uptime);
        if (request == 0) return;
        armWatchdog(true, request, uptime);
        if (lastCaptureUptime >= 0) captureInterval = uptime-lastCaptureUptime;
        lastCaptureUptime = uptime;
        policy.captured(now);
        try {
            if (Build.VERSION.SDK_INT >= 34) takeScreenshotOfWindow(target, mainExecutor, callback(token, target, request, uptime, wall));
            else if (Build.VERSION.SDK_INT >= 30) takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback(token, target, request, uptime, wall));
            else { pipeline.endCapture(request); lastStatus = "Android 11 以下不支持无障碍截图"; }
        } catch (RuntimeException e) { pipeline.endCapture(request); errors++; lastError = "screenshot_request"; reason = "截图请求失败"; lastStatus = reason; diagLog("screenshot request failed"); publish(); }
        schedule();
    }

    private boolean valid(int token, int target) {
        return alive() && updateForeground() && windowId == target && policy.valid(token, isRunning, foreground);
    }

    @android.annotation.TargetApi(30)
    private TakeScreenshotCallback callback(int token, int target, long request, long requestUptime, long requestWall) {
        return new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap hardware = null, copy = null;
                try {
                    checkTimeouts();
                    boolean timedOut = pipeline.captureTimedOut() && !pipeline.captureUsable(request);
                    boolean usable = pipeline.captureUsable(request);
                    if (!pipeline.endCapture(request)) return;
                    recoveredAfterTimeout(timedOut);
                    if (!usable || !valid(token, target)) return;
                    long callbackUptime = SystemClock.uptimeMillis(), callbackWall = System.currentTimeMillis();
                    long capturedUptime = FramePipeline.captureUptime(result.getTimestamp(), requestUptime, callbackUptime);
                    long capturedWall = FramePipeline.captureWall(result.getTimestamp(), requestUptime, requestWall, callbackUptime, callbackWall);
                    screenshots++;
                    hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    if (hardware == null) throw new IllegalStateException("empty screenshot");
                    copy = hardware.copy(Bitmap.Config.ARGB_8888, false);
                    if (copy == null) throw new IllegalStateException("copy failed");
                    pipeline.offer(new FramePipeline.Frame<>(copy, copy.getAllocationByteCount(), capturedWall,
                            capturedUptime, token, target, request), callbackUptime);
                    copy = null;
                } catch (RuntimeException e) { if (valid(token, target)) { errors++; lastError = "screenshot_processing"; logic.reset(); reason = "截图处理失败"; lastStatus = reason; diagLog("screenshot processing failed"); publish(); } }
                finally {
                    if (hardware != null) hardware.recycle();
                    buffer.close();
                    if (copy != null) copy.recycle();
                    FarmAccessibilityService owner = instance;
                    if (owner != null && owner.alive()) { owner.pumpOcr(); owner.publish(); owner.schedule(); }
                }
            }
            @Override public void onFailure(int code) {
                checkTimeouts();
                boolean timedOut = pipeline.captureTimedOut() && !pipeline.captureUsable(request);
                boolean usable = pipeline.captureUsable(request);
                if (!pipeline.endCapture(request)) return;
                recoveredAfterTimeout(timedOut);
                if (!alive()) { FarmAccessibilityService owner = instance; if (owner != null && owner.alive()) owner.schedule(); return; }
                if (usable && valid(token, target)) {
                    lastError = "screenshot_code_" + code;
                    diagLog("screenshot failure code=" + code);
                    if (code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) nextCaptureAllowed = SystemClock.elapsedRealtime()+2000;
                    errors++; logic.reset(); reason = "截图错误代码 " + code + "（安全窗口或系统限制可能阻止截图）"; lastStatus = reason; publish();
                }
                schedule();
            }
        };
    }

    private void pumpOcr() {
        checkTimeouts();
        if (!alive() || !isRunning || !foreground || pipeline.ocrBusy() || !pipeline.startAllowed()) return;
        if (recognizer == null) recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        FramePipeline.Frame<Bitmap> frame = pipeline.beginOcr(SystemClock.uptimeMillis());
        if (frame == null) return;
        if (!valid(frame.generation, frame.window)) { pipeline.finishOcr(frame.request); pumpOcr(); return; }
        ocrOwner = this;
        ocrStartedUptime = SystemClock.uptimeMillis();
        armWatchdog(false, frame.request, ocrStartedUptime);
        try {
            recognizer.process(InputImage.fromBitmap(frame.value, 0)).addOnCompleteListener(mainExecutor, task -> {
                try {
                    checkTimeouts();
                    if (!pipeline.ocrUsable(frame.request)) return;
                    if (!valid(frame.generation, frame.window)) return;
                    if (SystemClock.uptimeMillis()-frame.uptime > FramePipeline.TTL_MS) { pipeline.dropExpired++; logic.reset(); return; }
                    if (lastProcessedRequest + 1 != frame.request) logic.reset();
                    lastProcessedRequest = frame.request;
                    ocr++; ocrMs = SystemClock.uptimeMillis()-ocrStartedUptime;
                    if (!task.isSuccessful() || task.getResult() == null) {
                        ocrFailures++; lastError = "ocr_failed"; farm = false; policy.clearBurst(); logic.reset(); reason = "OCR失败"; lastStatus = reason;
                    } else processText(task.getResult(), frame.value.getWidth(), frame.value.getHeight(), frame.wall, frame.generation, frame.window);
                    publish();
                } catch (RuntimeException e) {
                    if (valid(frame.generation, frame.window)) { ocrFailures++; lastError = "ocr_processing"; farm = false; policy.clearBurst(); logic.reset(); reason = "OCR处理失败"; lastStatus = reason; publish(); }
                } finally { finishOcr(frame); }
            });
        } catch (RuntimeException e) {
            if (valid(frame.generation, frame.window)) { ocrFailures++; lastError = "ocr_start"; logic.reset(); reason = "OCR启动失败"; lastStatus = reason; publish(); }
            finishOcr(frame);
        }
    }

    private void armWatchdog(boolean capture, long request, long started) {
        long delay = Math.max(0, FramePipeline.REQUEST_TIMEOUT_MS - (SystemClock.uptimeMillis()-started));
        handler.postDelayed(() -> {
            if (!alive()) return;
            boolean timedOut = capture ? pipeline.timeoutCapture(request, SystemClock.uptimeMillis())
                    : pipeline.timeoutOcr(request, SystemClock.uptimeMillis());
            if (timedOut) {
                setRunning(false);
                lastError = capture ? "screenshot_timeout_inflight" : "ocr_timeout_inflight"; publish();
            }
        }, delay);
    }

    private void finishOcr(FramePipeline.Frame<Bitmap> frame) {
        boolean timedOut = pipeline.ocrTimedOut() && !pipeline.ocrUsable(frame.request);
        if (!pipeline.finishOcr(frame.request)) return;
        ocrStartedUptime = 0; ocrOwner = null;
        if (closeRecognizerWhenIdle && recognizer != null) { recognizer.close(); recognizer = null; }
        recoveredAfterTimeout(timedOut);
        FarmAccessibilityService owner = instance;
        if (owner != null && owner.alive()) { owner.pumpOcr(); owner.publish(); }
    }

    private void processText(Text text, int width, int height, long capturedAt, int token, int target) {
        if (!alive()) return;
        List<OcrLine> lines = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) for (Text.Line line : block.getLines()) {
            Rect box = line.getBoundingBox();
            if (box != null) lines.add(new OcrLine(line.getText(), box.left, box.top, box.right, box.bottom));
        }
        ScenePresence.Change change = scene.observe(ScenePresence.isFarmScene(lines, width, height));
        if (change == ScenePresence.Change.ENTER) { sceneEnters++; MonitorFeedback.entered(this); }
        else if (change == ScenePresence.Change.EXIT) { sceneExits++; feedback.reset(); MonitorFeedback.exited(this); }
        String friend = OcrFrameParser.friend(lines, width, height);
        Long countdown = OcrFrameParser.countdown(lines);
        if (friend != null) { farm = true; farmSeen = SystemClock.elapsedRealtime(); }
        else { farm = false; policy.clearBurst(); logic.reset(); }
        Long maturity = logic.observe(friend, countdown, capturedAt);
        if (friend == null) { rejectedFriend++; reason = "未确认农场或唯一昵称"; }
        else if (countdown == null || countdown <= 0) { rejectedTime++; reason = "无明确有效成熟时间（阶段时间不录入）"; }
        else if (maturity == null) { candidates++; reason = "候选等待连续帧确认"; }
        else if (valid(token, target)) {
            if (feedback.confirmed(friend, maturity)) { recognizedEvents++; MonitorFeedback.recognized(this); }
            int lead = getSharedPreferences("settings", MODE_PRIVATE).getInt("leadSeconds", 60);
            ReminderStore.AddResult result = new ReminderStore(this).addIfNew(friend, maturity, lead);
            if (result.outcome == SaveOutcome.DUPLICATE) { duplicate++; reason = "重复记录"; }
            else if (result.outcome == SaveOutcome.FAILED) { saveFailures++; lastError = "save_failed"; reason = "提醒保存失败"; MonitorFeedback.saveFailed(this); }
            else { added++; reason = "新增提醒"; MonitorFeedback.added(this); new AlarmScheduler(this).schedule(result.reminder); }
        }
        lastStatus = reason;
    }

    private void leaveScene() {
        if (scene.leave() == ScenePresence.Change.EXIT) { sceneExits++; MonitorFeedback.exited(this); }
        feedback.reset();
    }

    private void publish() {
        if (!alive()) return;
        diagnostics = "截图 " + screenshots + " · 点击事件 " + clicks + " · 内容事件 " + contents
                + " · OCR " + ocr + "（最近 " + ocrMs + "ms）\n暂停 " + paused + " · 昵称/场景未确认 " + rejectedFriend
                + " · 无成熟时间 " + rejectedTime + " · 候选 " + candidates + " · 新增 " + added
                + " · 重复 " + duplicate + " · 截图错误 " + errors
                + "\n截图中 " + pipeline.captureBusy() + " · OCR中 " + pipeline.ocrBusy() + " · 待识别 " + pipeline.pendingCount()
                + " / " + pipeline.pendingBytes() + "B · 队列峰值 " + pipeline.maxQueueObserved
                + " · 丢弃(满/过期/失效) " + pipeline.dropFull + "/" + pipeline.dropExpired + "/" + pipeline.dropInvalidated
                + " · 截图间隔 " + captureInterval + "ms · 排队 " + pipeline.lastQueueWaitMs + "ms"
                + "\n农场画面 " + scene.present() + " · 进入/退出 " + sceneEnters + "/" + sceneExits
                + " · 识别反馈 " + recognizedEvents + " · 保存失败 " + saveFailures + "\n" + reason;
        snapshot();
        long now = SystemClock.elapsedRealtime();
        if (debugEnabled() && now - lastSummaryLog >= 5000) {
            lastSummaryLog = now;
            diagLog("summary screenshots=" + screenshots + " ocr=" + ocr + " errors=" + errors
                    + " rejectedFriend=" + rejectedFriend + " rejectedTime=" + rejectedTime);
        }
    }

    @Override public void onDestroy() {
        boolean owner = alive();
        destroySequence = ++lifecycleSequence; destroyCount++;
        destroyAt = SystemClock.elapsedRealtime();
        if (owner) leaveScene();
        destroyed = true;
        if (owner) {
            isRunning = false; isConnected = false; instance = null;
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("monitoringEnabled", false).apply();
            lastStatus = pipeline.startAllowed() ? "服务断开，请重新启动监测" : blockedStatus();
        }
        handler.removeCallbacks(sample); policy.invalidate(); if (owner) pipeline.clear(); logic.reset();
        if (receiverRegistered) { try { unregisterReceiver(screenReceiver); } catch (IllegalArgumentException ignored) { } receiverRegistered = false; }
        if (recognizer != null) { if (ocrOwner == this) closeRecognizerWhenIdle = true; else { recognizer.close(); recognizer = null; } }
        snapshot(); diagLog("destroy");
        super.onDestroy();
    }
}
