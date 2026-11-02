package com.local.farmreminder;

/** Pure decisions for the user-controlled foreground monitoring session. */
final class MonitorSessionPolicy {
    enum Action { START, STOP, IGNORE }
    enum Decision { START, KEEP, STOP, IGNORE }

    static Decision decide(Action action, boolean active, boolean running) {
        if (action == Action.STOP) return Decision.STOP;
        if (action != Action.START) return Decision.IGNORE;
        if (!active) return Decision.START;
        return running ? Decision.KEEP : Decision.STOP;
    }

    static boolean shouldEnd(boolean foregroundStarted, boolean connected, boolean running) {
        return !foregroundStarted || !connected || !running;
    }

    private MonitorSessionPolicy() { }
}
