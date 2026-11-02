package com.local.farmreminder;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class DurableFrameFenceTest {
    @Test public void stopBeforeAdmissionPreventsBusinessAndAck() {
        DurableFrameFence f = new DurableFrameFence(); long e = f.start();
        assertTrue(f.beginFrame(e)); f.stop();
        assertFalse(f.tryAdmitTransaction(e)); assertFalse(f.canRecover());
        assertEquals(-1, f.start()); f.finishFrame();
        assertTrue(f.canRecover()); assertTrue(f.start() > e);
    }
    @Test public void admittedTransactionFinishesAfterStopAndBlocksNextStart() throws Exception {
        DurableFrameFence f = new DurableFrameFence(); long e = f.start();
        assertTrue(f.beginFrame(e));
        CountDownLatch beforeAck = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicInteger ack = new AtomicInteger();
        Thread worker = new Thread(() -> {
            assertTrue(f.tryAdmitTransaction(e));
            beforeAck.countDown();
            try { assertTrue(finish.await(5, TimeUnit.SECONDS)); } catch (InterruptedException ex) { throw new AssertionError(ex); }
            ack.incrementAndGet(); f.finishTransaction(); f.finishFrame();
        });
        worker.start(); assertTrue(beforeAck.await(5, TimeUnit.SECONDS));
        f.stop(); assertFalse(f.valid(e)); assertEquals(-1, f.start()); assertFalse(f.canRecover());
        finish.countDown(); worker.join(5000);
        assertFalse(worker.isAlive()); assertEquals(1, ack.get()); assertTrue(f.start() > e);
    }
    @Test public void realCompletionPreventsTimeoutEvenWhenDiskIsBlocked() {
        DurableFrameFence f = new DurableFrameFence(); long e = f.start();
        assertTrue(f.beginFrame(e)); assertTrue(f.beginModel(e));
        assertTrue(f.modelDone()); assertFalse(f.modelDone()); // exactly one bitmap owner releases
        assertFalse(f.timeout(e)); assertFalse(f.canRecover());
        f.stop(); assertEquals(-1, f.start()); f.finishFrame(); assertTrue(f.start() > e);
    }
    @Test public void nativeTimeoutKeepsBarrierUntilRealCallback() {
        DurableFrameFence f = new DurableFrameFence(); long e = f.start();
        assertTrue(f.beginFrame(e)); assertTrue(f.beginModel(e));
        assertTrue(f.timeout(e)); assertEquals(-1, f.start());
        assertTrue(f.modelActive()); assertTrue(f.modelDone()); f.finishFrame(); assertTrue(f.start() > e);
    }
    @Test public void failureNotificationBelongsToCurrentRunOnly() {
        DurableFrameFence f = new DurableFrameFence(); long e = f.start();
        assertTrue(f.beginFrame(e)); assertTrue(f.tryAdmitTransaction(e));
        assertTrue(f.shouldNotifyFailure(e)); // failed commit emits exactly one queued notification
        f.stop(); assertFalse(f.shouldNotifyFailure(e));
        f.finishTransaction(); f.finishFrame(); f.start();
        assertFalse(f.shouldNotifyFailure(e));
    }
    @Test public void recoveryRetriesAfterErrorButNeverTouchesActiveFrame() {
        DurableFrameFence f = new DurableFrameFence(); AtomicInteger calls = new AtomicInteger();
        DurableFrameRecovery.Attempt operation = () -> { if (calls.incrementAndGet() == 1) throw new Exception(); };
        assertFalse(DurableFrameRecovery.attempt(f, operation));
        long e = f.start(); assertTrue(f.beginFrame(e));
        assertFalse(DurableFrameRecovery.attempt(f, operation)); assertEquals(1, calls.get());
        f.stop(); f.finishFrame();
        assertTrue(DurableFrameRecovery.attempt(f, operation)); assertEquals(2, calls.get());
    }
    @Test public void thousandsOfFailuresPumpWithoutRecursion() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        DurableFramePump pump = new DurableFramePump(queue::add);
        AtomicInteger count = new AtomicInteger();
        Runnable[] work = new Runnable[1];
        work[0] = () -> { if (count.incrementAndGet() < 10000) pump.request(work[0]); };
        pump.request(work[0]); pump.request(work[0]);
        while (!queue.isEmpty()) queue.remove().run();
        assertEquals(10000, count.get());
    }
}
