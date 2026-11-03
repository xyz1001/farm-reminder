package com.local.farmreminder;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class GateStateTest {
    @Test public void strictTtlAndTimeoutFence() {
        assertTrue(GateState.fresh(1499));
        assertFalse(GateState.fresh(1500));
        GateState state = new GateState();
        long first = state.begin();
        assertTrue(state.finish(first, 1499));
        long second = state.begin();
        assertFalse(state.finish(second, 1500));
        long third = state.begin();
        state.invalidate(); // timeout fired, including a return in the same millisecond
        assertFalse(state.finish(third, 1499));
    }

    @Test public void invalidationKeepsSingleFlightUntilTransportReturns() {
        for (int event = 0; event < 3; event++) { // stop, death, permission invalid
            GateState state = new GateState();
            long old = state.begin();
            state.invalidate();
            assertEquals(-1, state.begin());
            assertFalse(state.finish(old, 1));
            long next = state.begin();
            assertTrue(state.finish(next, 1));
        }
    }

    @Test public void blockedTransportDoesNotBlockLocalStateAndWakeupsCoalesce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), second = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Object gate = new Object();
        GateState state = new GateState();
        AsyncControl control = new AsyncControl(() -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            } else second.countDown();
        });
        control.wake();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        synchronized (gate) { state.invalidate(); assertFalse(GateState.fresh(1500)); }
        for (int i = 0; i < 10000; i++) control.wake();
        release.countDown();
        assertTrue(second.await(2, TimeUnit.SECONDS));
        assertEquals(2, calls.get());
    }
}
