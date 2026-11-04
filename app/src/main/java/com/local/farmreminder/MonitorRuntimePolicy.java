package com.local.farmreminder;

/** Main-thread session fencing and readiness transitions. */
final class MonitorRuntimePolicy {
    enum Trigger { USER_START, VISIBLE_RESUME, BOOT, UPDATE, SYSTEM_STICKY, READINESS }
    private Object owner;
    private long revision;
    private boolean attempted, wasReady;
    void attach(Object owner, long revision) { this.owner = owner; this.revision = revision; attempted = false; wasReady = false; }
    void detach(Object owner) { if (this.owner == owner) { this.owner = null; attempted = false; wasReady = false; } }
    boolean current(Object owner, long revision, MonitorIntentState state) {
        return this.owner == owner && this.revision == revision && state != null && state.mayRun(revision);
    }
    boolean attemptOnReady(Object owner, long revision, MonitorIntentState state, boolean ready, boolean running) {
        if (!current(owner, revision, state)) return false;
        if (!ready) { wasReady = false; attempted = false; return false; }
        if (running) { wasReady = true; return false; }
        if (wasReady || attempted) return false;
        wasReady = true; attempted = true; return true;
    }
    static boolean mayLaunch(Trigger trigger, MonitorIntentState state, boolean ownerExists) {
        return trigger != Trigger.READINESS && !ownerExists && state != null && state.mayRun(state.revision);
    }
    static boolean modeMatches(MonitorIntentState state, boolean useShizuku) {
        return state != null && (useShizuku ? MonitorIntentState.SHIZUKU : MonitorIntentState.USAGE).equals(state.sourceMode);
    }
    static boolean interruptRequiresSuspension(boolean running) { return running; }
    static boolean captureAvailable(boolean captureBusy) { return !captureBusy; }
}
