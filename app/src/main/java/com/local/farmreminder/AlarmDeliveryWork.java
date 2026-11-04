package com.local.farmreminder;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small bounded-broadcast work policy; no Android or file APIs are required here. */
final class AlarmDeliveryWork {
    static final long IMAGE_BUDGET_MS = 2500;
    interface Clock { long elapsed(); }
    interface Work<T> {
        T prepare();
        void deliver(T prepared);
    }
    interface Snapshot { String ref(); }
    interface Loader<T> { T load(String ref); }

    private AlarmDeliveryWork() { }

    /** Read a reference under the store lock, but never load the asset there. */
    static <T> T prepare(Object lock, Snapshot snapshot, Loader<T> loader, Clock clock, long entryElapsed) {
        String ref;
        synchronized (lock) { ref = snapshot.ref(); }
        return ref == null || ref.isEmpty() || !withinBudget(clock.elapsed(), entryElapsed) ? null : loader.load(ref);
    }

    static boolean sameRef(String prepared, String current) {
        return prepared != null && !prepared.isEmpty() && prepared.equals(current);
    }

    static <T> void submit(Executor executor, Clock clock, long entryElapsed,
                           Work<T> work, Runnable finish) {
        AtomicBoolean started = new AtomicBoolean(), finished = new AtomicBoolean();
        Runnable finishOnce = () -> { if (finished.compareAndSet(false, true)) finish.run(); };
        Runnable task = () -> {
            started.set(true);
            try {
                T prepared = null;
                if (withinBudget(clock.elapsed(), entryElapsed)) {
                    try { prepared = work.prepare(); }
                    catch (RuntimeException | OutOfMemoryError ignored) { }
                    if (!withinBudget(clock.elapsed(), entryElapsed)) prepared = null;
                }
                work.deliver(prepared);
            } finally { finishOnce.run(); }
        };
        try { executor.execute(task); }
        catch (RejectedExecutionException rejected) {
            if (started.get()) throw rejected;
            // The existing delivery path still runs; never decode on the receiver thread.
            try { work.deliver(null); } finally { finishOnce.run(); }
        } catch (RuntimeException | Error failure) {
            finishOnce.run();
            throw failure;
        }
    }

    private static boolean withinBudget(long now, long entry) {
        return now >= entry && now - entry < IMAGE_BUDGET_MS;
    }
}
