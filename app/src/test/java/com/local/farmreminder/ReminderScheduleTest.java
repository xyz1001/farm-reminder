package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ReminderScheduleTest {
    @Test public void schedulesOverdueOnceUntilFired() {
        Reminder r = new Reminder();
        r.matureAt = 20000;
        r.triggerAt = 15000;
        assertEquals(15000, Reminder.scheduledTime(r, 10000));
        assertEquals(17000, Reminder.scheduledTime(r, 16000));
        assertEquals(16000, Reminder.scheduledTime(r, 15000));
        assertEquals(21000, Reminder.scheduledTime(r, 20000));
        assertEquals(22000, Reminder.scheduledTime(r, 21000));
        r.fired = true;
        assertEquals(0, Reminder.scheduledTime(r, 10000));
    }
}
