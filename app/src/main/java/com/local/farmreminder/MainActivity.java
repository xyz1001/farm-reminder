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
    private TextView captureStatus, diagnosticsText, feedback, exactHint, listTitle;
    private TextView processingSummary, ocrState, queueDetails;
    private TextView foregroundStatus, foregroundError;
    private TextView modeHint;
    private RadioGroup modeChoices;
    private RadioButton usageMode, shizukuMode;
    private boolean updatingMode;
    private Button monitorButton, retryButton, notificationButton, exactButton;
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
    private LinearLayout homePage, settingsPage, toolsPage, setupPage, navigation;
    private TextView setupProgress, setupTitle, setupDetail;
    private LinearLayout setupModes;
    private Button setupAction, notificationContinue, setupBack;
    private LinearLayout setupMonitorPanel;
    private TextView setupMonitorStatus;
    private Button setupMonitorRetry, setupMonitorStop;
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
                exportState.message = "Σ╕èµ¼íσ»╝σç║τ╗ôµ₧£µ£¬τí«Φ«ñ∩╝îΦ»╖µúÇµƒÑΣ┐¥σ¡ÿΣ╜ìτ╜«∩╝¢σÅ»Θçìµû░σ»╝σç║πÇé";
        }
        exportState.activity = new WeakReference<>(this);
        settings = getSharedPreferences("settings", MODE_PRIVATE);
        if (state != null) {
            feedbackMessage = state.getString("feedbackMessage", "");
            ArrayList<String> expanded = state.getStringArrayList("expandedGroups");
            if (expanded != null) expandedGroups.addAll(expanded);
            lifecycleAttempt = state.getLong("lifecycleGeneration", 0) == LifecycleDiagnostics.generation(this) ? state.getLong("lifecycleAttempt", 0) : 0;
            observingUserStart = lifecycleAttempt != 0 && state.getBoolean("observingUserStart", false);
            currentPage = state.getString("currentPage", "home");
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
        homePage = column(); settingsPage = column(); toolsPage = column(); setupPage = column();
        page.addView(homePage); page.addView(settingsPage); page.addView(toolsPage); page.addView(setupPage);
        addText(homePage, "σå£σ£║µÅÉΘåÆ", 30, INK, true, 0);
        monitorButton = button(homePage, "σ╝Çσºïτ¢æµ╡ï", true, 24, () -> {
            MonitorPresentation current = MonitorPresentation.from(MonitorCoordinator.getSnapshot(this),
                    Build.VERSION.SDK_INT >= 30, SetupFlowPolicy.requiredReady(setupStep()));
            if (current.showStop) requestStopMonitoring();
            else requestMonitoring();
        });
        captureStatus = addText(homePage, "", 14, MUTED, false, 8);
        captureStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        listTitle = addText(homePage, "σ╛àµÅÉΘåÆ 0 τ╗ä", 21, INK, true, 30);
        reminders = column(); homePage.addView(reminders, spaced(-1, -2, 8));

        addText(toolsPage, "σ╖Ñσà╖", 28, INK, true, 0);
        LinearLayout progress = card(toolsPage, 22, Color.WHITE);
        addText(progress, "σñäτÉåΣ╕Äσ¡ÿσé¿", 19, INK, true, 0);
        processingSummary = addText(progress, "", 13, INK, false, 12);
        ocrState = addText(progress, "", 13, MUTED, false, 8);
        retryButton = button(progress, "ΘçìΦ»òσñäτÉå", false, 10, () -> {
            try {
                FarmAccessibilityService.retryFailedFrames(this);
                showFeedback(MonitorCoordinator.getSnapshot(this).monitoringRunning
                        ? "σ╖▓Φ»╖µ▒éΘçìΦ»ò" : "σ╖▓Φ»╖µ▒éΘçìΦ»ò∩╝îΘÿƒσêùΣ┐¥τòÖ");
            } catch (RuntimeException e) { showFeedback("µ£¬Φâ╜ΘçìΦ»ò∩╝îσñ▒Φ┤Ñσ¢╛τëçΣ╗ìΣ┐¥τòÖ"); }
        });
        retryButton.setVisibility(View.GONE);
        LinearLayout export = card(toolsPage, 16, Color.WHITE);
        addText(export, "Φ»èµû¡σîà", 19, INK, true, 0);
        exportButton = button(export, "σ»╝σç║ ZIP", false, 12, () ->
                new AlertDialog.Builder(this).setTitle("σ»╝σç║Φ»èµû¡σîà∩╝ƒ")
                        .setMessage("σîàσÉ½Σ┐¥τòÖτÜäµò┤σ▒Åµê¬σ¢╛Σ╕ÄΦ»åσê½Φ«░σ╜ò∩╝îσÅ»Φâ╜σÉ½σà╢Σ╗ûΘí╡Θ¥óµêûµòÅµäƒσåàσ«╣πÇéΦ»╖ΘÇëµï⌐σÅ»Σ┐íτÜäΣ┐¥σ¡ÿΣ╜ìτ╜«πÇé")
                        .setNegativeButton("σÅûµ╢ê", null).setPositiveButton("ΘÇëµï⌐Σ╜ìτ╜«", (d, w) -> requestDiagnosticsExport()).show());
        exportStatus = addText(export, "", 12, MUTED, false, 8);
        Button diagnosticsToggle = button(toolsPage, "µèÇµ£»Φ»èµû¡", false, 16, () -> {});
        LinearLayout technical = card(toolsPage, 8, Color.WHITE);
        technical.setVisibility(View.GONE);
        foregroundStatus = addText(technical, "", 13, INK, false, 0);
        foregroundError = addText(technical, "", 13, INK, false, 8);
        queueDetails = addText(technical, "", 12, MUTED, false, 8);
        diagnosticsText = addText(technical, "", 12, MUTED, false, 10);
        diagnosticsText.setTextIsSelectable(true);
        diagnosticsToggle.setOnClickListener(v -> {
            boolean expanded = technical.getVisibility() != View.VISIBLE;
            technical.setVisibility(expanded ? View.VISIBLE : View.GONE);
            diagnosticsToggle.setText(expanded ? "µö╢Φ╡╖µèÇµ£»Φ»èµû¡" : "µèÇµ£»Φ»èµû¡");
        });
        button(technical, "σê╖µû░Φ»èµû¡", false, 10, () -> {
            FarmAccessibilityService.refreshDiskProgress(this); refresh();
        });

        addText(settingsPage, "Φ«╛τ╜«", 28, INK, true, 0);
        LinearLayout permissions = card(settingsPage, 22, Color.WHITE);
        button(permissions, "τ¢æµ╡ïµû╣σ╝ÅΣ╕Äµ¥âΘÖÉ", false, 0, () -> {
            reviewMode = true; backStep = null; setupVisible = true; refresh();
        });
        exactButton = button(permissions, "τ▓╛τí«Θù╣ΘÆƒ", false, 8, this::requestExactAlarm);
        exactHint = addText(permissions, "µ£¬σ╝ÇσÉ»σÅ»Φâ╜σ╗╢Φ┐ƒµÅÉΘåÆ", 12, MUTED, false, 7);
        LinearLayout notificationSettings = card(settingsPage, 16, Color.WHITE);
        vibrationStatus = buildNotificationSettings(notificationSettings);
        notificationButton = button(notificationSettings, "σàüΦ«╕σ║öτö¿ΘÇÜτƒÑ", false, 8, this::requestNotifications);
        vibrationHint = notificationSettingsHint(notificationSettings);
        LinearLayout timing = card(settingsPage, 16, Color.WHITE);
        addText(timing, "µÅÉσëìσñÜΣ╣àµÅÉΘåÆ", 18, INK, true, 0);
        addText(timing, "Σ╗àσ»╣µû░Φ«░σ╜òτöƒµòê", 12, MUTED, false, 7);
        LinearLayout choices = new LinearLayout(this);
        timing.addView(choices, spaced(-1, -2, 13));
        int[] values = {0, 60, 180, 300};
        String[] labels = {"µêÉτåƒµù╢", "1 σêåΘÆƒ", "3 σêåΘÆƒ", "5 σêåΘÆƒ"};
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

        button(timing, "µ╡ïΦ»òµÅÉΘåÆ ┬╖ 10 τºÆσÉÄ", false, 13, () -> {
            try {
                showMessage(AlarmPlan.testScheduleMessage(scheduler.scheduleTest(10)));
            } catch (RuntimeException e) { showMessage("µ╡ïΦ»òµ£¬Φâ╜σ«ëµÄÆ∩╝îΦ»╖µúÇµƒÑΦ«╛τ╜«"); }
        });

        button(settingsPage, "µê¬σ¢╛Σ╕ÄΘÜÉτºü", false, 8, this::showPrivacy);
        buildSetupPanel();
        feedback = addText(root, feedbackMessage, 13, GREEN, false, 0);
        feedback.setPadding(dp(20), dp(10), dp(20), dp(10));
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(feedbackMessage.isEmpty() ? View.GONE : View.VISIBLE);
        navigation = new LinearLayout(this);
        navigation.setPadding(dp(14), dp(6), dp(14), dp(10));
        root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        for (String name : new String[]{"τ¢æµ╡ï", "Φ«╛τ╜«", "σ╖Ñσà╖"}) {
            Button tab = makeButton(name, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(3), 0, dp(3), 0); navigation.addView(tab, lp);
            tab.setOnClickListener(v -> {
                currentPage = "Φ«╛τ╜«".equals(name) ? "settings" : "σ╖Ñσà╖".equals(name) ? "tools" : "home";
                setupVisible = false; reviewMode = false; backStep = null; refresh(); scroll.smoothScrollTo(0, 0);
            });
        }
    }

    private void buildSetupPanel() {
        setupProgress = addText(setupPage, "σçåσñç", 13, GREEN, true, 6);
        setupTitle = addText(setupPage, "ΘÇëµï⌐τ¢æµ╡ïµû╣σ╝Å", 28, INK, true, 24);
        setupDetail = addText(setupPage, "", 15, MUTED, false, 12);
        setupModes = card(setupPage, 24, Color.WHITE);
        modeChoices = new RadioGroup(this);
        modeChoices.setOrientation(RadioGroup.VERTICAL); modeChoices.setSaveEnabled(false);
        setupModes.addView(modeChoices);
        usageMode = modeChoice("Σ╜┐τö¿µâàσå╡ ┬╖ Θ╗ÿΦ«ñ"); shizukuMode = modeChoice("Shizuku");
        modeChoices.check(ShizukuTaskGate.isEnabled(this) ? shizukuMode.getId() : usageMode.getId());
        modeChoices.setOnCheckedChangeListener((group, id) -> {
            if (updatingMode) return;
            if (!canChangeMode()) { showFeedback("Φ»╖σàêσü£µ¡óτ¢æµ╡ï"); refresh(); return; }
            settings.edit().putBoolean("useShizuku", id == shizukuMode.getId()).apply(); refresh();
        });
        modeHint = addText(setupModes, "", 12, MUTED, false, 8);
        setupAction = button(setupPage, "τ╗ºτ╗¡", true, 24, this::performSetupAction);
        notificationContinue = button(setupPage, "µÜéΣ╕ìσ╝ÇσÉ»∩╝îτ╗ºτ╗¡", false, 8, () -> {
            if (displayedStep != SetupFlowPolicy.Step.NOTIFICATIONS) return;
            settings.edit().putBoolean("notificationDeferred", true).apply();
            reviewMode = false; backStep = null; setupVisible = false; currentPage = "home"; refresh();
        });
        setupBack = button(setupPage, "Φ┐öσ¢₧", false, 8, this::navigateBack);
        setupMonitorPanel = card(setupPage, 24, Color.WHITE);
        setupMonitorStatus = addText(setupMonitorPanel, "", 14, INK, false, 0);
        setupMonitorRetry = button(setupMonitorPanel, "ΘçìΦ»òτ¢æµ╡ï", false, 12, this::requestMonitoring);
        setupMonitorStop = button(setupMonitorPanel, "σü£µ¡óτ¢æµ╡ï", false, 8, this::requestStopMonitoring);
        button(setupPage, "ΘÇÜτƒÑΣ╕Äµî»σè¿Φ«╛τ╜«", false, 16, this::openNotificationSettings);
    }

    private TextView buildNotificationSettings(LinearLayout parent) {
        addText(parent, "ΘÇÜτƒÑΣ╕Äµî»σè¿", 18, INK, true, 0);
        TextView status = addText(parent, "", 14, INK, false, 10);
        button(parent, "µëôσ╝Çτ│╗τ╗ƒΦ«╛τ╜«", false, 10, this::openNotificationSettings)
                .setContentDescription("µëôσ╝ÇΘÇÜτƒÑΣ╕Äµî»σè¿τ│╗τ╗ƒΦ«╛τ╜«");
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
        if (opened == null) showMessage("µùáµ│òµëôσ╝Ç∩╝îΦ»╖σ£¿τ│╗τ╗ƒΦ«╛τ╜«Σ╕¡µë╛σê░µ£¼σ║öτö¿πÇé");
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
            showFeedback("σÉ»σè¿Φ»╖µ▒éµ£¬τí«Φ«ñ∩╝îΦ»╖µúÇµƒÑσ╜ôσëìτè╢µÇüσÉÄΘçìΦ»ò");
        }
        refresh();
    }

    private void requestStopMonitoring() {
        observingUserStart = false;
        try {
            MonitorCoordinator.RequestResult result = MonitorForegroundService.requestStop(this);
            showFeedback(result.code == MonitorCoordinator.RequestCode.ACCEPTED
                    ? "" : MonitorPresentation.stopFeedback(result.code));
        } catch (RuntimeException e) { showFeedback("σü£µ¡óΦ»╖µ▒éµ£¬τí«Φ«ñ∩╝îΦ»╖ΘçìΦ»ò"); }
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
            tab.setContentDescription(tab.getText() + (selected ? "∩╝îσ╜ôσëìΘí╡Θ¥ó" : ""));
        }
        if (!setupVisible) return;
        displayedStep = SetupFlowPolicy.displayedStep(actual, reviewMode, backStep);
        boolean mode = displayedStep == SetupFlowPolicy.Step.MODE;
        setupModes.setVisibility(mode ? View.VISIBLE : View.GONE);
        setupProgress.setVisibility(mode ? View.GONE : View.VISIBLE);
        setupAction.setEnabled(true);
        notificationContinue.setVisibility(displayedStep == SetupFlowPolicy.Step.NOTIFICATIONS
                ? View.VISIBLE : View.GONE);
        setupBack.setText(displayedStep == SetupFlowPolicy.Step.WAITING ? "Φ┐öσ¢₧µê¬σ¢╛Φ«╛τ╜«" : "Φ┐öσ¢₧");
        setupDetail.setVisibility(View.VISIBLE);
        switch (displayedStep) {
            case MODE:
                setupProgress.setText("σçåσñç"); setupTitle.setText("ΘÇëµï⌐τ¢æµ╡ïµû╣σ╝Å");
                setupDetail.setText(""); setupAction.setText("τ╗ºτ╗¡"); break;
            case SOURCE:
                setupProgress.setText("1 / 3");
                if (ShizukuTaskGate.isEnabled(this)) {
                    setupTitle.setText("Φ┐₧µÄÑ Shizuku");
                    setupDetail.setText(shortShizukuStatus());
                    setupAction.setText(ShizukuTaskGate.get(this).hasPermission() ? "τ╗ºτ╗¡" : "µÄêµ¥â / σê╖µû░");
                } else {
                    setupTitle.setText("σ║öτö¿Σ╜┐τö¿µâàσå╡µ¥âΘÖÉ");
                    setupDetail.setText("τö¿Σ║Äσêñµû¡σ╛«Σ┐íσ░Åτ¿ïσ║Åµÿ»σÉªσ£¿σëìσÅ░");
                    setupAction.setText(UsageAccessGate.hasPermission(this) ? "τ╗ºτ╗¡" : "σÄ╗µÄêµ¥â");
                }
                break;
            case SCREENSHOT:
                setupProgress.setText("2 / 3"); setupTitle.setText("σÉ»τö¿µê¬σ¢╛µ£ìσèí");
                setupDetail.setText("µò┤σ▒Åµê¬σ¢╛σÅ»Φâ╜σîàσÉ½σà╢Σ╗ûσåàσ«╣∩╝¢µê¬σ¢╛σÆîΦ»åσê½µûçσ¡ùσ£¿µ£¼µ£║Σ┐¥τòÖ∩╝îσÅ»σ»╝σç║πÇé");
                setupAction.setText(FarmAccessibilityService.isEnabled(this) ? "µƒÑτ£ïτ│╗τ╗ƒΦ«╛τ╜«" : "σëìσ╛ÇσÉ»τö¿"); break;
            case WAITING:
                setupProgress.setText("2 / 3"); setupTitle.setText("τ¡ëσ╛àµê¬σ¢╛µ£ìσèíΦ┐₧µÄÑ");
                setupDetail.setText("σ╖▓σÉ»τö¿∩╝îσÅ»τ¿ìσÉÄσê╖µû░");
                setupAction.setText("σê╖µû░"); break;
            case NOTIFICATIONS:
                setupProgress.setText("3 / 3 ┬╖ σÅ»ΘÇë"); setupTitle.setText("σ╝ÇσÉ»ΘÇÜτƒÑ");
                setupDetail.setText("σà│Θù¡ΘÇÜτƒÑσÅ»Φâ╜µö╢Σ╕ìσê░µÅÉΘåÆ∩╝îΣ╗ìσÅ»τ¢æµ╡ïπÇé");
                setupAction.setText("σàüΦ«╕ΘÇÜτƒÑ"); break;
            case UNSUPPORTED:
                setupProgress.setText("Φ«╛σñçΘÖÉσê╢"); setupTitle.setText("Θ£ÇΦªü Android 11 σÅèΣ╗ÑΣ╕è");
                setupDetail.setText("µ¡ñΦ«╛σñçΣ╕ìΦâ╜µê¬σ¢╛∩╝îσ╖▓µ£ëµÅÉΘåÆΣ╕Äσ»╝σç║Σ╗ìσÅ»Σ╜┐τö¿");
                setupAction.setText("Φ┐öσ¢₧ΘªûΘí╡"); break;
            default:
                setupProgress.setText("σ╖▓σ«îµêÉ"); setupTitle.setText("σçåσñçσ░▒τ╗¬");
                setupProgress.setVisibility(View.GONE);
                setupDetail.setText("");
                setupDetail.setVisibility(View.GONE);
                setupAction.setText("Φ┐öσ¢₧ΘªûΘí╡");
        }
        setupDetail.setVisibility(blank(setupDetail.getText().toString()) ? View.GONE : View.VISIBLE);
    }

    private String shortShizukuStatus() {
        String status = ShizukuTaskGate.get(this).status();
        if (ShizukuTaskGate.get(this).hasPermission()) return "σ╖▓Φ┐₧µÄÑ";
        // Full backend status stays in the diagnostics panel, not in the permission prompt.
        if (status != null && (status.contains("µ£¬Φ┐ÉΦíî") || status.contains("µ£¬σ«ëΦúà") || status.contains("Σ╕ìσÅ»τö¿")))
            return "σ░Üµ£¬Φ┐₧µÄÑ∩╝îΦ»╖σàêσÉ»σè¿ Shizuku";
        return "τ¡ëσ╛àΦ┐₧µÄÑµêûµÄêµ¥â";
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
        new AlertDialog.Builder(this).setTitle("µê¬σ¢╛Σ╕ÄΘÜÉτºü")
                .setMessage("µê¬σ¢╛µÿ»µò┤σ▒Åτö╗Θ¥ó∩╝îσêçµìóΘí╡Θ¥óπÇüΘÇÜτƒÑµêûΦªåτ¢ûσ▒éσÅ»Φâ╜Σ╜┐µê¬σ¢╛σîàσÉ½σà╢Σ╗ûσ║öτö¿µêûτ│╗τ╗ƒσåàσ«╣πÇéΦ»╖σàêσà│Θù¡µòÅµäƒΘí╡Θ¥óπÇé\n\n"
                        + "µê¬σ¢╛Σ╕Ä OCR µûçσ¡ùσ£¿µ£¼µ£║Σ┐¥σ¡ÿ∩╝îΦ»åσê½σÉÄΣ╗ìΣ┐¥τòÖ∩╝îΣ╕ìΦç¬σè¿σêáΘÖñµêûΣ╕èΣ╝á∩╝îσÅ»µëïσè¿σ»╝σç║∩╝¢Φ»èµû¡σîàσÅ»Φâ╜σÉ½µÿ╡τº░τ¡ëµòÅµäƒΣ┐íµü»∩╝îΣ╗àΣ║ñτ╗ÖσÅ»Σ┐íΣ║║σæÿπÇéσü£µ¡óτ¢æµ╡ïΣ╝ÜµÜéσü£µê¬σ¢╛σÆîΘÿƒσêùσñäτÉå∩╝îΣ┐¥τòÖσ¢╛τëçΣ╕ÄµÅÉΘåÆπÇé\n\n"
                        + "Σ╜┐τö¿µâàσå╡µ¿íσ╝ÅΣ╕ìΦâ╜σî║σêåσà╖Σ╜ôσ░Åµ╕╕µêÅ∩╝îΘªûµ¼íΘ¬îΦ»üµê¬σ¢╛σÅ»Φâ╜µÿ»σà╢Σ╗ûσ░Åµ╕╕µêÅµêûΘí╡Θ¥ó∩╝îΣ╣ƒΣ╝Üσ£¿µ£¼σ£░Σ┐¥τòÖπÇüσÅ»σ»╝σç║∩╝¢µûçσ¡ùτë╣σ╛üΣ╗àΣ╜£σÉ»σÅæσ╝Åσêñµû¡∩╝îΘ¬îΦ»üΘÇÜΦ┐çσÉÄΣ╗ìµùáµ│òµäƒτƒÑσ╛«Σ┐íσñìτö¿σ«╣σÖ¿σêçµìóσê░σà╢Σ╗ûσ░Åµ╕╕µêÅπÇéShizuku µ¿íσ╝Åµîëτ│╗τ╗ƒΣ╗╗σèíσêñµû¡∩╝îµö»µîüσ╖▓µÄêµ¥âτÜä Root / Shell∩╝îµ¥âΘÖÉΦîâσ¢┤µ¢┤σñº∩╝îRoot ΘúÄΘÖ⌐µ¢┤Θ½ÿπÇéΣ╕ìΦ»╗σÅûµùáΘÜ£τóìΦèéτé╣µêûτ¬ùσÅúσêùΦí¿∩╝îΣ╕ìτ¢æσÉ¼τòîΘ¥óΣ║ïΣ╗╢πÇéσëìσÅ░σêñµû¡σÆîµê¬σ¢╛σ╣╢Θ¥₧σÉîµù╢σ«îµêÉ∩╝îσñÜτ¬ùσÅúΣ╕ìσÅ»Θ¥á∩╝îΣ╕ìΦâ╜Σ┐¥Φ»üσÅ¬µê¬σå£σ£║µêûΘü┐σàìσ╛«Σ┐íΘúÄµÄºπÇé\n\n"
                        + "Σ╕ìΣ╝ÜΦç¬σè¿τé╣σç╗σ╛«Σ┐íµêûµôìΣ╜£µ╕╕µêÅπÇéΣ╜┐τö¿µâàσå╡µ¿íσ╝ÅΦ┐¢σàÑσ░Åτ¿ïσ║ÅσÇÖΘÇëσÉÄ∩╝îΣ╗àµê¬ 1 σ╝áσ£¿µ£¼σ£░Θ¬îΦ»ü∩╝¢σî╣Θàìσå£σ£║σèáΦ╜╜Θí╡µêûΦç¬σ╖▒σ«╢σÉÄµëìµîüτ╗¡Φ«░σ╜ò∩╝îµ£¬σî╣ΘàìµêûΘ¬îΦ»üσñ▒Φ┤ÑσêÖµ£¼µ¼íσü£µ¡óµê¬σ¢╛πÇéΘÇÇσç║σ░Åτ¿ïσ║ÅσÉÄΘçìΦ┐¢∩╝îµêûµëïσè¿σü£µ¡óσåìσ╝Çσºï∩╝îσÅ»Θçìµû░Θ¬îΦ»üπÇéShizuku µ¿íσ╝ÅΣ╗ìµîëσå£σ£║τ│╗τ╗ƒΣ╗╗σèíµ¥íΣ╗╢µîüτ╗¡µê¬σ¢╛πÇéµÿ╡τº░µ£¬Φ»åσê½Σ╗ìσÅ»Φ«░σ╜òπÇéΘÇÜτƒÑπÇüµî»σè¿σÆîµÅÉΘåÆµù╢Θù┤σÅùµ¥âΘÖÉΣ╕Äτ│╗τ╗ƒΦ«╛τ╜«σ╜▒σôìπÇé")
                .setPositiveButton("τƒÑΘüôΣ║å", null).show();
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
            exportState.message = "µ¡ñΦ«╛σñçΣ╕ìµö»µîüΘÇëµï⌐Σ┐¥σ¡ÿΣ╜ìτ╜«∩╝îµùáµ│òσ»╝σç║Φ»èµû¡σîàπÇé";
            renderExportState();
        } catch (RuntimeException e) {
            exportState.picking = false;
            exportState.message = "µùáµ│òµëôσ╝ÇΣ┐¥σ¡ÿΣ╜ìτ╜«ΘÇëµï⌐σÖ¿∩╝îΦ»╖τ¿ìσÉÄΘçìΦ»òπÇé";
            renderExportState();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_DIAGNOSTICS) return;
        exportState.picking = false;
        Uri destination = data == null ? null : data.getData();
        if (resultCode != RESULT_OK || destination == null) {
            exportState.message = "σ╖▓σÅûµ╢êσ»╝σç║∩╝îσ╖▓µ£ëµê¬σ¢╛σÆîΦ»åσê½Φ«░σ╜òΣ╗ìΣ┐¥τòÖπÇé";
            renderExportState();
            return;
        }
        exportState.busy = true;
        exportState.message = "";
        renderExportState();
        try {
            FarmAccessibilityService.exportDiagnostics(getApplicationContext(), destination, exportState);
        } catch (RuntimeException e) {
            exportState.onFailure("σ»╝σç║σñ▒Φ┤Ñ");
        }
    }

    private void renderExportState() {
        if (isFinishing() || isDestroyed()) return;
        exportButton.setEnabled(!exportState.busy && !exportState.picking);
        exportButton.setText(exportState.busy ? "µ¡úσ£¿σ»╝σç║ΓÇª" : "σ»╝σç║ ZIP");
        String message = exportState.busy ? "µ¡úσ£¿σ»╝σç║∩╝îΦ»╖τ¿ìσÇÖ∩╝¢Σ╕ìΣ╝Üµö╣σÅÿτ¢æµ╡ïτè╢µÇüπÇé"
                : exportState.picking ? "Φ»╖ΘÇëµï⌐Φ»èµû¡σîàτÜäΣ┐¥σ¡ÿΣ╜ìτ╜«πÇé"
                : exportState.message.isEmpty() ? "µùáΘ£ÇσÉ»σè¿τ¢æµ╡ïµêûσÉ»τö¿µùáΘÜ£τóìµ£ìσèíσì│σÅ»σ»╝σç║σ╖▓µ£ëΦ«░σ╜òπÇé"
                : exportState.message;
        if (FarmAccessibilityService.getOcrProgress().hasUnsavedCapture)
            message += "\nΦ┐ÿµ£ëµê¬σ¢╛µ£¬Σ┐¥σ¡ÿ∩╝îσÅ»Φâ╜Σ╕ìσ£¿Φ»èµû¡σîàσåà∩╝îσàêΘçìΦ»òΣ┐¥σ¡ÿ∩╝¢Φ»╖σï┐σà│Θù¡σ║öτö¿πÇé";
        exportStatus.setText(message);
    }

    // Retain only export state across rotation; the worker callback never owns an Activity.
    private static final class ExportState implements DiagnosticsExportCallback {
        private WeakReference<MainActivity> activity = new WeakReference<>(null);
        private boolean busy, picking;
        private String message = "";

        @Override public void onSuccess(int frameCount) {
            finish("σ╖▓σ»╝σç║" + frameCount + "σ╝áµê¬σ¢╛σÅèΦ»åσê½Φ«░σ╜ò"
                    + (frameCount == 0 ? "∩╝êµùáµê¬σ¢╛∩╝îΣ╗àσÉ½Φ»èµû¡σàâµò░µì«∩╝ë" : ""));
        }

        @Override public void onFailure(String ignored) {
            finish("σ»╝σç║σñ▒Φ┤Ñ∩╝îΦ»╖µúÇµƒÑΣ┐¥σ¡ÿΣ╜ìτ╜«σÆîσÅ»τö¿τ⌐║Θù┤σÉÄΘçìΦ»òπÇéµ£¬σ«îµêÉτÜä ZIP σÅ»Φâ╜Σ╕ìσ«îµò┤∩╝îΦ»╖σï┐τö¿Σ║Äµá╕σ»╣πÇé");
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
        new AlertDialog.Builder(this).setTitle("σ╝ÇσÉ»ΘÇÜτƒÑ")
                .setMessage("τö¿Σ║ÄµêÉτåƒµÅÉΘåÆ∩╝¢Σ╕ìσ╝ÇσÉ»Σ╗ìσÅ»τ¢æµ╡ïπÇé")
                .setNegativeButton("τ¿ìσÉÄ", null)
                .setPositiveButton("τ╗ºτ╗¡", (d, w) -> requestNotificationPermission()).show();
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
        if (results.length == 0) showMessage("σ╖▓σÅûµ╢êΘÇÜτƒÑµÄêµ¥â∩╝îσÅ»τ¿ìσÉÄσ£¿Φ«╛τ╜«Σ╕¡σ╝ÇσÉ»πÇé");
        else if (!notificationsAllowed()) showMessage("ΘÇÜτƒÑµ£¬σ╝ÇσÉ»∩╝îΦ»åσê½Σ╗ìσÅ»Σ╜┐τö¿∩╝¢µÅÉΘåÆΘÇÜτƒÑσÆîµî»σè¿σÅ»Φâ╜σÅùΘÖÉπÇé");
        else scheduler.rescheduleAll();
        refresh();
    }

    private void requestExactAlarm() {
        if (Build.VERSION.SDK_INT >= 31) {
            openSettings(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
        } else showMessage("µ¡ñΦ«╛σñçσ╖▓µö»µîüτ▓╛τí«µÅÉΘåÆ∩╝îΣ╗ìσÅ»Φâ╜σÅùτ│╗τ╗ƒΦ«╛τ╜«σ╜▒σôì");
    }

    private void openSettings(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))); }
            catch (ActivityNotFoundException | SecurityException ignored) {
                showMessage("µùáµ│òµëôσ╝ÇΦ«╛τ╜«∩╝îΦ»╖σ£¿τ│╗τ╗ƒΦ«╛τ╜«Σ╕¡µë╛σê░µ£¼σ║öτö¿πÇé");
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
                showFeedback("µùáµ│òµëôσ╝ÇΣ╜┐τö¿µâàσå╡Φ«┐Θù«Φ«╛τ╜«∩╝îΦ»╖σ£¿τ│╗τ╗ƒΦ«╛τ╜«Σ╕¡µƒÑµë╛ΓÇ£Σ╜┐τö¿µâàσå╡Φ«┐Θù«µ¥âΘÖÉΓÇ¥σ╣╢σàüΦ«╕µ£¼σ║öτö¿πÇé");
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        FarmAccessibilityService.refreshDiskProgress(this);
        resumed = true;
        MonitorCoordinator.onVisible(this);
        try { scheduler.rescheduleAll(); }
        catch (RuntimeException e) { showMessage("Θâ¿σêåµÅÉΘåÆµ£¬Φâ╜Θçìµû░σ«ëµÄÆ∩╝îΦ»╖µúÇµƒÑµ¥âΘÖÉπÇé"); }
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
        boolean foregroundActive = monitor.foregroundActive;
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
        foregroundStatus.setText("σëìσÅ░τ¢æµ╡ïµ£ìσèí ┬╖ " + (foregroundActive ? "Φ┐ÉΦíîΣ╕¡" : "µ£¬σÉ»σè¿")
                + "\nτ¢æµ╡ïτè╢µÇü ┬╖ " + monitor.state.name() + " ┬╖ " + monitor.reason.name());
        boolean requiredReady = supported && sourceReady && enabled && connected;
        MonitorPresentation presentation = MonitorPresentation.from(monitor, supported, requiredReady);
        updatingMode = true;
        modeChoices.check(useShizuku ? shizukuMode.getId() : usageMode.getId());
        updatingMode = false;
        boolean modeEditable = presentation.modeEditable;
        usageMode.setEnabled(modeEditable);
        shizukuMode.setEnabled(modeEditable);
        setOptionalText(modeHint, !modeEditable ? "Φ»╖σàêσü£µ¡óτ¢æµ╡ï∩╝îσåìσêçµìóµ¿íσ╝Å"
                : !useShizuku && !usageAllowed ? "σ░Üµ£¬σàüΦ«╕Σ╜┐τö¿µâàσå╡Φ«┐Θù«" : "");
        foregroundError.setVisibility(presentation.needsAttention ? View.VISIBLE : View.GONE);
        foregroundError.setText(presentation.detail);
        // Only confirmed problems belong beside the button; runtime status stays in Tools.
        String shizukuIssue = useShizuku ? ShizukuTaskGate.get(this).notificationIssue() : null;
        String monitorProblem = "";
        if (!supported) {
            monitorProblem = "µê¬σ¢╛Θ£ÇΦªü Android 11 σÅèΣ╗ÑΣ╕èπÇé";
        } else if ("SHIZUKU_PERMISSION".equals(shizukuIssue)) {
            monitorProblem = "Shizuku µÄêµ¥âΣ╕ìσÅ»τö¿∩╝îΦ»╖µúÇµƒÑµÄêµ¥âπÇé";
        } else if ("SHIZUKU_CONNECTION".equals(shizukuIssue)) {
            monitorProblem = "Shizuku Φ┐₧µÄÑσ╖▓µû¡σ╝Ç∩╝îΦ»╖µúÇµƒÑµ£ìσèíπÇé";
        } else if (presentation.needsAttention) {
            // A suspended session still requires manual recovery even if its dependency recovered.
            monitorProblem = monitor.reason == MonitorCoordinator.Reason.SHIZUKU
                    ? "τ¢æµ╡ïσ╖▓µÜéσü£∩╝îΦ»╖σü£µ¡óσÉÄΘçìµû░σ╝ÇσºïπÇé" : presentation.detail;
        } else if (monitor.state == MonitorCoordinator.State.STOPPED
                && monitor.reason == MonitorCoordinator.Reason.SYSTEM_STOP) {
            monitorProblem = "τ│╗τ╗ƒσ╖▓σü£µ¡óτ¢æµ╡ï∩╝îΦ»╖µëïσè¿σ╝ÇσºïπÇé";
        } else if (monitor.state == MonitorCoordinator.State.WAITING) {
            if (monitor.reason == MonitorCoordinator.Reason.ACCESSIBILITY && (!enabled || !connected))
                monitorProblem = "µê¬σ¢╛µ£ìσèíµ£¬Φ┐₧µÄÑ∩╝îΦ»╖µúÇµƒÑµ¥âΘÖÉπÇé";
            else if (monitor.reason == MonitorCoordinator.Reason.SOURCE_PERMISSION && !useShizuku && !usageAllowed)
                monitorProblem = "Σ╜┐τö¿µâàσå╡Φ«┐Θù«µ¥âΘÖÉσ╖▓σà│Θù¡∩╝îΦ»╖Θçìµû░µÄêµ¥âπÇé";
        }
        setOptionalText(captureStatus, monitorProblem);
        // The existing setup gate can hide the dashboard; retain a stop/retry affordance inside it too.
        setupMonitorPanel.setVisibility(presentation.showStop ? View.VISIBLE : View.GONE);
        setupMonitorStatus.setText(presentation.title + (presentation.detail.isEmpty() ? "" : "\n" + presentation.detail));
        setupMonitorRetry.setVisibility(presentation.showStart ? View.VISIBLE : View.GONE);
        setupMonitorRetry.setText(presentation.startLabel);
        setupMonitorRetry.setEnabled(presentation.startEnabled);
        setupMonitorStop.setVisibility(presentation.showStop ? View.VISIBLE : View.GONE);
        OcrProgress progress = FarmAccessibilityService.getOcrProgress();
        renderExportState();
        processingSummary.setText(ScreenPresentation.processingSummary(progress));
        setOptionalText(ocrState, ScreenPresentation.processingNotice(progress));
        queueDetails.setText(String.format(Locale.CHINA,
                "σ╖▓σñäτÉå %d ┬╖ σ╛àσñäτÉå %d ┬╖ τòÖσ¡ÿ %d ┬╖ σñ▒Φ┤Ñ %d\nσÄåσÅ▓τ╝║σ¢╛/µ╕àτÉåΘí╣ %d ┬╖ %.1f MiB\nµÜéσü£σÄƒσ¢á %s ┬╖ σåÖσàÑ %s ┬╖ µ£¬Σ┐¥σ¡ÿ %s",
                progress.processed, progress.queued, progress.archivedCount, progress.failedCount, progress.cleanupPendingCount,
                progress.retainedBytes / (1024f * 1024f), progress.pauseReason, progress.writing, progress.hasUnsavedCapture));
        retryButton.setVisibility(progress.retryAvailable ? View.VISIBLE : View.GONE);
        retryButton.setEnabled(progress.retryAvailable);
        String diagnostics = FarmAccessibilityService.diagnostics;
        diagnosticsText.setText("τ¢æµ╡ïτè╢µÇü∩╝Ü" + FarmAccessibilityService.lastStatus
                + (useShizuku ? "\nShizuku∩╝Ü" + ShizukuTaskGate.get(this).status() : "")
                + "\n" + (blank(diagnostics) ? "µÜéµùáΦ»èµû¡µò░µì«" : diagnostics));
        monitorButton.setText(presentation.showStop ? "σü£µ¡óτ¢æµ╡ï" : "σ╝Çσºïτ¢æµ╡ï");
        monitorButton.setEnabled(presentation.showStop || presentation.startEnabled);
        monitorButton.setAlpha(monitorButton.isEnabled() ? 1f : 0.55f);
        boolean notified = notificationsAllowed();
        notificationButton.setVisibility(notified ? View.GONE : View.VISIBLE);
        refreshNotificationSettings();
        boolean exact = scheduler.canScheduleExact();
        exactButton.setText(exact ? "τ▓╛τí«Θù╣ΘÆƒ ┬╖ σ╖▓σ╝ÇσÉ»" : "τ▓╛τí«Θù╣ΘÆƒ ┬╖ µ£¬σ╝ÇσÉ»");
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
        SimpleDateFormat date = new SimpleDateFormat("yyyyσ╣┤Mµ£êdµùÑ HH:mm:ss", Locale.CHINA);
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
            if (allFired || waiting) addText(row, allFired ? "σ╖▓µÅÉΣ║ñΘÇÜτƒÑ" : "τ¡ëσ╛àµÅÉΣ║ñΘÇÜτƒÑ", 12,
                    allFired ? MUTED : GREEN, true, 0);
            renderAvatarTime(row, group.anchor(), ReminderImagePresentation.plateRef(group.reminders),
                    ReminderListGroups.summary(group), "µ£Çµù⌐µêÉτåƒ");
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
            toggle.setText(expanded ? "µö╢Φ╡╖µÿÄτ╗å" : "µƒÑτ£ïµÿÄτ╗å");
            toggle.setContentDescription((expanded ? "µö╢Φ╡╖ " : "µƒÑτ£ï ") + group.reminders.size() + " µ¥íµÅÉΘåÆµÿÄτ╗å");
            toggle.setOnClickListener(v -> {
                boolean open = details.getVisibility() != View.VISIBLE;
                if (open) expandedGroups.add(group.identity); else expandedGroups.remove(group.identity);
                details.setVisibility(open ? View.VISIBLE : View.GONE);
                toggle.setText(open ? "µö╢Φ╡╖µÿÄτ╗å" : "µƒÑτ£ïµÿÄτ╗å");
                toggle.setContentDescription((open ? "µö╢Φ╡╖ " : "µƒÑτ£ï ") + group.reminders.size() + " µ¥íµÅÉΘåÆµÿÄτ╗å");
            });
        }
    }

    private void renderReminder(LinearLayout row, Reminder r, SimpleDateFormat date, long now) {
        if (r.fired || r.triggerAt <= now) addText(row, r.fired ? "σ╖▓µÅÉΣ║ñΘÇÜτƒÑ" : "τ¡ëσ╛àµÅÉΣ║ñΘÇÜτƒÑ", 12,
                r.fired ? MUTED : GREEN, true, 0);
        renderAvatarTime(row, r, r.plateRef, "", "µêÉτåƒ");
        addText(row, ScreenPresentation.schedule(r.matureAt, r.triggerAt), 12, MUTED, false, 5);
        Button delete = button(row, "σêáΘÖñ", false, 10, () -> new AlertDialog.Builder(this)
                .setTitle("σêáΘÖñµÅÉΘåÆ∩╝ƒ").setMessage("µêÉτåƒµù╢Θù┤∩╝Ü" + date.format(new Date(r.matureAt))
                        + "\nΣ╗àσêáΘÖñΦ┐Öµ¥íΦ«░σ╜ò∩╝îµ£¬ΦºªσÅæτÜäµÅÉΘåÆσ░åσÅûµ╢êπÇé")
                .setNegativeButton("Σ┐¥τòÖ", null).setPositiveButton("σêáΘÖñ", (d, w) -> {
                    store.delete(r.id); refresh();
                }).show());
        delete.setContentDescription("σêáΘÖñ " + date.format(new Date(r.matureAt)) + " µêÉτåƒτÜäµÅÉΘåÆ");
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
        addText(words, new SimpleDateFormat("yyyyσ╣┤Mµ£êdµùÑ", Locale.CHINA)
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
            b.setContentDescription(b.getText() + (selected ? "∩╝îσ╖▓ΘÇëµï⌐" : "∩╝îµ£¬ΘÇëµï⌐"));
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
