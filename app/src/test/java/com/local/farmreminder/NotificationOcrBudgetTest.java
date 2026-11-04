package com.local.farmreminder;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class NotificationOcrBudgetTest {
    @Test public void expiredDecodeCannotStartNativeAndAwaitUsesRemaining() throws Exception {
        long[] time = {100}; AtomicInteger starts = new AtomicInteger();
        time[0] = 2250; // Decode or another prior stage consumed the entire deadline.
        assertNull(NotificationOcrBudget.startIfTime(2250, () -> time[0], () -> { starts.incrementAndGet(); return "task"; }));
        assertEquals(0, starts.get());
        time[0] = 1800;
        String result = NotificationOcrBudget.await(2250, () -> time[0], left -> { assertEquals(450, left); return "ok"; });
        assertEquals("ok", result);
        time[0] = 2250;
        assertNull(NotificationOcrBudget.await(2250, () -> time[0], left -> { fail("late await"); return null; }));
    }
    @Test public void interruptedAwaitRestoresInterrupt() throws Exception {
        try {
            NotificationOcrBudget.await(100, () -> 1, left -> { throw new InterruptedException(); });
            fail("expected interruption");
        } catch (InterruptedException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
    }
}
