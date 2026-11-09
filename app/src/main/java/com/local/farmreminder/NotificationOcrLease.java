package com.local.farmreminder;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Single native OCR task, retained after a consumer timeout until native completion. */
final class NotificationOcrLease {
    interface Probe { boolean complete(); }
    static final class Gate {
        private static final Executor MAINTENANCE = maintenanceExecutor();
        private final Executor maintenance;
        private NotificationOcrLease active;
        Gate() { this(MAINTENANCE); }
        Gate(Executor maintenance) { this.maintenance = maintenance; }
        private static Executor maintenanceExecutor() {
            ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1), runnable -> new Thread(runnable, "farm-ocr-cleanup"));
            executor.allowCoreThreadTimeOut(true);
            return executor;
        }
        NotificationOcrLease acquire(Runnable cleanup) {
            NotificationOcrLease busy;
            synchronized (this) {
                if (active == null) return active = new NotificationOcrLease(this, cleanup);
                busy = active;
            }
            busy.schedule();
            return null; // A pending probe or cleanup never blocks the notification worker.
        }
    }
    private final Gate gate;
    private final Runnable cleanup;
    private Probe probe;
    private boolean nativeDone = true, consumerDone, scheduled, cleaning;
    private long generation;

    private NotificationOcrLease(Gate gate, Runnable cleanup) { this.gate = gate; this.cleanup = cleanup; }
    long beginTask() {
        synchronized (gate) {
            if (consumerDone || !nativeDone || cleaning) throw new IllegalStateException("task still active");
            nativeDone = false; probe = null; return ++generation;
        }
    }
    void setProbe(long token, Probe probe) { synchronized (gate) { if (token == generation && !nativeDone) this.probe = probe; } }
    void nativeDone(long token) { synchronized (gate) { if (token != generation) return; nativeDone = true; } schedule(); }
    void consumerDone() { synchronized (gate) { consumerDone = true; } schedule(); }

    private void schedule() {
        synchronized (gate) {
            if (gate.active != this || scheduled || cleaning || !consumerDone || (!nativeDone && probe == null)) return;
            scheduled = true;
        }
        try { gate.maintenance.execute(this::maintain); }
        catch (RuntimeException | Error rejected) {
            // Retain the slot; a later acquire/callback may retry maintenance.
            synchronized (gate) { scheduled = false; }
        }
    }
    private void maintain() {
        Probe check;
        long token;
        synchronized (gate) {
            if (gate.active != this) return;
            check = !nativeDone && consumerDone ? probe : null;
            token = generation;
        }
        boolean complete = false;
        if (check != null) {
            try { complete = check.complete(); } catch (RuntimeException | Error ignored) { }
        }
        synchronized (gate) {
            if (complete && token == generation) nativeDone = true;
            if (!nativeDone || !consumerDone) { scheduled = false; return; }
            cleaning = true;
        }
        boolean success = false;
        try { cleanup.run(); success = true; }
        catch (RuntimeException | Error ignored) { }
        finally {
            synchronized (gate) {
                // On cleanup failure retain one bounded slot rather than start another native task.
                if (success && gate.active == this) gate.active = null;
                scheduled = false;
            }
        }
    }
}
