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
}
