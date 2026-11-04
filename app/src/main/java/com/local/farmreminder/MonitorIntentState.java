package com.local.farmreminder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Durable user intent only. Never inferred from the legacy monitoringEnabled preference. */
final class MonitorIntentState {
    static final int MAX_HISTORY = 64;
    static final String USAGE = "USAGE", SHIZUKU = "SHIZUKU";
    enum Suspension { NONE, SYSTEM_STOP, MANUAL_RETRY_REQUIRED }
    static final class Exit {
        final long timestamp; final int pid, reason; final String process;
        Exit(long timestamp, int pid, int reason, String process) {
            if (timestamp < 0 || pid <= 0 || reason < 0 || process == null || process.isEmpty() || process.length() > 256)
                throw new IllegalArgumentException("invalid exit fingerprint");
            this.timestamp = timestamp; this.pid = pid; this.reason = reason; this.process = process;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Exit)) return false; Exit e = (Exit) o;
            return timestamp == e.timestamp && pid == e.pid && reason == e.reason && process.equals(e.process);
        }
        @Override public int hashCode() { return Objects.hash(timestamp, pid, reason, process); }
    }
    final boolean enrolled, wanted;
    final long revision, manualStartWall;
    final String sourceMode;
    final Suspension suspension;
    final List<Exit> acknowledged;
    MonitorIntentState(boolean enrolled, boolean wanted, long revision, long manualStartWall,
                       String sourceMode, Suspension suspension, List<Exit> acknowledged) {
        if (revision < 0 || manualStartWall < 0 || sourceMode == null || suspension == null || acknowledged == null
                || acknowledged.size() > MAX_HISTORY || (wanted && (!enrolled || suspension == Suspension.SYSTEM_STOP))
                || (enrolled && !(USAGE.equals(sourceMode) || SHIZUKU.equals(sourceMode)))
                || (!enrolled && (!sourceMode.isEmpty() || wanted || manualStartWall != 0)))
            throw new IllegalArgumentException("invalid monitor intent");
        LinkedHashSet<Exit> unique = new LinkedHashSet<>(acknowledged);
        if (unique.size() != acknowledged.size() || unique.contains(null)) throw new IllegalArgumentException("invalid history");
        this.enrolled = enrolled; this.wanted = wanted; this.revision = revision; this.manualStartWall = manualStartWall;
        this.sourceMode = sourceMode; this.suspension = suspension;
        this.acknowledged = Collections.unmodifiableList(new ArrayList<>(acknowledged));
    }
    static MonitorIntentState empty() { return new MonitorIntentState(false, false, 0, 0, "", Suspension.NONE, Collections.emptyList()); }
    static List<Exit> bounded(List<Exit> history) {
        if (history == null || history.contains(null)) throw new IllegalArgumentException("invalid history");
        LinkedHashSet<Exit> unique = new LinkedHashSet<>(history);
        List<Exit> result = new ArrayList<>(unique);
        return new ArrayList<>(result.subList(0, Math.min(MAX_HISTORY, result.size())));
    }
    MonitorIntentState start(String mode, List<Exit> history, long now) {
        if (!(USAGE.equals(mode) || SHIZUKU.equals(mode)) || now < 0) throw new IllegalArgumentException("invalid start");
        return new MonitorIntentState(true, true, nextRevision(), now, mode, Suspension.NONE, bounded(history));
    }
    MonitorIntentState stop() {
        return new MonitorIntentState(enrolled, false, nextRevision(), manualStartWall, sourceMode, Suspension.NONE, acknowledged);
    }
    MonitorIntentState suspendFatal(long ownerRevision) {
        if (revision != ownerRevision || !wanted) return this;
        return new MonitorIntentState(enrolled, true, nextRevision(), manualStartWall, sourceMode, Suspension.MANUAL_RETRY_REQUIRED, acknowledged);
    }
    MonitorIntentState observe(List<Exit> history, int userRequestedReason) {
        List<Exit> current = bounded(history);
        if (wanted) for (Exit e : current) if (e.reason == userRequestedReason && !acknowledged.contains(e))
            return new MonitorIntentState(enrolled, false, nextRevision(), manualStartWall, sourceMode, Suspension.SYSTEM_STOP, current);
        if (wanted && suspension != Suspension.MANUAL_RETRY_REQUIRED) for (Exit e : current)
            if (MonitorExitHistory.fatal(e.reason) && !acknowledged.contains(e))
                return new MonitorIntentState(enrolled, true, nextRevision(), manualStartWall, sourceMode, Suspension.MANUAL_RETRY_REQUIRED, current);
        return this;
    }
    boolean mayRun(long expectedRevision) {
        return enrolled && wanted && suspension == Suspension.NONE && revision == expectedRevision;
    }
    private long nextRevision() {
        if (revision == Long.MAX_VALUE) throw new IllegalStateException("revision exhausted");
        return revision + 1;
    }
}
