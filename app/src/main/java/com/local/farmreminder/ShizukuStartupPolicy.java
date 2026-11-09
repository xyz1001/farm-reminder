package com.local.farmreminder;

/** Guards a single user-service startup attempt; callers hold the gate lock. */
final class ShizukuStartupPolicy {
    // Local budget informed by the observed server startup timeout, not an SDK guarantee.
    static final long STARTUP_BUDGET_MS = 30_000;

    private ShizukuStartupPolicy() { }

    static boolean current(Object connection, Object attempt, long epoch, long attemptEpoch, boolean active) {
        return active && connection == attempt && connection != null && epoch == attemptEpoch;
    }

    static boolean shouldTimeout(Object connection, Object attempt, long epoch, long attemptEpoch,
                                 boolean active, boolean binding) {
        return binding && current(connection, attempt, epoch, attemptEpoch, active);
    }
}
