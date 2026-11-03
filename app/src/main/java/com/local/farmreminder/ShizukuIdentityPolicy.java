package com.local.farmreminder;

/** Accepted identities of an already running Shizuku server or user service. */
final class ShizukuIdentityPolicy {
    private ShizukuIdentityPolicy() {}

    static boolean isSupportedUid(int uid) {
        return uid == 0 || uid == 2000;
    }
}
