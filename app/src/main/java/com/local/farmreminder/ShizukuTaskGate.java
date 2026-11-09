package com.local.farmreminder;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.Shizuku;

/** Fail-closed snapshots; all potentially blocking Shizuku calls run on one bounded worker. */
public final class ShizukuTaskGate {
    // Increment whenever the user-service code or its dependencies change.
    private static final int USER_SERVICE_VERSION = 2;
    public static final class Snapshot {
        public final boolean match;
        public final String state, key, component, reason;
        public final long age, generation;
        private Snapshot(String state, String key, String component, String reason, long age, long generation) {
            this.state = state; this.match = "MATCH".equals(state); this.key = key;
            this.component = component; this.reason = reason; this.age = age; this.generation = generation;
        }
    }
    private static volatile ShizukuTaskGate instance;
    private final Context context;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Shizuku.UserServiceArgs args;
    private final GateState query = new GateState();
    private final ShizukuPermissionState authorization = new ShizukuPermissionState();
    private final AsyncControl control;
    private boolean active, binding, initialized, poll, request, queryWanted, bindWanted;
    private long epoch, generation, started = -1;
    private ITaskQuery service;
    private IBinder pendingBinder;
    private ServiceConnection connection, cleanup;
    private String state = "UNKNOWN", key, component, reason = "not monitoring", status = "未监测";
    // Current confirmed dependency failure, independent of the fail-closed task snapshot.
    private String notificationIssue;
    // Observations are local and guarded by this gate's lock, not a global async trace.
    private String debugStage = "IDLE", debugReject = "none", debugException = "none";
    private String debugRemoteReason = "none", debugInvalidate = "INITIAL";
    private long debugStageAt = -1, debugPermissionAt = -1;
    private long bindAttempts, connectedCallbacks, linkSuccess, queryStarted, queryReturned, queryAccepted, queryTimeout;

    private final Shizuku.OnBinderReceivedListener received = () -> {
        synchronized (this) { permissionInvalid("Shizuku 连接已更新"); poll = true; }
        wakeControl();
    };
    private final Shizuku.OnBinderDeadListener dead = () -> {
        synchronized (this) { permissionInvalid("Shizuku 已断开"); notificationIssue = "SHIZUKU_CONNECTION"; poll = true; }
        wakeControl();
    };
    private final Shizuku.OnRequestPermissionResultListener permission = (requestCode, grantResult) -> {
        if (requestCode != 4321) return;
        synchronized (this) {
            permissionInvalid(grantResult == PackageManager.PERMISSION_GRANTED ? "已授权，等待检查" : "未授权");
            notificationIssue = grantResult == PackageManager.PERMISSION_GRANTED ? null : "SHIZUKU_PERMISSION";
            poll = true;
        }
        wakeControl();
    };

