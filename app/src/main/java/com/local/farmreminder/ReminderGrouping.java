package com.local.farmreminder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Deterministic partitions of pending reminders, bounded by each group's first maturity. */
final class ReminderGrouping {
    static final long WINDOW_MS = MaturityIdentity.WINDOW_MS;
    static final class Group {
        final List<Reminder> reminders;
        final String identity;
        Group(List<Reminder> items) {
            reminders = Collections.unmodifiableList(new ArrayList<>(items));
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                for (Reminder r : items) {
                    byte[] id = r.id.getBytes(StandardCharsets.UTF_8);
                    digest.update((byte)(id.length >>> 24)); digest.update((byte)(id.length >>> 16));
                    digest.update((byte)(id.length >>> 8)); digest.update((byte)id.length); digest.update(id);
                    digest.update((":" + r.matureAt + ":" + r.triggerAt + ";").getBytes(StandardCharsets.UTF_8));
                }
                StringBuilder result = new StringBuilder();
                for (byte b : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                identity = result.toString();
            } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
        Reminder anchor() { return reminders.get(0); }
        boolean due(long now) { return anchor().triggerAt <= now || anchor().matureAt <= now; }
    }
    static List<Group> plan(List<Reminder> reminders) {
        List<Group> groups = new ArrayList<>();
        for (List<Reminder> partition : MaturityIdentity.partition(reminders)) {
            List<Reminder> pending = new ArrayList<>();
            for (Reminder r : partition) if (!r.fired) pending.add(r);
            if (!pending.isEmpty()) groups.add(new Group(pending));
        }
        return groups;
    }
}
