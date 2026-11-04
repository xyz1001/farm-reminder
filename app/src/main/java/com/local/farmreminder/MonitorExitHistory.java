package com.local.farmreminder;

import java.util.Collections;
import java.util.List;

/** Bounded historical facts, never descriptions or notification content. */
final class MonitorExitHistory {
    // ApplicationExitInfo.REASON_USER_REQUESTED; available since API 30.
    static final int USER_REQUESTED = 10;
    // ApplicationExitInfo.REASON_CRASH, REASON_CRASH_NATIVE and REASON_ANR.
    static final int CRASH = 4, CRASH_NATIVE = 5, ANR = 6;
    static boolean fatal(int reason) { return reason == CRASH || reason == CRASH_NATIVE || reason == ANR; }
    enum Status { OK, UNSUPPORTED, ERROR }
    final Status status;
    final List<MonitorIntentState.Exit> exits;
    private MonitorExitHistory(Status status, List<MonitorIntentState.Exit> exits) {
        this.status = status; this.exits = Collections.unmodifiableList(MonitorIntentState.bounded(exits));
    }
    static MonitorExitHistory ok(List<MonitorIntentState.Exit> exits) { return new MonitorExitHistory(Status.OK, exits); }
    static MonitorExitHistory unavailable(Status status) {
        if (status == Status.OK) throw new IllegalArgumentException("expected unavailable status");
        return new MonitorExitHistory(status, Collections.emptyList());
    }
}
