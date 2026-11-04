package com.local.farmreminder;

/** Opaque SHA-256 asset key, never a path. */
final class AvatarRefPolicy {
    static String normalize(String value) { return value != null && value.matches("[0-9a-f]{64}") ? value : ""; }
}
