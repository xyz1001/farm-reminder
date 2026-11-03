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
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** A local-only dashboard. Accessibility monitoring and reminders are independent. */
public class MainActivity extends Activity {
    private static final int NOTIFICATIONS = 102;
    private static final int EXPORT_DIAGNOSTICS = 103;
    private static final int INK = 0xFF20382D, GREEN = 0xFF276344;
    private static final int MUTED = 0xFF657367, PAPER = 0xFFF7F6EF;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Button> leadButtons = new ArrayList<>();
    private TextView runningLabel, captureStatus, diagnosticsText, feedback, notificationStatus, exactStatus, listTitle;
    private TextView processedCount, pendingCount, ocrState, queueDetails;
    private TextView foregroundStatus, foregroundError, monitorNotificationHint;
    private TextView usageAccessStatus;
    private TextView modeHint, modeDescription, shizukuStatus;
    private RadioGroup modeChoices;
    private RadioButton usageMode, shizukuMode;
    private Button usageSettingsButton, shizukuPermissionButton;
    private boolean updatingMode;
    private Button startButton, stopButton, retryButton, notificationButton, exactButton, clearButton;
    private Button exportButton;
    private TextView exportStatus;
    private ExportState exportState;
    private LinearLayout reminders;
    private SharedPreferences settings;
    private ReminderStore store;
    private AlarmScheduler scheduler;
    private int leadSeconds;
    private boolean resumed;
    private boolean foregroundStartupPending, foregroundRequestFailed;
    private long foregroundStartupDeadline;
    private String foregroundErrorBeforeStart = "";
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
        Object retained = getLastNonConfigurationInstance();
        exportState = retained instanceof ExportState ? (ExportState) retained : new ExportState();
        if (retained == null && state != null) {
            exportState.picking = state.getBoolean("exportPicking", false);
            if (state.getBoolean("exportBusy", false))
                exportState.message = "上次导出结果未确认，请检查保存位置；可重新导出。";
        }
        exportState.activity = new WeakReference<>(this);
        settings = getSharedPreferences("settings", MODE_PRIVATE);
        if (state != null) {
            foregroundStartupPending = state.getBoolean("foregroundStartupPending", false);
            foregroundStartupDeadline = state.getLong("foregroundStartupDeadline", 0);
            foregroundErrorBeforeStart = state.getString("foregroundErrorBeforeStart", "");
        }
        leadSeconds = settings.getInt("leadSeconds", 60);
        if (leadSeconds != 0 && leadSeconds != 60 && leadSeconds != 300) leadSeconds = 60;
        store = new ReminderStore(this);
        scheduler = new AlarmScheduler(this);
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        buildScreen();
        renderExportState();
        FarmAccessibilityService.refreshDiskProgress(this);
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
        addText(capture, "两种模式均需自行启用仅用于截图的无障碍服务，不读取节点树、窗口列表或监听界面事件。截图与识别结果保存在应用私有目录，不自动删除，仅手动导出，不自动上传。", 14, MUTED, false, 9);
        addText(capture, "前台判断模式", 14, INK, true, 16);
        modeChoices = new RadioGroup(this);
        modeChoices.setOrientation(RadioGroup.VERTICAL);
        // Preferences own this selection; restoring generated view IDs must not change the mode.
        modeChoices.setSaveEnabled(false);
        capture.addView(modeChoices, spaced(-1, -2, 5));
        usageMode = modeChoice("使用情况模式（默认）");
        shizukuMode = modeChoice("Shizuku 系统任务模式");
        modeChoices.check(ShizukuTaskGate.isEnabled(this) ? shizukuMode.getId() : usageMode.getId());
        modeChoices.setOnCheckedChangeListener((group, checkedId) -> {
            if (updatingMode) return;
            if (!canChangeMode()) {
                showFeedback("请先停止监测，待前台服务停止后再切换模式。");
                refresh();
                return;
            }
            settings.edit().putBoolean("useShizuku", checkedId == shizukuMode.getId()).apply();
            showFeedback("模式已保存；不会自动授权或启动监测，请检查权限后手动开始。");
            refresh();
        });
        modeHint = addText(capture, "仅停止监测后可切换模式。", 12, MUTED, false, 5);
        modeDescription = addText(capture, "", 13, MUTED, false, 9);
        shizukuStatus = addText(capture, "Shizuku · 待检查", 13, GREEN, true, 9);
        shizukuPermissionButton = button(capture, "请求 Shizuku 授权 / 刷新状态", false, 8, () -> {
            ShizukuTaskGate.get(this).requestPermission();
            showFeedback("已请求检查 Shizuku 运行与授权状态，请查看上方状态更新；如弹出授权请求，请自行确认。支持已授权的 Root 或 ADB Shell 运行方式，无需切换方式。");
            refresh();
        });
        addText(capture, "满足所选模式的截图条件后，以 500 毫秒为目标间隔截取整屏。每次进入只观察首20秒：期间识别到有效成熟倒计时（包括已有提醒）即可继续截图，不要求读到昵称；之后不会因暂时无结果而暂停。首20秒始终无结果则暂停本次停留的新增截图，已保存图片继续识别。离开后重新进入、上下文变化或手动停止再开始可重试；迟到识别不会自动恢复已超时的截图。缺权限、锁屏或状态未知时仍暂停，手动停止也暂停队列处理。\n成熟词不证明游戏身份。请先关闭敏感页面，再手动进入农场。系统快照、使用记录和识别可能延迟，前台判断与截图不是原子操作，切换或覆盖可能截到其他应用或系统内容，多窗口不可靠，不保证只截农场或消除风控。", 13, MUTED, false, 9);
        usageAccessStatus = addText(capture, "使用情况访问 · 待检查", 13, GREEN, true, 9);
        captureStatus = addText(capture, "未运行", 13, INK, false, 14);
        foregroundStatus = addText(capture, "前台监测服务 · 未启动", 13, GREEN, true, 8);
        addText(capture, "监测时显示静默常驻通知，可降低被系统回收的概率，仍受系统管理。点击监测通知可返回本应用。", 12, MUTED, false, 7);
        monitorNotificationHint = addText(capture, "通知栏中的监测状态和停止按钮可能不可见，可返回应用停止。", 12, MUTED, false, 7);
        monitorNotificationHint.setVisibility(View.GONE);
        foregroundError = addText(capture, "", 13, INK, false, 7);
        foregroundError.setVisibility(View.GONE);
        LinearLayout progress = column();
        progress.setPadding(dp(14), dp(14), dp(14), dp(14));
        progress.setBackground(background(0xFFF7FAF3, 14, 0xFFD6E2D1));
        capture.addView(progress, spaced(-1, -2, 14));
        addText(progress, "OCR 处理进度", 12, GREEN, true, 0);
        processedCount = addText(progress, "已识别 0 张", 21, INK, true, 8);
        pendingCount = addText(progress, "待识别 0 张", 21, GREEN, true, 4);
        ocrState = addText(progress, "暂无正在识别的画面", 13, GREEN, false, 9);
        addText(progress, "已识别指完成 OCR 尝试，不代表已添加提醒。待识别仅计排队图片，不含正在识别的 1 张或失败保留图片。", 12, MUTED, false, 8);
        queueDetails = addText(progress, "", 12, MUTED, false, 8);
        addText(progress, "队列无固定张数限制；空间不足时暂停新增截图，不淘汰待处理图片。重新打开应用只恢复统计，需手动开始监测才会继续处理。", 12, MUTED, false, 8);
        retryButton = button(progress, "重试保存/失败图片", false, 10, () -> {
            try {
                FarmAccessibilityService.retryFailedFrames(this);
                showFeedback(FarmAccessibilityService.isRunning
                        ? "已请求重试失败图片，请等待统计更新。"
                        : "已请求重试失败图片；尚未开始监测，需手动开始才会继续识别。");
            } catch (RuntimeException e) { showFeedback("未能请求重试，请稍后再试。失败图片仍保留。"); }
        });
        retryButton.setVisibility(View.GONE);
        addText(progress, "所有已保存截图在识别后仍会保留，用于核对；不会自动上传。请复现后停止监测并导出，避免持续占用空间。", 12, MUTED, false, 10);
        addText(progress, "可先手动离开微信，确认已停止新增截图并等待队列处理完成，再停止监测并导出。诊断包包含截图、识别文字及好友昵称等敏感信息，仅交给可信人员；请选择可信的保存位置。", 12, MUTED, false, 8);
        exportButton = button(progress, "导出诊断包", false, 10, this::requestDiagnosticsExport);
        exportStatus = addText(progress, "无需启动监测或启用无障碍服务即可导出已有记录。", 12, MUTED, false, 7);
        startButton = button(capture, "检查无障碍设置", true, 16, this::requestMonitoring);
        stopButton = button(capture, "停止监测", false, 8, () -> {
            try {
                MonitorForegroundService.requestStop(this);
                foregroundStartupPending = false;
                showFeedback("已请求停止监测：停止截图和队列处理，已保存图片与提醒均保留。");
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
        usageSettingsButton = button(capture, "设置使用情况访问权限", false, 8, this::openUsageAccessSettings);

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
        addText(page, "单张截图识别到成熟倒计时即添加提醒；名称读不到时标为“昵称未识别”；已有提醒不重复添加，也算本次首20秒内的有效结果。生长阶段倒计时不作为成熟时间。仅在 OCR 解析中将“后戌熟”定向纠正为“后成熟”，原始截图与 OCR 文件保持原样。请核对记录。", 13, MUTED, false, 7);
        addText(page, "仅在本机处理画面，不上传数据。不会自动点击微信或操作游戏，不需要悬浮窗权限。", 12, MUTED, false, 12);
    }

    private void requestMonitoring() {
        if (foregroundStartupPending || MonitorForegroundService.isActive) return;
        if (Build.VERSION.SDK_INT < 30) {
            showFeedback("此设备低于 Android 11，不支持所需的无障碍截图，无法开始监测。已有提醒仍可使用。");
        } else if (ShizukuTaskGate.isEnabled(this) && !ShizukuTaskGate.get(this).hasPermission()) {
            showFeedback("Shizuku 尚未就绪，请查看运行与授权状态。当前状态："
                    + ShizukuTaskGate.get(this).status()
                    + "。请确认 Shizuku 服务已运行，并通过本页按钮检查或请求授权；支持已授权的 Root 或 ADB Shell 运行方式，无需切换方式。尚未开始监测，不会自动回退使用情况模式。");
        } else if (!ShizukuTaskGate.isEnabled(this) && !UsageAccessGate.hasPermission(this)) {
            showFeedback("尚未允许使用情况访问，不能开始监测。请在系统设置中允许，返回后手动开始。");
            openUsageAccessSettings();
        } else if (!FarmAccessibilityService.isEnabled(this)) {
            showFeedback("服务当前不可用，等待系统连接或恢复；请检查系统无障碍设置。尚未开始监测，当前状态不代表你已关闭授权。");
            openSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } else if (!FarmAccessibilityService.isConnected) {
            showFeedback("等待系统连接无障碍服务；尚未开始监测。需服务已连接才能启动，可稍后返回检查，或查看系统无障碍设置。");
        } else if (!FarmAccessibilityService.isRunning) {
            try {
                foregroundErrorBeforeStart = MonitorForegroundService.lastError;
                MonitorForegroundService.requestStart(this);
                foregroundStartupPending = true;
                foregroundRequestFailed = false;
                foregroundStartupDeadline = SystemClock.uptimeMillis() + 5000;
                showFeedback("已请求启动，等待监测状态更新。");
            } catch (RuntimeException e) {
                foregroundStartupPending = false;
                foregroundRequestFailed = true;
                showFeedback("无法请求启动前台监测服务，请检查系统设置后重试。");
            }
        }
        refresh();
    }

    private void requestDiagnosticsExport() {
        if (exportState.busy || exportState.picking) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, "farm-diagnostics-"
                        + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".zip");
        exportState.picking = true;
        renderExportState();
        try { startActivityForResult(intent, EXPORT_DIAGNOSTICS); }
        catch (ActivityNotFoundException e) {
            exportState.picking = false;
            exportState.message = "此设备不支持选择保存位置，无法导出诊断包。";
            renderExportState();
        } catch (RuntimeException e) {
            exportState.picking = false;
            exportState.message = "无法打开保存位置选择器，请稍后重试。";
            renderExportState();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_DIAGNOSTICS) return;
        exportState.picking = false;
        Uri destination = data == null ? null : data.getData();
        if (resultCode != RESULT_OK || destination == null) {
            exportState.message = "已取消导出，已有截图和识别记录仍保留。";
            renderExportState();
            return;
        }
        exportState.busy = true;
        exportState.message = "";
        renderExportState();
        try {
            FarmAccessibilityService.exportDiagnostics(getApplicationContext(), destination, exportState);
        } catch (RuntimeException e) {
            exportState.onFailure("导出失败");
        }
    }

    private void renderExportState() {
        if (isFinishing() || isDestroyed()) return;
        exportButton.setEnabled(!exportState.busy && !exportState.picking);
        exportButton.setText(exportState.busy ? "正在导出…" : "导出诊断包");
        String message = exportState.busy ? "正在导出，请稍候；不会改变监测状态。"
                : exportState.picking ? "请选择诊断包的保存位置。"
                : exportState.message.isEmpty() ? "无需启动监测或启用无障碍服务即可导出已有记录。"
                : exportState.message;
        if (FarmAccessibilityService.getOcrProgress().hasUnsavedCapture)
            message += "\n还有截图未保存，可能不在诊断包内，先重试保存；请勿关闭应用。";
        exportStatus.setText(message);
    }

    // Retain only export state across rotation; the worker callback never owns an Activity.
    private static final class ExportState implements DiagnosticsExportCallback {
        private WeakReference<MainActivity> activity = new WeakReference<>(null);
        private boolean busy, picking;
        private String message = "";

        @Override public void onSuccess(int frameCount) {
            finish("已导出" + frameCount + "张截图及识别记录"
                    + (frameCount == 0 ? "（无截图，仅含诊断元数据）" : ""));
        }

        @Override public void onFailure(String ignored) {
            finish("导出失败，请检查保存位置和可用空间后重试。未完成的 ZIP 可能不完整，请勿用于核对。");
        }

        private void finish(String result) {
            busy = false;
            message = result;
            MainActivity current = activity.get();
            if (current != null) current.renderExportState();
        }
    }

    @Override public Object onRetainNonConfigurationInstance() { return exportState; }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("exportPicking", exportState.picking);
        state.putBoolean("exportBusy", exportState.busy);
        state.putBoolean("foregroundStartupPending", foregroundStartupPending);
        state.putLong("foregroundStartupDeadline", foregroundStartupDeadline);
        state.putString("foregroundErrorBeforeStart", foregroundErrorBeforeStart);
        super.onSaveInstanceState(state);
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

    private void openUsageAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException | SecurityException | IllegalArgumentException e) {
            try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
            catch (ActivityNotFoundException | SecurityException | IllegalArgumentException ignored) {
                showFeedback("无法打开使用情况访问设置，请在系统设置中查找“使用情况访问权限”并允许本应用。");
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        FarmAccessibilityService.refreshDiskProgress(this);
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
        if (exportState.activity.get() == this) exportState.activity.clear();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void refresh() {
        boolean supported = Build.VERSION.SDK_INT >= 30;
        boolean enabled = FarmAccessibilityService.isEnabled(this);
        boolean connected = FarmAccessibilityService.isConnected;
        boolean running = FarmAccessibilityService.isRunning;
        boolean useShizuku = ShizukuTaskGate.isEnabled(this);
        boolean usageAllowed = !useShizuku && UsageAccessGate.hasPermission(this);
        boolean sourceReady = useShizuku ? ShizukuTaskGate.get(this).hasPermission() : usageAllowed;
        usageAccessStatus.setText(useShizuku ? "使用情况访问 · 此模式不需要"
                : usageAllowed ? "使用情况访问 · 已允许" : "使用情况访问 · 未允许，不能新增截图");
        usageSettingsButton.setVisibility(useShizuku ? View.GONE : View.VISIBLE);
        shizukuStatus.setVisibility(useShizuku ? View.VISIBLE : View.GONE);
        shizukuPermissionButton.setVisibility(useShizuku ? View.VISIBLE : View.GONE);
        if (useShizuku) shizukuStatus.setText("Shizuku · " + ShizukuTaskGate.get(this).status());
        modeDescription.setText(useShizuku
                ? "当前：Shizuku 系统任务模式。支持用户已授权的 Shizuku Root 或 ADB Shell 运行方式；本工具不主动提权或启动 Shizuku，授权须自行点击确认。无需使用情况访问。只读固定系统任务命令，需前台任务、焦点、可见／RESUMED 状态与精确“QQ经典农场”任务描述标题匹配才截图；不是无障碍窗口标题。断连、撤权、快照过期或格式未知会暂停新增，不自动回退。权限范围实际比使用情况访问更大，Root 运行的授权风险更高；标题兼容性待真机验收，不保证实时或无风险。"
                : "当前：使用情况模式。需使用情况访问权限，以历史记录中的微信 AppBrand 小程序容器线索开始截图；不限固定容器编号，不能区分具体小程序，可能截图其他小游戏。不是农场身份核验。");
        boolean foregroundActive = MonitorForegroundService.isActive;
        String serviceError = MonitorForegroundService.lastError;
        boolean newServiceError = !blank(serviceError)
                && !serviceError.equals(foregroundErrorBeforeStart);
        if (foregroundStartupPending) {
            if (foregroundActive) {
                foregroundStartupPending = false;
                showFeedback(running ? (useShizuku
                        ? "监测已开启；等待系统任务绑定与前台快照，符合农场任务条件后开始截图。请手动进入农场，避免展示敏感页面。"
                        : "监测已开启；检测到小程序容器后开始截图。请手动进入农场，避免展示敏感页面。")
                        : "前台监测服务已启动，等待监测状态更新。");
            } else if (newServiceError) {
                foregroundStartupPending = false;
                showFeedback("前台监测未能启动，请检查无障碍服务连接及系统设置后重试。");
            } else if (SystemClock.uptimeMillis() >= foregroundStartupDeadline) {
                foregroundStartupPending = false;
                showFeedback("启动状态尚未确认，请查看监测状态后重试。");
            }
        }
        foregroundStatus.setText("前台监测服务 · " + (foregroundActive ? "运行中" : "未启动")
                + (foregroundStartupPending ? " · 等待启动" : ""));
        updatingMode = true;
        modeChoices.check(useShizuku ? shizukuMode.getId() : usageMode.getId());
        updatingMode = false;
        boolean modeEditable = canChangeMode();
        usageMode.setEnabled(modeEditable);
        shizukuMode.setEnabled(modeEditable);
        modeHint.setText(modeEditable ? "仅停止监测后可切换模式；切换不会自动开始。"
                : "监测运行或启动中，模式已锁定；请先停止监测，待前台服务停止后再切换。");
        boolean hasForegroundError = !foregroundActive && !foregroundStartupPending
                && (foregroundRequestFailed || !blank(serviceError));
        foregroundError.setVisibility(hasForegroundError ? View.VISIBLE : View.GONE);
        foregroundError.setText("前台监测启动或运行遇到问题，请检查无障碍服务连接及系统设置后重试。");
        String readiness = !supported ? "需要 Android 11 及以上；此设备不支持无障碍截图，无法开始监测。"
                : !enabled ? (connected
                        ? "服务当前不可用，等待系统恢复；请检查系统无障碍设置。连接标记仍在，但当前查询无法确认服务可用，不代表已关闭授权。"
                        : "服务当前不可用／等待系统连接；请检查系统无障碍设置。当前查询无法确认服务可用，不代表已关闭授权。")
                : !connected ? "等待系统连接无障碍服务；当前无法开始监测，请检查系统无障碍设置。"
                : "无障碍服务已连接";
        runningLabel.setText(!supported ? "○  此设备不支持监测"
                : !sourceReady ? (useShizuku ? "○  Shizuku 未就绪 · 不新增截图" : "○  使用情况访问未允许 · 不新增截图")
                : !enabled || !connected ? "○  服务尚未就绪"
                : running ? "●  监测已开启 · 截图状态见下方" : "○  监测未启动 · 队列保留");
        String status = FarmAccessibilityService.lastStatus;
        captureStatus.setText(readiness + "\n监测状态：" + monitorStatusText(status));
        OcrProgress progress = FarmAccessibilityService.getOcrProgress();
        renderExportState();
        processedCount.setText("已识别 " + progress.processed + " 张");
        pendingCount.setText("待识别 " + progress.queued + " 张");
        ocrState.setText((progress.recognizing ? "正在识别 1 张 · 不计入待识别"
                : !running ? "监测未启动 · 已保存队列保留，需手动开始"
                : "暂无正在识别的图片") + "\n" + capturePauseText(progress, running, connected));
        queueDetails.setText(String.format(Locale.CHINA,
                "成功归档（已留存）%d 张 · 失败保留 %d 张\n历史缺图/旧清理记录 %d 项 · 占用 %.1f MiB（含截图、OCR 和元数据）%s",
                progress.archivedCount, progress.failedCount, progress.cleanupPendingCount,
                progress.retainedBytes / (1024f * 1024f),
                (progress.writing ? "\n正在保存截图 · 尚未确认可靠落盘，请勿清除应用数据" : "")
                        + (progress.hasUnsavedCapture
                         ? "\n还有截图未保存，可能不在诊断包内，先重试保存；请勿关闭应用"
                        : progress.writing ? ""
                        : "WRITE_FAILED".equals(progress.pauseReason)
                        ? "\n可能存在未可靠保存的截图，请检查存储空间后重试" : "")));
        retryButton.setVisibility(progress.retryAvailable ? View.VISIBLE : View.GONE);
        retryButton.setEnabled(progress.retryAvailable);
        String diagnostics = FarmAccessibilityService.diagnostics;
        diagnosticsText.setText(blank(diagnostics) ? "暂无诊断数据。开始后可查看截图计数、OCR 处理与拒绝原因；不展示原图或完整识别文本。" : diagnostics);
        startButton.setEnabled(supported && !running && !foregroundActive
                && !foregroundStartupPending && (!sourceReady || !enabled || connected));
        startButton.setAlpha(startButton.isEnabled() ? 1f : 0.55f);
        startButton.setText(!supported ? "此设备不支持监测"
                : foregroundStartupPending ? "等待启动…" : !sourceReady
                ? (useShizuku ? "检查 Shizuku 运行与授权状态" : "设置使用情况访问权限")
                : !enabled ? "检查无障碍设置"
                : !connected ? "等待服务连接…" : running ? "监测已开启"
                : foregroundActive ? "前台监测服务运行中" : "开始监测");
        stopButton.setVisibility(running || foregroundActive || foregroundStartupPending ? View.VISIBLE : View.GONE);
        boolean notified = notificationsAllowed();
        monitorNotificationHint.setVisibility(notified ? View.GONE : View.VISIBLE);
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
            addText(empty, "开始监测后，请手动在微信打开好友农场的成熟时间详情。每次进入的首20秒内需识别到成熟倒计时，否则暂停截图；离开后重新进入或手动重启监测可重试。名称读不到不影响添加提醒；生长阶段倒计时不作为成熟时间。", 14, MUTED, false, 9);
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

    private String monitorStatusText(String status) {
        if (blank(status)) return "未运行";
        if ("已停止".equals(status)) return "监测已停止，已保存队列保留，需手动开始";
        if ("STORAGE_FAILED".equals(status)) return "本地存储遇到问题，监测已停止；请检查存储空间后手动重试";
        if ("OCR_TIMEOUT".equals(status)) return "图片识别超时，监测已停止；等待任务结束后手动开始";
        if ("LOW_SPACE".equals(status)) return "存储空间不足，暂停新增截图";
        if ("WRITE_FAILED".equals(status)) return "截图保存遇到问题，暂停新增截图";
        if ("SERVICE_UNAVAILABLE".equals(status)) return "截图服务暂不可用，请检查服务连接";
        if ("NOT_FOREGROUND".equals(status)) return "停止截图，已保存图片继续识别";
        if ("CAPTURE_NO_MATCH_TIMEOUT".equals(status))
            return "首20秒未识别到成熟倒计时，已暂停截图；离开后重新进入或手动重启监测可重试，已保存图片继续识别";
        if (!ShizukuTaskGate.isEnabled(this) && status.startsWith("等待微信前台"))
            return "等待使用记录中的微信小程序容器线索；请手动进入农场。判断可能延迟，已保存图片继续识别";
        if (status.startsWith("处理完成:")) return "图片处理完成，不代表已添加提醒";
        return status;
    }

    private String capturePauseText(OcrProgress progress, boolean running, boolean connected) {
        if ("LOW_SPACE".equals(progress.pauseReason))
            return "存储空间不足 · 暂停新增截图，已保存图片仍可处理";
        if ("WRITE_FAILED".equals(progress.pauseReason))
            return "截图保存遇到问题 · 暂停新增截图，请检查存储空间";
        if ("SERVICE_UNAVAILABLE".equals(progress.pauseReason) || !connected)
            return "截图服务尚未就绪 · 已保存图片保留";
        if ("STOPPED".equals(progress.pauseReason) || !running)
            return "监测已停止 · 不再截图，队列保留等待手动开始";
        if (ShizukuTaskGate.isEnabled(this) && !ShizukuTaskGate.get(this).hasPermission())
            return "Shizuku 未就绪 · 暂停新增截图，不自动回退；已保存图片仍可处理";
        if (!ShizukuTaskGate.isEnabled(this) && !UsageAccessGate.hasPermission(this))
            return "使用情况访问未允许 · 不新增截图，已保存图片仍可处理";
        if ("NOT_FOREGROUND".equals(progress.pauseReason))
            return "当前不满足截图条件 · 停止截图，已保存图片继续识别";
        if (progress.capturePaused) return "新增截图已暂停 · 请查看监测状态，已保存图片保留";
        return (ShizukuTaskGate.isEnabled(this) ? "农场系统任务条件满足时截图" : "小程序容器内截图")
                + "，首20秒等待成熟倒计时 · 具体状态见监测状态，已保存图片按序处理";
    }

    private boolean canChangeMode() {
        return !FarmAccessibilityService.isRunning && !MonitorForegroundService.isActive
                && !foregroundStartupPending;
    }

    private RadioButton modeChoice(String text) {
        RadioButton choice = new RadioButton(this);
        choice.setId(View.generateViewId());
        choice.setSaveEnabled(false);
        choice.setText(text);
        choice.setTextSize(14);
        choice.setTextColor(INK);
        choice.setButtonTintList(android.content.res.ColorStateList.valueOf(GREEN));
        choice.setMinHeight(dp(48));
        modeChoices.addView(choice, new RadioGroup.LayoutParams(-1, -2));
        return choice;
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
