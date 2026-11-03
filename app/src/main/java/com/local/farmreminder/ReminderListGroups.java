package com.local.farmreminder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    static String nickname(Reminder reminder) {
        return reminder.friend == null || reminder.friend.trim().isEmpty()
                ? "昵称未识别" : reminder.friend.trim();
    }

    static String summary(ReminderGrouping.Group group) {
        Set<String> names = new LinkedHashSet<>();
        for (Reminder reminder : group.reminders) names.add(nickname(reminder));
        StringBuilder text = new StringBuilder();
        int count = 0;
        for (String name : names) {
            if (count == 2) break;
            if (count++ > 0) text.append("、");
            text.append(name.length() > 12 ? name.substring(0, 12) + "…" : name);
        }
        return group.reminders.size() + " 条提醒 · " + text + (names.size() > 2 ? " 等" : "");
    }

    static String lead(Reminder reminder) {
        long seconds = Math.max(0, (reminder.matureAt - reminder.triggerAt) / 1000);
        if (seconds == 0) return "成熟时提醒";
        return "提前 " + (seconds % 60 == 0 ? seconds / 60 + " 分钟" : seconds + " 秒") + "提醒";
    }
}
