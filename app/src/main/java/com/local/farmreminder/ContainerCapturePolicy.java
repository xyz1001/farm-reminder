package com.local.farmreminder;

/** Main-thread observation window, using elapsed realtime rather than wall time. */
final class ContainerCapturePolicy {
    static final long WINDOW_MS = 20_000;
    private String session;
    private long epoch, deadline;
    private boolean recognized, timedOut;

    void enter(String session, long epoch, long now) {
        this.session = session; this.epoch = epoch; deadline = now + WINDOW_MS;
        recognized = false; timedOut = false;
    }
    void clear() { session = null; recognized = false; timedOut = false; }
    boolean matches(String session, long epoch) { return this.session != null && this.session.equals(session) && this.epoch == epoch; }
    boolean expire(long now) {
        if (session != null && !recognized && !timedOut && now >= deadline) { timedOut = true; return true; }
        return false;
    }
    boolean recognize(String session, long epoch, boolean valid, long now) {
        if (!matches(session, epoch) || !valid || timedOut || now >= deadline) return false;
        recognized = true; return true;
    }
    boolean allows(long now) { expire(now); return session != null && !timedOut; }
    boolean timedOut() { return timedOut; }
    boolean recognized() { return recognized; }
    long remaining(long now) { return session == null || recognized || timedOut ? 0 : Math.max(0, deadline - now); }
}
