package com.local.farmreminder;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class AlarmDispatchPolicyTest {
    private static Reminder reminder(String id, long matureAt, boolean fired) {
        Reminder r = new Reminder(); r.id = id; r.matureAt = matureAt; r.triggerAt = matureAt; r.fired = fired; return r;
    }

    private static String identity(Reminder... reminders) {
        return ReminderGrouping.plan(Arrays.asList(reminders)).get(0).identity;
    }

    private static class Adapter implements AlarmDispatchPolicy.Adapter {
        final List<Reminder> stored = new ArrayList<>();
        List<Reminder> notified, marked;
        int reconciles;
        public boolean ready() { return true; }
        public List<Reminder> list() { return stored; }
        public boolean notifyGroup(ReminderGrouping.Group group) { notified = group.reminders; return true; }
        public boolean markFired(List<Reminder> reminders) { marked = reminders; return true; }
        public void reconcile() { reconciles++; }
    }

    @Test public void dueJustBeforeExpiryButNotAtExpiryEvenWhenStillStored() {
        Reminder r = reminder("pending", 1000, false);
        String id = identity(r);
        Adapter before = new Adapter(); before.stored.add(r);
        assertTrue(AlarmDispatchPolicy.dispatch(new Object(), before, id, 60999));
        assertEquals(Arrays.asList(r), before.notified);
        assertEquals(Arrays.asList(r), before.marked);

        // Represents either no pruning or failed pruning: the expired record is still returned.
        Adapter expired = new Adapter(); expired.stored.add(r);
        assertFalse(AlarmDispatchPolicy.dispatch(new Object(), expired, id, 61000));
        assertNull(expired.notified); assertNull(expired.marked);
        assertEquals(1, expired.reconciles);
    }

    @Test public void changedIdentityIsRejectedAndRemainingGroupCanDeliver() {
        Reminder old = reminder("old", 0, false);
        Reminder active = reminder("active", 10000, false);
        String oldId = identity(old, active), activeId = identity(active);
        assertNotEquals(oldId, activeId);
        Adapter adapter = new Adapter(); adapter.stored.addAll(Arrays.asList(old, active));
        assertFalse(AlarmDispatchPolicy.dispatch(new Object(), adapter, oldId, 60000));
        assertNull(adapter.notified); assertNull(adapter.marked);
        assertEquals(1, adapter.reconciles);
        assertTrue(AlarmDispatchPolicy.dispatch(new Object(), adapter, activeId, 60000));
        assertEquals(Arrays.asList(active), adapter.notified);
        assertEquals(Arrays.asList(active), adapter.marked);
    }

    @Test public void firedExpiredAnchorCannotChangeActiveGrouping() {
        Reminder fired = reminder("fired", 0, true);
        Reminder first = reminder("first", 10000, false);
        Reminder second = reminder("second", 19000, false);
        String activeId = identity(first, second);
        assertNotEquals(activeId, identity(fired, first, second));
        Adapter adapter = new Adapter(); adapter.stored.addAll(Arrays.asList(fired, first, second));
        assertTrue(AlarmDispatchPolicy.dispatch(new Object(), adapter, activeId, 60000));
        assertEquals(Arrays.asList(first, second), adapter.notified);
        assertEquals(Arrays.asList(first, second), adapter.marked);
    }
}
