package com.local.farmreminder;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class ReminderRetentionPolicyTest {
    private static Reminder reminder(long maturity, boolean fired) {
        Reminder r = new Reminder(); r.matureAt = maturity; r.fired = fired; return r;
    }

    @Test public void expiryBoundaryAndOverflow() {
        Reminder r = reminder(1000, false);
        assertFalse(ReminderRetentionPolicy.isExpired(r, 60999));
        assertTrue(ReminderRetentionPolicy.isExpired(r, 61000));
        assertFalse(ReminderRetentionPolicy.isExpired(reminder(Long.MAX_VALUE - 59999, false), Long.MAX_VALUE));
        assertTrue(ReminderRetentionPolicy.isExpired(reminder(Long.MAX_VALUE - 60000, false), Long.MAX_VALUE));
        assertFalse(ReminderRetentionPolicy.isExpired(reminder(Long.MIN_VALUE, false), Long.MIN_VALUE));
        assertTrue(ReminderRetentionPolicy.isExpired(reminder(Long.MIN_VALUE, false), Long.MIN_VALUE + 60000));
    }

    private static class Storage implements ReminderRetentionPolicy.Storage {
        List<Reminder> items = new ArrayList<>();
        int writes;
        boolean repairOk = true, writeOk = true, readFails, writeFails;
        Exception repairCause, readCause, writeCause;
        String stage; Throwable cause; int failures; boolean observerFails;
        public void failure(String stage, Throwable cause) {
            this.stage = stage; this.cause = cause; failures++;
            if (observerFails) throw new IllegalStateException();
        }
        public boolean repair() throws Exception { if (repairCause != null) throw repairCause; return repairOk; }
        public List<Reminder> read() throws Exception {
            if (readCause != null) throw readCause;
            if (readFails) throw new Exception();
            return items;
        }
        public boolean write(List<Reminder> retained) throws Exception {
            writes++;
            if (writeCause != null) throw writeCause;
            if (writeFails) throw new Exception();
            if (writeOk) items = retained;
            return writeOk;
        }
    }

    @Test public void prunesFiredAndPendingWithoutWritingWhenUnchanged() {
        Storage s = new Storage();
        Reminder active = reminder(1001, true);
        s.items.addAll(Arrays.asList(reminder(1000, false), reminder(0, true), active));
        assertEquals(2, ReminderRetentionPolicy.prune(s, 61000));
        assertEquals(Arrays.asList(active), s.items);
        assertEquals(1, s.writes);
        assertEquals(0, s.failures);
        assertEquals(0, ReminderRetentionPolicy.prune(s, 61000));
        assertEquals(1, s.writes);
        assertEquals(0, s.failures);
    }

    @Test public void failuresNeverReportDeletion() {
        Storage s = new Storage(); s.items.add(reminder(0, false));
        s.repairOk = false;
        assertEquals(-1, ReminderRetentionPolicy.prune(s, 60000));
        assertEquals("REPAIR_FAILED", s.stage); assertNull(s.cause);
        assertEquals(0, s.writes);
        s.repairOk = true; s.readFails = true;
        assertEquals(-1, ReminderRetentionPolicy.prune(s, 60000));
        assertEquals("READ_FAILED", s.stage); assertNotNull(s.cause);
        s.readFails = false; s.writeOk = false;
        assertEquals(-1, ReminderRetentionPolicy.prune(s, 60000));
        assertEquals("WRITE_FAILED", s.stage); assertNull(s.cause);
        assertEquals(1, s.items.size());
        s.writeFails = true;
        assertEquals(-1, ReminderRetentionPolicy.prune(s, 60000));
        assertEquals(1, s.items.size());
    }

    @Test public void reportsOriginalExceptionsAndObserverCannotBreakFailure() {
        Storage s = new Storage(); s.items.add(reminder(0, false));
        Exception original = new Exception("private data");
        s.repairCause = original;
        assertEquals(-1, ReminderRetentionPolicy.prune(s, 60000));
        assertEquals("REPAIR_FAILED", s.stage); assertSame(original, s.cause);
        s.repairCause = null; s.readCause = original;
        assertEquals(-1, ReminderRetentionPolicy.prune(s, 60000));
        assertEquals("READ_FAILED", s.stage); assertSame(original, s.cause);
        s.readCause = null; s.writeCause = original; s.observerFails = true;
        assertEquals(-1, ReminderRetentionPolicy.prune(s, 60000));
        assertEquals("WRITE_FAILED", s.stage); assertSame(original, s.cause);
        assertEquals(3, s.failures);
    }
}
