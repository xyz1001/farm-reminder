package com.local.farmreminder;

import android.content.Context;
import android.os.Build;
import android.os.Looper;

/** Serializes explicit intent changes and fences lifecycle callbacks on the main thread. */
final class MonitorCoordinator {
    enum State { NOT_ENROLLED, STOPPED, STARTING, WAITING, RUNNING, SUSPENDED, ERROR }
    enum RequestCode { ACCEPTED, ALREADY_ACTIVE, STORAGE_FAILED, UNSUPPORTED, START_REJECTED }
    enum Reason { NONE, USER_STOP, SYSTEM_STOP, ACCESSIBILITY, SOURCE_PERMISSION, SHIZUKU, LOCKED, NOT_TARGET,
        BUSY, WATCHDOG, ENGINE_FATAL, SOURCE_CHANGED, START_REJECTED, STORAGE_ERROR, HISTORY_UNAVAILABLE, UNSUPPORTED, UNEXPECTED_STOP }
    static final class Snapshot {
        final long revision; final boolean enrolled, wanted, foregroundActive, monitoringRunning;
        final State state; final Reason reason;
        Snapshot(long revision, boolean enrolled, boolean wanted, boolean foregroundActive, boolean monitoringRunning, State state, Reason reason) {
            this.revision = revision; this.enrolled = enrolled; this.wanted = wanted; this.foregroundActive = foregroundActive;
            this.monitoringRunning = monitoringRunning; this.state = state; this.reason = reason;
        }
    }
    static final class RequestResult {
        final RequestCode code; final Snapshot snapshot;
        RequestResult(RequestCode code, Snapshot snapshot) { this.code = code; this.snapshot = snapshot; }
    }
    private static final MonitorRuntimePolicy POLICY = new MonitorRuntimePolicy();
    private static volatile boolean failedClosed, historyBlocked;
    private static volatile State runtimeState = State.STOPPED;
    private static volatile Reason runtimeReason = Reason.NONE;
    private static volatile String lastTrigger = "NONE", lastWriteOk = "UNKNOWN";
    private MonitorCoordinator() { }
    private static boolean main() { return Looper.myLooper() == Looper.getMainLooper(); }
    private static boolean usable(MonitorIntentRepository.Result r) {
        return r != null && (r.code == MonitorIntentRepository.Code.OK || r.code == MonitorIntentRepository.Code.MISSING) && r.state != null;
    }
    static Snapshot getSnapshot(Context context) {
        try {
            MonitorIntentRepository.Result r = MonitorIntentStore.get(context).read();
            if (!usable(r) || failedClosed || historyBlocked) return new Snapshot(r != null && r.state != null ? r.state.revision : 0,
                    r != null && r.state != null && r.state.enrolled, r != null && r.state != null && r.state.wanted,
                    MonitorForegroundService.isActive, FarmAccessibilityService.isRunning, State.ERROR, historyBlocked && !failedClosed ? Reason.HISTORY_UNAVAILABLE : Reason.STORAGE_ERROR);
            MonitorIntentState s = r.state;
            State state = runtimeState == State.ERROR ? State.ERROR : !s.enrolled ? State.NOT_ENROLLED : s.suspension != MonitorIntentState.Suspension.NONE ? State.SUSPENDED
                    : !s.wanted ? State.STOPPED : MonitorForegroundService.isActive && runtimeState == State.WAITING ? State.WAITING
                    : FarmAccessibilityService.isRunning ? State.RUNNING : MonitorForegroundService.isActive ? State.WAITING
                    : runtimeState == State.ERROR ? State.ERROR : State.STARTING;
            Reason reason = state == State.ERROR ? runtimeReason : s.suspension == MonitorIntentState.Suspension.SYSTEM_STOP ? Reason.SYSTEM_STOP
                    : s.suspension == MonitorIntentState.Suspension.MANUAL_RETRY_REQUIRED
                    ? (runtimeReason == Reason.NONE ? Reason.UNEXPECTED_STOP : runtimeReason)
                    : !s.enrolled ? Reason.NONE : !s.wanted ? Reason.USER_STOP : runtimeReason;
            return new Snapshot(s.revision, s.enrolled, s.wanted, MonitorForegroundService.isActive, FarmAccessibilityService.isRunning, state, reason);
        } catch (RuntimeException e) { return new Snapshot(0, false, false, false, false, State.ERROR, Reason.STORAGE_ERROR); }
    }
    static RequestResult start(Context context, long attempt) {
        if (!main()) return new RequestResult(RequestCode.START_REJECTED, getSnapshot(context));
        lastTrigger = "USER_START";
        if (Build.VERSION.SDK_INT < 30) { runtimeState = State.ERROR; runtimeReason = Reason.UNSUPPORTED; return new RequestResult(RequestCode.UNSUPPORTED, getSnapshot(context)); }
        Snapshot before = getSnapshot(context);
        if (before.wanted && before.monitoringRunning && !failedClosed && !historyBlocked) return new RequestResult(RequestCode.ALREADY_ACTIVE, before);
        MonitorIntentRepository.Result result;
        try {
            String mode = ShizukuTaskGate.isEnabled(context) ? MonitorIntentState.SHIZUKU : MonitorIntentState.USAGE;
            result = MonitorIntentStore.get(context).start(mode, MonitorExitInfo.query(context), System.currentTimeMillis());
        } catch (RuntimeException e) { result = null; }
        if (!usable(result)) {
            lastWriteOk = result != null && (result.code == MonitorIntentRepository.Code.UNSUPPORTED || result.code == MonitorIntentRepository.Code.HISTORY_UNAVAILABLE) ? "UNKNOWN" : "FALSE";
            runtimeState = State.ERROR;
            runtimeReason = result != null && (result.code == MonitorIntentRepository.Code.UNSUPPORTED || result.code == MonitorIntentRepository.Code.HISTORY_UNAVAILABLE)
                    ? Reason.HISTORY_UNAVAILABLE : Reason.STORAGE_ERROR;
            if (runtimeReason == Reason.HISTORY_UNAVAILABLE) historyBlocked = true;
            else failedClosed = true;
            if (MonitorForegroundService.hasOwner()) MonitorForegroundService.stopImmediately(context);
            return new RequestResult(result != null && result.code == MonitorIntentRepository.Code.UNSUPPORTED ? RequestCode.UNSUPPORTED : RequestCode.STORAGE_FAILED, getSnapshot(context));
        }
        lastWriteOk = "TRUE"; failedClosed = false; historyBlocked = false; runtimeState = State.STARTING; runtimeReason = Reason.NONE;
        if (!MonitorForegroundService.launch(context, result.state.revision, attempt)) {
            suspend(context, result.state.revision, Reason.START_REJECTED); return new RequestResult(RequestCode.START_REJECTED, getSnapshot(context));
        }
        return new RequestResult(RequestCode.ACCEPTED, getSnapshot(context));
    }
    static RequestResult stop(Context context) {
        if (!main()) return new RequestResult(RequestCode.START_REJECTED, getSnapshot(context));
        lastTrigger = "USER_STOP";
        MonitorIntentRepository.Result result;
        try { result = MonitorIntentStore.get(context).stop(); } catch (RuntimeException e) { result = null; }
        lastWriteOk = usable(result) ? "TRUE" : "FALSE";
        if (!usable(result)) { failedClosed = true; runtimeReason = Reason.STORAGE_ERROR; runtimeState = State.ERROR; }
        else { failedClosed = false; historyBlocked = false; runtimeReason = Reason.USER_STOP; runtimeState = State.STOPPED; }
        MonitorForegroundService.stopImmediately(context);
        return new RequestResult(usable(result) ? RequestCode.ACCEPTED : RequestCode.STORAGE_FAILED, getSnapshot(context));
    }
    static void onVisible(Context context) { restore(context, MonitorRuntimePolicy.Trigger.VISIBLE_RESUME); }
    static void restore(Context context, MonitorRuntimePolicy.Trigger trigger) {
        if (!main() || failedClosed || Build.VERSION.SDK_INT < 30 || trigger == MonitorRuntimePolicy.Trigger.READINESS) return;
        lastTrigger = trigger.name();
        MonitorIntentRepository.Result r;
        try { r = MonitorIntentStore.get(context).observe(MonitorExitInfo.query(context)); }
        catch (RuntimeException e) { historyBlocked = true; runtimeState = State.ERROR; runtimeReason = Reason.HISTORY_UNAVAILABLE; if (MonitorForegroundService.hasOwner()) MonitorForegroundService.stopImmediately(context); return; }
        if (!usable(r)) {
            boolean historyError = r != null && (r.code == MonitorIntentRepository.Code.HISTORY_UNAVAILABLE || r.code == MonitorIntentRepository.Code.UNSUPPORTED);
            historyBlocked = historyError; if (!historyError) failedClosed = true;
            runtimeState = State.ERROR; runtimeReason = historyError ? Reason.HISTORY_UNAVAILABLE : Reason.STORAGE_ERROR;
            if (MonitorForegroundService.hasOwner()) MonitorForegroundService.stopImmediately(context); return;
        }
        historyBlocked = false;
        if (!r.state.wanted && runtimeState == State.ERROR) { runtimeState = State.STOPPED; runtimeReason = Reason.NONE; }
        if (!r.state.mayRun(r.state.revision) && MonitorForegroundService.hasOwner()) {
            if (r.state.suspension == MonitorIntentState.Suspension.SYSTEM_STOP) runtimeReason = Reason.SYSTEM_STOP;
            MonitorForegroundService.stopImmediately(context);
            return;
        }
        if (!MonitorRuntimePolicy.mayLaunch(trigger, r.state, MonitorForegroundService.hasOwner())) return;
        runtimeState = State.STARTING; runtimeReason = Reason.NONE;
        if (!MonitorForegroundService.launch(context, r.state.revision, 0)) suspend(context, r.state.revision, Reason.START_REJECTED);
    }
    static MonitorIntentState current(Context context) {
        if (failedClosed || historyBlocked) return null;
        try { MonitorIntentRepository.Result r = MonitorIntentStore.get(context).read(); return usable(r) ? r.state : null; }
        catch (RuntimeException e) { return null; }
    }
    static MonitorIntentState currentAfterHistory(Context context) {
        if (failedClosed) return null;
        try {
            MonitorIntentRepository.Result r = MonitorIntentStore.get(context).observe(MonitorExitInfo.query(context));
            if (usable(r)) { historyBlocked = false; return r.state; }
            boolean historyError = r.code == MonitorIntentRepository.Code.HISTORY_UNAVAILABLE || r.code == MonitorIntentRepository.Code.UNSUPPORTED;
            historyBlocked = historyError; if (!historyError) failedClosed = true;
            runtimeState = State.ERROR; runtimeReason = historyError ? Reason.HISTORY_UNAVAILABLE : Reason.STORAGE_ERROR;
        } catch (RuntimeException e) { historyBlocked = true; runtimeState = State.ERROR; runtimeReason = Reason.HISTORY_UNAVAILABLE; }
        return null;
    }
    static void attach(Object owner, long revision) { POLICY.attach(owner, revision); }
    static void detach(Object owner) { POLICY.detach(owner); }
    static boolean currentOwner(Context context, Object owner, long revision) { return main() && POLICY.current(owner, revision, current(context)); }
    static boolean attemptOnReady(Context context, Object owner, long revision, boolean ready, boolean running) {
        return POLICY.attemptOnReady(owner, revision, current(context), ready, running);
    }
    static void waiting(Reason reason) { runtimeState = State.WAITING; runtimeReason = reason; }
    static void running() { runtimeState = State.RUNNING; runtimeReason = Reason.NONE; }
    static void trigger(MonitorRuntimePolicy.Trigger trigger) { lastTrigger = trigger.name(); }
    static String diagnostics() { return "trigger=" + lastTrigger + " lastWriteOK=" + lastWriteOk; }
    static void suspendIfOwner(Context context, Object owner, long revision, Reason reason) {
        if (currentOwner(context, owner, revision)) suspend(context, revision, reason);
    }
    static void suspendRevision(Context context, long revision, Reason reason) {
        MonitorIntentState state = current(context);
        if (main() && state != null && state.mayRun(revision)) suspend(context, revision, reason);
    }
    private static void suspend(Context context, long revision, Reason reason) {
        try {
            MonitorIntentRepository.Result r = MonitorIntentStore.get(context).suspendFatal(revision);
            if (r.code != MonitorIntentRepository.Code.STALE) lastWriteOk = usable(r) ? "TRUE" : "FALSE";
            if (!usable(r) && (r == null || r.code != MonitorIntentRepository.Code.STALE)) { failedClosed = true; runtimeReason = Reason.STORAGE_ERROR; runtimeState = State.ERROR; return; }
            if (r.code != MonitorIntentRepository.Code.STALE) { runtimeReason = reason; runtimeState = State.SUSPENDED; }
        } catch (RuntimeException e) { lastWriteOk = "FALSE"; failedClosed = true; runtimeReason = Reason.STORAGE_ERROR; runtimeState = State.ERROR; }
    }
}
