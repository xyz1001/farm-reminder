package com.local.farmreminder;

import java.io.IOException;

/** Serialized read/modify/write; a failed commit never returns the proposed state as durable. */
final class MonitorIntentRepository {
    interface Storage { byte[] read() throws IOException; void write(byte[] bytes) throws IOException; }
    enum Code { OK, MISSING, CORRUPT, STORAGE_ERROR, HISTORY_UNAVAILABLE, UNSUPPORTED, INVALID, STALE }
    static final class Result {
        final Code code; final MonitorIntentState state;
        Result(Code code, MonitorIntentState state) { this.code = code; this.state = state; }
    }
    private final Storage storage;
    MonitorIntentRepository(Storage storage) { this.storage = storage; }
    synchronized Result read() {
        final byte[] bytes;
        try { bytes = storage.read(); } catch (IOException | RuntimeException e) { return new Result(Code.STORAGE_ERROR, null); }
        if (bytes == null) return new Result(Code.MISSING, MonitorIntentState.empty());
        try { return new Result(Code.OK, MonitorIntentCodec.decode(bytes)); }
        catch (IOException | RuntimeException e) { return new Result(Code.CORRUPT, null); }
    }
    synchronized Result start(String mode, MonitorExitHistory history, long now) {
        Result old = read(); if (!usable(old)) return old;
        Code historyError = historyError(history); if (historyError != null) return new Result(historyError, old.state);
        try { return save(old.state, old.state.start(mode, history.exits, now)); }
        catch (IllegalArgumentException | IllegalStateException e) { return new Result(Code.INVALID, old.state); }
    }
    synchronized Result stop() {
        Result old = read(); if (!usable(old)) return old;
        try { return save(old.state, old.state.stop()); }
        catch (IllegalStateException e) { return new Result(Code.INVALID, old.state); }
    }
    synchronized Result suspendFatal(long revision) {
        Result old = read(); if (!usable(old)) return old;
        try {
            MonitorIntentState next = old.state.suspendFatal(revision);
            return next == old.state ? new Result(Code.STALE, old.state) : save(old.state, next);
        } catch (IllegalStateException e) { return new Result(Code.INVALID, old.state); }
    }
    synchronized Result observe(MonitorExitHistory history) {
        Result old = read(); if (!usable(old)) return old;
        Code historyError = historyError(history); if (historyError != null) return new Result(historyError, old.state);
        try {
            MonitorIntentState next = old.state.observe(history.exits, MonitorExitHistory.USER_REQUESTED);
            return next == old.state ? new Result(old.code, old.state) : save(old.state, next);
        } catch (IllegalStateException e) { return new Result(Code.INVALID, old.state); }
    }
    private static boolean usable(Result r) { return r.code == Code.OK || r.code == Code.MISSING; }
    private static Code historyError(MonitorExitHistory history) {
        if (history == null || history.status == MonitorExitHistory.Status.ERROR) return Code.HISTORY_UNAVAILABLE;
        return history.status == MonitorExitHistory.Status.UNSUPPORTED ? Code.UNSUPPORTED : null;
    }
    private Result save(MonitorIntentState old, MonitorIntentState next) {
        try { storage.write(MonitorIntentCodec.encode(next)); return new Result(Code.OK, next); }
        catch (IOException | RuntimeException e) { return new Result(Code.STORAGE_ERROR, old); }
    }
}
