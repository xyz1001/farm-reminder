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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
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
    public static final String ACTION_SHOW_REMINDERS = "com.local.farmreminder.SHOW_REMINDERS";
    private static final int NOTIFICATIONS = 102;
    private static final int EXPORT_DIAGNOSTICS = 103;
    private static final int INK = 0xFF20382D, GREEN = 0xFF276344;
    private static final int MUTED = 0xFF657367, PAPER = 0xFFF7F6EF;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Button> leadButtons = new ArrayList<>();
    private TextView captureStatus, feedback, exactHint, listTitle;
    private TextView modeHint;
    private RadioGroup modeChoices;
    private RadioButton usageMode, shizukuMode;
    private boolean updatingMode;
    private Button monitorButton, notificationButton, exactButton;
    private Button exportButton;
    private TextView exportStatus;
    private TextView vibrationStatus, vibrationHint;
    private ExportState exportState;
    private LinearLayout reminders;
    private AvatarViewBinder avatars;
    private SharedPreferences settings;
    private ReminderStore store;
    private AlarmScheduler scheduler;
    private int leadSeconds;
    private boolean resumed;
    private boolean observingUserStart;
    private long lifecycleAttempt;
    private String listSignature = "";
    private String feedbackMessage = "";
    private final Set<String> expandedGroups = new HashSet<>();
    private LinearLayout homePage, settingsPage, setupPage, navigation;
    private TextView setupProgress, setupTitle, setupDetail;
    private LinearLayout setupModes;
    private Button setupAction, setupBack;
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
            ArrayList<String> expanded = state.getStringArrayList("expandedGroups");
            if (expanded != null) expandedGroups.addAll(expanded);
            lifecycleAttempt = state.getLong("lifecycleGeneration", 0) == LifecycleDiagnostics.generation(this) ? state.getLong("lifecycleAttempt", 0) : 0;
            observingUserStart = lifecycleAttempt != 0 && state.getBoolean("observingUserStart", false);
            currentPage = "settings".equals(state.getString("currentPage", "home")) ? "settings" : "home";
            setupVisible = state.getBoolean("setupVisible", false);
            reviewMode = state.getBoolean("reviewMode", false);
            String savedStep = state.getString("backStep", "");
            try { if (!savedStep.isEmpty()) backStep = SetupFlowPolicy.Step.valueOf(savedStep); }
            catch (IllegalArgumentException ignored) { backStep = null; }
        }
        leadSeconds = settings.getInt("leadSeconds", 60);
        if (leadSeconds != 0 && leadSeconds != 60 && leadSeconds != 180 && leadSeconds != 300) leadSeconds = 60;
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
        showRemindersIfRequested(getIntent());
        refresh();
        renderExportState();
        FarmAccessibilityService.refreshDiskProgress(this);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (showRemindersIfRequested(intent)) refresh();
    }

    private boolean showRemindersIfRequested(Intent intent) {
        if (intent == null || !ACTION_SHOW_REMINDERS.equals(intent.getAction())) return false;
        currentPage = "home";
        setupVisible = false;
        reviewMode = false;
        backStep = null;
        lastRequiredReady = SetupFlowPolicy.requiredReady(setupStep());
        // Consume this one-off route so later recreation preserves the user's selected tab.
        setIntent(new Intent(intent).setAction(null));
        return true;
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
        homePage = column(); settingsPage = column(); setupPage = column();
        page.addView(homePage); page.addView(settingsPage); page.addView(setupPage);
        addText(homePage, "农场提醒", 30, INK, true, 0);
        monitorButton = button(homePage, "开始监测", true, 24, () -> {
            MonitorPresentation current = MonitorPresentation.from(MonitorCoordinator.getSnapshot(this),
                    Build.VERSION.SDK_INT >= 30, SetupFlowPolicy.requiredReady(setupStep()));
            if (current.showStop) requestStopMonitoring();
            else requestMonitoring();
        });
        captureStatus = addText(homePage, "", 14, MUTED, false, 8);
        captureStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        listTitle = addText(homePage, "待提醒 0 组", 21, INK, true, 30);
        reminders = column(); homePage.addView(reminders, spaced(-1, -2, 8));

        addText(settingsPage, "设置", 28, INK, true, 0);
        LinearLayout permissions = card(settingsPage, 22, Color.WHITE);
        button(permissions, "监测方式", false, 0, () -> {
            reviewMode = true; backStep = null; setupVisible = true; refresh();
        });
        exactButton = button(permissions, "精确闹钟", false, 8, this::requestExactAlarm);
        exactHint = addText(permissions, "未开启可能延迟提醒", 12, MUTED, false, 7);
        LinearLayout notificationSettings = card(settingsPage, 16, Color.WHITE);
        vibrationStatus = buildNotificationSettings(notificationSettings);
        notificationButton = button(notificationSettings, "允许应用通知", false, 8, this::requestNotifications);
        vibrationHint = notificationSettingsHint(notificationSettings);
        LinearLayout timing = card(settingsPage, 16, Color.WHITE);
        addText(timing, "提前多久提醒", 18, INK, true, 0);
        addText(timing, "仅对新记录生效", 12, MUTED, false, 7);
        LinearLayout choices = new LinearLayout(this);
        timing.addView(choices, spaced(-1, -2, 13));
        int[] values = {0, 60, 180, 300};
        String[] labels = {"成熟时", "1 分钟", "3 分钟", "5 分钟"};
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

        LinearLayout export = card(settingsPage, 16, Color.WHITE);
        addText(export, "诊断包", 19, INK, true, 0);
        Switch saveScreenshots = new Switch(this);
        saveScreenshots.setText("保存截图（调试）");
        saveScreenshots.setTextSize(16);
        saveScreenshots.setTextColor(INK);
        saveScreenshots.setSwitchPadding(dp(16));
        saveScreenshots.setMinHeight(dp(48));
        saveScreenshots.setSaveEnabled(false);
        saveScreenshots.setChecked(ScreenshotRetentionPolicy.enabled(this));
        export.addView(saveScreenshots, spaced(-1, -2, 8));
        saveScreenshots.setOnCheckedChangeListener((view, checked) ->
                ScreenshotRetentionPolicy.setEnabled(this, checked));
        addText(export, "开启后保留截图，用于排查和导出；关闭后，截图在识别完成后自动删除。", 12, MUTED, false, 4);
        addText(export, "仅对新截图生效，已保留的截图不受影响。", 12, MUTED, false, 4);
        exportButton = button(export, "导出 ZIP", false, 12, () ->
                new AlertDialog.Builder(this).setTitle("导出诊断包？")
                        .setMessage("包含保留的整屏截图与识别记录，可能含其他页面或敏感内容。请选择可信的保存位置。")
                        .setNegativeButton("取消", null).setPositiveButton("选择位置", (d, w) -> requestDiagnosticsExport()).show());
        exportStatus = addText(export, "", 12, MUTED, false, 8);
        buildSetupPanel();
        feedback = addText(root, feedbackMessage, 13, GREEN, false, 0);
        feedback.setPadding(dp(20), dp(10), dp(20), dp(10));
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(feedbackMessage.isEmpty() ? View.GONE : View.VISIBLE);
        navigation = new LinearLayout(this);
        navigation.setPadding(dp(14), dp(6), dp(14), dp(10));
        root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        for (String name : new String[]{"监测", "设置"}) {
            Button tab = makeButton(name, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(3), 0, dp(3), 0); navigation.addView(tab, lp);
            tab.setOnClickListener(v -> {
                currentPage = "设置".equals(name) ? "settings" : "home";
                setupVisible = false; reviewMode = false; backStep = null; refresh(); scroll.smoothScrollTo(0, 0);
            });
        }
    }

    private void buildSetupPanel() {
        setupProgress = addText(setupPage, "准备", 13, GREEN, true, 6);
        setupTitle = addText(setupPage, "监测方式", 28, INK, true, 24);
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
        setupAction = button(setupPage, "继续", true, 24, this::performSetupAction);
        setupBack = button(setupPage, "返回", false, 8, this::navigateBack);
    }

    private TextView buildNotificationSettings(LinearLayout parent) {
        addText(parent, "通知与振动", 18, INK, true, 0);
        TextView status = addText(parent, "", 14, INK, false, 10);
        button(parent, "打开系统设置", false, 10, this::openNotificationSettings)
                .setContentDescription("打开通知与振动系统设置");
        return status;
    }

    private TextView notificationSettingsHint(LinearLayout parent) {
        return addText(parent, "", 12, MUTED, false, 8);
    }

    private void refreshNotificationSettings() {
        NotificationSettingsPolicy.State state = NotificationSettingsNavigator.read(this);
        vibrationStatus.setText(ScreenPresentation.notificationStatus(state));
        setOptionalText(vibrationHint, ScreenPresentation.notificationHint(state));
    }

    private void openNotificationSettings() {
        NotificationSettingsPolicy.Target opened = NotificationSettingsNavigator.open(this);
        if (opened == null) showMessage("无法打开，请在系统设置中找到本应用。");
        else clearFeedback();
    }

    private void requestMonitoring() {
        long clickAttempt = LifecycleDiagnostics.click(this);
        LifecycleDiagnostics.event(this, clickAttempt, LifecycleHistory.Event.UI_CLICK, LifecycleHistory.Reason.NONE, null);
        MonitorCoordinator.Snapshot before = MonitorCoordinator.getSnapshot(this);
        MonitorPresentation presentation = MonitorPresentation.from(before, Build.VERSION.SDK_INT >= 30,
                SetupFlowPolicy.requiredReady(setupStep()));
        if (!presentation.showStart) {
            LifecycleDiagnostics.event(this, clickAttempt, LifecycleHistory.Event.SETUP_REJECT, LifecycleHistory.Reason.ALREADY_ACTIVE, null); return;
        }
        if (setupStep() != SetupFlowPolicy.Step.READY) {
            LifecycleDiagnostics.event(this, clickAttempt, LifecycleHistory.Event.SETUP_REJECT, LifecycleHistory.Reason.SETUP, null);
            setupVisible = true; reviewMode = false; backStep = null; refresh(); return;
        }
        lifecycleAttempt = clickAttempt;
        LifecycleDiagnostics.setCurrent(this, lifecycleAttempt);
        try {
            LifecycleDiagnostics.event(this, lifecycleAttempt, LifecycleHistory.Event.START_REQUEST, LifecycleHistory.Reason.NONE, null);
            MonitorCoordinator.RequestResult result = MonitorForegroundService.requestStart(this, lifecycleAttempt);
            observingUserStart = result.code == MonitorCoordinator.RequestCode.ACCEPTED;
            showFeedback(MonitorPresentation.startFeedback(result.code, result.snapshot.reason));
        } catch (RuntimeException e) {
            LifecycleDiagnostics.event(this, lifecycleAttempt, LifecycleHistory.Event.START_REQUEST, LifecycleHistory.Reason.EXCEPTION, e);
            observingUserStart = false;
            showFeedback("启动请求未确认，请检查当前状态后重试");
        }
        refresh();
    }

    private void requestStopMonitoring() {
        observingUserStart = false;
        try {
            MonitorCoordinator.RequestResult result = MonitorForegroundService.requestStop(this);
            showFeedback(result.code == MonitorCoordinator.RequestCode.ACCEPTED
                    ? "" : MonitorPresentation.stopFeedback(result.code));
        } catch (RuntimeException e) { showFeedback("停止请求未确认，请重试"); }
        refresh();
    }

    private SetupFlowPolicy.Step setupStep() {
        boolean shizuku = ShizukuTaskGate.isEnabled(this);
        SetupFlowPolicy.Step step = SetupFlowPolicy.next(Build.VERSION.SDK_INT >= 30,
                settings.getBoolean("modeChosen", false), shizuku,
                !shizuku && UsageAccessGate.hasPermission(this),
                shizuku && ShizukuTaskGate.get(this).hasPermission(),
                FarmAccessibilityService.isEnabled(this), FarmAccessibilityService.isConnected,
                notificationsAllowed(), settings.getBoolean("notificationDeferred", false));
        // Optional notification permission is managed only from Settings, never a setup gate.
        return SetupFlowPolicy.requiredReady(step) ? SetupFlowPolicy.Step.READY : step;
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
        setupPage.setVisibility(setupVisible ? View.VISIBLE : View.GONE);
        // Keep Home reachable during permission loss so monitoring can still be stopped there.
        navigation.setVisibility(View.VISIBLE);
        if (Build.VERSION.SDK_INT >= 33) updateBackCallback();
        for (int i = 0; i < navigation.getChildCount(); i++) {
            Button tab = (Button) navigation.getChildAt(i);
            boolean selected = !setupVisible && i == ("settings".equals(currentPage) ? 1 : 0);
            tab.setSelected(selected);
            tab.setTextColor(selected ? Color.WHITE : GREEN);
            tab.setBackground(background(selected ? GREEN : 0xFFF0F3EB, 13, 0));
            tab.setContentDescription(tab.getText() + (selected ? "，当前页面" : ""));
        }
        if (!setupVisible) return;
        displayedStep = SetupFlowPolicy.displayedStep(actual, reviewMode, backStep);
        boolean mode = displayedStep == SetupFlowPolicy.Step.MODE;
        setupModes.setVisibility(mode ? View.VISIBLE : View.GONE);
        setupProgress.setVisibility(mode ? View.GONE : View.VISIBLE);
        setupAction.setEnabled(true);
        setupBack.setText(displayedStep == SetupFlowPolicy.Step.WAITING ? "返回截图设置" : "返回");
        setupDetail.setVisibility(View.VISIBLE);
        switch (displayedStep) {
            case MODE:
                setupProgress.setText("准备"); setupTitle.setText("监测方式");
                setupDetail.setText(""); setupAction.setText("继续"); break;
            case SOURCE:
                setupProgress.setText("1 / 2");
                if (ShizukuTaskGate.isEnabled(this)) {
                    setupTitle.setText("连接 Shizuku");
                    setupDetail.setText(shortShizukuStatus());
                    setupAction.setText(ShizukuTaskGate.get(this).hasPermission() ? "继续" : "授权 / 刷新");
                } else {
                    setupTitle.setText("应用使用情况权限");
                    setupDetail.setText("用于判断微信小程序是否在前台");
                    setupAction.setText(UsageAccessGate.hasPermission(this) ? "继续" : "去授权");
                }
                break;
            case SCREENSHOT:
                setupProgress.setText("2 / 2"); setupTitle.setText("启用截图服务");
                setupDetail.setText("整屏截图可能包含其他内容；截图和识别文字在本机保留，可导出。");
                setupAction.setText(FarmAccessibilityService.isEnabled(this) ? "查看系统设置" : "前往启用"); break;
            case WAITING:
                setupProgress.setText("2 / 2"); setupTitle.setText("等待截图服务连接");
                setupDetail.setText("已启用，可稍后刷新");
                setupAction.setText("刷新"); break;
            case UNSUPPORTED:
                setupProgress.setText("设备限制"); setupTitle.setText("需要 Android 11 及以上");
                setupDetail.setText("此设备不能截图，已有提醒与导出仍可使用");
                setupAction.setText("返回首页"); break;
            default:
                setupProgress.setText("已完成"); setupTitle.setText("准备就绪");
                setupProgress.setVisibility(View.GONE);
                setupDetail.setText("");
                setupDetail.setVisibility(View.GONE);
                setupAction.setText("返回首页");
        }
        setupDetail.setVisibility(blank(setupDetail.getText().toString()) ? View.GONE : View.VISIBLE);
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
            message += "\n还有截图未保存，可能不在诊断包内；请勿关闭应用，先处理保存错误。";
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
        state.putStringArrayList("expandedGroups", new ArrayList<>(expandedGroups));
        state.putBoolean("exportPicking", exportState.picking);
        state.putBoolean("exportBusy", exportState.busy);
        state.putBoolean("observingUserStart", observingUserStart);
        state.putLong("lifecycleGeneration", LifecycleDiagnostics.generation(this));
        state.putLong("lifecycleAttempt", lifecycleAttempt);
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
                .setMessage("用于成熟提醒；不开启仍可监测。")
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
        MonitorCoordinator.onVisible(this);
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
        MonitorCoordinator.Snapshot monitor = MonitorCoordinator.getSnapshot(this);
        boolean running = monitor.monitoringRunning;
        boolean useShizuku = ShizukuTaskGate.isEnabled(this);
        boolean usageAllowed = !useShizuku && UsageAccessGate.hasPermission(this);
        boolean sourceReady = useShizuku ? ShizukuTaskGate.get(this).hasPermission() : usageAllowed;
        if (observingUserStart) {
            if (monitor.state == MonitorCoordinator.State.RUNNING && running) {
                LifecycleDiagnostics.event(this, lifecycleAttempt, LifecycleHistory.Event.PENDING_CONFIRMED, LifecycleHistory.Reason.NONE, null);
                observingUserStart = false;
            } else if (monitor.state == MonitorCoordinator.State.SUSPENDED || monitor.state == MonitorCoordinator.State.ERROR
                    || monitor.state == MonitorCoordinator.State.STOPPED && !monitor.wanted) {
                LifecycleDiagnostics.event(this, lifecycleAttempt, LifecycleHistory.Event.PENDING_FAILED, LifecycleHistory.Reason.NOT_RUNNING, null);
                observingUserStart = false;
            }
        }
        boolean requiredReady = supported && sourceReady && enabled && connected;
        MonitorPresentation presentation = MonitorPresentation.from(monitor, supported, requiredReady);
        updatingMode = true;
        modeChoices.check(useShizuku ? shizukuMode.getId() : usageMode.getId());
        updatingMode = false;
        boolean modeEditable = presentation.modeEditable;
        usageMode.setEnabled(modeEditable);
        shizukuMode.setEnabled(modeEditable);
        setOptionalText(modeHint, !modeEditable ? "请先停止监测，再切换模式"
                : !useShizuku && !usageAllowed ? "尚未允许使用情况访问" : "");
        // Only confirmed problems belong beside the button.
        String shizukuIssue = useShizuku ? ShizukuTaskGate.get(this).notificationIssue() : null;
        String monitorProblem = "";
        if (!supported) {
            monitorProblem = "截图需要 Android 11 及以上。";
        } else if ("SHIZUKU_PERMISSION".equals(shizukuIssue)) {
            monitorProblem = "Shizuku 授权不可用，请检查授权。";
        } else if ("SHIZUKU_CONNECTION".equals(shizukuIssue)) {
            monitorProblem = "Shizuku 连接已断开，请检查服务。";
        } else if (presentation.needsAttention) {
            // A suspended session still requires manual recovery even if its dependency recovered.
            monitorProblem = monitor.reason == MonitorCoordinator.Reason.SHIZUKU
                    ? "监测已暂停，请停止后重新开始。" : presentation.detail;
        } else if (monitor.state == MonitorCoordinator.State.STOPPED
                && monitor.reason == MonitorCoordinator.Reason.SYSTEM_STOP) {
            monitorProblem = "系统已停止监测，请手动开始。";
        } else if (monitor.state == MonitorCoordinator.State.WAITING) {
            if (monitor.reason == MonitorCoordinator.Reason.ACCESSIBILITY && (!enabled || !connected))
                monitorProblem = "截图服务未连接，请检查权限。";
            else if (monitor.reason == MonitorCoordinator.Reason.SOURCE_PERMISSION && !useShizuku && !usageAllowed)
                monitorProblem = "使用情况访问权限已关闭，请重新授权。";
        }
        setOptionalText(captureStatus, monitorProblem);
        renderExportState();
        monitorButton.setText(presentation.showStop ? "停止监测" : "开始监测");
        monitorButton.setEnabled(presentation.showStop || presentation.startEnabled);
        monitorButton.setAlpha(monitorButton.isEnabled() ? 1f : 0.55f);
        boolean notified = notificationsAllowed();
        notificationButton.setVisibility(notified ? View.GONE : View.VISIBLE);
        refreshNotificationSettings();
        boolean exact = scheduler.canScheduleExact();
        exactButton.setText(exact ? "精确闹钟 · 已开启" : "精确闹钟 · 未开启");
        exactHint.setVisibility(exact ? View.GONE : View.VISIBLE);
        renderNavigation();
        long now = System.currentTimeMillis();
        int pruned = store.pruneExpired(now);
        if (pruned > 0) scheduler.rescheduleAll();
        ReminderListGroups groups = new ReminderListGroups(store.list(), now);
        List<Reminder> items = new ArrayList<>();
        for (ReminderGrouping.Group group : groups.active) items.addAll(group.reminders);
        listTitle.setText(groups.title());
        String next = ReminderListGroups.signature(items, now) + ReminderImagePresentation.signature(items);
        if (next.equals(listSignature)) return;
        listSignature = next;
        avatars.clearRows();
        reminders.removeAllViews();
        if (items.isEmpty()) return;
        SimpleDateFormat date = new SimpleDateFormat("yyyy年M月d日 HH:mm:ss", Locale.CHINA);
        for (ReminderGrouping.Group group : groups.active) {
            LinearLayout row = card(reminders, 10, Color.WHITE);
            if (group.reminders.size() == 1) {
                renderReminder(row, group.anchor(), date, now);
                continue;
            }
            boolean allFired = true, waiting = false;
            for (Reminder reminder : group.reminders) {
                allFired &= reminder.fired;
                waiting |= !reminder.fired && reminder.triggerAt <= now;
            }
            if (allFired || waiting) addText(row, allFired ? "已提交通知" : "等待提交通知", 12,
                    allFired ? MUTED : GREEN, true, 0);
            renderAvatarTime(row, group.anchor(), ReminderImagePresentation.plateRef(group.reminders),
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
            toggle.setText(expanded ? "收起明细" : "查看明细");
            toggle.setContentDescription((expanded ? "收起 " : "查看 ") + group.reminders.size() + " 条提醒明细");
            toggle.setOnClickListener(v -> {
                boolean open = details.getVisibility() != View.VISIBLE;
                if (open) expandedGroups.add(group.identity); else expandedGroups.remove(group.identity);
                details.setVisibility(open ? View.VISIBLE : View.GONE);
                toggle.setText(open ? "收起明细" : "查看明细");
                toggle.setContentDescription((open ? "收起 " : "查看 ") + group.reminders.size() + " 条提醒明细");
            });
        }
    }

    private void renderReminder(LinearLayout row, Reminder r, SimpleDateFormat date, long now) {
        if (r.fired || r.triggerAt <= now) addText(row, r.fired ? "已提交通知" : "等待提交通知", 12,
                r.fired ? MUTED : GREEN, true, 0);
        renderAvatarTime(row, r, r.plateRef, "", "成熟");
        addText(row, ScreenPresentation.schedule(r.matureAt, r.triggerAt), 12, MUTED, false, 5);
        Button delete = button(row, "删除", false, 10, () -> new AlertDialog.Builder(this)
                .setTitle("删除提醒？").setMessage("成熟时间：" + date.format(new Date(r.matureAt))
                        + "\n仅删除这条记录，未触发的提醒将取消。")
                .setNegativeButton("保留", null).setPositiveButton("删除", (d, w) -> {
                    store.delete(r.id); refresh();
                }).show());
        delete.setContentDescription("删除 " + date.format(new Date(r.matureAt)) + " 成熟的提醒");
    }

    private void renderAvatarTime(LinearLayout row, Reminder reminder, String plateRef, String label, String timeLabel) {
        int contentTop = row.getChildCount() == 0 ? 0 : 10;
        ImageView plate = new ImageView(this);
        plate.setScaleType(ImageView.ScaleType.FIT_CENTER);
        plate.setPadding(dp(4), dp(4), dp(4), dp(4));
        plate.setBackground(background(0xFFF0F3EB, 12, 0xFFE0E6DA));
        plate.setClipToOutline(false);
        row.addView(plate, spaced(-1, dp(80), contentTop)); // First layout resolves the responsive placeholder aspect.
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(heading, spaced(-1, -2, 10));
        LinearLayout words = column();
        LinearLayout.LayoutParams textLayout = new LinearLayout.LayoutParams(0, -2, 1);
        heading.addView(words, textLayout);
        if (!blank(label)) addText(words, label, 12, MUTED, false, 0);
        addText(words, timeLabel + " " + new SimpleDateFormat("HH:mm:ss", Locale.CHINA)
                .format(new Date(reminder.matureAt)), 20, INK, true, blank(label) ? 0 : 3);
        addText(words, new SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
                .format(new Date(reminder.matureAt)), 12, MUTED, false, 3);
        avatars.bindPlate(plate, plateRef);
    }

    private boolean canChangeMode() {
        return MonitorPresentation.from(MonitorCoordinator.getSnapshot(this), Build.VERSION.SDK_INT >= 30, true).modeEditable;
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
        int[] values = {0, 60, 180, 300};
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
    private void setOptionalText(TextView view, String text) {
        view.setText(text);
        view.setVisibility(blank(text) ? View.GONE : View.VISIBLE);
    }
    private void showFeedback(String message) { showMessage(message); }
    private void showMessage(String message) {
        feedbackMessage = message;
        if (feedback != null) {
            feedback.setText(message);
            feedback.setVisibility(blank(message) ? View.GONE : View.VISIBLE);
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
