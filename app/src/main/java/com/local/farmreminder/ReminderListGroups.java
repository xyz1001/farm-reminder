package com.local.farmreminder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read-only list presentation; pending partitions always come from the alarm planner. */
final class ReminderListGroups {
    final List<ReminderGrouping.Group> pending;
    final List<Reminder> history;

    ReminderListGroups(List<Reminder> records) {
        pending = ReminderGrouping.plan(records);
        history = new ArrayList<>();
        for (Reminder reminder : records) if (reminder.fired) history.add(reminder);
        history.sort(Comparator.comparingLong((Reminder r) -> r.matureAt).thenComparing(r -> r.id));
    }

    String title() { return "待提醒 " + pending.size() + " 组"; }

    static String summary(ReminderGrouping.Group group) {
        return "好友 · " + group.reminders.size() + " 条提醒";
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
            append(out, r.id); append(out, r.crop); append(out, r.avatarRef);
            out.append(r.matureAt).append('|').append(r.triggerAt).append('|')
                    .append(r.fired).append('|').append(r.triggerAt <= now).append(';');
        }
        return records.isEmpty() ? "empty" : out.toString();
    }

    private static void append(StringBuilder out, String value) {
        String text = value == null ? "" : value;
        out.append(text.length()).append(':').append(text).append('|');
    }

    static String lead(Reminder reminder) {
        long seconds = Math.max(0, (reminder.matureAt - reminder.triggerAt) / 1000);
        if (seconds == 0) return "成熟时提醒";
        return "提前 " + (seconds % 60 == 0 ? seconds / 60 + " 分钟" : seconds + " 秒") + "提醒";
    }
}
