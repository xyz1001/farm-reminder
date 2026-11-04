package com.local.farmreminder;

import org.junit.Test;
import java.util.Arrays;
import java.util.LinkedHashSet;
import static org.junit.Assert.*;
import static com.local.farmreminder.AvatarBindingPolicy.Request.*;

public class AvatarBindingPolicyTest {
    @Test public void sameAssetCoalescesAcrossRowsAndDeduplicatesRepeatedVisibilityChecks() {
        AvatarBindingPolicy policy = new AvatarBindingPolicy(2);
        long a = policy.bind("asset"), b = policy.bind("asset");
        assertEquals(START, policy.request(a, "asset"));
        assertEquals(JOIN, policy.request(a, "asset"));
        assertEquals(JOIN, policy.request(b, "asset"));
        assertEquals(new LinkedHashSet<>(Arrays.asList(a, b)), policy.complete("asset"));
    }

    @Test public void staleCompletionCannotTargetRebuiltRowsEvenWithTheSameReference() {
        AvatarBindingPolicy policy = new AvatarBindingPolicy(2);
        long old = policy.bind("asset");
        assertEquals(START, policy.request(old, "asset"));
        policy.clearBindings();
        long fresh = policy.bind("asset");
        assertFalse(policy.current(old, "asset"));
        assertNotEquals(old, fresh);
        assertTrue(policy.complete("asset").isEmpty());
    }

    @Test public void rebuiltRowCanExplicitlyJoinExistingAssetWithoutSecondDecode() {
        AvatarBindingPolicy policy = new AvatarBindingPolicy(1);
        long old = policy.bind("asset"); policy.request(old, "asset");
        policy.clearBindings();
        long fresh = policy.bind("asset");
        assertEquals(JOIN, policy.request(fresh, "asset"));
        assertEquals(new LinkedHashSet<>(Arrays.asList(fresh)), policy.complete("asset"));
    }

    @Test public void referenceMismatchAndEmptyAssetNeverStartWork() {
        AvatarBindingPolicy policy = new AvatarBindingPolicy(2);
        long token = policy.bind("first");
        assertEquals(INVALID, policy.request(token, "other"));
        assertEquals(INVALID, policy.request(token, ""));
        assertEquals(INVALID, policy.request(token, null));
        assertEquals(INVALID, policy.request(999, "first"));
        assertFalse(policy.current(token, "other"));
    }

    @Test public void requestLimitDefersInsteadOfDoingWorkOnCallerAndCompletionFreesCapacity() {
        AvatarBindingPolicy policy = new AvatarBindingPolicy(1);
        long a = policy.bind("a"), b = policy.bind("b");
        assertEquals(START, policy.request(a, "a"));
        assertEquals(DEFER, policy.request(b, "b"));
        assertEquals(JOIN, policy.request(a, "a"));
        policy.complete("a");
        assertEquals(START, policy.request(b, "b"));
    }

    @Test public void closeInvalidatesBindingsAndPendingCallbacks() {
        AvatarBindingPolicy policy = new AvatarBindingPolicy(2);
        long a = policy.bind("a"); policy.request(a, "a");
        policy.close();
        assertFalse(policy.current(a, "a"));
        assertTrue(policy.complete("a").isEmpty());
        assertEquals(INVALID, policy.request(a, "a"));
    }
}
