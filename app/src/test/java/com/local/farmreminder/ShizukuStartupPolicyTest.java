package com.local.farmreminder;

import static org.junit.Assert.*;

import org.junit.Test;

public class ShizukuStartupPolicyTest {
    // Models only the gate's lock-protected callback/deadline decisions, not Android IPC.
    private static final class Attempt {
        Object connection;
        long epoch;
        boolean active = true, binding, pending;

        Object start() { connection = new Object(); binding = true; pending = false; return connection; }
        void reset() { epoch++; connection = null; binding = false; pending = false; }
        void callback(Object attempt, long generation) {
            if (ShizukuStartupPolicy.current(connection, attempt, epoch, generation, active)) {
                pending = true; binding = false;
            }
        }
        void deadline(Object attempt, long generation) {
            if (ShizukuStartupPolicy.shouldTimeout(connection, attempt, epoch, generation, active, binding)) reset();
        }
    }

    @Test public void startupBudgetCoversObservedDelaysAndIsBounded() {
        assertEquals(30_000, ShizukuStartupPolicy.STARTUP_BUDGET_MS);
        Attempt a = new Attempt(); Object first = a.start();
        // No deadline is scheduled at either observed delay.
        assertTrue(3_000 < ShizukuStartupPolicy.STARTUP_BUDGET_MS);
        assertTrue(5_000 < ShizukuStartupPolicy.STARTUP_BUDGET_MS);
        assertSame(first, a.connection);
        a.deadline(first, 0);
        assertNull(a.connection);
        assertNotSame(first, a.start()); // a timeout permits the next bounded attempt
    }

    @Test public void deliveredBinderSurvivesDeadlineWhileLinkWorkerIsDelayed() {
        Attempt a = new Attempt(); Object first = a.start();
        a.callback(first, 0);
        assertTrue(a.pending);
        a.deadline(first, 0);
        assertSame(first, a.connection);
        assertTrue(a.pending);
        a.pending = false; // link worker has taken the binder, but not finished linking
        a.deadline(first, 0);
        assertSame(first, a.connection);
    }

    @Test public void stoppedOrInvalidatedAttemptCannotAffectNewAttempt() {
        Attempt a = new Attempt(); Object old = a.start();
        a.active = false; a.reset(); // stop
        a.callback(old, 0); a.deadline(old, 0);
        assertNull(a.connection);
        a.active = true; Object next = a.start();
        a.callback(old, 0); a.deadline(old, 0);
        assertSame(next, a.connection); assertFalse(a.pending);
        a.reset(); // permission invalidation
        Object afterPermission = a.start();
        a.callback(next, 1); a.deadline(next, 1);
        assertSame(afterPermission, a.connection); assertFalse(a.pending);
        a.callback(afterPermission, 2);
        assertTrue(a.pending);
    }

    @Test public void failedAttemptCanBeRetriedAndOldDeadlineIsHarmless() {
        Attempt a = new Attempt(); Object failed = a.start();
        a.reset(); // bind or link failure
        Object retry = a.start();
        a.deadline(failed, 0); a.callback(failed, 0);
        assertSame(retry, a.connection); assertTrue(a.binding);
        a.deadline(retry, 1);
        assertNull(a.connection);
    }
}
