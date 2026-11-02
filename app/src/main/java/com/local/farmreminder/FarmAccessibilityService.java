package com.local.farmreminder;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
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

/** User-controlled, local-only screenshots; all mutable instance state is on the main thread. */
public class FarmAccessibilityService extends AccessibilityService {
    public static volatile boolean isConnected, isRunning;
    public static volatile String lastStatus = "无障碍服务未连接", diagnostics = "本次会话尚无数据";
    private static volatile FarmAccessibilityService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = handler::post;
    private final SamplingPolicy policy = new SamplingPolicy();
    private final RecognitionLogic logic = new RecognitionLogic();
    private TextRecognizer recognizer;
    private boolean busy, foreground, farm, destroyed;
    private int windowId = -1;
    private long farmSeen, screenshots, clicks, contents, ocr, ocrMs, paused, rejectedFriend,
            rejectedTime, candidates, added, duplicate, errors;
    private String reason = "等待启动";
    private final Runnable sample = this::sample;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { if (alive()) { updateForeground(); schedule(); } }
    };

    private boolean alive() { return InstanceOwner.alive(instance, this, destroyed); }

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
        instance = this; isConnected = true; isRunning = false;
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("monitoringEnabled", false).apply();
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, filter);
        lastStatus = "服务已连接，请手动启动监测";
    }

    private void setRunning(boolean running) {
        if (!alive()) return;
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("monitoringEnabled", running).apply();
        handler.removeCallbacks(sample); policy.invalidate(); logic.reset(); farm = false; foreground = false; windowId = -1;
        isRunning = running;
        lastStatus = running ? "等待微信前台" : "已停止";
        reason = running ? "等待微信前台" : "已停止"; publish();
        if (running) { updateForeground(); schedule(); }
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
        setMonitoring(this, false); lastStatus = "服务中断，请重新启动";
    }

    @Override public boolean onUnbind(Intent intent) {
        if (alive()) {
            setRunning(false);
            isConnected = false; instance = null;
            lastStatus = "服务断开，请重新启动监测";
        }
        return super.onUnbind(intent);
    }

    private int foregroundWindow() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (power == null || !power.isInteractive() || keyguard == null || keyguard.isKeyguardLocked()) return -1;
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active == null) return -1;
        try {
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
            policy.invalidate(); logic.reset(); farm = false;
            if (!now && foreground) paused++;
            foreground = now; windowId = current;
            reason = now ? "微信前台，低频探测场景" : "非微信前台或屏幕锁定，暂停";
            lastStatus = reason; publish();
        }
        return now;
    }

    private void schedule() {
        if (!alive()) return;
        handler.removeCallbacks(sample);
        if (!isRunning) return;
        if (!foreground) { handler.postDelayed(sample, 2000); return; }
        long now = SystemClock.elapsedRealtime();
        if (farm && now - farmSeen > 10000) { farm = false; policy.clearBurst(); logic.reset(); }
        handler.postDelayed(sample, Math.max(0, policy.next(now, farm) - now));
    }

    private void sample() {
        if (!alive() || !isRunning) return;
        if (!updateForeground()) { schedule(); return; }
        if (busy) { handler.postDelayed(sample, 400); return; }
        long now = SystemClock.elapsedRealtime();
        long next = policy.next(now, farm);
        if (next > now) { handler.postDelayed(sample, next - now); return; }
        int token = policy.generation(), target = windowId;
        policy.captured(now); busy = true;
        try {
            if (Build.VERSION.SDK_INT >= 34) takeScreenshotOfWindow(target, mainExecutor, callback(token, target));
            else if (Build.VERSION.SDK_INT >= 30) takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback(token, target));
            else { busy = false; lastStatus = "Android 11 以下不支持无障碍截图"; }
        } catch (RuntimeException e) { busy = false; errors++; reason = "截图请求失败"; lastStatus = reason; publish(); }
        schedule();
    }

    private boolean valid(int token, int target) {
        return alive() && updateForeground() && windowId == target && policy.valid(token, isRunning, foreground);
    }

    @android.annotation.TargetApi(30)
    private TakeScreenshotCallback callback(int token, int target) {
        return new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap hardware = null, copy = null;
                boolean launched = false;
                try {
                    if (!valid(token, target)) return;
                    screenshots++;
                    hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    if (hardware == null) throw new IllegalStateException("empty screenshot");
                    copy = hardware.copy(Bitmap.Config.ARGB_8888, false);
                    if (copy == null) throw new IllegalStateException("copy failed");
                    final Bitmap frame = copy;
                    final long capturedAt = System.currentTimeMillis(), started = SystemClock.elapsedRealtime();
                    recognizer.process(InputImage.fromBitmap(frame, 0)).addOnCompleteListener(mainExecutor, task -> {
                        try {
                            if (!valid(token, target)) return;
                            ocr++; ocrMs = SystemClock.elapsedRealtime() - started;
                            if (!task.isSuccessful() || task.getResult() == null) {
                                farm = false; policy.clearBurst(); logic.reset(); reason = "OCR失败"; lastStatus = reason;
                            } else processText(task.getResult(), frame.getWidth(), frame.getHeight(), capturedAt, token, target);
                            publish();
                        } catch (RuntimeException e) { if (alive()) { farm = false; policy.clearBurst(); logic.reset(); reason = "OCR处理失败"; lastStatus = reason; publish(); } }
                        finally { frame.recycle(); if (alive()) { busy = false; schedule(); } }
                    });
                    launched = true;
                    copy = null;
                } catch (RuntimeException e) { if (alive()) { errors++; logic.reset(); reason = "截图处理失败"; lastStatus = reason; publish(); } }
                finally {
                    if (hardware != null) hardware.recycle();
                    buffer.close();
                    if (copy != null) copy.recycle();
                    if (!launched && alive()) { busy = false; schedule(); }
                }
            }
            @Override public void onFailure(int code) {
                if (!alive()) return;
                busy = false;
                if (valid(token, target)) { errors++; logic.reset(); reason = "截图错误代码 " + code + "（安全窗口或系统限制可能阻止截图）"; lastStatus = reason; publish(); }
                schedule();
            }
        };
    }

    private void processText(Text text, int width, int height, long capturedAt, int token, int target) {
        if (!alive()) return;
        List<OcrLine> lines = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) for (Text.Line line : block.getLines()) {
            Rect box = line.getBoundingBox();
            if (box != null) lines.add(new OcrLine(line.getText(), box.left, box.top, box.right, box.bottom));
        }
        String friend = OcrFrameParser.friend(lines, width, height);
        Long countdown = OcrFrameParser.countdown(lines);
        if (friend != null) { farm = true; farmSeen = SystemClock.elapsedRealtime(); }
        else { farm = false; policy.clearBurst(); logic.reset(); }
        Long maturity = logic.observe(friend, countdown, capturedAt);
        if (friend == null) { rejectedFriend++; reason = "未确认农场或唯一昵称"; }
        else if (countdown == null || countdown <= 0) { rejectedTime++; reason = "无明确有效成熟时间（阶段时间不录入）"; }
        else if (maturity == null) { candidates++; reason = "候选等待连续帧确认"; }
        else if (valid(token, target)) {
            int lead = getSharedPreferences("settings", MODE_PRIVATE).getInt("leadSeconds", 60);
            Reminder r = new ReminderStore(this).addIfNew(friend, maturity, lead);
            if (r == null) { duplicate++; reason = "重复记录"; }
            else { added++; reason = "新增提醒"; new AlarmScheduler(this).schedule(r); }
        }
        lastStatus = reason;
    }

    private void publish() {
        if (!alive()) return;
        diagnostics = "截图 " + screenshots + " · 点击事件 " + clicks + " · 内容事件 " + contents
                + " · OCR " + ocr + "（最近 " + ocrMs + "ms）\n暂停 " + paused + " · 昵称/场景未确认 " + rejectedFriend
                + " · 无成熟时间 " + rejectedTime + " · 候选 " + candidates + " · 新增 " + added
                + " · 重复 " + duplicate + " · 截图错误 " + errors + "\n" + reason;
    }

    @Override public void onDestroy() {
        boolean owner = alive();
        destroyed = true;
        if (owner) {
            isRunning = false; isConnected = false; instance = null;
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("monitoringEnabled", false).apply();
            lastStatus = "服务断开，请重新启动监测";
        }
        handler.removeCallbacks(sample); policy.invalidate(); logic.reset();
        try { unregisterReceiver(screenReceiver); } catch (IllegalArgumentException ignored) { }
        if (recognizer != null) recognizer.close();
        super.onDestroy();
    }
}
