package com.local.farmreminder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Process-local evidence of successful scheduling; persisted identities are only a cleanup journal. */
final class AlarmPlan {
    interface Backend {
        Set<String> recorded();
        boolean record(Set<String> ids);
        void cancel(String id);
        boolean schedule(String id, long at);
    }
    private final Set<String> scheduled = new HashSet<>();
    void delivered(String id) { scheduled.remove(id); }
    void reconcile(List<ReminderGrouping.Group> groups, Backend backend) {
        Set<String> old = new HashSet<>(backend.recorded()), next = new HashSet<>();
        for (ReminderGrouping.Group group : groups) next.add(group.identity);
        Set<String> union = new HashSet<>(old); union.addAll(next);
        if (!backend.record(union)) return;
        for (String id : old) if (!next.contains(id)) { backend.cancel(id); scheduled.remove(id); }
        for (ReminderGrouping.Group group : groups) {
            if (!scheduled.contains(group.identity)
                    && backend.schedule(group.identity, group.anchor().triggerAt))
                scheduled.add(group.identity);
        }
        backend.record(next);
    }
}
