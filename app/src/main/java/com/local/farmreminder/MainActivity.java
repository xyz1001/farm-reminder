package com.local.farmreminder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** A local-only dashboard. Accessibility monitoring and reminders are independent. */
public class MainActivity extends Activity {
    private static final int NOTIFICATIONS = 102;
    private static final int INK = 0xFF20382D, GREEN = 0xFF276344;
    private static final int MUTED = 0xFF657367, PAPER = 0xFFF7F6EF;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Button> leadButtons = new ArrayList<>();
    private TextView runningLabel, captureStatus, diagnosticsText, feedback, notificationStatus, exactStatus, listTitle;
    private TextView processedCount, pendingCount, ocrState, queueDetails;
    private Button startButton, stopButton, notificationButton, exactButton, clearButton;
    private LinearLayout reminders;
    private SharedPreferences settings;
    private ReminderStore store;
    private AlarmScheduler scheduler;
    private int leadSeconds;
    private boolean resumed;
    private String listSignature = "";
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = getSharedPreferences("settings", MODE_PRIVATE);
        leadSeconds = settings.getInt("leadSeconds", 60);
        if (leadSeconds != 0 && leadSeconds != 60 && leadSeconds != 300) leadSeconds = 60;
        store = new ReminderStore(this);
        scheduler = new AlarmScheduler(this);
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        buildScreen();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAPER);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets.consumeSystemWindowInsets();
        });
        setContentView(root);
        root.requestApplyInsets();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout page = column();
        page.setPadding(dp(20), dp(26), dp(20), dp(28));
        scroll.addView(page);
        addText(page, "本机工具  /  FARM REMINDER", 11, GREEN, true, 0);
        addText(page, "农场成熟提醒", 29, INK, true, 9);
        addText(page, "看清成熟时间，再安心离开。", 15, MUTED, false, 7);

        LinearLayout capture = card(page, 24, 0xFFE9EFE5);
        runningLabel = addText(capture, "○  监测未启动", 13, GREEN, true, 0);
        addText(capture, "记录好友农场的成熟时间", 20, INK, true, 12);
        addText(capture, "可自行选择启用无障碍服务：用于读取前台窗口线索，并获取本地单张截图来识别倒计时。不自动点击、不上传、不保存截图。", 14, MUTED, false, 9);
        addText(capture, "启用服务后，仍需手动开始监测。离开微信暂停；小游戏窗口身份待验证，可能需要少量画面探测。请保持好友名和成熟倒计时清晰可见。", 13, MUTED, false, 9);
        captureStatus = addText(capture, "未运行", 13, INK, false, 14);
        LinearLayout progress = column();
        progress.setPadding(dp(14), dp(14), dp(14), dp(14));
        progress.setBackground(background(0xFFF7FAF3, 14, 0xFFD6E2D1));
        capture.addView(progress, spaced(-1, -2, 14));
        addText(progress, "OCR 处理进度", 12, GREEN, true, 0);
        processedCount = addText(progress, "已识别 0 张", 21, INK, true, 8);
        pendingCount = addText(progress, "待识别 0 张", 21, GREEN, true, 4);
        ocrState = addText(progress, "暂无正在识别的画面", 13, GREEN, false, 9);
        addText(progress, "已识别指完成 OCR 处理，不代表已添加提醒。待识别仅计排队画面，不含正在识别的 1 张。", 12, MUTED, false, 8);
        queueDetails = addText(progress, "", 12, MUTED, false, 8);
        startButton = button(capture, "检查无障碍设置", true, 16, this::requestMonitoring);
        stopButton = button(capture, "停止监测", false, 8, () -> {
            try {
                FarmAccessibilityService.setMonitoring(this, false);
                showFeedback("已请求停止监测，已记录的提醒会保留。");
            } catch (RuntimeException e) { showFeedback("停止失败，请稍后重试。"); }
            refresh();
        });
        feedback = addText(capture, "", 13, GREEN, false, 10);
        feedback.setVisibility(View.GONE);
        Button diagnosticsToggle = button(capture, "查看监测诊断 ▾", false, 12, () -> {});
        diagnosticsText = addText(capture, "暂无诊断数据", 12, MUTED, false, 9);
        diagnosticsText.setVisibility(View.GONE);
        diagnosticsToggle.setOnClickListener(v -> {
            boolean expanded = diagnosticsText.getVisibility() != View.VISIBLE;
            diagnosticsText.setVisibility(expanded ? View.VISIBLE : View.GONE);
            diagnosticsToggle.setText(expanded ? "收起监测诊断 ▴" : "查看监测诊断 ▾");
        });
        button(capture, "查看无障碍设置", false, 8,
                () -> openSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        LinearLayout timing = card(page, 16, Color.WHITE);
        addText(timing, "提前多久提醒", 18, INK, true, 0);
        addText(timing, "只用于之后新记录的提醒，不会改变已记录的提醒时间。", 13, MUTED, false, 7);
        LinearLayout choices = new LinearLayout(this);
        timing.addView(choices, spaced(-1, -2, 13));
        int[] values = {0, 60, 300};
        String[] labels = {"成熟时", "提前 1 分钟", "提前 5 分钟"};
        for (int i = 0; i < values.length; i++) {
            final int value = values[i];
            Button choice = makeButton(labels[i], false);
            choice.setTextSize(12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = dp(6);
            choices.addView(choice, lp);
            leadButtons.add(choice);
            choice.setOnClickListener(v -> {
                leadSeconds = value;
                settings.edit().putInt("leadSeconds", value).apply();
                updateLeadButtons();
                if (FarmAccessibilityService.isRunning) showFeedback("已保存，将用于之后新记录的提醒。");
            });
        }
        updateLeadButtons();

        LinearLayout permissions = card(page, 16, Color.WHITE);
        addText(permissions, "提醒是否准备好", 18, INK, true, 0);
        notificationStatus = addText(permissions, "", 14, INK, false, 13);
        notificationButton = button(permissions, "允许通知", false, 8, this::requestNotifications);
        exactStatus = addText(permissions, "", 14, INK, false, 17);
        exactButton = button(permissions, "设置精确提醒权限", false, 8, this::requestExactAlarm);
        addText(permissions, "到点仅短振动一次，无铃声、不弹窗。通知权限、勿扰及系统设置可能影响通知和振动；未允许精确闹钟或系统省电可能导致延迟。", 12, MUTED, false, 12);
        button(permissions, "测试振动 · 10 秒后", false, 13, () -> {
            try {
                scheduler.scheduleTest(10);
                showMessage("已安排 10 秒后测试短振动一次，无铃声；通知和振动受权限、勿扰及系统设置影响。");
            } catch (RuntimeException e) { showMessage("测试振动未能安排，请检查系统设置后重试。"); }
        });

        LinearLayout heading = column();
        page.addView(heading, spaced(-1, -2, 27));
        listTitle = addText(heading, "已记录提醒 · 0", 21, INK, true, 0);
        clearButton = button(heading, "清除已提交记录", false, 10, () ->
                new AlertDialog.Builder(this).setTitle("清除已提交记录？")
                        .setMessage("只移除已提交提醒的记录，不影响待提醒项目。提交不代表实际送达。")
                        .setNegativeButton("取消", null).setPositiveButton("清除", (d, w) -> {
                            store.clearFired(); refresh();
                        }).show());
        reminders = column();
        page.addView(reminders, spaced(-1, -2, 4));
        addText(page, "识别规则", 14, INK, true, 25);
        addText(page, "只识别明确标注“后成熟”的倒计时，忽略生长阶段倒计时；好友或时间不确定时不录入。请核对记录的成熟时间。", 13, MUTED, false, 7);
        addText(page, "仅在本机处理画面，不上传数据。不会自动点击微信或操作游戏，不需要悬浮窗权限。", 12, MUTED, false, 12);
    }

    private void requestMonitoring() {
        if (Build.VERSION.SDK_INT < 30) {
            showFeedback("此设备低于 Android 11，不支持所需的无障碍截图，无法开始监测。已有提醒仍可使用。");
        } else if (!FarmAccessibilityService.isEnabled(this)) {
            showFeedback("服务当前不可用，等待系统连接或恢复；请检查系统无障碍设置。尚未开始监测，当前状态不代表你已关闭授权。");
            openSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } else if (!FarmAccessibilityService.isConnected) {
            showFeedback("等待系统连接无障碍服务；尚未开始监测。需服务已连接才能启动，可稍后返回检查，或查看系统无障碍设置。");
        } else if (!FarmAccessibilityService.isRunning) {
            try {
                FarmAccessibilityService.setMonitoring(this, true);
                if (FarmAccessibilityService.isConnected && FarmAccessibilityService.isRunning) {
                    showFeedback("监测已开启；切换到微信后按窗口线索进行监测。");
                } else {
                    showFeedback("监测未能启动，需无障碍服务已连接；请等待系统恢复并检查系统无障碍设置后重试。");
                }
            } catch (RuntimeException e) {
                showFeedback("无法开始监测，请检查无障碍服务状态后重试。");
            }
        }
        refresh();
    }

    private boolean notificationsAllowed() {
        return (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED)
                && ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).areNotificationsEnabled();
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && (!settings.getBoolean("notificationAsked", false)
                    || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            settings.edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
        } else {
            openSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != NOTIFICATIONS) return;
        if (results.length == 0) showMessage("已取消通知授权，可稍后在设置中开启。");
        else if (!notificationsAllowed()) showMessage("通知未开启，识别仍可使用；提醒通知和振动可能受限。");
        refresh();
    }

    private void requestExactAlarm() {
        if (scheduler.canScheduleExact()) {
            showMessage("当前已支持精确提醒，仍可能受系统设置影响。");
        } else if (Build.VERSION.SDK_INT >= 31) {
            openSettings(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void openSettings(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))); }
            catch (ActivityNotFoundException | SecurityException ignored) {
                showMessage("无法打开设置，请在系统设置中找到本应用。");
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        try { scheduler.rescheduleAll(); }
        catch (RuntimeException e) { showMessage("部分提醒未能重新安排，请检查权限。"); }
        handler.removeCallbacks(ticker);
        ticker.run();
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void refresh() {
        boolean supported = Build.VERSION.SDK_INT >= 30;
        boolean enabled = FarmAccessibilityService.isEnabled(this);
        boolean connected = FarmAccessibilityService.isConnected;
        boolean running = FarmAccessibilityService.isRunning;
        String readiness = !supported ? "需要 Android 11 及以上；此设备不支持无障碍截图，无法开始监测。"
                : !enabled ? (connected
                        ? "服务当前不可用，等待系统恢复；请检查系统无障碍设置。连接标记仍在，但当前查询无法确认服务可用，不代表已关闭授权。"
                        : "服务当前不可用／等待系统连接；请检查系统无障碍设置。当前查询无法确认服务可用，不代表已关闭授权。")
                : !connected ? "等待系统连接无障碍服务；当前无法开始监测，请检查系统无障碍设置。"
                : "无障碍服务已连接";
        runningLabel.setText(!supported ? "○  此设备不支持监测"
                : !enabled || !connected ? "○  服务尚未就绪"
                : running ? "●  监测已开启 · 离开微信暂停" : "○  监测未启动");
        String status = FarmAccessibilityService.lastStatus;
        captureStatus.setText(readiness + "\n监测状态：" + (blank(status) ? "未运行" : status));
        OcrProgress progress = FarmAccessibilityService.getOcrProgress();
        processedCount.setText("已识别 " + progress.processed + " 张");
        pendingCount.setText("待识别 " + progress.queued + " 张");
        ocrState.setText(progress.recognizing ? "正在识别 1 张 · 不计入待识别"
                : !connected ? "服务未连接 · 暂无正在识别的画面"
                : !running ? "监测未启动 · 暂无正在识别的画面"
                : "暂无正在识别的画面");
        queueDetails.setText(String.format(Locale.CHINA,
                "队列内存 %.1f / %.1f MiB\n队列最多 %d 张 · 已丢弃 %d 张",
                progress.pendingBytes / (1024f * 1024f),
                progress.maxPendingBytes / (1024f * 1024f),
                progress.maxPendingFrames, progress.dropped));
        String diagnostics = FarmAccessibilityService.diagnostics;
        diagnosticsText.setText(blank(diagnostics) ? "暂无诊断数据。开始后可查看截图计数、OCR 处理与拒绝原因；不展示原图或完整识别文本。" : diagnostics);
        startButton.setEnabled(supported && !running && (!enabled || connected));
        startButton.setAlpha(startButton.isEnabled() ? 1f : 0.55f);
        startButton.setText(!supported ? "此设备不支持监测" : !enabled ? "检查无障碍设置"
                : !connected ? "等待服务连接…" : running ? "监测已开启" : "开始监测");
        stopButton.setVisibility(running ? View.VISIBLE : View.GONE);
        boolean notified = notificationsAllowed();
        notificationStatus.setText(notified ? "通知 · 已开启" : "通知 · 未开启");
        notificationButton.setText(notified ? "查看通知设置" : "开启通知 / 前往设置");
        boolean exact = scheduler.canScheduleExact();
        exactStatus.setText(exact ? "精确闹钟 · 可用" : "精确闹钟 · 未允许，提醒可能延迟");
        exactButton.setVisibility(exact ? View.GONE : View.VISIBLE);
        List<Reminder> items = store.list();
        items.sort(Comparator.comparing((Reminder r) -> r.fired).thenComparingLong(r -> r.matureAt));
        StringBuilder signature = new StringBuilder();
        long now = System.currentTimeMillis();
        boolean hasFired = false;
        for (Reminder r : items) {
            hasFired |= r.fired;
            signature.append(r.id).append('|').append(r.friend).append('|').append(r.crop)
                    .append('|').append(r.matureAt).append('|').append(r.triggerAt)
                    .append('|').append(r.fired).append('|').append(r.triggerAt <= now).append('\n');
        }
        listTitle.setText("已记录提醒 · " + items.size());
        clearButton.setVisibility(hasFired ? View.VISIBLE : View.GONE);
        String next = items.isEmpty() ? "empty" : signature.toString();
        if (next.equals(listSignature)) return;
        listSignature = next;
        reminders.removeAllViews();
        if (items.isEmpty()) {
            LinearLayout empty = card(reminders, 10, 0xFFEDEFE6);
            addText(empty, "还没有成熟提醒", 17, INK, true, 0);
            addText(empty, "开始监测后，在微信打开好友农场。识别并确认好友和成熟倒计时后，记录会出现在这里。", 14, MUTED, false, 9);
            return;
        }
        SimpleDateFormat date = new SimpleDateFormat("yyyy年M月d日 HH:mm:ss", Locale.CHINA);
        for (Reminder r : items) {
            LinearLayout row = card(reminders, 10, Color.WHITE);
            addText(row, r.fired ? "已提交提醒" : r.triggerAt <= now ? "提醒时间已到 · 待提交" : "待提醒", 12,
                    r.fired ? MUTED : GREEN, true, 0);
            addText(row, blank(r.friend) ? "未命名好友" : r.friend, 20, INK, true, 8);
            addText(row, "作物：" + (blank(r.crop) ? "未识别" : r.crop), 13, MUTED, false, 5);
            addText(row, "成熟  " + date.format(new Date(r.matureAt)), 14, INK, false, 13);
            addText(row, "提醒  " + date.format(new Date(r.triggerAt)), 12, MUTED, false, 5);
            button(row, "删除这条提醒", false, 12, () -> new AlertDialog.Builder(this)
                    .setTitle("删除这条提醒？").setMessage("记录会被移除，尚未触发的提醒也会取消。")
                    .setNegativeButton("保留", null).setPositiveButton("删除", (d, w) -> {
                        store.delete(r.id); refresh();
                    }).show());
        }
    }

    private void updateLeadButtons() {
        int[] values = {0, 60, 300};
        for (int i = 0; i < leadButtons.size(); i++) {
            Button b = leadButtons.get(i);
            boolean selected = leadSeconds == values[i];
            b.setSelected(selected);
            b.setTextColor(selected ? Color.WHITE : GREEN);
            b.setBackground(background(selected ? GREEN : 0xFFF0F3EB, 12, 0));
            b.setContentDescription(b.getText() + (selected ? "，已选择" : "，未选择"));
        }
    }

    private boolean blank(String text) { return text == null || text.trim().isEmpty(); }
    private void showFeedback(String message) { feedback.setText(message); feedback.setVisibility(View.VISIBLE); }
    private void showMessage(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout.LayoutParams spaced(int width, int height, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
        lp.topMargin = dp(top);
        return lp;
    }
    private GradientDrawable background(int color, int radius, int stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        if (stroke != 0) bg.setStroke(dp(1), stroke);
        return bg;
    }
    private LinearLayout card(LinearLayout parent, int top, int color) {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(20), dp(18), dp(18));
        card.setBackground(background(color, 22, color == Color.WHITE ? 0xFFE6E9DF : 0));
        parent.addView(card, spaced(-1, -2, top));
        return card;
    }
    private TextView addText(LinearLayout parent, String text, int size, int color, boolean bold, int top) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        label.setLineSpacing(dp(3), 1.08f);
        parent.addView(label, spaced(-1, -2, top));
        return label;
    }
    private Button makeButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(primary ? Color.WHITE : GREEN);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        android.content.res.ColorStateList ripple = android.content.res.ColorStateList.valueOf(0x24276344);
        b.setBackground(new android.graphics.drawable.RippleDrawable(ripple,
                background(primary ? GREEN : 0xFFF0F3EB, 13, 0), null));
        b.setStateListAnimator(null);
        return b;
    }
    private Button button(LinearLayout parent, String text, boolean primary, int top, Runnable action) {
        Button b = makeButton(text, primary);
        parent.addView(b, spaced(-1, -2, top));
        b.setOnClickListener(v -> action.run());
        return b;
    }
}
