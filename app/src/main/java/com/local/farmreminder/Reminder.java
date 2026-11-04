package com.local.farmreminder;

public class Reminder {
    public String id, friend, crop;
    public String avatarRef = "";
    public String sourceFrameRef = "";
    public long matureAt, triggerAt;
    public boolean fired;

    /** Returns zero when no alarm should be scheduled. */
    static long scheduledTime(Reminder reminder, long now) {
        if (reminder.fired) return 0;
        return reminder.matureAt > now && reminder.triggerAt > now ? reminder.triggerAt : now + 1000L;
    }
}
