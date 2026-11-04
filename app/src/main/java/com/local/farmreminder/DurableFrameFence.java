package com.local.farmreminder;

/** In-memory linearization only: no operation holding this monitor performs IO. */
final class DurableFrameFence {
    private long epoch;
    private boolean running, frameActive, nativeActive, transaction;

    synchronized long start() {
        if (running || frameActive || transaction) return -1;
        running = true; return ++epoch;
    }
    synchronized long stop() { running = false; return ++epoch; }
    synchronized boolean valid(long token) { return running && epoch == token; }
    synchronized boolean running() { return running; }
    synchronized long epoch() { return epoch; }
    synchronized boolean beginFrame(long token) {
        if (!valid(token) || frameActive) return false;
        frameActive = true; return true;
    }
    synchronized boolean beginModel(long token) {
        if (!valid(token) || !frameActive || nativeActive) return false;
        nativeActive = true; return true;
    }
    /** Called on the model completion executor, not the potentially busy disk executor. */
    synchronized boolean modelDone() {
        if (!nativeActive) return false;
        nativeActive = false; return true;
    }
    synchronized boolean timeout(long token) {
        if (!nativeActive || !valid(token)) return false;
        stop(); return true;
    }
    synchronized boolean modelActive() { return nativeActive; }
    synchronized boolean frameActive() { return frameActive; }
    synchronized int diagnosticState() { return (running ? 1 : 0) | (frameActive ? 2 : 0) | (nativeActive ? 4 : 0) | (transaction ? 8 : 0); }
    synchronized boolean canRecover() { return !frameActive && !transaction; }
    synchronized boolean shouldNotifyFailure(long token) { return valid(token); }

    /**
     * Admission and stop share the monitor. If admitted first, the entire commit/ACK
     * transaction may finish after stop; otherwise neither starts. The frame barrier
     * remains held through the final durable transition and blocks a subsequent start.
     */
    synchronized boolean tryAdmitTransaction(long token) {
        if (!valid(token) || !frameActive || nativeActive || transaction) return false;
        transaction = true; return true;
    }
    synchronized void finishTransaction() { transaction = false; }
    synchronized void finishFrame() {
        if (nativeActive || transaction) throw new IllegalStateException("frame still in use");
        frameActive = false;
    }
}
