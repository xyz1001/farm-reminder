package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class InstanceOwnerTest {
    @Test public void destroyedOrReplacedOwnerCannotRunCallbacks() {
        Object oldOwner = new Object();
        Object newOwner = new Object();
        assertTrue(InstanceOwner.alive(oldOwner, oldOwner, false));
        assertFalse(InstanceOwner.alive(oldOwner, oldOwner, true));
        assertFalse(InstanceOwner.alive(newOwner, oldOwner, false));
        assertFalse(InstanceOwner.alive(newOwner, oldOwner, true));
        assertTrue(InstanceOwner.alive(newOwner, newOwner, false));
        assertFalse(InstanceOwner.alive(null, oldOwner, false));
    }
}
