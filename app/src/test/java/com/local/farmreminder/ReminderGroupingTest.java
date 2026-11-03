package com.local.farmreminder;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import static org.junit.Assert.*;

public class ReminderGroupingTest {
    private static Reminder r(String id, String friend, long maturity, long trigger) {
        Reminder r = new Reminder(); r.id = id; r.friend = friend; r.matureAt = maturity; r.triggerAt = trigger; return r;
    }
    @Test public void strictWindowAndAnchorNotChained() {
        Reminder a = r("a", "one", 1000000, 900000), b = r("b", "two", 1119999, 800000), c = r("c", "three", 1120000, 700000);
        List<ReminderGrouping.Group> groups = ReminderGrouping.plan(Arrays.asList(c, b, a));
        assertEquals(2, groups.size()); assertEquals(Arrays.asList(a, b), groups.get(0).reminders);
        assertSame(c, groups.get(1).anchor()); assertEquals(900000, Reminder.scheduledTime(groups.get(0).anchor(), 1));
        assertEquals("two", groups.get(0).reminders.get(1).friend);
        b.matureAt = 1090000; c.matureAt = 1180000;
        assertEquals(2, ReminderGrouping.plan(Arrays.asList(a,b,c)).size());
    }
    @Test public void tieFiredAndMembershipIdentity() {
        Reminder a = r("a", "a", 1000, 500), b = r("b", "b", 1000, 100);
        List<Reminder> all = new ArrayList<>(Arrays.asList(b, a));
        ReminderGrouping.Group group = ReminderGrouping.plan(all).get(0);
        assertSame(a, group.anchor()); String before = group.identity;
        all.remove(b); assertNotEquals(before, ReminderGrouping.plan(all).get(0).identity);
        all.add(b); a.fired = true; assertSame(b, ReminderGrouping.plan(all).get(0).anchor());
    }
    private static final class State implements AlarmDispatchPolicy.Adapter {
        final List<Reminder> items = new ArrayList<>(); boolean ready = true, allowed = true, throwsNotify, save = true; int notifications;
        public boolean ready() { return ready; }
        public List<Reminder> list() { return items; }
        public boolean notifyGroup(ReminderGrouping.Group group) { if (throwsNotify) throw new SecurityException(); if (!allowed) return false; notifications++; return true; }
        public boolean markFired(List<Reminder> group) { for (Reminder r : group) r.fired = true; return save; }
    }
    @Test public void dispatchRejectsStaleEarlyAndFailures() {
        Object lock = new Object(); State s = new State(); Reminder a = r("a", "a", 10000, 5000), b = r("b", "b", 11000, 4000);
        s.items.add(a); String old = ReminderGrouping.plan(s.items).get(0).identity; s.items.add(b);
        String current = ReminderGrouping.plan(s.items).get(0).identity;
        assertFalse(AlarmDispatchPolicy.dispatch(lock,s,old,6000));
        assertFalse(AlarmDispatchPolicy.dispatch(lock,s,current,4999));
        s.allowed = false; assertFalse(AlarmDispatchPolicy.dispatch(lock,s,current,6000)); assertFalse(a.fired);
        s.allowed = true; s.throwsNotify = true; assertFalse(AlarmDispatchPolicy.dispatch(lock,s,current,6000)); assertFalse(a.fired);
        s.throwsNotify = false; s.save = false; assertFalse(AlarmDispatchPolicy.dispatch(lock,s,current,6000));
        assertTrue(a.fired); assertTrue(b.fired); assertTrue(ReminderGrouping.plan(s.items).isEmpty());
        // A failed disk write may be rolled back on restart: pending reminders then reappear in the plan.
        a.fired = false; b.fired = false; assertEquals(current, ReminderGrouping.plan(s.items).get(0).identity);
    }
    @Test public void concurrentDispatchNotifiesOnce() throws Exception {
        Object lock = new Object(); State s = new State(); s.items.add(r("a", "a", 10000, 5000));
        String id = ReminderGrouping.plan(s.items).get(0).identity; CountDownLatch start = new CountDownLatch(1);
        Runnable run = () -> { try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); } AlarmDispatchPolicy.dispatch(lock,s,id,6000); };
        Thread a = new Thread(run), b = new Thread(run); a.start(); b.start(); start.countDown(); a.join(); b.join();
        assertEquals(1, s.notifications);
    }
}
