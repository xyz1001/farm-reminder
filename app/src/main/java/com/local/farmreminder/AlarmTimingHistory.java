package com.local.farmreminder;

import java.util.ArrayDeque;

/** Anonymous timing samples, kept as raw wall and elapsed clocks without inferred lateness. */
final class AlarmTimingHistory {
    enum Event { SCHEDULE, RECEIVE, NOTIFY }
    enum Mode { EXACT, INEXACT, SECURITY_FALLBACK, FAILED, RESET_FAILED, UNKNOWN }
    private final ArrayDeque<String> records = new ArrayDeque<>();
    static long validToken(long token) { return token > 0 ? token : 0; }
    void add(long token, long requested, long scheduledWall, long scheduledElapsed, long wall, long elapsed,
             Event event, Mode mode, int exact, int success, long duration, Throwable error) {
        token = validToken(token);
        if (token == 0) { requested = 0; scheduledWall = 0; scheduledElapsed = 0; }
        String name = error == null ? "" : error.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_$]", "");
        if (name.length() > 48) name = name.substring(0, 48);
        append(token + "|" + Math.max(0, requested) + "|" + Math.max(0, scheduledWall) + "|" + Math.max(0, scheduledElapsed)
                + "|" + Math.max(0, wall) + "|" + Math.max(0, elapsed) + "|" + event + "|" + mode
                + "|" + Math.max(-1, Math.min(1, exact)) + "|" + Math.max(-1, Math.min(1, success)) + "|" + Math.max(-1, duration) + "|" + name);
    }
    private void append(String line) { if (records.size() == 64) records.removeFirst(); records.addLast(line); }
    void prepend(String previous) {
        ArrayDeque<String> current = new ArrayDeque<>(records); records.clear();
        try {
            if (previous != null) {
                if (previous.length() > 32768) throw new IllegalArgumentException();
                String[] lines = previous.split("\n");
                if (lines.length > 64) throw new IllegalArgumentException();
                for (String line : lines) {
                    if (line.isEmpty()) continue;
                    String[] p = line.split("\\|", -1);
                    if (p.length != 12) throw new IllegalArgumentException();
                    for (int i = 0; i < 6; i++) if (Long.parseLong(p[i]) < 0) throw new IllegalArgumentException();
                    Event.valueOf(p[6]); Mode.valueOf(p[7]);
                    if (!p[8].matches("-1|0|1") || !p[9].matches("-1|0|1") || Long.parseLong(p[10]) < -1 || !p[11].matches("[A-Za-z0-9_$]{0,48}")) throw new IllegalArgumentException();
                    append(line);
                }
            }
        } catch (RuntimeException invalid) { records.clear(); }
        for (String line : current) append(line);
    }
    String serialize() { StringBuilder result = new StringBuilder(); for (String line : records) result.append(line).append('\n'); return result.toString(); }
}
