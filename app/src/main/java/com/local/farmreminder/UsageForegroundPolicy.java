package com.local.farmreminder;

import java.util.HashMap;
import java.util.Map;

/** A bounded-history inference, not proof of visibility or exclusive display ownership. */
final class UsageForegroundPolicy {
    static final int RESUMED = 1, PAUSED = 2, STOPPED = 3, RESET = 4;
    private static final String WECHAT = "com.tencent.mm";
    private static final String APPBRAND = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI";
    private final Map<String, Candidate> active = new HashMap<>();
    private long lastTimestamp = Long.MIN_VALUE;
    private int timestampOrdinal;
    private boolean invalid, sawEvent, ambiguous;

    void event(long timestamp, String pkg, String cls, int type) {
        if (timestamp < lastTimestamp) {
            invalid = true; return;
        }
        timestampOrdinal = timestamp == lastTimestamp ? timestampOrdinal + 1 : 0;
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
        if (type == RESUMED) { if (active.containsKey(key)) ambiguous = true; else active.put(key, new Candidate(cls, timestamp, timestampOrdinal)); }
        else if (type == PAUSED || type == STOPPED) active.remove(key);
        else invalid = true;
    }

    boolean canCapture(boolean permission, boolean unlocked, boolean interactive, boolean locked) {
        return candidate(permission, unlocked, interactive, locked) != null;
    }
    enum State { CANDIDATE, CONFIRMED_NON_CANDIDATE, UNKNOWN }
    State state(boolean permission, boolean unlocked, boolean interactive, boolean locked) {
        if (!permission || !unlocked || !interactive || locked || invalid || ambiguous || !sawEvent || active.size() != 1) return State.UNKNOWN;
        return candidate(permission, unlocked, interactive, locked) == null ? State.CONFIRMED_NON_CANDIDATE : State.CANDIDATE;
    }
    static final class Candidate {
        final String className;
        final long resumeTimestamp;
        final int resumeOrdinal;
        Candidate(String className, long resumeTimestamp, int resumeOrdinal) { this.className = className; this.resumeTimestamp = resumeTimestamp; this.resumeOrdinal = resumeOrdinal; }
        String key() { return className + "\u0000" + resumeTimestamp + "\u0000" + resumeOrdinal; }
    }
    Candidate candidate(boolean permission, boolean unlocked, boolean interactive, boolean locked) {
        if (!permission || !unlocked || !interactive || locked || invalid || ambiguous || !sawEvent || active.size() != 1) return null;
        Map.Entry<String, Candidate> entry = active.entrySet().iterator().next();
        String prefix = WECHAT + "\u0000";
        if (!entry.getKey().startsWith(prefix)) return null;
        String cls = entry.getKey().substring(prefix.length());
        if (!cls.startsWith(APPBRAND)) return null;
        for (int i = APPBRAND.length(); i < cls.length(); i++) if (cls.charAt(i) < '0' || cls.charAt(i) > '9') return null;
        return entry.getValue();
    }
}
