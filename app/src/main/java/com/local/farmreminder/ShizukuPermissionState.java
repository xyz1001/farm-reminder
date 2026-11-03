package com.local.farmreminder;

/** Last confirmed authorization is for setup only; monitoring requires a recent check. */
final class ShizukuPermissionState {
    private static final long TTL = 3000;
    private boolean granted;
    private long checkedAt = -1;

    boolean knownGrant() { return granted; }
    void grant(long now) { granted = true; checkedAt = now; }
    void invalidate() { granted = false; checkedAt = -1; }
    boolean fresh(long now) {
        long age = now - checkedAt;
        return granted && checkedAt >= 0 && age >= 0 && age < TTL;
    }
    boolean runtimeReady(boolean active, boolean enabled, long now, long queryAge) {
        return active && enabled && fresh(now) && GateState.fresh(queryAge);
    }
    static boolean shouldPoll(boolean enabled) { return enabled; }
    static boolean shouldQuery(boolean active, boolean enabled) { return active && enabled; }
}
