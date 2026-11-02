package com.local.farmreminder;

/** Identity and lifetime check for callbacks belonging to a service instance. */
final class InstanceOwner {
    private InstanceOwner() { }

    static boolean alive(Object current, Object owner, boolean destroyed) {
        return !destroyed && owner != null && current == owner;
    }
}
