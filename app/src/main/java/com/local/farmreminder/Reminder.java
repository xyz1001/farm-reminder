package com.local.farmreminder;

public class Reminder {
    public String id, friend, crop;
    public long matureAt, triggerAt;
    public boolean fired;

    /** Returns zero when no alarm should be scheduled. */
    static long scheduledTime(Reminder reminder, long now) {
        if (reminder.fired || reminder.matureAt <= now) return 0;
        return reminder.triggerAt > now ? reminder.triggerAt : now + 1000L;
    }
}
