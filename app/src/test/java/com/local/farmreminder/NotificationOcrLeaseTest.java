package com.local.farmreminder;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class NotificationOcrLeaseTest {
    private static final class Manual implements Executor {
        Runnable queued;
        boolean reject;
        public void execute(Runnable task) {
            if (reject) throw new RejectedExecutionException();
            assertNull("maintenance must coalesce", queued); queued = task;
        }
        void run() { Runnable task = queued; queued = null; assertNotNull(task); task.run(); }
    }
    @Test public void immediateNativeCompletionWaitsForConsumer() {
        Manual executor = new Manual(); NotificationOcrLease.Gate gate = new NotificationOcrLease.Gate(executor);
        AtomicInteger cleaned = new AtomicInteger();
        NotificationOcrLease first = gate.acquire(() -> { assertFalse(Thread.holdsLock(gate)); cleaned.incrementAndGet(); });
        first.nativeDone(); assertNull(executor.queued); assertNull(gate.acquire(() -> { }));
        first.consumerDone(); first.nativeDone(); first.consumerDone();
        assertEquals(0, cleaned.get()); assertNull(gate.acquire(() -> { })); executor.run();
        assertEquals(1, cleaned.get()); assertNotNull(gate.acquire(() -> { }));
    }
    @Test public void timeoutKeepsSlotUntilActualCompletion() {
        Manual executor = new Manual(); NotificationOcrLease.Gate gate = new NotificationOcrLease.Gate(executor);
        AtomicInteger cleaned = new AtomicInteger();
        NotificationOcrLease first = gate.acquire(cleaned::incrementAndGet);
        first.setProbe(() -> { assertFalse(Thread.holdsLock(gate)); return false; }); first.consumerDone();
        assertNull(gate.acquire(() -> { })); executor.run(); assertEquals(0, cleaned.get());
        first.nativeDone(); assertNull(gate.acquire(() -> { })); executor.run();
        assertEquals(1, cleaned.get()); assertNotNull(gate.acquire(() -> { }));
    }
    @Test public void noTaskFailureCleansOnlyAfterBothSignals() {
        Manual executor = new Manual(); NotificationOcrLease.Gate gate = new NotificationOcrLease.Gate(executor);
        AtomicInteger cleaned = new AtomicInteger(); NotificationOcrLease first = gate.acquire(cleaned::incrementAndGet);
        first.nativeDone(); first.consumerDone(); assertNull(gate.acquire(() -> { }));
        executor.run(); assertEquals(1, cleaned.get()); assertNotNull(gate.acquire(() -> { }));
    }
    @Test public void failedListenerRegistrationReapsOnlyAfterProbeCompletes() {
        Manual executor = new Manual(); NotificationOcrLease.Gate gate = new NotificationOcrLease.Gate(executor);
        AtomicInteger cleaned = new AtomicInteger(); boolean[] done = {false};
        NotificationOcrLease first = gate.acquire(cleaned::incrementAndGet);
        first.setProbe(() -> { assertFalse(Thread.holdsLock(gate)); return done[0]; }); first.consumerDone();
        executor.run(); assertEquals(0, cleaned.get()); done[0] = true;
        assertNull(gate.acquire(() -> { })); executor.run(); assertEquals(1, cleaned.get());
        assertNotNull(gate.acquire(() -> { })); first.nativeDone(); assertEquals(1, cleaned.get());
    }
    @Test public void busyAcquireReturnsBeforeBlockedCleanupFinishes() throws Exception {
        ExecutorService maintenance = Executors.newSingleThreadExecutor(), caller = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        try {
            NotificationOcrLease.Gate gate = new NotificationOcrLease.Gate(maintenance); AtomicInteger cleaned = new AtomicInteger();
            NotificationOcrLease lease = gate.acquire(() -> {
                assertFalse(Thread.holdsLock(gate)); entered.countDown();
                try { if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("cleanup timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                cleaned.incrementAndGet(); done.countDown();
            });
            lease.nativeDone(); lease.consumerDone(); assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertNull(caller.submit(() -> gate.acquire(() -> { })).get(1, TimeUnit.SECONDS));
            assertEquals(0, cleaned.get()); release.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS));
            // The task signals done just before it clears the placeholder; join the worker task first.
            maintenance.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertEquals(1, cleaned.get()); assertNotNull(gate.acquire(() -> { }));
        } finally { release.countDown(); maintenance.shutdownNow(); caller.shutdownNow(); }
    }
    @Test public void rejectedMaintenanceAndFailedCleanupRetainSingleSlot() {
        Manual executor = new Manual(); NotificationOcrLease.Gate gate = new NotificationOcrLease.Gate(executor);
        AtomicInteger calls = new AtomicInteger(); NotificationOcrLease lease = gate.acquire(() -> {
            assertFalse(Thread.holdsLock(gate)); calls.incrementAndGet(); throw new IllegalStateException();
        });
        executor.reject = true; lease.nativeDone(); lease.consumerDone(); assertEquals(0, calls.get());
        executor.reject = false; assertNull(gate.acquire(() -> { })); executor.run();
        assertEquals(1, calls.get()); assertNull(gate.acquire(() -> { })); assertNull(executor.queued);
    }
}
