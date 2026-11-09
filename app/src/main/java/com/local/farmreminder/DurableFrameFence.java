package com.local.farmreminder;

/** In-memory linearization only: no operation holding this monitor performs IO. */
final class DurableFrameFence {
    enum CompletionAction { RETURN_PENDING, TIMEOUT, OCR_FAILED, PROCESS }
    static CompletionAction completionAction(boolean valid, int completion, boolean successful) {
        if (!valid) return CompletionAction.RETURN_PENDING;
        if (completion == 1) return CompletionAction.TIMEOUT;
        return successful ? CompletionAction.PROCESS : CompletionAction.OCR_FAILED;
    }
    private long epoch;
    private boolean running, frameActive, nativeActive, transaction;
    private long nextAttempt, activeAttempt;
    private boolean timedOut;

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
        nativeActive = true; activeAttempt = ++nextAttempt; timedOut = false; return true;
    }
    synchronized long modelAttempt() { return activeAttempt; }
    /** Called on the model completion executor, not the potentially busy disk executor. */
    synchronized boolean modelDone() {
        return completeModel(activeAttempt) >= 0;
    }
    synchronized boolean timeout(long token) {
        return timeout(token, activeAttempt);
    }
    synchronized boolean timeout(long token, long attempt) {
        if (!nativeActive || activeAttempt != attempt || timedOut || !valid(token)) return false;
        timedOut = true; return true;
    }
    /** -1 means stale completion; otherwise 1 means timeout and 0 means normal. */
    synchronized int completeModel(long attempt) {
        if (!nativeActive || activeAttempt != attempt) return -1;
        nativeActive = false;
        return timedOut ? 1 : 0;
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
