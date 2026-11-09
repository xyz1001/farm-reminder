package com.local.farmreminder;

import java.util.List;

/** Applies a user-selected lead to pending records without changing their identity or maturity. */
final class ReminderLeadPolicy {
    static boolean valid(int seconds) {
        return seconds == 0 || seconds == 60 || seconds == 180 || seconds == 300;
    }

    static void update(List<Reminder> reminders, int seconds) {
        if (!valid(seconds)) throw new IllegalArgumentException("invalid lead");
        for (Reminder reminder : reminders)
            if (!reminder.fired) reminder.triggerAt = reminder.matureAt - seconds * 1000L;
    }
}
