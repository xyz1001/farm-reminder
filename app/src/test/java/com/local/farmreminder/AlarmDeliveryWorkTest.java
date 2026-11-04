package com.local.farmreminder;

import org.junit.Test;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AlarmDeliveryWorkTest {
    private static final class Deferred implements Executor {
        Runnable task;
        public void execute(Runnable task) { this.task = task; }
    }
    @Test public void normalSnapshotIsLockedButLoaderIsNotAndFinishesOnce() {
        Object lock = new Object(); Deferred executor = new Deferred();
        AtomicInteger loads = new AtomicInteger(), deliveries = new AtomicInteger(), finishes = new AtomicInteger();
        AlarmDeliveryWork.submit(executor, () -> 100L, 100L, new AlarmDeliveryWork.Work<String>() {
            public String prepare() {
                return AlarmDeliveryWork.prepare(lock, () -> {
                    assertTrue(Thread.holdsLock(lock)); return "ref";
                }, ref -> { assertFalse(Thread.holdsLock(lock)); loads.incrementAndGet(); return "bitmap"; },
                        () -> 100L, 100L);
            }
            public void deliver(String result) { assertEquals("bitmap", result); deliveries.incrementAndGet(); }
        }, finishes::incrementAndGet);
        assertEquals(0, finishes.get()); executor.task.run();
        assertEquals(1, loads.get()); assertEquals(1, deliveries.get()); assertEquals(1, finishes.get());
    }
    @Test public void prepareFailureDeliversWithoutImage() {
        AtomicInteger delivered = new AtomicInteger(), finished = new AtomicInteger();
        AlarmDeliveryWork.submit(Runnable::run, () -> 100, 100, new AlarmDeliveryWork.Work<String>() {
            public String prepare() { throw new IllegalStateException(); }
            public void deliver(String result) { assertNull(result); delivered.incrementAndGet(); }
        }, finished::incrementAndGet);
        assertEquals(1, delivered.get()); assertEquals(1, finished.get());
    }
    @Test public void snapshotConsumesBudgetSoLoaderNeverRunsAndDeliveryFinishes() {
        Object lock = new Object(); long[] clock = {100};
        AtomicInteger loads = new AtomicInteger(), deliveries = new AtomicInteger(), finishes = new AtomicInteger();
        AlarmDeliveryWork.submit(Runnable::run, () -> clock[0], 100, new AlarmDeliveryWork.Work<String>() {
            public String prepare() {
                return AlarmDeliveryWork.prepare(lock, () -> {
                    assertTrue(Thread.holdsLock(lock)); clock[0] = 2600; return "ref";
                }, ref -> {
                    assertFalse(Thread.holdsLock(lock)); loads.incrementAndGet(); return "bitmap";
                }, () -> clock[0], 100);
            }
            public void deliver(String result) { assertNull(result); deliveries.incrementAndGet(); }
        }, finishes::incrementAndGet);
        assertEquals(0, loads.get()); assertEquals(1, deliveries.get()); assertEquals(1, finishes.get());
    }
    @Test public void rejectedQueueDeliversWithoutPreparingAndFinishesOnce() {
        AtomicInteger prepared = new AtomicInteger(), delivered = new AtomicInteger(), finished = new AtomicInteger();
        Executor reject = task -> { throw new RejectedExecutionException(); };
        AlarmDeliveryWork.submit(reject, () -> 100, 100, new AlarmDeliveryWork.Work<String>() {
            public String prepare() { prepared.incrementAndGet(); return "bitmap"; }
            public void deliver(String result) { assertNull(result); delivered.incrementAndGet(); }
        }, finished::incrementAndGet);
        assertEquals(0, prepared.get()); assertEquals(1, delivered.get()); assertEquals(1, finished.get());
    }
    @Test public void deliveryThrowStillFinishesOnceWithoutRetry() {
        AtomicInteger delivered = new AtomicInteger(), finished = new AtomicInteger();
        try {
            AlarmDeliveryWork.submit(Runnable::run, () -> 100, 100, new AlarmDeliveryWork.Work<String>() {
                public String prepare() { return null; }
                public void deliver(String result) { delivered.incrementAndGet(); throw new RejectedExecutionException(); }
            }, finished::incrementAndGet);
            fail("expected delivery failure");
        } catch (RejectedExecutionException expected) { }
        assertEquals(1, delivered.get()); assertEquals(1, finished.get());
    }
    @Test public void queueWaitAndSlowPreparationDropOnlyOptionalImage() {
        Deferred queue = new Deferred(); AtomicInteger prepared = new AtomicInteger(), delivered = new AtomicInteger();
        long[] clock = {100};
        AlarmDeliveryWork.submit(queue, () -> clock[0], 100, new AlarmDeliveryWork.Work<String>() {
            public String prepare() { prepared.incrementAndGet(); return "bitmap"; }
            public void deliver(String result) { assertNull(result); delivered.incrementAndGet(); }
        }, () -> { });
        clock[0] = 2600; queue.task.run();
        assertEquals(0, prepared.get()); assertEquals(1, delivered.get());
        clock[0] = 100;
        AlarmDeliveryWork.submit(Runnable::run, () -> clock[0], 100, new AlarmDeliveryWork.Work<String>() {
            public String prepare() { clock[0] = 2600; return "bitmap"; }
            public void deliver(String result) { assertNull(result); delivered.incrementAndGet(); }
        }, () -> { });
        assertEquals(2, delivered.get());
    }
    @Test public void staleGroupIsReconciledWithoutPosting() {
        Reminder old = new Reminder(); old.id = "old"; old.triggerAt = 1; old.matureAt = 1;
        Reminder replacement = new Reminder(); replacement.id = "new"; replacement.triggerAt = 1; replacement.matureAt = 1;
        String oldId = ReminderGrouping.plan(Arrays.asList(old)).get(0).identity;
        AtomicInteger posts = new AtomicInteger(), reconciles = new AtomicInteger();
        boolean result = AlarmDispatchPolicy.dispatch(new Object(), new AlarmDispatchPolicy.Adapter() {
            public boolean ready() { return true; }
            public java.util.List<Reminder> list() { return Arrays.asList(replacement); }
            public boolean notifyGroup(ReminderGrouping.Group group) { posts.incrementAndGet(); return true; }
            public boolean markFired(java.util.List<Reminder> reminders) { fail("stale group"); return false; }
            public void reconcile() { reconciles.incrementAndGet(); }
        }, oldId, 100);
        assertFalse(result); assertEquals(0, posts.get()); assertEquals(1, reconciles.get());
    }
    @Test public void changedOrMissingAvatarCannotReusePreparedBitmap() {
        assertTrue(AlarmDeliveryWork.sameRef("first", "first"));
        assertFalse(AlarmDeliveryWork.sameRef("first", "second"));
        assertFalse(AlarmDeliveryWork.sameRef("first", null));
        assertFalse(AlarmDeliveryWork.sameRef("", ""));
    }
    @Test public void lateIdentityDropsLabelButKeepsAlreadyLoadedAvatar() {
        long[] clock = {100}; AtomicInteger delivered = new AtomicInteger(), finished = new AtomicInteger();
        AlarmDeliveryWork.submit(Runnable::run, () -> clock[0], 100, new AlarmDeliveryWork.Work<String[]>() {
            public String[] prepare() { clock[0] = 2600; return new String[]{"avatar", "identity"}; }
            public String[] budgetExpired(String[] prepared) { return new String[]{prepared[0], ""}; }
            public void deliver(String[] result) {
                assertEquals("avatar", result[0]); assertEquals("", result[1]); delivered.incrementAndGet();
            }
        }, finished::incrementAndGet);
        assertEquals(1, delivered.get()); assertEquals(1, finished.get());
    }
    @Test public void genericSnapshotMustRecheckBudgetAfterUnlock() {
        Object lock = new Object(); long[] clock = {100}; AtomicInteger loaded = new AtomicInteger();
        String result = AlarmDeliveryWork.prepareSnapshot(lock, () -> {
            assertTrue(Thread.holdsLock(lock)); clock[0] = 2600; return "snapshot";
        }, value -> { assertFalse(Thread.holdsLock(lock)); loaded.incrementAndGet(); return value; }, () -> clock[0], 100);
        assertNull(result); assertEquals(0, loaded.get());
    }
}
