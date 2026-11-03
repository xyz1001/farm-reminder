package com.local.farmreminder;

import java.util.HashSet;
import java.util.Set;

/** A bounded-history inference, not proof of visibility or exclusive display ownership. */
final class UsageForegroundPolicy {
    static final int RESUMED = 1, PAUSED = 2, STOPPED = 3, RESET = 4;
    private static final String WECHAT = "com.tencent.mm";
    private final Set<String> active = new HashSet<>();
    private long lastTimestamp = Long.MIN_VALUE;
    private boolean invalid, sawEvent, ambiguous;

    void event(long timestamp, String pkg, String cls, int type) {
        if (timestamp < lastTimestamp) {
            invalid = true; return;
        }
        lastTimestamp = timestamp;
        if (type == RESET) {
            active.clear();
            ambiguous = false;
            sawEvent = false;
            return;
        }
        if (pkg == null || pkg.isEmpty() || cls == null || cls.isEmpty()) {
            invalid = true; return;
        }
        sawEvent = true;
        String key = pkg + "\u0000" + cls;
        if (type == RESUMED) { if (!active.add(key)) ambiguous = true; }
        else if (type == PAUSED || type == STOPPED) active.remove(key);
        else invalid = true;
    }

    boolean canCapture(boolean permission, boolean unlocked, boolean interactive, boolean locked) {
        if (!permission || !unlocked || !interactive || locked || invalid || ambiguous || !sawEvent || active.isEmpty()) return false;
        for (String key : active) if (!key.startsWith(WECHAT + "\u0000")) return false;
        return true;
    }
}
