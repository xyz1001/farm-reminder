package com.local.farmreminder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShizukuIdentityPolicyTest {
    @Test public void acceptsOnlyRootAndShell() {
        assertTrue(ShizukuIdentityPolicy.isSupportedUid(0));
        assertTrue(ShizukuIdentityPolicy.isSupportedUid(2000));
        for (int uid : new int[]{-1, 1000, 10000, 1002000, Integer.MAX_VALUE}) {
            assertFalse("unexpected supported UID: " + uid, ShizukuIdentityPolicy.isSupportedUid(uid));
        }
    }
}
