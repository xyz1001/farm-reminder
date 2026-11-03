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
    public static final class Snapshot {
        public final boolean match;
        public final String state, key, component, reason;
        public final long age, generation;
        private Snapshot(String state, String key, String component, String reason, long age, long generation) {
            this.state = state; this.match = "MATCH".equals(state); this.key = key;
            this.component = component; this.reason = reason; this.age = age; this.generation = generation;
        }
    }
    private static final long PERMISSION_TTL = 3000;
    private static volatile ShizukuTaskGate instance;
    private final Context context;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Shizuku.UserServiceArgs args;
    private final GateState query = new GateState();
    private final AsyncControl control;
    private boolean active, granted, binding, initialized, poll, request, queryWanted, bindWanted;
    private long epoch, generation, started = -1, permissionAt = -1;
    private ITaskQuery service;
    private IBinder pendingBinder;
    private ServiceConnection connection, cleanup;
    private String state = "UNKNOWN", key, component, reason = "not monitoring", status = "未监测";

    private final Shizuku.OnBinderReceivedListener received = () -> {
        synchronized (this) { permissionInvalid("Shizuku 连接已更新"); poll = true; }
        wakeControl();
    };
    private final Shizuku.OnBinderDeadListener dead = () -> {
        synchronized (this) { permissionInvalid("Shizuku 已断开"); poll = true; }
        wakeControl();
    };
    private final Shizuku.OnRequestPermissionResultListener permission = (requestCode, grantResult) -> {
        if (requestCode != 4321) return;
        synchronized (this) {
            permissionInvalid(grantResult == PackageManager.PERMISSION_GRANTED ? "已授权，等待检查" : "未授权");
            poll = true;
        }
        wakeControl();
    };

    private ShizukuTaskGate(Context context) {
        this.context = context.getApplicationContext();
        args = new Shizuku.UserServiceArgs(new ComponentName(this.context, ShizukuTaskQueryService.class))
                .processNameSuffix("task_query").tag("task_query").daemon(false).version(1)
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
    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("useShizuku", false);
    }
    public boolean hasPermission() {
        synchronized (this) {
            if (!permissionFresh()) { poll = true; control.wake(); return false; }
            return granted;
        }
    }
    public synchronized String status() {
        if (!isEnabled(context)) return "未启用";
        if (!permissionFresh()) return "等待检查授权";
        return status;
    }
    public void requestPermission() {
        synchronized (this) { request = true; }
        control.wake();
    }
    public void start() {
        synchronized (this) {
            if (!active) { active = true; epoch++; invalidate("等待任务查询"); }
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
        if (!active || !isEnabled(context) || !permissionFresh() || !GateState.fresh(age))
            return new Snapshot("UNKNOWN", null, null, "stale or unavailable", age, generation);
        return new Snapshot(state, key, component, reason, age, generation);
    }
    private boolean permissionFresh() {
        long age = SystemClock.elapsedRealtime() - permissionAt;
        return granted && permissionAt >= 0 && age >= 0 && age < PERMISSION_TTL;
    }
    private void invalidate(String why) {
        query.invalidate(); state = "UNKNOWN"; key = component = null; reason = why; status = why;
        started = -1; generation++;
    }
    private void detach() {
        if (connection != null) cleanup = connection;
        connection = null; service = null; pendingBinder = null; binding = false;
    }
    private void permissionInvalid(String why) {
        granted = false; permissionAt = -1; epoch++; invalidate(why); detach();
    }
    private void tick() {
        synchronized (this) {
            if (active && isEnabled(context)) {
                if (granted && !permissionFresh()) permissionInvalid("授权检查超时");
                poll = true; queryWanted = true; bindWanted = true;
            }
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
                                if (connection != this || epoch != bindEpoch || !active) return;
                                pendingBinder = binder;
                            }
                            control.wake();
                        }
                        @Override public void onServiceDisconnected(ComponentName name) { disconnected(this); }
                    };
                    connection = bind; binding = true; bindWanted = false; action = 5;
                    final ServiceConnection scheduled = bind;
                    timer.schedule(() -> { synchronized (ShizukuTaskGate.this) {
                        if (connection == scheduled && binding) { epoch++; invalidate("连接超时"); detach(); }
                    } control.wake(); }, 3, TimeUnit.SECONDS);
                } else if (pendingBinder != null && connection != null) {
                    link = pendingBinder; pendingBinder = null; bind = connection; run = epoch; action = 6;
                } else if (active && isEnabled(context) && permissionFresh() && service != null && queryWanted && !query.busy()) {
                    queryWanted = false; target = service; run = epoch; token = query.begin();
                    queryStart = SystemClock.elapsedRealtime(); action = 7;
                    final long scheduledToken = token;
                    timer.schedule(() -> { synchronized (ShizukuTaskGate.this) {
                        if (query.current(scheduledToken)) invalidate("查询超时");
                    } }, GateState.TTL, TimeUnit.MILLISECONDS);
                } else return;
            }
            if (action == 1) {
                try { Shizuku.addBinderReceivedListenerSticky(received); Shizuku.addBinderDeadListener(dead);
                    Shizuku.addRequestPermissionResultListener(permission); } catch (RuntimeException ignored) { }
            } else if (action == 2) {
                try { Shizuku.unbindUserService(args, old, true); } catch (RuntimeException ignored) { }
            } else if (action == 3) {
                try {
                    if (Shizuku.pingBinder() && ShizukuIdentityPolicy.isSupportedUid(Shizuku.getUid()) &&
                            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(4321);
                } catch (RuntimeException ignored) { }
                synchronized (this) { poll = true; }
            } else if (action == 4) {
                boolean ok = false; String message = "Shizuku 不可用";
                try {
                    if (!Shizuku.pingBinder()) message = "Shizuku 未运行";
                    else if (!ShizukuIdentityPolicy.isSupportedUid(Shizuku.getUid())) message = "不支持的 Shizuku 身份";
                    else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) message = "等待授权";
                    else { ok = true; message = "等待任务查询"; }
                } catch (RuntimeException ignored) { }
                synchronized (this) {
                    if (epoch == run) {
                        if (!ok) permissionInvalid(message);
                        else { granted = true; permissionAt = SystemClock.elapsedRealtime(); if (service == null) status = message; }
                    }
                }
            } else if (action == 5) {
                try { Shizuku.bindUserService(args, bind); }
                catch (RuntimeException e) { disconnected(bind); }
            } else if (action == 6) {
                final ServiceConnection linked = bind;
                boolean ok = true;
                try { link.linkToDeath(() -> disconnected(linked), 0); } catch (Exception e) { ok = false; }
                synchronized (this) {
                    if (connection == bind && epoch == run) {
                        if (!ok) { epoch++; invalidate("服务已断开"); detach(); }
                        else { binding = false; service = ITaskQuery.Stub.asInterface(link); invalidate("已连接，等待查询"); queryWanted = true; }
                    }
                }
            } else {
                Bundle result = null;
                try { result = target.queryForegroundTask(); } catch (Exception ignored) { }
                synchronized (this) {
                    boolean valid = query.finish(token, SystemClock.elapsedRealtime() - queryStart);
                    if (!valid || !active || epoch != run || service != target || !isEnabled(context) || !permissionFresh()) continue;
                    if (result == null) { invalidate("查询失败或超时"); continue; }
                    String value = result.getString("state", "UNKNOWN");
                    String k = result.getString("key"), c = result.getString("component");
                    if ((!"MATCH".equals(value) && !"NON_MATCH".equals(value)) || ("MATCH".equals(value) && (k == null || c == null))) {
                        invalidate("查询结果无效"); continue;
                    }
                    state = value; key = k; component = c; reason = result.getString("reason", "");
                    started = queryStart; status = "监测中"; generation++;
                }
            }
        }
    }
    private void disconnected(ServiceConnection old) {
        synchronized (this) {
            if (connection == old) { epoch++; invalidate("服务已断开"); detach(); }
        }
        control.wake();
    }
}
