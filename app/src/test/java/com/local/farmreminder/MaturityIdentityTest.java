package com.local.farmreminder;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class MaturityIdentityTest {
    private static Reminder record(String id, long time, boolean fired) {
        Reminder r = new Reminder(); r.id = id; r.friend = "legacy"; r.crop = "crop";
        r.matureAt = time; r.triggerAt = time - 100; r.fired = fired; return r;
    }
    @Test public void boundaryAndEarlierAnchorTie() {
        Reminder a = record("a", 20000, false), b = record("b", 40000, false);
        MaturityIdentity identity = new MaturityIdentity(Arrays.asList(b, a));
        assertSame(a, identity.match(10000)); assertNull(identity.match(9999));
        assertSame(a, identity.match(30000)); assertSame(b, identity.match(50000));
        assertNull(identity.match(50001));
    }
    @Test public void firedAnchorParticipatesBeforePendingFiltering() {
        Reminder a = record("a", 1000, true), b = record("b", 10000, false), c = record("c", 19000, false);
        java.util.List<ReminderGrouping.Group> groups = ReminderGrouping.plan(Arrays.asList(c, b, a));
        assertEquals(2, groups.size()); assertEquals(Collections.singletonList(b), groups.get(0).reminders);
        assertEquals(Collections.singletonList(c), groups.get(1).reminders); assertSame(b, groups.get(0).anchor());
        assertSame(a, new MaturityIdentity(Arrays.asList(c, b, a)).match(10000));
        assertTrue(a.fired); assertEquals("legacy", a.friend); assertEquals("crop", a.crop);
    }
    @Test public void refPolicyRejectsPathsAndMalformedValues() {
        String valid = "a".repeat(64);
        assertEquals(valid, AvatarRefPolicy.normalize(valid));
        assertEquals("", AvatarRefPolicy.normalize(null)); assertEquals("", AvatarRefPolicy.normalize("../" + valid));
        assertEquals("", AvatarRefPolicy.normalize("A".repeat(64)));
    }
}
