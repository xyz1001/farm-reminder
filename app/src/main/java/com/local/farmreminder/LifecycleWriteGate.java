package com.local.farmreminder;

/** One active writer, with at most one replaceable pending snapshot. */
final class LifecycleWriteGate {
    private boolean running;
    private String pending;
    synchronized boolean offer(String snapshot) {
        pending = snapshot;
        if (running) return false;
        running = true;
        return true;
    }
    synchronized String next() {
        if (pending == null) { running = false; return null; }
        String result = pending; pending = null; return result;
    }
}
