package com.local.farmreminder;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class AlarmPlanTest {
    private static Reminder reminder(String id, long maturity, long trigger) {
        Reminder r = new Reminder(); r.id = id; r.matureAt = maturity; r.triggerAt = trigger; return r;
    }
    private static class Backend implements AlarmPlan.Backend {
        Set<String> recorded = new HashSet<>(); Map<String, Long> alarms = new HashMap<>();
        int attempts; boolean fail, failRecord;
        public Set<String> recorded() { return recorded; }
        public boolean record(Set<String> ids) { if (failRecord) return false; recorded = new HashSet<>(ids); return true; }
        public void cancel(String id) { alarms.remove(id); }
        public boolean schedule(String id, long at) { attempts++; if (fail) return false; alarms.put(id, at); return true; }
    }
    private static final class State implements AlarmDispatchPolicy.Adapter {
        final Object lock = new Object(); final List<Reminder> items = new ArrayList<>();
        final Backend backend = new Backend(); AlarmPlan plan = new AlarmPlan();
        boolean dirty, repairSucceeds = true; int notifications;
        public boolean ready() { if (dirty && !repairSucceeds) return false; dirty = false; return true; }
        public List<Reminder> list() { return items; }
        public boolean notifyGroup(ReminderGrouping.Group group) { notifications++; return true; }
        public boolean markFired(List<Reminder> group) { for (Reminder r : group) r.fired = true; return true; }
        public void reconcile() { plan.reconcile(ReminderGrouping.plan(items), backend); }
        boolean deliver(String id, long now) { backend.alarms.remove(id); plan.delivered(id); return AlarmDispatchPolicy.dispatch(lock, this, id, now); }
    }
    @Test public void overdueReconcilesDoNotSlideAndFailureAndRestartRetry() {
        State s = new State(); s.items.add(reminder("a", 1000, 500));
        s.backend.fail = true; s.reconcile(); assertEquals(1, s.backend.attempts);
        s.backend.fail = false; s.reconcile(); assertEquals(2, s.backend.attempts);
        String id = ReminderGrouping.plan(s.items).get(0).identity;
        for (long now = 2000; now < 7000; now += 500) {
            s.reconcile(); assertEquals(Long.valueOf(500), s.backend.alarms.get(id));
        }
        assertEquals(2, s.backend.attempts);
        // A fresh process must not trust the journal as proof that an alarm exists.
        s.backend.alarms.clear(); s.plan = new AlarmPlan(); s.reconcile();
        assertEquals(3, s.backend.attempts); assertTrue(s.deliver(id, 7000));
        assertFalse(s.deliver(id, 7500)); assertEquals(1, s.notifications);
    }
    @Test public void staleDeliveryAfterFailedDeleteRepairSchedulesRemainingGroup() {
        State s = new State(); Reminder a = reminder("a", 1000, 500), b = reminder("b", 2000, 600);
        s.items.add(a); s.items.add(b); s.reconcile();
        String old = ReminderGrouping.plan(s.items).get(0).identity;
        // SharedPreferences failed commit has already changed its in-memory image.
        s.items.remove(a); s.dirty = true;
        assertFalse(s.deliver(old, 3000)); assertFalse(s.dirty); assertEquals(0, s.notifications);
        String next = ReminderGrouping.plan(s.items).get(0).identity;
        assertEquals(Long.valueOf(600), s.backend.alarms.get(next));
        assertTrue(s.deliver(next, 3000)); assertEquals(1, s.notifications); assertTrue(b.fired);
    }
    @Test public void capabilityUpgradeAndGrantRearmWithoutChangingTrigger() {
        State s = new State(); s.items.add(reminder("a", 1000, 500));
        String id = ReminderGrouping.plan(s.items).get(0).identity;
        s.plan.capability(false, false); s.reconcile(); assertEquals(1, s.backend.attempts);
        s.plan.capability(false, false); s.reconcile(); assertEquals(1, s.backend.attempts);
        s.plan.capability(true, false); s.reconcile(); assertEquals(2, s.backend.attempts);
        assertEquals(Long.valueOf(500), s.backend.alarms.get(id));
        s.plan.capability(true, false); s.reconcile(); assertEquals(2, s.backend.attempts);
        s.plan.capability(true, true); s.reconcile(); assertEquals(3, s.backend.attempts);
        s.plan.capability(true, false); s.reconcile(); assertEquals(3, s.backend.attempts);
        s.plan.capability(false, false); s.reconcile(); assertEquals(3, s.backend.attempts);
        s.plan.capability(false, true); s.reconcile(); assertEquals(3, s.backend.attempts);
    }
    @Test public void failedJournalDuringUpgradePreservesExistingAlarmAndRetries() {
        State s = new State(); s.items.add(reminder("a", 1000, 500));
        String id = ReminderGrouping.plan(s.items).get(0).identity;
        s.plan.capability(false, false); s.reconcile();
        Set<String> before = new HashSet<>(s.backend.recorded);
        s.backend.failRecord = true; s.plan.capability(true, false); s.reconcile();
        assertEquals(1, s.backend.attempts); assertEquals(before, s.backend.recorded);
        assertEquals(Long.valueOf(500), s.backend.alarms.get(id));
        s.backend.failRecord = false; s.reconcile(); assertEquals(2, s.backend.attempts);
    }
    @Test public void testFeedbackReflectsFailureWithoutExactPromise() {
        assertTrue(AlarmPlan.testScheduleMessage(false).contains("未能安排"));
        assertTrue(AlarmPlan.testScheduleMessage(true).contains("可能受系统限制延迟"));
    }
    @Test public void exactSecurityExceptionAndFallbackFailureRetryAtSameCapability() {
        Reminder r = reminder("a", 1000, 500);
        List<ReminderGrouping.Group> groups = ReminderGrouping.plan(Collections.singletonList(r));
        AlarmPlan plan = new AlarmPlan();
        Backend backend = new Backend() {
            boolean available;
            @Override public boolean schedule(String id, long at) {
                attempts++;
                if (!available) {
                    try { throw new SecurityException(); }
                    catch (SecurityException exactFailure) {
                        try { throw new IllegalStateException(); }
                        catch (RuntimeException fallbackFailure) { available = true; return false; }
                    }
                }
                alarms.put(id, at); return true;
            }
        };
        plan.capability(true, false); plan.reconcile(groups, backend);
        assertEquals(1, backend.attempts); assertTrue(backend.alarms.isEmpty());
        plan.capability(true, false); plan.reconcile(groups, backend);
        assertEquals(2, backend.attempts); assertEquals(Long.valueOf(500), backend.alarms.get(groups.get(0).identity));
    }
}
