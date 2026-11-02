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
import android.media.projection.MediaProjectionManager;
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

/** A local-only dashboard. Screen capture and notification permission are independent. */
public class MainActivity extends Activity {
    private static final int SCREEN_CAPTURE = 101, NOTIFICATIONS = 102;
    private static final int INK = 0xFF20382D, GREEN = 0xFF276344;
    private static final int MUTED = 0xFF657367, PAPER = 0xFFF7F6EF;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Button> leadButtons = new ArrayList<>();
    private TextView runningLabel, captureStatus, feedback, notificationStatus, exactStatus, listTitle;
    private Button startButton, stopButton, notificationButton, exactButton, clearButton;
    private LinearLayout reminders;
    private SharedPreferences settings;
    private ReminderStore store;
    private AlarmScheduler scheduler;
    private int leadSeconds;
    private boolean capturePending, resumed;
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
        capturePending = state != null && state.getBoolean("capturePending", false);
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
        runningLabel = addText(capture, "●  识别未启动", 13, GREEN, true, 0);
        addText(capture, "记录屏幕上的成熟倒计时", 20, INK, true, 12);
        addText(capture, "启动并授权后，切换到好友农场，保持好友名和成熟倒计时清晰可见。", 14, MUTED, false, 9);
        captureStatus = addText(capture, "未运行", 13, INK, false, 14);
        startButton = button(capture, "启动屏幕识别", true, 16, this::requestCapture);
        stopButton = button(capture, "停止识别", false, 8, () -> {
            if (CaptureService.isRunning) {
                try {
                    startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
                    showFeedback("已请求停止识别，已记录的提醒会保留。");
                } catch (RuntimeException e) { showFeedback("停止失败，请稍后重试。"); }
            }
            refresh();
        });
        feedback = addText(capture, "", 13, GREEN, false, 10);
        feedback.setVisibility(View.GONE);

        LinearLayout timing = card(page, 16, Color.WHITE);
        addText(timing, "提前多久提醒", 18, INK, true, 0);
        addText(timing, "只用于之后新记录的提醒。识别中修改需停止后重新启动。", 13, MUTED, false, 7);
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
                if (CaptureService.isRunning) showFeedback("已保存；停止并重新启动识别后生效。");
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

    private void requestCapture() {
        if (capturePending || CaptureService.isRunning) return;
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) { showFeedback("此设备无法请求屏幕录制授权。"); return; }
            capturePending = true;
            startActivityForResult(manager.createScreenCaptureIntent(), SCREEN_CAPTURE);
        } catch (RuntimeException e) {
            capturePending = false;
            showFeedback("无法打开屏幕授权，请稍后重试。");
        }
        refresh();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SCREEN_CAPTURE) return;
        capturePending = false;
        if (resultCode != RESULT_OK || data == null) {
            showFeedback("已取消屏幕授权，未启动识别。你可以随时重试。");
        } else if (!CaptureService.isRunning) {
            try {
                startForegroundService(new Intent(this, CaptureService.class)
                        .putExtra("resultCode", resultCode).putExtra("data", data)
                        .putExtra("leadSeconds", leadSeconds));
                showFeedback("已提交启动请求，请稍候并查看识别状态。");
            } catch (RuntimeException e) {
                showFeedback("无法启动识别，请重新授权后重试。");
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

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("capturePending", capturePending);
        super.onSaveInstanceState(state);
    }

    private void refresh() {
        boolean running = CaptureService.isRunning;
        runningLabel.setText(running ? "●  正在识别" : "○  识别未启动");
        captureStatus.setText("识别状态：" + (CaptureService.lastStatus == null ? "未运行" : CaptureService.lastStatus));
        startButton.setEnabled(!running && !capturePending);
        startButton.setText(capturePending ? "等待屏幕授权…" : running ? "识别运行中" : "启动屏幕识别");
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
            addText(empty, "启动识别后，打开好友农场。确认好友和成熟倒计时后，记录会出现在这里。", 14, MUTED, false, 9);
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
