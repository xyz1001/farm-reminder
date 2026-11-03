package com.local.farmreminder;

/** Selects exactly one foreground source; absence never falls back to the other source. */
final class ForegroundSourcePolicy {
    static String key(boolean shizuku, String usageKey, boolean match, boolean fresh,
                      boolean permission, boolean unlocked, boolean interactive, boolean locked,
                      String taskKey, String component) {
        if (!unlocked || !interactive || locked) return null;
        if (!shizuku) return usageKey == null ? null : "usage\u0000" + usageKey;
        if (!match || !fresh || !permission || taskKey == null || taskKey.isEmpty()
                || component == null || component.isEmpty()) return null;
        return "shizuku\u0000" + taskKey + "\u0000" + component;
    }
}
