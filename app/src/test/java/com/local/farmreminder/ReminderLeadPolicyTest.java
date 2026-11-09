package com.local.farmreminder;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class ReminderLeadPolicyTest {
    private Reminder reminder(String id, long maturity, boolean fired) {
        Reminder r = new Reminder(); r.id = id; r.friend = "friend"; r.crop = "crop";
        r.avatarRef = "avatar"; r.plateRef = "plate"; r.sourceFrameRef = "source";
        r.matureAt = maturity; r.triggerAt = maturity - 300000; r.fired = fired; return r;
    }

    @Test public void updatesPendingOnlyAndPreservesAnchorsAndReferences() {
        Reminder a = reminder("a", 1000000, false), b = reminder("b", 2000000, false);
        Reminder fired = reminder("fired", 3000000, true);
        List<Reminder> items = Arrays.asList(a, b, fired);
        ReminderLeadPolicy.update(items, 180);
        assertEquals(820000, a.triggerAt); assertEquals(1820000, b.triggerAt);
        assertEquals(2700000, fired.triggerAt); assertTrue(fired.fired);
        assertEquals("a", a.id); assertEquals(1000000, a.matureAt);
        assertEquals("friend", a.friend); assertEquals("crop", a.crop);
        assertEquals("avatar", a.avatarRef); assertEquals("plate", a.plateRef); assertEquals("source", a.sourceFrameRef);
        ReminderLeadPolicy.update(items, 180); assertEquals(820000, a.triggerAt);
        for (int seconds : new int[]{0, 60, 300}) {
            ReminderLeadPolicy.update(items, seconds);
            assertEquals(a.matureAt - seconds * 1000L, a.triggerAt);
            assertEquals(2700000, fired.triggerAt);
        }
    }

    @Test public void rejectsInvalidLeadBeforeMutation() {
        Reminder r = reminder("a", 1000000, false);
        try { ReminderLeadPolicy.update(Arrays.asList(r), 120); fail(); }
        catch (IllegalArgumentException expected) { assertEquals(700000, r.triggerAt); }
    }
}
