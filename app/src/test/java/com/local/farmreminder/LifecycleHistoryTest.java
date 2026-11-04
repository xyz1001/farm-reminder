package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class LifecycleHistoryTest {
    @Test public void boundedAndReloadedAcrossProcesses() {
        LifecycleHistory h = new LifecycleHistory(null, 11);
        for (int i = 0; i < 70; i++) h.add(1, i, i, LifecycleHistory.Event.UI_CLICK, LifecycleHistory.Reason.NONE, false, false, false, false, null);
        String saved = h.serialize();
        assertEquals(65, saved.split("\n").length);
        assertFalse(saved.contains("11|1|5|5|"));
        LifecycleHistory next = new LifecycleHistory(saved, 22);
        assertTrue(next.serialize().contains("11|1|6|6|"));
        assertEquals(1, next.nextAttempt());
        next.add(1, 10, 20, LifecycleHistory.Event.ACCESS_START_OK, LifecycleHistory.Reason.NONE, false, false, false, false, null);
        assertTrue(next.serialize().contains("22|1|10|20|"));
    }
    @Test public void exceptionOnlyClassAndInvalidFileFailsOpen() {
        LifecycleHistory h = new LifecycleHistory("1\nnot a record\n", 3);
        h.add(h.nextAttempt(), 1, 2, LifecycleHistory.Event.FGS_EXCEPTION, LifecycleHistory.Reason.EXCEPTION, false, false, false, false, new IllegalStateException("secret"));
        assertFalse(h.serialize().contains("secret"));
        assertTrue(h.serialize().contains("IllegalStateException"));
        assertEquals(2, h.serialize().split("\n").length);
        assertFalse(new LifecycleHistory("x".repeat(40000), 4).serialize().contains("x"));
    }
    @Test public void outcomeDoesNotBorrowPreviousProcessAttempt() {
        LifecycleHistory old = new LifecycleHistory(null, 10);
        old.add(1, 1, 2, LifecycleHistory.Event.FGS_EXCEPTION, LifecycleHistory.Reason.EXCEPTION, false, false, false, false, null);
        LifecycleHistory fresh = new LifecycleHistory(old.serialize(), 20);
        assertTrue(fresh.outcome(20).contains("lastStartOutcome=NONE"));
        fresh.nextAttempt();
        fresh.add(1, 5, 7, LifecycleHistory.Event.ACCESS_START_REJECT, LifecycleHistory.Reason.CAPTURE_BUSY, false, false, false, false, null);
        assertTrue(fresh.outcome(20).contains("ageMs=15 reason=CAPTURE_BUSY"));
    }
    private static void add(LifecycleHistory h, long id, long at, LifecycleHistory.Event event, LifecycleHistory.Reason reason) {
        h.add(id, at, at, event, reason, false, false, false, false, null);
    }
    @Test public void latestAttemptReportsSynchronousRequestException() {
        LifecycleHistory h = new LifecycleHistory(null, 7);
        long first = h.nextAttempt();
        add(h, first, 1, LifecycleHistory.Event.UI_CLICK, LifecycleHistory.Reason.NONE);
        add(h, first, 2, LifecycleHistory.Event.ACCESS_START_OK, LifecycleHistory.Reason.NONE);
        add(h, first, 3, LifecycleHistory.Event.ACCESS_STOP, LifecycleHistory.Reason.USER);
        long second = h.nextAttempt();
        add(h, second, 4, LifecycleHistory.Event.UI_CLICK, LifecycleHistory.Reason.NONE);
        add(h, second, 5, LifecycleHistory.Event.START_REQUEST, LifecycleHistory.Reason.NONE);
        add(h, second, 6, LifecycleHistory.Event.START_REQUEST, LifecycleHistory.Reason.EXCEPTION);
        assertTrue(h.outcome(10).contains("lastStartOutcome=START_REQUEST attempt=2 ageMs=4 reason=EXCEPTION"));
    }
    @Test public void specificRejectionSurvivesGenericRefusalAndPendingTimeout() {
        LifecycleHistory h = new LifecycleHistory(null, 8);
        long id = h.nextAttempt();
        add(h, id, 1, LifecycleHistory.Event.UI_CLICK, LifecycleHistory.Reason.NONE);
        add(h, id, 2, LifecycleHistory.Event.ACCESS_START_REJECT, LifecycleHistory.Reason.CAPTURE_BUSY);
        add(h, id, 3, LifecycleHistory.Event.FGS_REFUSE, LifecycleHistory.Reason.NOT_RUNNING);
        add(h, id, 4, LifecycleHistory.Event.PENDING_TIMEOUT, LifecycleHistory.Reason.NONE);
        String outcome = h.outcome(10);
        assertTrue(outcome.contains("lastStartOutcome=ACCESS_START_REJECT attempt=1 ageMs=8 reason=CAPTURE_BUSY"));
        assertTrue(outcome.contains("uiPendingObservation=PENDING_TIMEOUT uiPendingAgeMs=6"));
    }
    @Test public void oldCallbackCannotReplaceLatestClickOrBorrowFailure() {
        LifecycleHistory h = new LifecycleHistory(null, 9);
        long first = h.nextAttempt();
        add(h, first, 1, LifecycleHistory.Event.UI_CLICK, LifecycleHistory.Reason.NONE);
        add(h, first, 2, LifecycleHistory.Event.ACCESS_START_REJECT, LifecycleHistory.Reason.CAPTURE_BUSY);
        long second = h.nextAttempt();
        add(h, second, 3, LifecycleHistory.Event.UI_CLICK, LifecycleHistory.Reason.NONE);
        add(h, first, 4, LifecycleHistory.Event.FGS_REFUSE, LifecycleHistory.Reason.NOT_RUNNING);
        add(h, 0, 5, LifecycleHistory.Event.FGS_EXCEPTION, LifecycleHistory.Reason.EXCEPTION);
        assertTrue(h.outcome(10).contains("lastStartOutcome=UI_CLICK attempt=2 ageMs=7 reason=NONE"));
    }
    @Test public void ordinaryCallbacksDoNotEvictFailureAndLateReleaseIsRecorded() {
        LifecycleHistory h = new LifecycleHistory(null, 12);
        long id = h.nextAttempt();
        add(h, id, 1, LifecycleHistory.Event.UI_CLICK, LifecycleHistory.Reason.NONE);
        add(h, id, 2, LifecycleHistory.Event.CAPTURE_WATCHDOG, LifecycleHistory.Reason.WATCHDOG);
        for (int i = 0; i < 100; i++) {
            LifecycleHistory.Event event = LifecycleHistory.captureReleaseEvent(false, true);
            if (event != null) add(h, id, 3 + i, event, LifecycleHistory.Reason.WATCHDOG);
        }
        assertTrue(h.serialize().contains("|CAPTURE_WATCHDOG|WATCHDOG|"));
        assertEquals(3, h.serialize().split("\n").length);
        assertNull(LifecycleHistory.captureReleaseEvent(true, false));
        LifecycleHistory.Event late = LifecycleHistory.captureReleaseEvent(true, true);
        assertEquals(LifecycleHistory.Event.CAPTURE_LATE_CALLBACK, late);
        add(h, id, 104, late, LifecycleHistory.Reason.WATCHDOG);
        assertTrue(h.serialize().contains("|CAPTURE_LATE_CALLBACK|WATCHDOG|"));
    }
    @Test public void oldWriteCannotOverwriteNewerPendingSnapshot() {
        LifecycleWriteGate gate = new LifecycleWriteGate();
        assertTrue(gate.offer("old"));
        assertEquals("old", gate.next());
        assertFalse(gate.offer("middle"));
        assertFalse(gate.offer("latest"));
        assertEquals("latest", gate.next());
        assertNull(gate.next());
        assertTrue(gate.offer("next"));
    }
}
