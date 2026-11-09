package com.local.farmreminder;

import java.util.List;
import java.util.ArrayList;

/** Caller supplies the same process-wide lock used by mutations and reconciliation. */
final class AlarmDispatchPolicy {
    interface Adapter {
        boolean ready();
        List<Reminder> list();
        boolean notifyGroup(ReminderGrouping.Group group);
        boolean markFired(List<Reminder> reminders);
        default void reconcile() { }
    }
    static boolean dispatch(Object lock, Adapter adapter, String identity, long now) {
        synchronized (lock) {
            if (!adapter.ready()) return false;
            List<Reminder> active = new ArrayList<>();
            for (Reminder reminder : adapter.list())
                if (!ReminderRetentionPolicy.isExpired(reminder, now)) active.add(reminder);
            for (ReminderGrouping.Group group : ReminderGrouping.plan(active)) {
                if (group.identity.equals(identity) && group.due(now)) {
                    try { if (!adapter.notifyGroup(group)) return false; }
                    catch (RuntimeException e) { return false; }
                    return adapter.markFired(group.reminders);
                }
            }
            adapter.reconcile();
            return false;
        }
    }
}
