package com.local.farmreminder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read-only display partitions; delivery state never hides an unexpired reminder. */
final class ReminderListGroups {
    final List<ReminderGrouping.Group> active;

    ReminderListGroups(List<Reminder> records, long now) {
        List<Reminder> visible = new ArrayList<>();
        for (Reminder reminder : records)
            if (!ReminderRetentionPolicy.isExpired(reminder, now)) visible.add(reminder);
        active = new ArrayList<>();
        for (List<Reminder> partition : MaturityIdentity.partition(visible))
            active.add(new ReminderGrouping.Group(partition));
    }

    String title() { return "提醒 " + active.size() + " 组"; }

    static String summary(ReminderGrouping.Group group) {
        return group.reminders.size() + " 条提醒";
    }

    static String avatarRef(ReminderGrouping.Group group) {
        List<Reminder> ordered = new ArrayList<>(group.reminders);
        ordered.sort(Comparator.comparingLong((Reminder r) -> r.matureAt).thenComparing(r -> r.id));
        for (Reminder r : ordered) {
            String ref = AvatarRefPolicy.normalize(r.avatarRef);
            if (!r.fired && !ref.isEmpty()) return ref;
        }
        return "";
    }

    /** Includes identity and the opaque avatar key so attaching/changing a photo rebuilds its row. */
    static String signature(List<Reminder> records, long now) {
        StringBuilder out = new StringBuilder();
        for (Reminder r : records) {
            if (ReminderRetentionPolicy.isExpired(r, now)) continue;
            append(out, r.id); append(out, r.avatarRef);
            out.append(r.matureAt).append('|').append(r.triggerAt).append('|')
                    .append(r.fired).append('|').append(r.triggerAt <= now).append(';');
        }
        return out.length() == 0 ? "empty" : out.toString();
    }

    private static void append(StringBuilder out, String value) {
        String text = value == null ? "" : value;
        out.append(text.length()).append(':').append(text).append('|');
    }

}
