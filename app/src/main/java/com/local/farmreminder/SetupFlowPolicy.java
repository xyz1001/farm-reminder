package com.local.farmreminder;

/** Pure navigation decisions only: evaluating a step never grants or starts anything. */
final class SetupFlowPolicy {
    enum Step { MODE, SOURCE, SCREENSHOT, WAITING, NOTIFICATIONS, READY, UNSUPPORTED }

    static Step next(boolean supported, boolean modeChosen, boolean shizuku,
            boolean usageAllowed, boolean shizukuAllowed, boolean enabled, boolean connected,
            boolean notificationsAllowed, boolean notificationDeferred) {
        if (!supported) return Step.UNSUPPORTED;
        boolean source = shizuku ? shizukuAllowed : usageAllowed;
        // Existing, fully authorized installations do not need to repeat mode selection.
        if (!modeChosen && !(source && enabled && connected)) return Step.MODE;
        if (!source) return Step.SOURCE;
        if (!enabled) return Step.SCREENSHOT;
        if (!connected) return Step.WAITING;
        if (!notificationsAllowed && !notificationDeferred) return Step.NOTIFICATIONS;
        return Step.READY;
    }

    static boolean requiredReady(Step step) {
        return step == Step.READY || step == Step.NOTIFICATIONS;
    }

    static boolean canChangeMode(boolean running, boolean serviceActive, boolean startPending) {
        return !running && !serviceActive && !startPending;
    }

    private SetupFlowPolicy() {}
}
