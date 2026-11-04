package com.local.farmreminder;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class NotificationIdentityBindingTest {
    private static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String S = "9bc6e85c-58fa-47c1-bf4a-631556dd83b8";
    private static final String T = "2b5fa928-2eee-43c2-a47d-c52289e0c603";
    private static Reminder reminder(String id, long time, String avatar, String source) {
        Reminder r = new Reminder(); r.id = id; r.matureAt = time; r.avatarRef = avatar; r.sourceFrameRef = source; return r;
    }
    @Test public void samePrimaryAllowsIndependentSourceAndAvatarRevalidation() {
        Reminder a = reminder("a", 1, A, S);
        NotificationIdentityBinding.Snapshot old = NotificationIdentityBinding.select("g", Arrays.asList(a));
        a.avatarRef = B;
        NotificationIdentityBinding.Snapshot changedAvatar = NotificationIdentityBinding.select("g", Arrays.asList(a));
        assertTrue(NotificationIdentityBinding.sameSource(old, changedAvatar));
        assertFalse(NotificationIdentityBinding.sameAvatar(old, changedAvatar));
        a.avatarRef = A; a.sourceFrameRef = T;
        NotificationIdentityBinding.Snapshot changedSource = NotificationIdentityBinding.select("g", Arrays.asList(a));
        assertTrue(NotificationIdentityBinding.sameAvatar(old, changedSource));
        assertFalse(NotificationIdentityBinding.sameSource(old, changedSource));
        assertFalse(NotificationIdentityBinding.samePrimary(old, NotificationIdentityBinding.select("other", Arrays.asList(a))));
    }
    @Test public void representativeIsEarliestAvatarOrEarliestPending() {
        Reminder a = reminder("a", 1, "", S), b = reminder("b", 2, A, T);
        NotificationIdentityBinding.Snapshot avatar = NotificationIdentityBinding.select("g", Arrays.asList(a, b));
        assertEquals("b", avatar.id); assertEquals(T, avatar.source);
        b.avatarRef = "";
        NotificationIdentityBinding.Snapshot fallback = NotificationIdentityBinding.select("g", Arrays.asList(a, b));
        assertEquals("a", fallback.id); assertEquals(S, fallback.source);
        assertFalse(NotificationIdentityBinding.samePrimary(avatar, fallback));
        a.sourceFrameRef = "../invalid";
        assertEquals("", NotificationIdentityBinding.select("g", Arrays.asList(a)).source);
    }
    @Test public void plateWinsAndReferencesInvalidateIndependently() {
        Reminder a = reminder("a", 1, A, S), b = reminder("b", 2, B, T);
        b.plateRef = A;
        NotificationIdentityBinding.Snapshot original = NotificationIdentityBinding.select("g", Arrays.asList(a, b));
        assertEquals("b", original.id); assertEquals(T, original.source); assertEquals(B, original.avatar);
        b.plateRef = B;
        NotificationIdentityBinding.Snapshot changedPlate = NotificationIdentityBinding.select("g", Arrays.asList(a, b));
        assertFalse(NotificationIdentityBinding.samePlate(original, changedPlate));
        assertTrue(NotificationIdentityBinding.sameAvatar(original, changedPlate)); assertTrue(NotificationIdentityBinding.sameSource(original, changedPlate));
        b.plateRef = A; b.avatarRef = A;
        NotificationIdentityBinding.Snapshot changedAvatar = NotificationIdentityBinding.select("g", Arrays.asList(a, b));
        assertFalse(NotificationIdentityBinding.sameAvatar(original, changedAvatar)); assertTrue(NotificationIdentityBinding.samePlate(original, changedAvatar));
        b.sourceFrameRef = S;
        NotificationIdentityBinding.Snapshot changedSource = NotificationIdentityBinding.select("g", Arrays.asList(a, b));
        assertFalse(NotificationIdentityBinding.sameSource(original, changedSource)); assertTrue(NotificationIdentityBinding.samePlate(original, changedSource));
        b.plateRef = "";
        NotificationIdentityBinding.Snapshot newPrimary = NotificationIdentityBinding.select("g", Arrays.asList(a, b));
        assertEquals("a", newPrimary.id); assertFalse(NotificationIdentityBinding.samePrimary(original, newPrimary));
        assertFalse(NotificationIdentityBinding.samePlate(original, newPrimary)); assertFalse(NotificationIdentityBinding.sameAvatar(original, newPrimary));
    }
    @Test public void earliestValidPendingPlateBeatsAvatarAndIgnoresFiredOrBadPlate() {
        Reminder a = reminder("a", 1, A, S), b = reminder("b", 5, "", T), c = reminder("c", 4, "", S);
        a.plateRef = "../bad"; b.plateRef = B; c.plateRef = A;
        NotificationIdentityBinding.Snapshot selected = NotificationIdentityBinding.select("g", Arrays.asList(b, a, c));
        assertEquals("c", selected.id); assertEquals(S, selected.source); assertEquals("", selected.avatar);
        c.fired = true; selected = NotificationIdentityBinding.select("g", Arrays.asList(c, a, b));
        assertEquals("b", selected.id); assertEquals(T, selected.source);
    }
}