    private ShizukuTaskGate(Context context) {
        this.context = context.getApplicationContext();
        args = new Shizuku.UserServiceArgs(new ComponentName(this.context, ShizukuTaskQueryService.class))
                .processNameSuffix("task_query").tag("task_query").daemon(false).version(USER_SERVICE_VERSION)
                .debuggable((this.context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        control = new AsyncControl(this::work);
        control.wake();
        timer.scheduleAtFixedRate(this::tick, 0, 1, TimeUnit.SECONDS);
    }
    private void wakeControl() { control.wake(); }
    public static ShizukuTaskGate get(Context context) {
        if (instance == null) synchronized (ShizukuTaskGate.class) {
            if (instance == null) instance = new ShizukuTaskGate(context);
        }
        return instance;
    }
    static ShizukuTaskGate peek() { return instance; }
    String debugSnapshot() {
        long now = SystemClock.elapsedRealtime();
        Object[] values;
        synchronized (this) {
            values = new Object[] { active, authorization.knownGrant(), authorization.fresh(now), debugPermissionAt,
                    debugStage, debugStageAt, connection != null, binding, pendingBinder != null, service != null,
                    bindAttempts, connectedCallbacks, linkSuccess, queryStarted, queryReturned, queryAccepted, queryTimeout,
                    debugReject, debugException, debugRemoteReason, started, generation, debugInvalidate, status };
        }
        return "gate active=" + values[0] + " permissionKnownGrant=" + values[1] + " permissionFresh=" + values[2]
                + " permissionAge=" + LiveDiagnostics.age(now, (long) values[3]) + " stage=" + values[4]
                + " stageAge=" + LiveDiagnostics.age(now, (long) values[5]) + " connection=" + values[6]
                + " binding=" + values[7] + " pendingBinder=" + values[8] + " service=" + values[9]
                + " bindAttempts=" + values[10] + " connectedCallbacks=" + values[11] + " linkSuccess=" + values[12]
                + " queryStarted=" + values[13] + " queryReturned=" + values[14] + " queryAccepted=" + values[15]
                + " queryTimeout=" + values[16] + " lastReject=" + values[17] + " exceptionClass=" + values[18]
                + " remoteReason=" + values[19] + " querySnapshotAge=" + LiveDiagnostics.age(now, (long) values[20])
                + " generation=" + values[21] + " invalidateReason=" + values[22] + " status=" + values[23];
    }
    private void debugException(Exception e) { synchronized (this) { debugException = LiveDiagnostics.exceptionName(e); } }
    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("useShizuku", false);
    }
    public boolean hasPermission() {
        synchronized (this) {
            if (!permissionFresh()) { poll = true; control.wake(); }
            return authorization.knownGrant();
        }
    }
    public synchronized String status() {
        if (!isEnabled(context)) return "未启用";
        if (!permissionFresh()) return "等待检查授权";
        return status;
    }
    synchronized String notificationIssue() { return notificationIssue; }
    public void requestPermission() {
        synchronized (this) { request = true; }
        control.wake();
    }
    public void start() {
        synchronized (this) {
            if (!active) { active = true; epoch++; invalidate("等待任务查询"); notificationIssue = null; }
            poll = true;
        }
        control.wake();
    }
    public void stop() {
        synchronized (this) { active = false; epoch++; invalidate("未监测"); detach(); queryWanted = false; bindWanted = false; }
        control.wake();
    }
    public synchronized Snapshot snapshot() {
        long age = started < 0 ? Long.MAX_VALUE : SystemClock.elapsedRealtime() - started;
        if (!authorization.runtimeReady(active, isEnabled(context), SystemClock.elapsedRealtime(), age))
            return new Snapshot("UNKNOWN", null, null, "stale or unavailable", age, generation);
        return new Snapshot(state, key, component, reason, age, generation);
    }
    private boolean permissionFresh() {
        return authorization.fresh(SystemClock.elapsedRealtime());
    }
    private void invalidate(String why) {
        debugInvalidate = LiveDiagnostics.invalidateReason(why);
        query.invalidate(); state = "UNKNOWN"; key = component = null; reason = why; status = why;
        started = -1; generation++;
    }
    private void detach() {
        if (connection != null) cleanup = connection;
        connection = null; service = null; pendingBinder = null; binding = false;
    }
    private void permissionInvalid(String why) {
        authorization.invalidate(); debugPermissionAt = -1; epoch++; invalidate(why); detach();
    }
    private void tick() {
        synchronized (this) {
            boolean enabled = isEnabled(context);
            if (ShizukuPermissionState.shouldPoll(enabled)) poll = true;
            if (ShizukuPermissionState.shouldQuery(active, enabled)) { queryWanted = true; bindWanted = true; }
        }
        control.wake();
    }
    private void work() {
        for (;;) {
            ServiceConnection old = null, bind = null;
            IBinder link = null;
            ITaskQuery target = null;
            long run = 0, token = 0, queryStart = 0;
            int action;
            synchronized (this) {
                if (!initialized) { initialized = true; action = 1; }
                else if (cleanup != null) { old = cleanup; cleanup = null; action = 2; }
                else if (request) { request = false; action = 3; }
                else if (poll) { poll = false; run = epoch; action = 4; }
                else if (active && isEnabled(context) && permissionFresh() && bindWanted && connection == null) {
                    run = epoch; final long bindEpoch = run;
                    bind = new ServiceConnection() {
                        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                            synchronized (ShizukuTaskGate.this) {
                                connectedCallbacks++;
                                if (!ShizukuStartupPolicy.current(connection, this, epoch, bindEpoch, active)) return;
                                pendingBinder = binder;
                                // The deadline must not remove a binder waiting for the link worker.
                                binding = false;
                            }
                            control.wake();
                        }
                        @Override public void onServiceDisconnected(ComponentName name) { disconnected(this); }
                    };
                    connection = bind; binding = true; bindWanted = false; action = 5; bindAttempts++;
                } else if (pendingBinder != null && connection != null) {
                    link = pendingBinder; pendingBinder = null; bind = connection; run = epoch; action = 6;
                } else if (active && isEnabled(context) && permissionFresh() && service != null && queryWanted && !query.busy()) {
                    queryWanted = false; target = service; run = epoch; token = query.begin();
                    queryStart = SystemClock.elapsedRealtime(); action = 7; queryStarted++;
                    final long scheduledToken = token;
                    timer.schedule(() -> { synchronized (ShizukuTaskGate.this) {
                        if (query.current(scheduledToken)) { queryTimeout++; invalidate("查询超时"); }
                    } }, GateState.TTL, TimeUnit.MILLISECONDS);
                } else { debugStage = "IDLE"; debugStageAt = SystemClock.elapsedRealtime(); return; }
                debugStage = new String[] { "", "INIT", "UNBIND", "REQUEST", "POLL", "BIND", "LINK", "QUERY" }[action];
                debugStageAt = SystemClock.elapsedRealtime();
            }
            if (action == 1) {
                try { Shizuku.addBinderReceivedListenerSticky(received); Shizuku.addBinderDeadListener(dead);
                    Shizuku.addRequestPermissionResultListener(permission); } catch (RuntimeException e) { debugException(e); }
            } else if (action == 2) {
                try { Shizuku.unbindUserService(args, old, true); } catch (RuntimeException e) { debugException(e); }
            } else if (action == 3) {
                try {
                    if (Shizuku.pingBinder() && ShizukuIdentityPolicy.isSupportedUid(Shizuku.getUid()) &&
                            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(4321);
                } catch (RuntimeException e) { debugException(e); }
                synchronized (this) { poll = true; }
            } else if (action == 4) {
                boolean ok = false; String message = "Shizuku 不可用";
                try {
                    if (!Shizuku.pingBinder()) message = "Shizuku 未运行";
                    else if (!ShizukuIdentityPolicy.isSupportedUid(Shizuku.getUid())) message = "不支持的 Shizuku 身份";
                    else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) message = "等待授权";
                    else { ok = true; message = "等待任务查询"; }
                } catch (RuntimeException e) { debugException(e); }
                synchronized (this) {
                    if (epoch == run) {
                        if (!ok) { permissionInvalid(message); notificationIssue = "等待授权".equals(message) ? "SHIZUKU_PERMISSION" : "SHIZUKU_CONNECTION"; }
                        else { debugPermissionAt = SystemClock.elapsedRealtime(); authorization.grant(debugPermissionAt); if ("SHIZUKU_PERMISSION".equals(notificationIssue)) notificationIssue = null; if (service == null) status = message; }
                    }
                }
            } else if (action == 5) {
                try {
                    Shizuku.bindUserService(args, bind);
                    // Start our local budget after IPC returns, not before the server can start.
                    final ServiceConnection scheduled = bind;
                    final long scheduledEpoch = run;
                    timer.schedule(() -> { synchronized (ShizukuTaskGate.this) {
                        if (ShizukuStartupPolicy.shouldTimeout(connection, scheduled, epoch, scheduledEpoch, active, binding)) {
                            epoch++; invalidate("连接超时"); detach(); notificationIssue = "SHIZUKU_CONNECTION";
                        }
                    } control.wake(); }, ShizukuStartupPolicy.STARTUP_BUDGET_MS, TimeUnit.MILLISECONDS);
                }
                catch (RuntimeException e) { debugException(e); disconnected(bind); }
            } else if (action == 6) {
                final ServiceConnection linked = bind;
                boolean ok = true;
                try { link.linkToDeath(() -> disconnected(linked), 0); } catch (Exception e) { ok = false; debugException(e); }
                synchronized (this) {
                    if (ok) linkSuccess++;
                    if (connection == bind && epoch == run) {
                        if (!ok) { epoch++; invalidate("服务已断开"); detach(); notificationIssue = "SHIZUKU_CONNECTION"; }
                        else { binding = false; service = ITaskQuery.Stub.asInterface(link); invalidate("已连接，等待查询"); notificationIssue = null; queryWanted = true; }
                    }
                }
            } else {
                Bundle result = null;
                try { result = target.queryForegroundTask(); } catch (Exception e) { debugException(e); }
                synchronized (this) {
                    queryReturned++;
                    if (result == null) debugRemoteReason = "none";
                    else {
                        try { debugRemoteReason = LiveDiagnostics.remoteReason(result.getString("reason")); }
                        catch (RuntimeException e) { debugRemoteReason = "REDACTED"; debugException = LiveDiagnostics.exceptionName(e); }
                    }
                    boolean valid = query.finish(token, SystemClock.elapsedRealtime() - queryStart);
                    if (!valid) { debugReject = "TOKEN"; continue; }
                    if (!active || epoch != run || service != target || !isEnabled(context)) { debugReject = "EPOCH"; continue; }
                    if (result == null) { debugReject = "NULL_OR_EXCEPTION"; permissionInvalid("查询失败或超时"); notificationIssue = "SHIZUKU_CONNECTION"; continue; }
                    if (!permissionFresh()) { debugReject = "PERMISSION_STALE"; continue; }
                    String value = result.getString("state", "UNKNOWN");
                    String k = result.getString("key"), c = result.getString("component");
                    if ((!"MATCH".equals(value) && !"NON_MATCH".equals(value)) || ("MATCH".equals(value) && (k == null || c == null))) {
                        debugReject = "UNKNOWN_RESULT"; invalidate("查询结果无效"); continue;
                    }
                    state = value; key = k; component = c; reason = result.getString("reason", "");
                    started = queryStart; status = "监测中"; generation++; queryAccepted++;
                }
            }
        }
    }
    private void disconnected(ServiceConnection old) {
        synchronized (this) {
            if (connection == old) { epoch++; invalidate("服务已断开"); detach(); notificationIssue = "SHIZUKU_CONNECTION"; }
        }
        control.wake();
    }
}
