package com.local.farmreminder;

import java.util.ArrayDeque;

/** Bounded, strictly parsed anonymous records; no application data is accepted. */
final class LifecycleHistory {
    enum Event { UI_CLICK, SETUP_REJECT, START_REQUEST, PENDING_CONFIRMED, PENDING_FAILED, PENDING_TIMEOUT,
        FGS_ACTION, FGS_PROMOTE_BEGIN, FGS_PROMOTE_OK, FGS_EXCEPTION, FGS_REFUSE, FGS_TICK_STOP,
        USER_STOP, FGS_DESTROY, ACCESS_CONNECT, ACCESS_INTERRUPT, ACCESS_UNBIND, ACCESS_DESTROY,
        ACCESS_START_REJECT, ACCESS_START_OK, ACCESS_STOP, CAPTURE_WATCHDOG, CAPTURE_SUCCESS,
        CAPTURE_FAILURE, CAPTURE_LATE_CALLBACK, ENGINE_REJECT, OCR_WATCHDOG, STORAGE_FATAL }
    enum Reason { NONE, ALREADY_ACTIVE, SETUP, UNSUPPORTED, NOT_CONNECTED, CAPTURE_BUSY, ENGINE_BUSY,
        NOT_RUNNING, EXCEPTION, USER, INTERRUPT, UNBIND, DESTROY, WATCHDOG, SOURCE_CHANGED, OCR_TIMEOUT, STORAGE_FAILED }
    private final ArrayDeque<String> records = new ArrayDeque<>();
    private long generation, attempt;
    LifecycleHistory(String serialized, long processGeneration) {
        try {
            if (serialized == null || serialized.length() > 32768) throw new IllegalArgumentException();
            String[] lines = serialized.split("\n");
            generation = Long.parseLong(lines[0]);
            if (generation < 0 || generation == Long.MAX_VALUE || lines.length > 65) throw new IllegalArgumentException();
            for (int i = 1; i < lines.length; i++) {
                String[] p = lines[i].split("\\|", -1);
                if (p.length != 8) throw new IllegalArgumentException();
                for (int n = 0; n < 4; n++) if (Long.parseLong(p[n]) < 0) throw new IllegalArgumentException();
                Event.valueOf(p[4]); Reason.valueOf(p[5]);
                if (!p[6].matches("[01]{4}") || !p[7].matches("[A-Za-z0-9_$]{0,48}")) throw new IllegalArgumentException();
                records.addLast(lines[i]);
            }
        } catch (RuntimeException ignored) { generation = 0; records.clear(); }
        generation = processGeneration > 0 ? processGeneration : 1;
    }
    synchronized long generation() { return generation; }
    synchronized long nextAttempt() { return ++attempt; }
    synchronized void add(long id, long elapsed, long wall, Event event, Reason reason, boolean a, boolean b, boolean c, boolean d, Throwable error) {
        String name = error == null ? "" : error.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_$]", "");
        if (name.length() > 48) name = name.substring(0, 48);
        String line = generation + "|" + Math.max(0, id) + "|" + Math.max(0, elapsed) + "|" + Math.max(0, wall)
                + "|" + event + "|" + reason + "|" + (a ? 1 : 0) + (b ? 1 : 0) + (c ? 1 : 0) + (d ? 1 : 0) + "|" + name;
        if (records.size() == 64) records.removeFirst();
        records.addLast(line);
    }
    synchronized String serialize() { StringBuilder s = new StringBuilder().append(generation).append('\n'); for (String r : records) s.append(r).append('\n'); return s.toString(); }
    static Event captureReleaseEvent(boolean timedOut, boolean released) {
        return timedOut && released ? Event.CAPTURE_LATE_CALLBACK : null;
    }
    synchronized String outcome(long now) {
        Event outcome = null, pending = null;
        Reason reason = Reason.NONE;
        long outcomeAt = 0, pendingAt = 0;
        boolean specificFailure = false;
        for (String line : records) {
            String[] p = line.split("\\|", -1);
            if (!p[0].equals(Long.toString(generation)) || attempt == 0 || !p[1].equals(Long.toString(attempt))) continue;
            Event e = Event.valueOf(p[4]);
            Reason r = Reason.valueOf(p[5]);
            long at = Long.parseLong(p[2]);
            if (e == Event.PENDING_CONFIRMED || e == Event.PENDING_FAILED || e == Event.PENDING_TIMEOUT) {
                pending = e; pendingAt = at; continue;
            }
            boolean failure = e == Event.SETUP_REJECT || e == Event.ACCESS_START_REJECT || e == Event.FGS_EXCEPTION
                    || e == Event.FGS_REFUSE || e == Event.START_REQUEST && r == Reason.EXCEPTION;
            boolean specific = failure && r != Reason.NONE && r != Reason.NOT_RUNNING;
            if (specific || (!specificFailure && (failure || e == Event.ACCESS_START_OK || e == Event.START_REQUEST || e == Event.UI_CLICK))) {
                outcome = e; reason = r; outcomeAt = at;
                specificFailure |= specific;
            }
        }
        return "lastStartOutcome=" + (outcome == null ? "NONE" : outcome) + " attempt=" + attempt
                + " ageMs=" + (outcome == null ? -1 : Math.max(0, now - outcomeAt)) + " reason=" + reason
                + " uiPendingObservation=" + (pending == null ? "NONE" : pending)
                + " uiPendingAgeMs=" + (pending == null ? -1 : Math.max(0, now - pendingAt));
    }
}
