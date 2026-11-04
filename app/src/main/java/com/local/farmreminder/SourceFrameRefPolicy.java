package com.local.farmreminder;

import java.util.UUID;

/** Only the exact canonical spelling is accepted; never treat a ref as a path. */
final class SourceFrameRefPolicy {
    static String normalize(String value) {
        if (value == null) return "";
        try { String canonical = UUID.fromString(value).toString(); return canonical.equals(value) ? canonical : ""; }
        catch (IllegalArgumentException ignored) { return ""; }
    }
}
