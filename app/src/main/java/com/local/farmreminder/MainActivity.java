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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

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
    private TextView foregroundStatus, foregroundError;
    private TextView modeHint, modeDescription, shizukuStatus;
    private RadioGroup modeChoices;
    private RadioButton usageMode, shizukuMode;
    private boolean updatingMode;
    private Button startButton, stopButton, retryButton, notificationButton, exactButton, clearButton;
    private Button exportButton;
    private TextView exportStatus;
    private TextView vibrationStatus, setupVibrationStatus;
    private TextView vibrationHint, setupVibrationHint;
    private ExportState exportState;
    private LinearLayout reminders;
    private AvatarViewBinder avatars;
    private SharedPreferences settings;
    private ReminderStore store;
    private AlarmScheduler scheduler;
    private int leadSeconds;
    private boolean resumed;
    private boolean foregroundStartupPending, foregroundRequestFailed;
    private long lifecycleAttempt;
    private long foregroundStartupDeadline;
    private String foregroundErrorBeforeStart = "";
    private String listSignature = "";
    private String feedbackMessage = "";
    private final Set<String> expandedGroups = new HashSet<>();
    private boolean historyExpanded;
    private LinearLayout homePage, settingsPage, toolsPage, setupPage, navigation;
    private TextView homeMode, setupProgress, setupTitle, setupDetail;
    private LinearLayout setupModes;
    private Button setupAction, notificationContinue, setupBack;
    private boolean setupVisible, reviewMode, screenBuilt;
    private String currentPage = "home";
    private SetupFlowPolicy.Step displayedStep;
    private SetupFlowPolicy.Step backStep;
    private boolean lastRequiredReady;
    private android.window.OnBackInvokedCallback backCallback;
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
            feedbackMessage = state.getString("feedbackMessage", "");
            historyExpanded = state.getBoolean("historyExpanded", false);
            ArrayList<String> expanded = state.getStringArrayList("expandedGroups");
            if (expanded != null) expandedGroups.addAll(expanded);
            foregroundStartupPending = state.getBoolean("foregroundStartupPending", false);
            lifecycleAttempt = state.getLong("lifecycleGeneration", 0) == LifecycleDiagnostics.generation(this) ? state.getLong("lifecycleAttempt", 0) : 0;
            foregroundStartupDeadline = state.getLong("foregroundStartupDeadline", 0);
            foregroundErrorBeforeStart = state.getString("foregroundErrorBeforeStart", "");
            currentPage = state.getString("currentPage", "home");
            setupVisible = state.getBoolean("setupVisible", false);
            reviewMode = state.getBoolean("reviewMode", false);
            String savedStep = state.getString("backStep", "");
            try { if (!savedStep.isEmpty()) backStep = SetupFlowPolicy.Step.valueOf(savedStep); }
            catch (IllegalArgumentException ignored) { backStep = null; }
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
        avatars = new AvatarViewBinder(this, reminders);
        if (state == null) {
            setupVisible = setupStep() != SetupFlowPolicy.Step.READY;
            reviewMode = setupStep() == SetupFlowPolicy.Step.MODE;
        }
        screenBuilt = true;
        lastRequiredReady = SetupFlowPolicy.requiredReady(setupStep());
        refresh();
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
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout page = column();
        page.setPadding(dp(20), dp(26), dp(20), dp(28));
        scroll.addView(page);
        homePage = column(); settingsPage = column(); toolsPage = column(); setupPage = column();
        page.addView(homePage); page.addView(settingsPage); page.addView(toolsPage); page.addView(setupPage);
        addText(homePage, "农场提醒", 30, INK, true, 0);
        homeMode = addText(homePage, "使用情况模式", 13, MUTED, false, 6);

        LinearLayout capture = card(homePage, 24, 0xFFE4EEDD);
        addText(capture, "监测", 12, GREEN, true, 0);
        runningLabel = addText(capture, "准备就绪", 26, INK, true, 12);
        captureStatus = addText(capture, "", 14, MUTED, false, 8);
        startButton = button(capture, "开始监测", true, 22, this::requestMonitoring);
        stopButton = button(capture, "停止监测", true, 22, () -> {
            try {
                MonitorForegroundService.requestStop(this); foregroundStartupPending = false;
                showFeedback("已停止，截图与提醒保留");
            } catch (RuntimeException e) { showFeedback("停止失败，请重试"); }
            refresh();
        });
        LinearLayout counts = new LinearLayout(this);
        homePage.addView(counts, spaced(-1, -2, 20));
        LinearLayout completed = column(), pending = column();
        counts.addView(completed, new LinearLayout.LayoutParams(0, -2, 1));
        counts.addView(pending, new LinearLayout.LayoutParams(0, -2, 1));
        processedCount = addText(completed, "已识别 0 张", 17, INK, true, 0);
        pendingCount = addText(pending, "待处理 0 张", 17, INK, true, 0);
        listTitle = addText(homePage, "待提醒 0 组", 21, INK, true, 30);
        reminders = column(); homePage.addView(reminders, spaced(-1, -2, 8));

        addText(toolsPage, "工具", 28, INK, true, 0);
        LinearLayout progress = card(toolsPage, 22, Color.WHITE);
        addText(progress, "处理与存储", 19, INK, true, 0);
        ocrState = addText(progress, "", 13, INK, false, 12);
        queueDetails = addText(progress, "", 13, MUTED, false, 10);
        addText(progress, "已识别指完成识别尝试，不是新增提醒数。待处理不含正在识别及失败图片。", 12, MUTED, false, 10);
        retryButton = button(progress, "重试保存/失败图片", false, 10, () -> {
            try {
                FarmAccessibilityService.retryFailedFrames(this);
                showFeedback(FarmAccessibilityService.isRunning
                        ? "已请求重试" : "已请求重试，手动开始监测后继续识别");
            } catch (RuntimeException e) { showFeedback("未能重试，失败图片仍保留"); }
        });
        retryButton.setVisibility(View.GONE);
        LinearLayout export = card(toolsPage, 16, Color.WHITE);
        addText(export, "诊断包", 19, INK, true, 0);
        addText(export, "含截图、识别文字及昵称，仅交给可信人员。", 13, MUTED, false, 8);
        exportButton = button(export, "导出 ZIP", false, 12, () ->
                new AlertDialog.Builder(this).setTitle("导出诊断包？")
                        .setMessage("包含保留的整屏截图与识别记录，可能含其他页面或敏感内容。请选择可信的保存位置。")
                        .setNegativeButton("取消", null).setPositiveButton("选择位置", (d, w) -> requestDiagnosticsExport()).show());
        exportStatus = addText(export, "", 12, MUTED, false, 8);
        Button diagnosticsToggle = button(toolsPage, "展开诊断", false, 16, () -> {});
        LinearLayout technical = card(toolsPage, 8, Color.WHITE);
        technical.setVisibility(View.GONE);
        foregroundStatus = addText(technical, "", 13, INK, false, 0);
        foregroundError = addText(technical, "", 13, INK, false, 8);
        diagnosticsText = addText(technical, "", 12, MUTED, false, 10);
        diagnosticsText.setTextIsSelectable(true);
        diagnosticsToggle.setOnClickListener(v -> {
            boolean expanded = technical.getVisibility() != View.VISIBLE;
            technical.setVisibility(expanded ? View.VISIBLE : View.GONE);
            diagnosticsToggle.setText(expanded ? "收起诊断" : "展开诊断");
        });
        button(technical, "刷新诊断", false, 10, () -> {
            FarmAccessibilityService.refreshDiskProgress(this); refresh();
        });

        addText(settingsPage, "设置", 28, INK, true, 0);
        LinearLayout permissions = card(settingsPage, 22, Color.WHITE);
        addText(permissions, "模式与权限", 19, INK, true, 0);
        button(permissions, "权限中心", false, 12, () -> {
            reviewMode = true; backStep = null; setupVisible = true; refresh();
        });
        notificationStatus = addText(permissions, "", 14, INK, false, 16);
        notificationButton = button(permissions, "管理通知", false, 7, this::requestNotifications);
        exactStatus = addText(permissions, "", 14, INK, false, 16);
        exactButton = button(permissions, "管理精确闹钟（可选）", false, 7, this::requestExactAlarm);
        LinearLayout notificationSettings = card(settingsPage, 16, Color.WHITE);
        vibrationStatus = buildNotificationSettings(notificationSettings);
        vibrationHint = notificationSettingsHint(notificationSettings);
        LinearLayout timing = card(settingsPage, 16, Color.WHITE);
        addText(timing, "提前多久提醒", 18, INK, true, 0);
        addText(timing, "仅用于新记录的提醒", 12, MUTED, false, 7);
        LinearLayout choices = new LinearLayout(this);
        timing.addView(choices, spaced(-1, -2, 13));
        int[] values = {0, 60, 300};
        String[] labels = {"成熟时", "1 分钟", "5 分钟"};
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
            });
        }
        updateLeadButtons();

        button(timing, "测试提醒 · 10 秒后", false, 13, () -> {
            try {
                showMessage(AlarmPlan.testScheduleMessage(scheduler.scheduleTest(10)));
            } catch (RuntimeException e) { showMessage("测试未能安排，请检查设置"); }
        });

        clearButton = button(settingsPage, "清理已提交提醒", false, 16, () ->
                new AlertDialog.Builder(this).setTitle("清除已提交记录？")
                        .setMessage("只移除已提交提醒的记录，不影响待提醒项目。提交不代表实际送达。")
                        .setNegativeButton("取消", null).setPositiveButton("清除", (d, w) -> {
                            store.clearFired(); refresh();
                        }).show());
        button(settingsPage, "截图与隐私", false, 8, this::showPrivacy);
        buildSetupPanel();
        feedback = addText(root, feedbackMessage, 13, GREEN, false, 0);
        feedback.setPadding(dp(20), dp(10), dp(20), dp(10));
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(feedbackMessage.isEmpty() ? View.GONE : View.VISIBLE);
        navigation = new LinearLayout(this);
        navigation.setPadding(dp(14), dp(6), dp(14), dp(10));
        root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        for (String name : new String[]{"监测", "设置", "工具"}) {
            Button tab = makeButton(name, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(3), 0, dp(3), 0); navigation.addView(tab, lp);
            tab.setOnClickListener(v -> {
                currentPage = "设置".equals(name) ? "settings" : "工具".equals(name) ? "tools" : "home";
                setupVisible = false; reviewMode = false; backStep = null; refresh(); scroll.smoothScrollTo(0, 0);
            });
        }
    }

    private void buildSetupPanel() {
        setupProgress = addText(setupPage, "准备", 13, GREEN, true, 6);
        setupTitle = addText(setupPage, "选择监测方式", 28, INK, true, 24);
        setupDetail = addText(setupPage, "", 15, MUTED, false, 12);
        setupModes = card(setupPage, 24, Color.WHITE);
        modeChoices = new RadioGroup(this);
        modeChoices.setOrientation(RadioGroup.VERTICAL); modeChoices.setSaveEnabled(false);
        setupModes.addView(modeChoices);
        usageMode = modeChoice("使用情况 · 默认"); shizukuMode = modeChoice("Shizuku");
        modeChoices.check(ShizukuTaskGate.isEnabled(this) ? shizukuMode.getId() : usageMode.getId());
        modeChoices.setOnCheckedChangeListener((group, id) -> {
            if (updatingMode) return;
            if (!canChangeMode()) { showFeedback("请先停止监测"); refresh(); return; }
            settings.edit().putBoolean("useShizuku", id == shizukuMode.getId()).apply(); refresh();
        });
        modeHint = addText(setupModes, "", 12, MUTED, false, 8);
        modeDescription = addText(setupModes, "", 13, MUTED, false, 8);
        shizukuStatus = addText(setupPage, "", 14, GREEN, true, 20);
        setupAction = button(setupPage, "继续", true, 24, this::performSetupAction);
        notificationContinue = button(setupPage, "暂不开启，继续", false, 8, () -> {
            if (displayedStep != SetupFlowPolicy.Step.NOTIFICATIONS) return;
            settings.edit().putBoolean("notificationDeferred", true).apply();
            reviewMode = false; backStep = null; setupVisible = false; currentPage = "home"; refresh();
        });
        setupBack = button(setupPage, "返回", false, 8, this::navigateBack);
        LinearLayout notificationSettings = card(setupPage, 24, Color.WHITE);
        setupVibrationStatus = buildNotificationSettings(notificationSettings);
        setupVibrationHint = notificationSettingsHint(notificationSettings);
    }

    private TextView buildNotificationSettings(LinearLayout parent) {
        addText(parent, "通知与振动", 18, INK, true, 0);
        TextView status = addText(parent, "", 14, INK, false, 10);
        button(parent, "通知与振动设置", false, 10, () -> {
            NotificationSettingsPolicy.Target opened = NotificationSettingsNavigator.open(this);
            showMessage(opened == null ? "无法打开设置，请在系统设置中找到本应用并调整。"
                    : "请在系统设置中调整通知与振动，返回后将刷新状态。");
        });
        return status;
    }

    private TextView notificationSettingsHint(LinearLayout parent) {
        return addText(parent, "", 12, MUTED, false, 8);
    }

    private void refreshNotificationSettings() {
        NotificationSettingsPolicy.State state = NotificationSettingsNavigator.read(this);
        String hint = (state == NotificationSettingsPolicy.State.VIBRATION_OFF
                ? "点击上方按钮，开启渠道的振动。\n" : "")
                + "需在系统设置中开启；实际振动还受勿扰等设置影响。";
        for (TextView status : new TextView[]{vibrationStatus, setupVibrationStatus}) {
            if (!state.label.contentEquals(status.getText())) status.setText(state.label);
        }
        for (TextView label : new TextView[]{vibrationHint, setupVibrationHint}) {
            if (!hint.contentEquals(label.getText())) label.setText(hint);
        }
    }

    private void requestMonitoring() {
        long clickAttempt = LifecycleDiagnostics.click(this);
        LifecycleDiagnostics.event(this, clickAttempt, LifecycleHistory.Event.UI_CLICK, LifecycleHistory.Reason.NONE, null);
        if (foregroundStartupPending || MonitorForegroundService.isActive || FarmAccessibilityService.isRunning) {
            LifecycleDiagnostics.event(this, clickAttempt, LifecycleHistory.Event.SETUP_REJECT, LifecycleHistory.Reason.ALREADY_ACTIVE, null); return;
        }
        if (setupStep() != SetupFlowPolicy.Step.READY) {
            LifecycleDiagnostics.event(this, clickAttempt, LifecycleHistory.Event.SETUP_REJECT, LifecycleHistory.Reason.SETUP, null);
            setupVisible = true; reviewMode = false; backStep = null; refresh(); return;
        }
        lifecycleAttempt = clickAttempt;
        LifecycleDiagnostics.setCurrent(this, lifecycleAttempt);
        try {
            foregroundErrorBeforeStart = MonitorForegroundService.lastError;
            LifecycleDiagnostics.event(this, lifecycleAttempt, LifecycleHistory.Event.START_REQUEST, LifecycleHistory.Reason.NONE, null);
            MonitorForegroundService.requestStart(this, lifecycleAttempt);
            foregroundStartupPending = true;
            foregroundRequestFailed = false;
            foregroundStartupDeadline = SystemClock.uptimeMillis() + 5000;
            clearFeedback();
        } catch (RuntimeException e) {
            LifecycleDiagnostics.event(this, lifecycleAttempt, LifecycleHistory.Event.START_REQUEST, LifecycleHistory.Reason.EXCEPTION, e);
            foregroundStartupPending = false;
            foregroundRequestFailed = true;
            showFeedback("无法启动，请检查截图服务后重试");
        }
        refresh();
    }

    private SetupFlowPolicy.Step setupStep() {
        boolean shizuku = ShizukuTaskGate.isEnabled(this);
        return SetupFlowPolicy.next(Build.VERSION.SDK_INT >= 30,
                settings.getBoolean("modeChosen", false), shizuku,
                !shizuku && UsageAccessGate.hasPermission(this),
                shizuku && ShizukuTaskGate.get(this).hasPermission(),
                FarmAccessibilityService.isEnabled(this), FarmAccessibilityService.isConnected,
                notificationsAllowed(), settings.getBoolean("notificationDeferred", false));
    }

    private void renderNavigation() {
        SetupFlowPolicy.Step actual = setupStep();
        boolean ready = SetupFlowPolicy.requiredReady(actual);
        if (screenBuilt && lastRequiredReady && !ready && !reviewMode) {
            setupVisible = true; reviewMode = false; backStep = null;
        }
        lastRequiredReady = ready;
        if (setupVisible && actual == SetupFlowPolicy.Step.MODE && backStep == null) reviewMode = true;
        if (setupVisible && actual == SetupFlowPolicy.Step.READY && !reviewMode && backStep == null) {
            setupVisible = false; currentPage = "home";
        }
        homePage.setVisibility(!setupVisible && "home".equals(currentPage) ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(!setupVisible && "settings".equals(currentPage) ? View.VISIBLE : View.GONE);
        toolsPage.setVisibility(!setupVisible && "tools".equals(currentPage) ? View.VISIBLE : View.GONE);
        setupPage.setVisibility(setupVisible ? View.VISIBLE : View.GONE);
        navigation.setVisibility(setupVisible ? View.GONE : View.VISIBLE);
        if (Build.VERSION.SDK_INT >= 33) updateBackCallback();
        for (int i = 0; i < navigation.getChildCount(); i++) {
            Button tab = (Button) navigation.getChildAt(i);
            boolean selected = i == ("settings".equals(currentPage) ? 1 : "tools".equals(currentPage) ? 2 : 0);
            tab.setSelected(selected);
            tab.setTextColor(selected ? Color.WHITE : GREEN);
            tab.setBackground(background(selected ? GREEN : 0xFFF0F3EB, 13, 0));
            tab.setContentDescription(tab.getText() + (selected ? "，当前页面" : ""));
        }
        if (!setupVisible) return;
        displayedStep = SetupFlowPolicy.displayedStep(actual, reviewMode, backStep);
        boolean mode = displayedStep == SetupFlowPolicy.Step.MODE;
        setupModes.setVisibility(mode ? View.VISIBLE : View.GONE);
        shizukuStatus.setVisibility(displayedStep == SetupFlowPolicy.Step.SOURCE && ShizukuTaskGate.isEnabled(this)
                ? View.VISIBLE : View.GONE);
        setupAction.setEnabled(true);
        notificationContinue.setVisibility(displayedStep == SetupFlowPolicy.Step.NOTIFICATIONS
                ? View.VISIBLE : View.GONE);
        setupBack.setText(displayedStep == SetupFlowPolicy.Step.WAITING ? "返回截图设置" : "返回");
        switch (displayedStep) {
            case MODE:
                setupProgress.setText("准备"); setupTitle.setText("选择监测方式");
                setupDetail.setText("只设置所选方式需要的权限"); setupAction.setText("继续"); break;
            case SOURCE:
                setupProgress.setText("1 / 3");
                if (ShizukuTaskGate.isEnabled(this)) {
                    setupTitle.setText("连接 Shizuku");
                    setupDetail.setText("先启动 Shizuku，再点击授权");
                    shizukuStatus.setText(shortShizukuStatus());
                    setupAction.setText(ShizukuTaskGate.get(this).hasPermission() ? "继续" : "授权 / 刷新");
                } else {
                    setupTitle.setText("应用使用情况权限");
                    setupDetail.setText("用于判断微信小程序是否在前台");
                    setupAction.setText(UsageAccessGate.hasPermission(this) ? "继续" : "去授权");
                }
                break;
            case SCREENSHOT:
                setupProgress.setText("2 / 3"); setupTitle.setText("启用截图服务");
                setupDetail.setText("整屏截图可能包含其他内容；截图和识别文字在本机保留，可导出。");
                setupAction.setText(FarmAccessibilityService.isEnabled(this) ? "查看系统设置" : "前往启用"); break;
            case WAITING:
                setupProgress.setText("2 / 3"); setupTitle.setText("等待截图服务连接");
                setupDetail.setText("服务已启用，正在等待系统连接");
                setupAction.setText("刷新"); break;
            case NOTIFICATIONS:
                setupProgress.setText("3 / 3 · 可选"); setupTitle.setText("开启通知");
                setupDetail.setText("用于成熟提醒和监测状态；不开启仍可监测，但提醒可能不可见。");
                setupAction.setText("允许通知"); break;
            case UNSUPPORTED:
                setupProgress.setText("设备限制"); setupTitle.setText("需要 Android 11 及以上");
                setupDetail.setText("此设备不能截图，已有提醒与导出仍可使用");
                setupAction.setText("返回首页"); break;
            default:
                setupProgress.setText("已完成"); setupTitle.setText("准备就绪");
                setupDetail.setText("返回首页后，手动开始监测"); setupAction.setText("返回首页");
        }
    }

    private String shortShizukuStatus() {
        String status = ShizukuTaskGate.get(this).status();
        if (ShizukuTaskGate.get(this).hasPermission()) return "已连接";
        // Full backend status stays in the diagnostics panel, not in the permission prompt.
        if (status != null && (status.contains("未运行") || status.contains("未安装") || status.contains("不可用")))
            return "尚未连接，请先启动 Shizuku";
        return "等待连接或授权";
    }

    private void performSetupAction() {
        SetupFlowPolicy.Step step = displayedStep;
        clearFeedback();
        backStep = null;
        switch (step) {
            case MODE:
                settings.edit().putBoolean("modeChosen", true).apply(); reviewMode = false; break;
            case SOURCE:
                if (ShizukuTaskGate.isEnabled(this)) {
                    if (!ShizukuTaskGate.get(this).hasPermission()) ShizukuTaskGate.get(this).requestPermission();
                } else if (!UsageAccessGate.hasPermission(this)) openUsageAccessSettings();
                break;
            case SCREENSHOT:
                openSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                break;
            case WAITING: break; // A refresh is never an automatic trip back into settings.
            case NOTIFICATIONS: requestNotificationPermission(); break;
            default: setupVisible = false; currentPage = "home"; reviewMode = false;
        }
        refresh();
    }

    private void navigateBack() {
        if (setupVisible) {
            if (displayedStep == SetupFlowPolicy.Step.MODE || displayedStep == SetupFlowPolicy.Step.UNSUPPORTED) {
                setupVisible = false; reviewMode = false; backStep = null; currentPage = "home";
            } else if (displayedStep == SetupFlowPolicy.Step.SOURCE) {
                reviewMode = true; backStep = null;
            } else if (displayedStep == SetupFlowPolicy.Step.NOTIFICATIONS) {
                backStep = SetupFlowPolicy.Step.SCREENSHOT;
            } else if (displayedStep == SetupFlowPolicy.Step.WAITING) {
                backStep = SetupFlowPolicy.Step.SCREENSHOT;
            } else {
                backStep = SetupFlowPolicy.Step.SOURCE;
            }
        } else { currentPage = "home"; }
        clearFeedback();
        refresh();
    }

    @Override public void onBackPressed() {
        if (setupVisible || !"home".equals(currentPage)) navigateBack();
        else super.onBackPressed();
    }

    @android.annotation.TargetApi(33)
    private void updateBackCallback() {
        boolean intercept = setupVisible || !"home".equals(currentPage);
        if (intercept && backCallback == null) {
            backCallback = this::navigateBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        } else if (!intercept && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
    }

    private void showPrivacy() {
        new AlertDialog.Builder(this).setTitle("截图与隐私")
                .setMessage("截图是整屏画面，切换页面、通知或覆盖层可能使截图包含其他应用或系统内容。请先关闭敏感页面。\n\n"
                        + "截图与 OCR 文字在本机保存，识别后仍保留，不自动删除或上传，可手动导出；诊断包可能含昵称等敏感信息，仅交给可信人员。停止监测会暂停截图和队列处理，保留图片与提醒。\n\n"
                        + "使用情况模式不能区分具体小游戏，首次验证截图可能是其他小游戏或页面，也会在本地保留、可导出；文字特征仅作启发式判断，验证通过后仍无法感知微信复用容器切换到其他小游戏。Shizuku 模式按系统任务判断，支持已授权的 Root / Shell，权限范围更大，Root 风险更高。不读取无障碍节点或窗口列表，不监听界面事件。前台判断和截图并非同时完成，多窗口不可靠，不能保证只截农场或避免微信风控。\n\n"
                        + "不会自动点击微信或操作游戏。使用情况模式进入小程序候选后，仅截 1 张在本地验证；匹配农场加载页或自己家后才持续记录，未匹配或验证失败则本次停止截图。退出小程序后重进，或手动停止再开始，可重新验证。Shizuku 模式仍按农场系统任务条件持续截图。昵称未识别仍可记录。通知、振动和提醒时间受权限与系统设置影响。")
                .setPositiveButton("知道了", null).show();
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
        exportButton.setText(exportState.busy ? "正在导出…" : "导出 ZIP");
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
        state.putString("feedbackMessage", feedbackMessage);
        state.putBoolean("historyExpanded", historyExpanded);
        state.putStringArrayList("expandedGroups", new ArrayList<>(expandedGroups));
        state.putBoolean("exportPicking", exportState.picking);
        state.putBoolean("exportBusy", exportState.busy);
        state.putBoolean("foregroundStartupPending", foregroundStartupPending);
        state.putLong("lifecycleGeneration", LifecycleDiagnostics.generation(this));
        state.putLong("lifecycleAttempt", lifecycleAttempt);
        state.putLong("foregroundStartupDeadline", foregroundStartupDeadline);
        state.putString("foregroundErrorBeforeStart", foregroundErrorBeforeStart);
        state.putString("currentPage", currentPage);
        state.putBoolean("setupVisible", setupVisible);
        state.putBoolean("reviewMode", reviewMode);
        state.putString("backStep", backStep == null ? "" : backStep.name());
        super.onSaveInstanceState(state);
    }

    private boolean notificationsAllowed() {
        return (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED)
                && ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).areNotificationsEnabled();
    }

    private void requestNotifications() {
        if (notificationsAllowed()) {
            openSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            return;
        }
        new AlertDialog.Builder(this).setTitle("开启通知")
                .setMessage("用于成熟提醒和监测状态；不开启仍可监测，但提醒可能不可见。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("继续", (d, w) -> requestNotificationPermission()).show();
    }

    private void requestNotificationPermission() {
        // This is a skipped optional offer, never an assertion that permission was granted.
        settings.edit().putBoolean("notificationDeferred", true).apply();
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
        else scheduler.rescheduleAll();
        refresh();
    }

    private void requestExactAlarm() {
        if (Build.VERSION.SDK_INT >= 31) {
            openSettings(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
        } else showMessage("此设备已支持精确提醒，仍可能受系统设置影响");
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
        if (avatars != null) avatars.close();
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null)
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
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
        modeDescription.setText(useShizuku
                ? "按系统任务判断农场前台"
                : "通过使用记录判断小程序，无法区分具体小游戏。");
        boolean foregroundActive = MonitorForegroundService.isActive;
        String serviceError = MonitorForegroundService.lastError;
        boolean newServiceError = !blank(serviceError)
                && !serviceError.equals(foregroundErrorBeforeStart);
        if (foregroundStartupPending) {
            if (foregroundActive) {
                LifecycleDiagnostics.event(this, lifecycleAttempt, LifecycleHistory.Event.PENDING_CONFIRMED, LifecycleHistory.Reason.NONE, null);
                foregroundStartupPending = false;
                clearFeedback();
            } else if (newServiceError) {
                LifecycleDiagnostics.event(this, lifecycleAttempt, LifecycleHistory.Event.PENDING_FAILED, LifecycleHistory.Reason.NOT_RUNNING, null);
                foregroundStartupPending = false;
                showFeedback("启动失败，请检查截图服务后重试");
            } else if (SystemClock.uptimeMillis() >= foregroundStartupDeadline) {
                LifecycleDiagnostics.event(this, lifecycleAttempt, LifecycleHistory.Event.PENDING_TIMEOUT, LifecycleHistory.Reason.NONE, null);
                foregroundStartupPending = false;
                showFeedback("启动尚未确认，请检查后重试");
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
        modeHint.setText(!modeEditable ? "请先停止监测，再切换模式" : useShizuku
                ? "选择后不会自动开始监测"
                : "应用使用情况权限 · " + (usageAllowed ? "已授权" : "未授权"));
        boolean hasForegroundError = !foregroundActive && !foregroundStartupPending
                && (foregroundRequestFailed || !blank(serviceError));
        foregroundError.setVisibility(hasForegroundError ? View.VISIBLE : View.GONE);
        foregroundError.setText("前台监测启动或运行遇到问题，请检查无障碍服务连接及系统设置后重试。");
        homeMode.setText(useShizuku ? "Shizuku" : "使用情况模式");
        boolean requiredReady = supported && sourceReady && enabled && connected;
        runningLabel.setText(!supported ? "设备暂不支持" : foregroundStartupPending ? "正在启动"
                : !requiredReady ? "需要完成设置" : running ? "监测中" : foregroundActive ? "正在停止" : "准备就绪");
        captureStatus.setText(!supported ? "截图需要 Android 11 及以上"
                : !requiredReady ? "完成权限设置后，手动开始监测"
                : running ? monitorStatusText(FarmAccessibilityService.lastStatus)
                        + (notificationsAllowed() ? "" : "；通知未开启，提醒可能不可见")
                : !notificationsAllowed() ? "通知未开启，提醒可能不可见；可返回此处停止"
                : "手动开始后，进入好友农场查看成熟时间");
        OcrProgress progress = FarmAccessibilityService.getOcrProgress();
        renderExportState();
        processedCount.setText("已识别 " + progress.processed + " 张");
        pendingCount.setText("待处理 " + progress.queued + " 张");
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
        diagnosticsText.setText("监测状态：" + FarmAccessibilityService.lastStatus
                + (useShizuku ? "\nShizuku：" + ShizukuTaskGate.get(this).status() : "")
                + "\n" + (blank(diagnostics) ? "暂无诊断数据" : diagnostics));
        boolean active = running || foregroundActive || foregroundStartupPending;
        startButton.setVisibility(active ? View.GONE : View.VISIBLE);
        startButton.setEnabled(supported && !active);
        startButton.setAlpha(startButton.isEnabled() ? 1f : 0.55f);
        startButton.setText(!supported ? "设备不支持" : !requiredReady ? "完成设置" : "开始监测");
        stopButton.setVisibility(active ? View.VISIBLE : View.GONE);
        boolean notified = notificationsAllowed();
        notificationStatus.setText(notified ? "通知 · 已开启" : "通知 · 未开启");
        notificationButton.setText(notified ? "管理通知" : "开启通知");
        refreshNotificationSettings();
        boolean exact = scheduler.canScheduleExact();
        exactStatus.setText(exact ? "精确闹钟 · 已开启" : "精确闹钟 · 可选，未开启可能延迟");
        renderNavigation();
        List<Reminder> items = store.list();
        ReminderListGroups groups = new ReminderListGroups(items);
        long now = System.currentTimeMillis();
        boolean hasFired = false;
        for (Reminder r : items) {
            hasFired |= r.fired;
        }
        listTitle.setText(groups.title());
        clearButton.setVisibility(hasFired ? View.VISIBLE : View.GONE);
        String next = ReminderListGroups.signature(items, now);
        if (next.equals(listSignature)) return;
        listSignature = next;
        avatars.clearRows();
        reminders.removeAllViews();
        if (items.isEmpty()) {
            LinearLayout empty = card(reminders, 10, 0xFFEDEFE6);
            addText(empty, "还没有成熟提醒", 17, INK, true, 0);
            return;
        }
        SimpleDateFormat date = new SimpleDateFormat("yyyy年M月d日 HH:mm:ss", Locale.CHINA);
        for (ReminderGrouping.Group group : groups.pending) {
            LinearLayout row = card(reminders, 10, Color.WHITE);
            if (group.reminders.size() == 1) {
                renderReminder(row, group.anchor(), date, now);
                continue;
            }
            addText(row, group.due(now) ? "提醒时间已到 · 待提交" : "待提醒", 12, GREEN, true, 0);
            renderAvatarTime(row, group.anchor(), ReminderListGroups.avatarRef(group),
                    ReminderListGroups.summary(group), "最早成熟");
            LinearLayout details = column();
            Button toggle = button(row, "", false, 10, () -> {});
            row.addView(details, spaced(-1, -2, 0));
            for (Reminder r : group.reminders) {
                LinearLayout detail = column();
                detail.setPadding(0, dp(16), 0, dp(8));
                details.addView(detail);
                renderReminder(detail, r, date, now);
            }
            boolean expanded = expandedGroups.contains(group.identity);
            details.setVisibility(expanded ? View.VISIBLE : View.GONE);
            toggle.setText(expanded ? "收起明细" : "查看 " + group.reminders.size() + " 条明细");
            toggle.setOnClickListener(v -> {
                boolean open = details.getVisibility() != View.VISIBLE;
                if (open) expandedGroups.add(group.identity); else expandedGroups.remove(group.identity);
                details.setVisibility(open ? View.VISIBLE : View.GONE);
                toggle.setText(open ? "收起明细" : "查看 " + group.reminders.size() + " 条明细");
            });
        }
        if (!groups.history.isEmpty()) {
            Button toggle = button(reminders, "已提醒 · " + groups.history.size() + " 条 · "
                    + (historyExpanded ? "收起" : "展开"), false, 16, () -> {});
            LinearLayout history = column();
            reminders.addView(history);
            history.setVisibility(historyExpanded ? View.VISIBLE : View.GONE);
            addText(history, "仅为历史记录，已提交不代表实际送达。", 12, MUTED, false, 8);
            for (Reminder r : groups.history) renderReminder(card(history, 10, Color.WHITE), r, date, now);
            toggle.setOnClickListener(v -> {
                historyExpanded = !historyExpanded;
                history.setVisibility(historyExpanded ? View.VISIBLE : View.GONE);
                toggle.setText("已提醒 · " + groups.history.size() + " 条 · " + (historyExpanded ? "收起" : "展开"));
            });
        }
    }

    private void renderReminder(LinearLayout row, Reminder r, SimpleDateFormat date, long now) {
        addText(row, r.fired ? "已提交提醒" : r.triggerAt <= now ? "提醒时间已到 · 待提交" : "待提醒", 12,
                r.fired ? MUTED : GREEN, true, 0);
        renderAvatarTime(row, r, r.avatarRef, "好友", "成熟");
        addText(row, "作物：" + (blank(r.crop) ? "未识别" : r.crop), 13, MUTED, false, 5);
        addText(row, ReminderListGroups.lead(r) + " · " + date.format(new Date(r.triggerAt)), 12, MUTED, false, 5);
        button(row, "删除这条提醒", false, 10, () -> new AlertDialog.Builder(this)
                .setTitle("删除这条提醒？").setMessage("成熟时间：" + date.format(new Date(r.matureAt))
                        + "\n只删除此条记录，其他提醒保留；尚未触发的提醒也会取消。")
                .setNegativeButton("保留", null).setPositiveButton("删除", (d, w) -> {
                    store.delete(r.id); refresh();
                }).show());
    }

    private void renderAvatarTime(LinearLayout row, Reminder reminder, String avatarRef, String label, String timeLabel) {
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(heading, spaced(-1, -2, 10));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(dp(5), dp(5), dp(5), dp(5));
        image.setBackground(background(0xFFF0F3EB, 12, 0xFFE0E6DA));
        image.setClipToOutline(true);
        heading.addView(image, new LinearLayout.LayoutParams(dp(60), dp(60)));
        LinearLayout words = column();
        LinearLayout.LayoutParams textLayout = new LinearLayout.LayoutParams(0, -2, 1);
        textLayout.leftMargin = dp(13); heading.addView(words, textLayout);
        addText(words, label, 12, MUTED, false, 0);
        addText(words, timeLabel + " " + new SimpleDateFormat("HH:mm:ss", Locale.CHINA)
                .format(new Date(reminder.matureAt)), 20, INK, true, 3);
        addText(words, new SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
                .format(new Date(reminder.matureAt)), 12, MUTED, false, 3);
        avatars.bind(image, avatarRef);
    }

    private String monitorStatusText(String status) {
        if (blank(status)) return "等待进入小程序";
        if (!ShizukuTaskGate.isEnabled(this)) {
            if (status.contains("USAGE_VERIFY_PENDING")) return "正在验证农场";
            if (status.contains("USAGE_VERIFY_REJECTED")) return "未匹配农场，请退出小程序后重进";
            if (status.contains("USAGE_VERIFY_FAILED")) return "验证失败，请退出小程序后重进";
            if (status.contains("USAGE_VERIFY_READY")) return "已验证，正在记录";
        }
        if (status.contains("CAPTURE_NO_MATCH_TIMEOUT")) return ShizukuTaskGate.isEnabled(this)
                ? "等待农场前台，满足条件后继续截图" : "请重新开始监测";
        if (status.contains("LOW_SPACE")) return "空间不足，已暂停截图";
        if (status.contains("WRITE_FAILED") || status.contains("保存")) return "保存遇到问题，请查看工具";
        if (status.contains("STORAGE_FAILED")) return "存储遇到问题，请查看工具";
        if (status.contains("OCR_TIMEOUT")) return "识别超时，请稍后重试";
        if (status.contains("SERVICE_UNAVAILABLE") || status.contains("服务断开") || status.contains("服务中断")) return "截图服务尚未就绪";
        if (status.contains("等待") || status.contains("NOT_FOREGROUND")) return "等待进入小程序";
        if (status.contains("失败") || status.contains("错误")) return "截图遇到问题，请查看工具";
        if (status.contains("识别任务未结束")) return "等待上次识别完成";
        if (status.contains("停止")) return "监测已停止，截图与提醒保留";
        return "正在识别成熟时间";
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
        return (ShizukuTaskGate.isEnabled(this) ? "农场系统任务条件满足时持续截图"
                : "进入小程序候选后单张验证，匹配加载页或自己家才持续记录")
                + " · 已保存图片按序处理";
    }

    private boolean canChangeMode() {
        return SetupFlowPolicy.canChangeMode(FarmAccessibilityService.isRunning,
                MonitorForegroundService.isActive, foregroundStartupPending);
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
    private void showFeedback(String message) { showMessage(message); }
    private void showMessage(String message) {
        feedbackMessage = message;
        if (feedback != null) {
            feedback.setText(message);
            feedback.setVisibility(View.VISIBLE);
        }
    }
    private void clearFeedback() {
        feedbackMessage = "";
        feedback.setVisibility(View.GONE);
    }
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
