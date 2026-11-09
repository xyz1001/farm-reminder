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
        assertTrue(f.running()); assertTrue(f.valid(e)); assertTrue(f.modelActive());
        assertEquals(1, f.completeModel(f.modelAttempt())); f.finishFrame();
        assertTrue(f.beginFrame(e));
    }
    @Test public void oldWatchdogCannotInvalidateNextAttempt() {
        DurableFrameFence f = new DurableFrameFence(); long e = f.start();
        assertTrue(f.beginFrame(e)); assertTrue(f.beginModel(e)); long first = f.modelAttempt();
        assertTrue(f.timeout(e, first)); assertEquals(1, f.completeModel(first)); f.finishFrame();
        assertTrue(f.beginFrame(e)); assertTrue(f.beginModel(e)); long second = f.modelAttempt();
        assertFalse(f.timeout(e, first)); assertEquals(-1, f.completeModel(first));
        assertEquals(0, f.completeModel(second)); f.finishFrame();
    }
    @Test public void stopAfterTimeoutInvalidatesBusinessButKeepsNativeBarrier() {
        DurableFrameFence f = new DurableFrameFence(); long e = f.start();
        assertTrue(f.beginFrame(e)); assertTrue(f.beginModel(e)); long attempt = f.modelAttempt();
        assertTrue(f.timeout(e, attempt)); f.stop();
        assertFalse(f.tryAdmitTransaction(e)); assertEquals(-1, f.start());
        assertEquals(1, f.completeModel(attempt)); f.finishFrame(); assertTrue(f.start() > e);
    }
    @Test public void timeoutKeepsNativeAndFrameOwnershipUntilExactlyOneCompletion() {
        DurableFrameFence f = new DurableFrameFence(); long epoch = f.start();
        assertTrue(f.beginFrame(epoch)); assertTrue(f.beginModel(epoch)); long attempt = f.modelAttempt();
        assertTrue(f.timeout(epoch, attempt)); assertFalse(f.timeout(epoch, attempt));
        assertTrue(f.running()); assertTrue(f.modelActive()); assertTrue(f.frameActive());
        assertFalse(f.beginFrame(epoch)); assertFalse(f.beginModel(epoch));
        assertFalse(f.canRecover()); assertFalse(f.tryAdmitTransaction(epoch));
        assertEquals(1, f.completeModel(attempt)); assertEquals(-1, f.completeModel(attempt));
        assertFalse(f.timeout(epoch, attempt)); f.finishFrame();
        assertTrue(f.beginFrame(epoch)); assertTrue(f.beginModel(epoch));
        assertNotEquals(attempt, f.modelAttempt());
        assertEquals(0, f.completeModel(f.modelAttempt())); f.finishFrame();
    }
    @Test public void completionWinsWatchdogRaceAndProcessesSuccess() {
        DurableFrameFence f = new DurableFrameFence(); long epoch = f.start();
        assertTrue(f.beginFrame(epoch)); assertTrue(f.beginModel(epoch)); long attempt = f.modelAttempt();
        int completion = f.completeModel(attempt);
        assertEquals(0, completion); assertFalse(f.timeout(epoch, attempt));
        assertEquals(DurableFrameFence.CompletionAction.PROCESS,
                DurableFrameFence.completionAction(f.valid(epoch), completion, true));
        f.finishFrame();
    }
    @Test public void timeoutWinsAndLateSuccessIsDiscardedBeforeNextPumpTurn() {
        DurableFrameFence f = new DurableFrameFence(); long epoch = f.start();
        ArrayDeque<Runnable> executor = new ArrayDeque<>();
        DurableFramePump pump = new DurableFramePump(executor::add);
        assertTrue(f.beginFrame(epoch)); assertTrue(f.beginModel(epoch)); long attempt = f.modelAttempt();
        assertTrue(f.timeout(epoch, attempt)); int completion = f.completeModel(attempt);
        assertEquals(DurableFrameFence.CompletionAction.TIMEOUT,
                DurableFrameFence.completionAction(f.valid(epoch), completion, true));
        assertEquals(DurableFrameFence.CompletionAction.RETURN_PENDING,
                DurableFrameFence.completionAction(false, completion, true));
        f.finishFrame();
        AtomicInteger nextStarted = new AtomicInteger();
        pump.request(() -> { if (f.beginFrame(epoch) && f.beginModel(epoch)) nextStarted.incrementAndGet(); });
        assertEquals(0, nextStarted.get()); executor.remove().run(); assertEquals(1, nextStarted.get());
        assertFalse(f.timeout(epoch, attempt));
        assertEquals(0, f.completeModel(f.modelAttempt())); f.finishFrame();
    }
    @Test public void neverReturningNativeCannotRestartAndStopForbidsAdmission() {
        DurableFrameFence f = new DurableFrameFence(); long epoch = f.start();
        assertTrue(f.beginFrame(epoch)); assertTrue(f.beginModel(epoch)); long attempt = f.modelAttempt();
        assertTrue(f.timeout(epoch, attempt));
        assertFalse(f.beginFrame(epoch)); assertFalse(f.beginModel(epoch));
        f.stop(); assertFalse(f.tryAdmitTransaction(epoch)); assertEquals(-1, f.start());
        assertTrue(f.modelActive()); assertTrue(f.frameActive()); assertFalse(f.canRecover());
    }
    @Test public void failureNotificationBelongsToCurrentRunOnly() {
        DurableFrameFence f = new DurableFrameFence(); long e = f.start();
        assertTrue(f.beginFrame(e)); assertTrue(f.tryAdmitTransaction(e));
        assertTrue(f.shouldNotifyFailure(e)); // failed commit emits exactly one queued notification
        f.stop(); assertFalse(f.shouldNotifyFailure(e));
        f.finishTransaction(); f.finishFrame(); f.start();
        assertFalse(f.shouldNotifyFailure(e));
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
